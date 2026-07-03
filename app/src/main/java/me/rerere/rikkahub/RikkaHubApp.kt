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
     * 双子星计划 -- 自动探测本地模型 (model-router :18888) 和 Hermes 助手 (sse-proxy :6791)
     *
     * 启动后延迟 3 秒探测 localhost 端口：
     * 1. 18888 -- model-router，探测成功则创建/更新本地模型 Provider
     * 2. 6791 -- sse-proxy (Hermes)，探测成功则创建/更新 Hermes 助手 Provider
     */
    private fun detectLocalModel() {
        get<AppScope>().launch(Dispatchers.IO) {
            delay(3000)
            runCatching {
                val store = get<SettingsStore>()
                val settings = store.settingsFlowRaw.first()

                val targets = listOf(
                    "http://127.0.0.1:18888/health" to "model-router(18888)",
                    "http://127.0.0.1:6791/health" to "hermes-proxy(6791)",
                )

                val found: Map<String, String> = targets.filter { (url, _) ->
                    runCatching {
                        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                        conn.connectTimeout = 2000
                        conn.readTimeout = 2000
                        conn.requestMethod = "GET"
                        conn.connect()
                        conn.responseCode == 200
                    }.getOrDefault(false)
                }.associate { it }

                if (found.isEmpty()) {
                    Log.d(TAG, "detectLocalModel: 未找到本地服务")
                    return@launch
                }

                Log.i(TAG, "detectLocalModel: 发现 ${found.size} 个本地服务")

                store.update { s ->
                    var providers = s.providers.toMutableList()

                    if ("model-router(18888)" in found) {
                        try {
                            val conn = java.net.URL("http://127.0.0.1:18888/v1/models")
                                .openConnection() as java.net.HttpURLConnection
                            conn.connectTimeout = 2000
                            conn.readTimeout = 2000
                            conn.requestMethod = "GET"
                            val body = conn.inputStream.reader().readText()
                            Log.i(TAG, "detectLocalModel: model-router models: $body")

                            val modelItems = try {
                                Json.decodeFromString<ModelsResponse>(body).data
                            } catch (e: Exception) {
                                Log.w(TAG, "detectLocalModel: parse models failed", e)
                                emptyList()
                            }

                            val currentProviderId = DEFAULT_LOCAL_MODEL_PROVIDER_ID
                            val existingIdx = providers.indexOfFirst { it.id == currentProviderId && it is ProviderSetting.OpenAI }

                            val modelList = modelItems.mapIndexed { index, m ->
                                val modelId = if (index == 0) DEFAULT_AUTO_MODEL_ID else Uuid.random()
                                Model(
                                    id = modelId,
                                    modelId = m.id,
                                    displayName = m.id,
                                    inputModalities = listOf(Modality.TEXT),
                                    outputModalities = listOf(Modality.TEXT),
                                    abilities = listOf(),
                                )
                            }.ifEmpty {
                                listOf(Model(
                                    id = DEFAULT_AUTO_MODEL_ID,
                                    modelId = "local-model",
                                    displayName = "Local Model",
                                    inputModalities = listOf(Modality.TEXT),
                                    outputModalities = listOf(Modality.TEXT),
                                    abilities = listOf(),
                                ))
                            }

                            val localProvider = ProviderSetting.OpenAI(
                                id = currentProviderId,
                                name = "本地模型",
                                baseUrl = "http://127.0.0.1:18888/v1",
                                apiKey = "not-needed",
                                enabled = true,
                                builtIn = true,
                                models = modelList,
                            )

                            if (existingIdx >= 0) {
                                providers[existingIdx] = localProvider
                            } else {
                                providers.add(0, localProvider)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "detectLocalModel: get models failed", e)
                        }
                    }

                    if ("hermes-proxy(6791)" in found) {
                        val hermesId = DEFAULT_HERMES_PROVIDER_ID
                        val existingIdx = providers.indexOfFirst { it.id == hermesId && it is ProviderSetting.OpenAI }

                        val hermesProvider = ProviderSetting.OpenAI(
                            id = hermesId,
                            name = "Hermes 助手",
                            baseUrl = "http://127.0.0.1:6791/v1",
                            apiKey = "not-needed",
                            enabled = true,
                            builtIn = true,
                            models = listOf(Model(
                                id = Uuid.parse("f1a2b3c4-d5e6-4f7a-8b9c-0d1e2f3a4b5c"),
                                modelId = "hermes-agent",
                                displayName = "Hermes Agent",
                                inputModalities = listOf(Modality.TEXT),
                                outputModalities = listOf(Modality.TEXT),
                                abilities = listOf(),
                            )),
                        )

                        if (existingIdx >= 0) {
                            providers[existingIdx] = hermesProvider
                        } else {
                            providers.add(1, hermesProvider)
                        }
                    }

                    s.copy(providers = providers)
                }

                Log.i(TAG, "detectLocalModel: auto config done")
            }.onFailure {
                Log.e(TAG, "detectLocalModel failed", it)
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
