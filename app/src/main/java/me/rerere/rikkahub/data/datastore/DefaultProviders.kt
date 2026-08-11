package me.rerere.rikkahub.data.datastore

import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.ai.mcp.McpCommonOptions
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.ai.mcp.McpTool
import kotlin.uuid.Uuid

/**
 * 默认提供商列表 — 双子星计划
 *
 * 本地模型: model-router (:18888) → GPU 服务器 35B/9B
 * Hermes 助手: sse-proxy (:6791) → Hermes Agent 完整工具链
 *
 * 云端提供商仍可通过 UI 中的「添加提供商」手动添加。
 */
val DEFAULT_AUTO_MODEL_ID = Uuid.parse("b7055fb4-39f9-4042-a88a-0d80ed76cf08")
val DEFAULT_HERMES_ID = Uuid.parse("c8166fc5-4a0a-5153-b99f-f6fc9e827d1e")
val DEFAULT_HERMES_MCP_ID = Uuid.parse("e9277fd6-5b1b-6264-ca0a-0a7fd0e9383a")

val DEFAULT_PROVIDERS = listOf(
    // 本地模型 — model-router :18888
    ProviderSetting.OpenAI(
        id = Uuid.parse("a8d2d463-e8c0-41f2-b89e-f5eb8e716cce"),
        name = "本地模型",
        baseUrl = "http://127.0.0.1:18888/v1",
        apiKey = "not-needed",
        enabled = true,
        builtIn = true,
        models = listOf(
            Model(
                id = DEFAULT_AUTO_MODEL_ID,
                modelId = "auto",
                displayName = "Auto（自动检测）",
                inputModalities = listOf(Modality.TEXT),
                outputModalities = listOf(Modality.TEXT),
                abilities = listOf(),
            )
        ),
        description = { },
        shortDescription = { },
    ),
    // Hermes 助手 — 直连 model-router (:18888)
    ProviderSetting.OpenAI(
        id = DEFAULT_HERMES_ID,
        name = "Hermes 助手",
        baseUrl = "http://127.0.0.1:18888/v1",
        apiKey = "not-needed",
        enabled = true,
        builtIn = true,
        models = listOf(
            Model(
                id = Uuid.parse("d9277fd6-5b1b-6264-ca0a-0a7fd0e9382f"),
                modelId = "hermes-agent",
                displayName = "Hermes Agent",
                inputModalities = listOf(Modality.TEXT),
                outputModalities = listOf(Modality.TEXT),
                abilities = listOf(),
            )
        ),
        description = { },
        shortDescription = { },
    ),
)

/**
 * 默认 MCP 服务器 — 双子星：Hermes Agent
 *
 * Hermes MCP Bridge (127.0.0.1:6789) 将 Hermes Agent 的能力
 * 暴露为 MCP 工具，RikkaHub 通过 McpManager 自动发现并调用。
 * 支持 StreamableHTTP 传输协议（通过 hermes_mcp_server.py 桥接）。
 */
val DEFAULT_MCP_SERVERS = listOf(
    McpServerConfig.StreamableHTTPServer(
        id = DEFAULT_HERMES_MCP_ID,
        url = "http://127.0.0.1:6789/mcp",
        commonOptions = McpCommonOptions(
            enable = true,
            name = "MCP工具",
            headers = emptyList(),
            tools = emptyList(),  // tools auto-discovered on connect
        ),
    ),
)
