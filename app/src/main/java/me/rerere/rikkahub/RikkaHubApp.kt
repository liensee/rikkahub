package me.rerere.rikkahub

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.compose.foundation.ComposeFoundationFlags
import androidx.compose.runtime.Composer
import androidx.compose.runtime.tooling.ComposeStackTraceMode
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.remoteConfigSettings
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.rerere.common.android.appTempFolder
import com.whl.quickjs.android.QuickJSLoader
import me.rerere.rikkahub.di.appModule
import me.rerere.rikkahub.di.dataSourceModule
import me.rerere.rikkahub.di.repositoryModule
import me.rerere.rikkahub.di.viewModelModule
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.service.WebServerService
import me.rerere.rikkahub.utils.CrashHandler
import me.rerere.rikkahub.utils.DatabaseUtil
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.androidx.workmanager.koin.workManagerFactory
import org.koin.core.context.startKoin

private const val TAG = "RikkaHubApp"

const val CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID = "chat_completed"
const val CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID = "chat_live_update"
const val WEB_SERVER_NOTIFICATION_CHANNEL_ID = "web_server"

class RikkaHubApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidLogger()
            androidContext(this@RikkaHubApp)
            workManagerFactory()
            modules(appModule, viewModelModule, dataSourceModule, repositoryModule)
        }
        this.createNotificationChannel()

        // set cursor window size to 32MB
        DatabaseUtil.setCursorWindowSize(32 * 1024 * 1024)

        // install crash handler
        CrashHandler.install(this)

        // Init QuickJS native library
        QuickJSLoader.init()

        // delete temp files
        deleteTempFiles()

        // sync upload files to DB
        syncManagedFiles()

        // Init remote config
        get<FirebaseRemoteConfig>().apply {
            setConfigSettingsAsync(remoteConfigSettings {
                minimumFetchIntervalInSeconds = 1800
            })
            setDefaultsAsync(R.xml.remote_config_defaults)
            fetchAndActivate()
        }

        // Start WebServer if enabled in settings
        startWebServerIfEnabled()

        // Increment launch count
        incrementLaunchCount()

        // Auto-detect local model server
        detectLocalModel()

        // Composer.setDiagnosticStackTraceMode(ComposeStackTraceMode.Auto)
    }

    private fun incrementLaunchCount() {
        get<AppScope>().launch {
            runCatching {
                val store = get<SettingsStore>()
                val current = store.settingsFlowRaw.first()
                store.update(current.copy(launchCount = current.launchCount + 1))
                Log.i(TAG, "incrementLaunchCount: ${store.settingsFlowRaw.first().launchCount}")
            }.onFailure {
                Log.e(TAG, "incrementLaunchCount failed", it)
            }
        }
    }

    /**
     * 智能检测本地模型服务器（llama.cpp 等 OpenAI 兼容 API）
     *
     * 启动后延迟探测 127.0.0.1 的常见端口：
     * 1. 8080 — llama.cpp 默认端口
     * 2. 18888 — Hermes minibridge 端口
     *
     * 如果探测到本地模型可用，自动将该 Provider 设置为首选模型，
     * 并填充探测到的模型列表。
     */
    private fun detectLocalModel() {
        get<AppScope>().launch(Dispatchers.IO) {
            delay(3000) // 等待其他初始化完成
            runCatching {
                val store = get<SettingsStore>()
                val settings = store.settingsFlowRaw.first()

                // 如果已经启用本地模型并且之前已经探测成功，跳过
                val hasLocalProvider = settings.providers.any {
                    it is me.rerere.ai.provider.ProviderSetting.OpenAI &&
                        it.name == "本地模型" && it.enabled
                }
                if (hasLocalProvider && settings.chatModelId != me.rerere.rikkahub.data.datastore.DEFAULT_AUTO_MODEL_ID) {
                    Log.i(TAG, "detectLocalModel: 本地模型已配置，跳过探测")
                    return@launch
                }

                // 尝试探测端口
                val targets = listOf(
                    "http://127.0.0.1:8080/v1" to "llama.cpp(8080)",
                    "http://127.0.0.1:18888/v1" to "minibridge(18888)",
                )

                var foundUrl: String? = null
                var foundLabel = ""

                for ((baseUrl, label) in targets) {
                    try {
                        val url = java.net.URL("$baseUrl/models")
                        val conn = url.openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 2000
                        conn.readTimeout = 2000
                        conn.requestMethod = "GET"
                        conn.setRequestProperty("Accept", "application/json")

                        val responseCode = conn.responseCode
                        if (responseCode == 200) {
                            val body = conn.inputStream.reader().readText()
                            Log.i(TAG, "detectLocalModel: $label 响应 200, body=$body")
                            foundUrl = baseUrl
                            foundLabel = label
                            break
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "detectLocalModel: $label 不可用 ($e)")
                    }
                }

                if (foundUrl == null) {
                    Log.w(TAG, "detectLocalModel: 未找到本地模型服务器")
                    return@launch
                }

                Log.i(TAG, "detectLocalModel: ✅ 在 $foundLabel 发现本地模型 ($foundUrl)")

                // 解析模型列表
                @Serializable
                data class ModelItem(val id: String, val `object`: String = "model")
                @Serializable
                data class ModelsResponse(val `object`: String = "list", val data: List<ModelItem> = emptyList())

                val modelsBody = try {
                    val url = java.net.URL("$foundUrl/models")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 2000
                    conn.readTimeout = 2000
                    conn.requestMethod = "GET"
                    val body = conn.inputStream.reader().readText()
                    Json.decodeFromString<ModelsResponse>(body)
                } catch (e: Exception) {
                    Log.w(TAG, "detectLocalModel: 解析模型列表失败 ($e)")
                    ModelsResponse()
                }

                val modelList = modelsBody.data.ifEmpty {
                    listOf(ModelItem(id = "auto"))
                }

                Log.i(TAG, "detectLocalModel: 发现 ${modelList.size} 个模型: ${modelList.joinToString { it.id }}")

                // 构造 Provider 配置
                val providerId = kotlin.uuid.Uuid.parse("a8d2d463-e8c0-41f2-b89e-f5eb8e716cce")
                val models = modelList.mapIndexed { index, modelItem ->
                    me.rerere.ai.provider.Model(
                        id = if (index == 0) me.rerere.rikkahub.data.datastore.DEFAULT_AUTO_MODEL_ID
                              else kotlin.uuid.Uuid.random(),
                        modelId = modelItem.id,
                        displayName = modelItem.id,
                        type = me.rerere.ai.provider.ModelType.CHAT,
                        inputModalities = listOf(me.rerere.ai.provider.Modality.TEXT),
                        outputModalities = listOf(me.rerere.ai.provider.Modality.TEXT),
                        abilities = listOf(),
                    )
                }

                val localProvider = me.rerere.ai.provider.ProviderSetting.OpenAI(
                    id = providerId,
                    name = "本地模型",
                    baseUrl = foundUrl,
                    apiKey = "not-needed",
                    enabled = true,
                    builtIn = true,
                    models = models,
                )

                // 写入设置：替换旧 Provider 并设为当前模型
                store.update { s ->
                    val providers = s.providers.toMutableList()
                    val existingIdx = providers.indexOfFirst { it is me.rerere.ai.provider.ProviderSetting.OpenAI && it.name == "本地模型" }
                    if (existingIdx >= 0) {
                        providers[existingIdx] = localProvider
                    } else {
                        providers.add(0, localProvider)
                    }
                    s.copy(
                        providers = providers,
                        chatModelId = models.first().id,
                    )
                }

                Log.i(TAG, "detectLocalModel: ✅ 自动配置本地模型完成 (baseUrl=$foundUrl, model=${models.first().modelId})")
            }.onFailure {
                Log.e(TAG, "detectLocalModel 失败", it)
            }
        }
    }

    private fun deleteTempFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            val dir = appTempFolder
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
    }

    private fun syncManagedFiles() {
        get<AppScope>().launch(Dispatchers.IO) {
            runCatching {
                get<FilesManager>().syncFolder()
            }.onFailure {
                Log.e(TAG, "syncManagedFiles failed", it)
            }
        }
    }

    private fun startWebServerIfEnabled() {
        get<AppScope>().launch {
            runCatching {
                delay(500)
                val settings = get<SettingsStore>().settingsFlowRaw.first()
                if (settings.webServerEnabled) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            this@RikkaHubApp,
                            android.Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w(TAG, "startWebServerIfEnabled: notification permission not granted, skipping")
                        return@launch
                    }
                    if (Build.VERSION.SDK_INT >= 37 &&
                        !settings.webServerLocalhostOnly &&
                        ContextCompat.checkSelfPermission(
                            this@RikkaHubApp,
                            android.Manifest.permission.ACCESS_LOCAL_NETWORK
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w(TAG, "startWebServerIfEnabled: local network permission not granted, skipping")
                        return@launch
                    }
                    val intent = Intent(this@RikkaHubApp, WebServerService::class.java).apply {
                        action = WebServerService.ACTION_START
                        putExtra(WebServerService.EXTRA_PORT, settings.webServerPort)
                        putExtra(WebServerService.EXTRA_LOCALHOST_ONLY, settings.webServerLocalhostOnly)
                    }
                    startForegroundService(intent)
                }
            }.onFailure {
                Log.e(TAG, "startWebServerIfEnabled failed", it)
            }
        }
    }

    private fun createNotificationChannel() {
        val notificationManager = NotificationManagerCompat.from(this)
        val chatCompletedChannel = NotificationChannelCompat
            .Builder(
                CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_HIGH
            )
            .setName(getString(R.string.notification_channel_chat_completed))
            .setVibrationEnabled(true)
            .build()
        notificationManager.createNotificationChannel(chatCompletedChannel)

        val chatLiveUpdateChannel = NotificationChannelCompat
            .Builder(
                CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID,
                NotificationManagerCompat.IMPORTANCE_LOW
            )
            .setName(getString(R.string.notification_channel_chat_live_update))
            .setVibrationEnabled(false)
            .build()
        notificationManager.createNotificationChannel(chatLiveUpdateChannel)

        val webServerChannel = NotificationChannelCompat
            .Builder(WEB_SERVER_NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(getString(R.string.notification_channel_web_server))
            .setVibrationEnabled(false)
            .setShowBadge(false)
            .build()
        notificationManager.createNotificationChannel(webServerChannel)
    }

    override fun onTerminate() {
        super.onTerminate()
        get<AppScope>().cancel()
        stopService(Intent(this, WebServerService::class.java))
    }
}

class AppScope : CoroutineScope by CoroutineScope(
    SupervisorJob()
        + Dispatchers.Main
        + CoroutineName("AppScope")
        + CoroutineExceptionHandler { _, e ->
        Log.e(TAG, "AppScope exception", e)
    }
)
