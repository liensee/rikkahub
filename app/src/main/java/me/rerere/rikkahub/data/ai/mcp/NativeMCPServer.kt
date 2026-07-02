package me.rerere.rikkahub.data.ai.mcp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.routing.get
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.Locale

/**
 * Native MCP Server — 在 RikkaHub 侧运行，暴露 Android 原生能力给 Hermes。
 *
 * 暴露的 MCP tools:
 * - clipboard/read — 读取剪贴板
 * - clipboard/write — 写入剪贴板
 * - notification/post — 发送系统通知
 * - tts/speak — TTS 语音合成
 * - device/info — 设备信息
 * - file/browse — 浏览本地文件
 */
class NativeMCPServer(
    private val context: Context,
    private val appScope: CoroutineScope,
) {
    companion object {
        private const val TAG = "NativeMCPServer"
        private const val MCP_PROTOCOL_VERSION = "2025-03-26"
        const val DEFAULT_PORT = 6790
    }

    private var server: io.ktor.server.application.ApplicationEngine? = null
    private var serverJob: Job? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun start(port: Int = DEFAULT_PORT) {
        if (server != null) return

        // 初始化 TTS
        tts = TextToSpeech(context) { status ->
            ttsReady = (status == TextToSpeech.SUCCESS)
            tts?.language = Locale.CHINESE
        }

        serverJob = appScope.launch(Dispatchers.IO) {
            try {
                server = embeddedServer(CIO, port = port) {
                    install(ContentNegotiation) { json() }

                    routing {
                        // MCP SSE endpoint
                        get("/sse") {
                            call.respondText(
                                contentType = ContentType.Text.EventStream,
                                text = buildString {
                                    appendLine("event: endpoint")
                                    appendLine("data: /mcp")
                                    appendLine()
                                }
                            )
                        }

                        // MCP JSON-RPC endpoint
                        post("/mcp") {
                            val body = call.receiveText()
                            val request = try {
                                json.parseToJsonElement(body).jsonObject
                            } catch (e: Exception) {
                                call.respondText(
                                    contentType = ContentType.Application.Json,
                                    text = """{"jsonrpc":"2.0","id":null,"error":{"code":-32700,"message":"Parse error"}}"""
                                )
                                return@post
                            }

                            val msgId = request["id"]
                            val method = request["method"]?.jsonPrimitive?.content ?: ""
                            val params = request["params"]?.jsonObject ?: JsonObject(emptyMap())

                            val response = handleRequest(method, params, msgId)
                            call.respondText(
                                contentType = ContentType.Application.Json,
                                text = response,
                            )
                        }

                        // Health check
                        get("/health") {
                            call.respondText(
                                contentType = ContentType.Application.Json,
                                text = """{"status":"ok","protocol":"$MCP_PROTOCOL_VERSION","server":"rikkahub-native"}"""
                            )
                        }
                    }
                }.start(wait = false)

                Log.i(TAG, "Native MCP Server started on port $port")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Native MCP Server", e)
            }
        }
    }

    fun stop() {
        server?.stop(gracePeriodMillis = 1000, timeoutMillis = 3000)
        server = null
        serverJob?.cancel()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun handleRequest(method: String, params: JsonObject, msgId: kotlinx.serialization.json.JsonElement?): String {
        return try {
            when (method) {
                "initialize" -> jsonResponse(msgId, mapOf(
                    "protocolVersion" to MCP_PROTOCOL_VERSION,
                    "capabilities" to mapOf("tools" to emptyMap<String, String>()),
                    "serverInfo" to mapOf("name" to "rikkahub-native", "version" to "1.0"),
                ))

                "tools/list" -> jsonResponse(msgId, mapOf(
                    "tools" to listOf(
                        toolDef("clipboard_read", "读取系统剪贴板内容", emptyMap()),
                        toolDef("clipboard_write", "写入文本到系统剪贴板", mapOf(
                            "text" to mapOf("type" to "string", "description" to "要写入的文本")
                        )),
                        toolDef("notification_post", "发送系统通知", mapOf(
                            "title" to mapOf("type" to "string", "description" to "通知标题"),
                            "content" to mapOf("type" to "string", "description" to "通知内容"),
                        )),
                        toolDef("tts_speak", "TTS 语音朗读文本", mapOf(
                            "text" to mapOf("type" to "string", "description" to "要朗读的文本"),
                        )),
                        toolDef("device_info", "获取当前设备信息", emptyMap()),
                        toolDef("file_browse", "浏览设备文件目录", mapOf(
                            "path" to mapOf("type" to "string", "description" to "目录路径，默认为根目录"),
                        )),
                        toolDef("battery_info", "获取电池状态、电量、温度等信息", emptyMap()),
                        toolDef("wifi_info", "获取WiFi连接信息（SSID、信号强度、IP等）", emptyMap()),
                        toolDef("storage_info", "获取存储空间使用情况", emptyMap()),
                        toolDef("sensor_list", "列出设备所有传感器", emptyMap()),
                        toolDef("app_list", "列出已安装的应用", emptyMap()),
                        toolDef("screen_info", "获取屏幕分辨率、密度等信息", emptyMap()),
                        toolDef("location_get", "获取当前GPS位置（需要定位权限）", emptyMap()),
                    )
                ))

                "tools/call" -> {
                    val toolName = params["name"]?.jsonPrimitive?.content ?: ""
                    val args = params["arguments"]?.jsonObject ?: JsonObject(emptyMap())
                    handleToolCall(toolName, args, msgId)
                }

                else -> jsonError(msgId, -32601, "Method not found: $method")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling $method", e)
            jsonError(msgId, -32603, "Internal error: ${e.message}")
        }
    }

    private fun handleToolCall(name: String, args: JsonObject, msgId: kotlinx.serialization.json.JsonElement?): String {
        return try {
            val result = when (name) {
                "clipboard_read" -> {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val text = clipboard?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                    mapOf("content" to listOf(mapOf("type" to "text", "text" to text)))
                }

                "clipboard_write" -> {
                    val text = args["text"]?.jsonPrimitive?.content ?: ""
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("hermes", text))
                    mapOf("content" to listOf(mapOf("type" to "text", "text" to "ok")))
                }

                "notification_post" -> {
                    val title = args["title"]?.jsonPrimitive?.content ?: ""
                    val content = args["content"]?.jsonPrimitive?.content ?: ""
                    postNotification(title, content)
                    mapOf("content" to listOf(mapOf("type" to "text", "text" to "notification sent")))
                }

                "tts_speak" -> {
                    val text = args["text"]?.jsonPrimitive?.content ?: ""
                    speak(text)
                    mapOf("content" to listOf(mapOf("type" to "text", "text" to "speaking...")))
                }

                "device_info" -> {
                    mapOf("content" to listOf(mapOf("type" to "text", "text" to buildString {
                        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                        appendLine("Brand: ${Build.BRAND}")
                        appendLine("Product: ${Build.PRODUCT}")
                        appendLine("Board: ${Build.BOARD}")
                    })))
                }

                "file_browse" -> {
                    val path = args["path"]?.jsonPrimitive?.content ?: "/"
                    val files = try {
                        val dir = java.io.File(path)
                        if (dir.isDirectory) dir.listFiles()?.map { f ->
                            "${if (f.isDirectory) "📁" else "📄"} ${f.name} (${f.length()}B)"
                        }?.joinToString("\n") ?: "Empty directory"
                        else "Not a directory: $path"
                    } catch (e: Exception) {
                        "Error: ${e.message}"
                    }
                    mapOf("content" to listOf(mapOf("type" to "text", "text" to files)))
                }

                "battery_info" -> {
                    mapOf("content" to listOf(mapOf("type" to "text", "text" to getBatteryInfo())))
                }

                "wifi_info" -> {
                    mapOf("content" to listOf(mapOf("type" to "text", "text" to getWifiInfo())))
                }

                "storage_info" -> {
                    mapOf("content" to listOf(mapOf("type" to "text", "text" to getStorageInfo())))
                }

                "sensor_list" -> {
                    mapOf("content" to listOf(mapOf("type" to "text", "text" to getSensorList())))
                }

                "app_list" -> {
                    mapOf("content" to listOf(mapOf("type" to "text", "text" to getAppList())))
                }

                "screen_info" -> {
                    mapOf("content" to listOf(mapOf("type" to "text", "text" to getScreenInfo())))
                }

                "location_get" -> {
                    mapOf("content" to listOf(mapOf("type" to "text", "text" to getLocation())))
                }

                else -> return jsonError(msgId, -32602, "Unknown tool: $name")
            }
            jsonResponse(msgId, result)
        } catch (e: Exception) {
            jsonError(msgId, -32603, "Tool execution error: ${e.message}")
        }
    }

    private fun postNotification(title: String, content: String) {
        try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                    as android.app.NotificationManager
            val channel = android.app.NotificationChannel(
                "hermes_native",
                "Hermes 原生能力",
                android.app.NotificationManager.IMPORTANCE_DEFAULT,
            )
            notificationManager.createNotificationChannel(channel)

            val notification = android.app.Notification.Builder(context, "hermes_native")
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .build()

            notificationManager.notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post notification", e)
        }
    }

    private fun speak(text: String) {
        if (ttsReady && tts != null) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "hermes-tts")
        }
    }

    // ─── Phase 2: New Android tools ─────────────────────────────────

    private fun getBatteryInfo(): String {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (scale > 0) (level * 100 / scale) else -1
            val status = when (intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
                BatteryManager.BATTERY_STATUS_FULL -> "Full"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
                else -> "Unknown"
            }
            val plugged = when (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                else -> "None"
            }
            val temp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
            val voltage = (intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000f
            val health = when (intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
                BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
                else -> "Unknown"
            }
            buildString {
                appendLine("Battery: $pct% ($level/$scale)")
                appendLine("Status: $status")
                appendLine("Plugged: $plugged")
                appendLine("Temperature: $temp °C")
                appendLine("Voltage: $voltage V")
                appendLine("Health: $health")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                    bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.let { appendLine("Capacity: $it%") }
                    bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)?.let {
                        if (it > 0) appendLine("Charge counter: ${it / 1000} mAh")
                    }
                }
            }.trimEnd()
        } catch (e: Exception) {
            "Battery info error: ${e.message}"
        }
    }

    private fun getWifiInfo(): String {
        return try {
            val sb = StringBuilder()
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val connManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

            // Network type
            val network = connManager?.activeNetwork
            val caps = network?.let { connManager?.getNetworkCapabilities(it) }
            val networkType = when {
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "WiFi"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Cellular"
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
                else -> "Unknown/None"
            }
            sb.appendLine("Network type: $networkType")

            // WiFi details
            if (wifiManager != null) {
                val wifiState = when (wifiManager.wifiState) {
                    WifiManager.WIFI_STATE_ENABLED -> "Enabled"
                    WifiManager.WIFI_STATE_DISABLED -> "Disabled"
                    WifiManager.WIFI_STATE_ENABLING -> "Enabling"
                    WifiManager.WIFI_STATE_DISABLING -> "Disabling"
                    else -> "Unknown"
                }
                sb.appendLine("WiFi state: $wifiState")

                val info = wifiManager.connectionInfo
                sb.appendLine("SSID: ${info.ssid.ifEmpty { "<none>" }}")
                sb.appendLine("BSSID: ${info.bssid ?: "<none>"}")
                sb.appendLine("Signal: ${info.rssi} dBm (${getWifiSignalLevel(info.rssi)})")
                sb.appendLine("Link speed: ${info.linkSpeed} Mbps")
                val ip = info.ipAddress
                if (ip != 0) {
                    sb.appendLine("IP: ${(ip and 0xFF)}.${(ip shr 8 and 0xFF)}.${(ip shr 16 and 0xFF)}.${(ip shr 24 and 0xFF)}")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    sb.appendLine("Frequency: ${info.frequency} MHz")
                }
            }
            sb.toString().trimEnd()
        } catch (e: Exception) {
            "WiFi info error: ${e.message}"
        }
    }

    private fun getWifiSignalLevel(rssi: Int): String = when {
        rssi >= -50 -> "Excellent"
        rssi >= -60 -> "Good"
        rssi >= -70 -> "Fair"
        rssi >= -80 -> "Weak"
        else -> "Poor"
    }

    private fun getStorageInfo(): String {
        return try {
            buildString {
                appendLine("=== Internal Storage ===")
                appendStorageStats(Environment.getDataDirectory())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    appendLine("=== Device Storage ===")
                    appendStorageStats(Environment.getRootDirectory())
                }
                // External storage if available
                Environment.getExternalStorageDirectory()?.let {
                    if (it.exists()) {
                        appendLine("=== External Storage ===")
                        appendStorageStats(it)
                    }
                }
            }.trimEnd()
        } catch (e: Exception) {
            "Storage info error: ${e.message}"
        }
    }

    private fun StringBuilder.appendStorageStats(path: java.io.File) {
        try {
            val stat = StatFs(path.absolutePath)
            val blockSize = stat.blockSizeLong
            val total = stat.blockCountLong * blockSize
            val available = stat.availableBlocksLong * blockSize
            val used = total - available
            appendLine("Path: ${path.absolutePath}")
            appendLine("Total: ${formatBytes(total)}")
            appendLine("Used: ${formatBytes(used)}")
            appendLine("Available: ${formatBytes(available)}")
            appendLine("Use: ${if (total > 0) (used * 100 / total) else 0}%")
        } catch (e: Exception) {
            appendLine("Path: ${path.absolutePath} — ${e.message}")
        }
    }

    private fun formatBytes(bytes: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024 && unitIndex < units.size - 1) {
            value /= 1024
            unitIndex++
        }
        return "%.2f %s".format(Locale.US, value, units[unitIndex])
    }

    private fun getSensorList(): String {
        return try {
            val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            if (sensorManager == null) return "Sensor service not available"

            val sensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
            if (sensors.isEmpty()) return "No sensors found"

            buildString {
                appendLine("Total sensors: ${sensors.size}")
                appendLine()
                sensors.forEachIndexed { i, s ->
                    appendLine("[${i + 1}] ${s.name}")
                    appendLine("    Vendor: ${s.vendor}")
                    appendLine("    Version: ${s.version}")
                    appendLine("    Type: ${getSensorTypeName(s.type)} (${s.type})")
                    appendLine("    Power: ${s.power} mA")
                    appendLine("    Resolution: ${s.resolution}")
                    appendLine("    Max range: ${s.maximumRange}")
                    appendLine("    Min delay: ${s.minDelay} μs")
                    appendLine()
                }
            }.trimEnd()
        } catch (e: Exception) {
            "Sensor list error: ${e.message}"
        }
    }

    private fun getSensorTypeName(type: Int): String = when (type) {
        Sensor.TYPE_ACCELEROMETER -> "Accelerometer"
        Sensor.TYPE_AMBIENT_TEMPERATURE -> "Ambient Temperature"
        Sensor.TYPE_GAME_ROTATION_VECTOR -> "Game Rotation Vector"
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> "Geomagnetic Rotation Vector"
        Sensor.TYPE_GRAVITY -> "Gravity"
        Sensor.TYPE_GYROSCOPE -> "Gyroscope"
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> "Gyroscope Uncalibrated"
        Sensor.TYPE_HEART_RATE -> "Heart Rate"
        Sensor.TYPE_LIGHT -> "Light"
        Sensor.TYPE_LINEAR_ACCELERATION -> "Linear Acceleration"
        Sensor.TYPE_MAGNETIC_FIELD -> "Magnetic Field"
        Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> "Magnetic Field Uncalibrated"
        Sensor.TYPE_PRESSURE -> "Pressure"
        Sensor.TYPE_PROXIMITY -> "Proximity"
        Sensor.TYPE_RELATIVE_HUMIDITY -> "Relative Humidity"
        Sensor.TYPE_ROTATION_VECTOR -> "Rotation Vector"
        Sensor.TYPE_SIGNIFICANT_MOTION -> "Significant Motion"
        Sensor.TYPE_STEP_COUNTER -> "Step Counter"
        Sensor.TYPE_STEP_DETECTOR -> "Step Detector"
        Sensor.TYPE_ORIENTATION -> "Orientation (Deprecated)"
        else -> "Unknown"
    }

    private fun getAppList(): String {
        return try {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val sorted = apps.sortedBy { pm.getApplicationLabel(it).toString().lowercase(Locale.getDefault()) }
            buildString {
                appendLine("Installed apps: ${sorted.size}")
                appendLine()
                sorted.forEachIndexed { i, app ->
                    val label = pm.getApplicationLabel(app)
                    val isSystem = (app.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    appendLine("[${i + 1}] $label")
                    appendLine("    Package: ${app.packageName}")
                    appendLine("    Type: ${if (isSystem) "System" else "User"}")
                    appendLine("    Target SDK: ${app.targetSdkVersion}")
                    appendLine()
                }
            }.trimEnd()
        } catch (e: Exception) {
            "App list error: ${e.message}"
        }
    }

    private fun getScreenInfo(): String {
        return try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            val metrics = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val windowMetrics = wm?.currentWindowMetrics
                windowMetrics?.bounds?.let {
                    DisplayMetrics().also { dm ->
                        dm.widthPixels = it.width()
                        dm.heightPixels = it.height()
                        dm.density = context.resources.displayMetrics.density
                        dm.densityDpi = context.resources.displayMetrics.densityDpi
                        dm.scaledDensity = context.resources.displayMetrics.scaledDensity
                        dm.xdpi = context.resources.displayMetrics.xdpi
                        dm.ydpi = context.resources.displayMetrics.ydpi
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val dm = DisplayMetrics()
                @Suppress("DEPRECATION")
                wm?.defaultDisplay?.getRealMetrics(dm)
                dm
            } ?: context.resources.displayMetrics

            buildString {
                appendLine("Resolution: ${metrics.widthPixels} x ${metrics.heightPixels} px")
                appendLine("Density: ${metrics.density} (dpi: ${metrics.densityDpi})")
                appendLine("Scaled density: ${metrics.scaledDensity}")
                appendLine("Physical: ${metrics.xdpi} x ${metrics.ydpi} dpi")
                val diagPx = Math.sqrt((metrics.widthPixels * metrics.widthPixels + metrics.heightPixels * metrics.heightPixels).toDouble())
                val diagIn = diagPx / metrics.densityDpi
                appendLine("Diagonal: %.1f\"".format(Locale.US, diagIn))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val config = context.resources.configuration
                    appendLine("Smallest width: ${config.smallestScreenWidthDp} dp")
                    appendLine("Screen width: ${config.screenWidthDp} dp")
                    appendLine("Screen height: ${config.screenHeightDp} dp")
                }
            }.trimEnd()
        } catch (e: Exception) {
            "Screen info error: ${e.message}"
        }
    }

    private fun getLocation(): String {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            if (lm == null) return "Location service not available"

            val providers = lm.getProviders(true)
            if (providers.isNullOrEmpty()) return "No location providers enabled"

            buildString {
                appendLine("Available providers: ${providers.joinToString(", ")}")
                appendLine()

                // Try GPS first, then network
                val preferredProviders = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                for (provider in preferredProviders) {
                    if (provider in (providers ?: emptyList())) {
                        @Suppress("MissingPermission")
                        val location = lm.getLastKnownLocation(provider)
                        if (location != null) {
                            appendLine("Provider: $provider")
                            appendLine("Latitude: %.6f".format(Locale.US, location.latitude))
                            appendLine("Longitude: %.6f".format(Locale.US, location.longitude))
                            if (location.hasAltitude()) {
                                appendLine("Altitude: %.1f m".format(Locale.US, location.altitude))
                            }
                            if (location.hasAccuracy()) {
                                appendLine("Accuracy: %.1f m".format(Locale.US, location.accuracy))
                            }
                            if (location.hasSpeed()) {
                                appendLine("Speed: %.1f m/s".format(Locale.US, location.speed))
                            }
                            appendLine("Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(java.util.Date(location.time))}")
                            return@buildString
                        }
                    }
                }
                appendLine("No last known location available. Ensure GPS is enabled and location permissions granted.")
            }.trimEnd()
        } catch (e: SecurityException) {
            "Location permission denied: ${e.message}"
        } catch (e: Exception) {
            "Location error: ${e.message}"
        }
    }

    // ─── JSON-RPC helpers ────────────────────────────────────────────

    private fun toolDef(name: String, description: String, properties: Map<String, Any>): Map<String, Any> {
        return mapOf(
            "name" to name,
            "description" to description,
            "inputSchema" to mapOf(
                "type" to "object",
                "properties" to properties,
            ),
        )
    }

    private fun jsonResponse(id: kotlinx.serialization.json.JsonElement?, result: Map<String, Any?>): String {
        val resultJson = buildJsonObject {
            result.forEach { (key, value) ->
                put(key, anyToJsonElement(value))
            }
        }
        val jsonStr = json.encodeToString(JsonObject.serializer(), resultJson)
        return """{"jsonrpc":"2.0","id":${id?.toString() ?: "null"},"result":$jsonStr}"""
    }

    private fun anyToJsonElement(value: Any?): kotlinx.serialization.json.JsonElement {
        return when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Map<*, *> -> buildJsonObject {
                @Suppress("UNCHECKED_CAST")
                (value as Map<String, Any?>).forEach { (k, v) -> put(k, anyToJsonElement(v)) }
            }
            is List<*> -> buildJsonArray {
                value.forEach { v -> add(anyToJsonElement(v)) }
            }
            else -> JsonPrimitive(value.toString())
        }
    }

    private fun jsonError(id: kotlinx.serialization.json.JsonElement?, code: Int, message: String): String {
        return """{"jsonrpc":"2.0","id":${id?.toString() ?: "null"},"error":{"code":$code,"message":"$message"}}"""
    }
}
