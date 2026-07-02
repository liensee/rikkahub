package me.rerere.rikkahub.data.ai.mcp

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.files.SkillManager
import me.rerere.rikkahub.data.files.SkillMetadata
import java.net.InetAddress
import java.net.NetworkInterface
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import kotlin.uuid.Uuid

/**
 * Hermes MCP Bridge — 自动发现并连接手机上/局域网内的 Hermes MCP Server。
 *
 * 发现策略：
 * 1. 本机（Termux localhost:6789）— 优先连接
 * 2. mDNS 发现 _hermes-mcp._tcp.local. — 自动连接局域网内的 Hermes
 * 3. 手动输入地址
 */
class HermesMCPService(
    private val context: Context,
    private val settingsStore: SettingsStore,
    private val mcpManager: McpManager,
    private val skillManager: SkillManager,
    private val appScope: CoroutineScope,
) {
    companion object {
        private const val TAG = "HermesMCPBridge"
        const val MCP_SERVICE_TYPE = "_hermes-mcp._tcp.local."
        const val HERMES_SERVER_NAME = "Hermes MCP"
        const val DEFAULT_LOCAL_PORT = 6789
        const val LOCALHOST = "127.0.0.1"
        const val RECONNECT_INTERVAL_MS = 30_000L
    }

    // 连接状态
    private val _connectionStatus = MutableStateFlow(HermesConnectionStatus.Disconnected)
    val connectionStatus = _connectionStatus.asStateFlow()

    // 发现的 Hermes 技能列表（从 MCP 同步）
    private val _hermesSkills = MutableStateFlow<List<HermesSkill>>(emptyList())
    val hermesSkills = _hermesSkills.asStateFlow()

    // 本地技能列表
    private val _localSkills = MutableStateFlow<List<SkillMetadata>>(emptyList())
    val localSkills = _localSkills.asStateFlow()

    private var discoveryJob: Job? = null
    private var reconnectJob: Job? = null
    private var jmdns: JmDNS? = null
    private var started = false
    private var serverConfig: McpServerConfig.SseTransportServer? = null

    // 缓存 Hermes 的特殊方法 (non-standard MCP)
    var hermesAddress: String = LOCALHOST
        private set

    fun start() {
        if (started) return
        started = true
        loadLocalSkills()
        // 尝试本地连接，同时启动 mDNS 发现
        discoveryJob = appScope.launch {
            // Phase 1: 立即尝试连接本机 localhost:6789
            tryConnect(LOCALHOST, DEFAULT_LOCAL_PORT)

            // Phase 2: 启动 mDNS 发现局域网 Hermes
            launch(Dispatchers.IO) { discoverViaMdns() }
        }
    }

    fun stop() {
        started = false
        discoveryJob?.cancel()
        reconnectJob?.cancel()
        synchronized(this) {
            runCatching { jmdns?.close() }
            jmdns = null
        }
    }

    /**
     * 手动设置 Hermes MCP Server 地址
     */
    fun setAddress(host: String, port: Int = DEFAULT_LOCAL_PORT) {
        hermesAddress = host
        appScope.launch {
            tryConnect(host, port)
        }
    }

    /**
     * 从 Hermes 同步技能到本地
     */
    fun syncSkillsToLocal() {
        appScope.launch(Dispatchers.IO) {
            try {
                val skills = _hermesSkills.value
                var synced = 0
                for (skill in skills) {
                    val existing = skillManager.getSkill(skill.name)
                    if (existing == null) {
                        // 从 Hermes 读取完整内容并创建本地 skill
                        val content = fetchSkillContent(skill.name)
                        if (content != null) {
                            skillManager.saveSkill(skill.name, content)
                            synced++
                        }
                    }
                }
                loadLocalSkills()
                Log.i(TAG, "Synced $synced skills from Hermes to local")
            } catch (e: Exception) {
                Log.e(TAG, "Sync failed", e)
            }
        }
    }

    /**
     * 同步本地技能到 Hermes
     */
    fun syncLocalToHermes() {
        appScope.launch(Dispatchers.IO) {
            try {
                val localSkills = skillManager.listSkills()
                val hermesSkillNames = _hermesSkills.value.map { it.name }.toSet()
                var synced = 0
                for (skill in localSkills) {
                    if (skill.name !in hermesSkillNames) {
                        val content = skillManager.readSkillContent(skill.name)
                        if (content != null) {
                            createRemoteSkill(skill.name, content, skill.description)
                            synced++
                        }
                    }
                }
                Log.i(TAG, "Synced $synced local skills to Hermes")
            } catch (e: Exception) {
                Log.e(TAG, "Sync to Hermes failed", e)
            }
        }
    }

    /**
     * 在 Hermes 上创建新 skill
     */
    fun createSkillOnHermes(name: String, content: String, category: String = "") {
        appScope.launch(Dispatchers.IO) {
            try {
                callHermesMethod("skills/create", buildJsonObject {
                    put("name", name)
                    put("content", content)
                    put("category", category)
                })
                refreshHermesSkills()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create skill on Hermes", e)
            }
        }
    }

    /**
     * 搜索 Hermes skill hub
     */
    fun searchHermesHub(query: String, onResult: (List<Map<String, String>>) -> Unit) {
        appScope.launch(Dispatchers.IO) {
            try {
                val result = callHermesMethod("skills/search_hub", buildJsonObject {
                    put("query", query)
                })
                val contentStr = result["content"]?.toString() ?: "[]"
                val parsed = mutableListOf<Map<String, String>>()
                try {
                    val jsonArray = io.modelcontextprotocol.kotlin.sdk.shared.Json.parseToJsonElement(contentStr).jsonArray
                    for (item in jsonArray) {
                        val obj = item.jsonObject
                        parsed.add(mapOf(
                            "name" to (obj["name"]?.jsonPrimitive?.content ?: ""),
                            "description" to (obj["description"]?.jsonPrimitive?.content ?: ""),
                            "category" to (obj["category"]?.jsonPrimitive?.content ?: "")
                        ))
                    }
                } catch (_: Exception) { }
                withContext(Dispatchers.Main) { onResult(parsed) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onResult(emptyList()) }
            }
        }
    }

    // ─── 内部方法 ─────────────────────────────────────────────────────

    private val mcpLock = Mutex()

    private suspend fun tryConnect(host: String, port: Int) {
        mcpLock.withLock {
            val url = "http://$host:$port/sse"
            Log.i(TAG, "Attempting to connect to Hermes MCP at $url")

            setStatus(HermesConnectionStatus.Connecting(host, port))

        try {
            val config = McpServerConfig.SseTransportServer(
                id = Uuid.random(),
                url = url,
                commonOptions = McpCommonOptions(
                    enable = true,
                    name = "$HERMES_SERVER_NAME ($host:$port)",
                )
            )

            // 如果已有同 id 配置，先移除
            serverConfig?.let { mcpManager.removeClient(it) }
            mcpManager.addClient(config)
            serverConfig = config

            // 保存到 settings store
            settingsStore.update { old ->
                val servers = old.mcpServers.toMutableList()
                if (servers.none { it is McpServerConfig.SseTransportServer && it.url == url }) {
                    servers.add(config)
                }
                old.copy(mcpServers = servers)
            }

            hermesAddress = host
            setStatus(HermesConnectionStatus.Connected(host, port))
            refreshHermesSkills()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to connect to $url: ${e.message}")
            setStatus(HermesConnectionStatus.Error("${e.message}"))

            // 若不是 localhost，启动定时重连
            if (host != LOCALHOST) {
                scheduleReconnect(host, port)
            }
        }
        }
    }

    private fun scheduleReconnect(host: String, port: Int) {
        reconnectJob?.cancel()
        reconnectJob = appScope.launch {
            delay(RECONNECT_INTERVAL_MS)
            tryConnect(host, port)
        }
    }

    private suspend fun refreshHermesSkills() {
        val config = serverConfig ?: return
        try {
            // McpManager 会在 sync 时自动拉取 tools
            // 我们手动从 config 的 tools 中提取技能信息
            val currentSettings = settingsStore.settingsFlow.value
            val server = currentSettings.mcpServers.find { it.id == config.id }
            val tools = server?.commonOptions?.tools ?: emptyList()

            _hermesSkills.value = tools.filter { it.name.startsWith("skill_") }.map { tool ->
                HermesSkill(
                    name = tool.name.removePrefix("skill_").replace("_", "-"),
                    description = tool.description ?: "",
                    mcpToolName = tool.name,
                    inputSchema = tool.inputSchema,
                )
            }
            Log.i(TAG, "Refreshed ${_hermesSkills.value.size} Hermes skills")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh Hermes skills", e)
        }
    }

    private suspend fun callHermesMethod(method: String, params: JsonObject): Map<String, Any?> {
        val config = serverConfig ?: throw IllegalStateException("Not connected to Hermes")
        val client = mcpManager.getClient(config)
            ?: throw IllegalStateException("MCP client not available")

        // For custom Hermes methods (non-standard), we use MCP tools/call
        // or implement via the ClientSession directly
        val toolResult = client.callTool(
            request = io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest(
                params = io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams(
                    name = method,
                    arguments = params,
                ),
            ),
            options = io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions(timeout = 30.seconds),
        )
        return mapOf("content" to toolResult.content.joinToString { it.toString() })
    }

    private suspend fun fetchSkillContent(name: String): String? {
        val config = serverConfig ?: return null
        return try {
            val client = mcpManager.getClient(config) ?: return null
            val result = client.readResource(
                request = io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest(
                    params = io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequestParams(
                        uri = "skill://$name",
                    ),
                ),
            )
            result.contents.firstOrNull()?.text
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch skill content for $name", e)
            null
        }
    }

    private suspend fun createRemoteSkill(name: String, content: String, description: String) {
        callHermesMethod("skills/create", buildJsonObject {
            put("name", name)
            put("content", content)
            put("description", description)
        })
    }

    private fun loadLocalSkills() {
        _localSkills.value = skillManager.listSkills()
    }

    private fun setStatus(status: HermesConnectionStatus) {
        _connectionStatus.value = status
    }

    // ─── mDNS 发现 ────────────────────────────────────────────────────

    private suspend fun discoverViaMdns() = withContext(Dispatchers.IO) {
        try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val multicastLock = wifiManager?.createMulticastLock("hermes-mdns-lock")?.apply {
                setReferenceCounted(true)
                acquire()
            }
            var mdnsStarted = false
            try {

            val address = getLocalIpAddress()
            if (address == null) {
                Log.w(TAG, "No WiFi IP, mDNS discovery skipped")
                return@withContext
            }

            val mdns = JmDNS.create(address, "rikkahub-hermes")
            jmdns = mdns

            // 监听 _hermes-mcp._tcp.local. 服务
            mdns.addServiceListener(MCP_SERVICE_TYPE) { event ->
                Log.i(TAG, "mDNS discovered: ${event.name} (${event.type})")
                val info = mdns.getServiceInfo(event.type, event.name)
                if (info != null) {
                    val host = info.inetAddresses.firstOrNull()?.hostAddress ?: return@addServiceListener
                    val port = info.port
                    Log.i(TAG, "Discovered Hermes MCP at $host:$port")
                    appScope.launch { tryConnect(host, port) }
                }
            }

            // 主动查询现有服务
            val services = mdns.list(MCP_SERVICE_TYPE)
            for (info in services) {
                val host = info.inetAddresses.firstOrNull()?.hostAddress ?: continue
                val port = info.port
                Log.i(TAG, "Listed Hermes MCP at $host:$port")
                appScope.launch { tryConnect(host, port) }
                } catch (e: Exception) { Log.w(TAG, "mDNS listener error", e) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "mDNS discovery failed: ${e.message}")
        }
    }

    private fun getLocalIpAddress(): InetAddress? {
        return try {
            NetworkInterface.getNetworkInterfaces()?.asSequence()
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.firstOrNull {
                    !it.isLoopbackAddress &&
                        it is java.net.Inet4Address &&
                        !it.hostAddress.isNullOrBlank()
                }
        } catch (e: Exception) {
            null
        }
    }
}

// ─── 数据类 ──────────────────────────────────────────────────────────

data class HermesSkill(
    val name: String,
    val description: String,
    val mcpToolName: String,
    val inputSchema: Any? = null,
)

sealed class HermesConnectionStatus {
    data object Disconnected : HermesConnectionStatus()
    data class Connecting(val host: String, val port: Int) : HermesConnectionStatus()
    data class Connected(val host: String, val port: Int) : HermesConnectionStatus()
    data class Error(val message: String) : HermesConnectionStatus()
}
