# Hermes MCP Bridge

> **双子星计划 (Project Gemini) 的替代方案** — 原计划已冻结 (`docs/gemini-freeze.md`)。
> 本组件是其核心成果的直接继承，架构更简洁、耦合更低。

将 Hermes Agent 的 MCP 工具暴露给 RikkaHub 的标准通道。

## 架构

```
RikkaHub APP
  └── McpManager ──→ :6789 (HTTP StreamableHTTP)
                        └── hermes_mcp_server.py
                              └── hermes mcp serve (stdio)
                                    ├── 10 bridge tools (消息桥接)
                                    └── 105+ skill__* tools (Hermes Skills)
```

## 端口

| 端口 | 服务 | 协议 |
|------|------|------|
| 6789 | Hermes MCP Bridge | HTTP → StreamableHTTP MCP |
| 18888 | model-router | OpenAI-compatible API |
| 6791 | sse-proxy (DeepSeek) | SSE / OpenAI-compatible |

## 手动启动

```bash
# 从 rikkahub-src 目录
python3 hermes_mcp_server.py

# 或者通过 runsv（已配置自启）
sv status hermes-mcp
```

## 健康检查

```bash
curl http://127.0.0.1:6789/
# → {"status": "ok", "hermes_pid": 19818, "skills_loaded": 105}
```

## MCP 端点

```bash
# tools/list — 发现所有工具
curl -X POST http://127.0.0.1:6789/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'

# tools/call — 调用工具
curl -X POST http://127.0.0.1:6789/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"messages_send","arguments":{}}}'
```

## 工具命名规则

| 前缀 | 来源 | 数量 |
|------|------|------|
| `conversations_*` | Hermes 桥接 | ~2 |
| `messages_*` | Hermes 桥接 | ~2 |
| `attachments_*` | Hermes 桥接 | ~1 |
| `events_*` | Hermes 桥接 | ~1 |
| `skill__*` | Hermes Skills | 105+ |

`skill__*` 工具命名规范：`skill__{skill_name}`，从 SKILL.md 的 frontmatter 自动生成。

## McpManager 集成

RikkaHub 的 `DefaultProviders.kt` 中定义：

```kotlin
val DEFAULT_MCP_SERVERS = listOf(
    McpServerConfig.StreamableHTTPServer(
        url = "http://127.0.0.1:6789/mcp",
        commonOptions = McpCommonOptions(
            enable = true,
            name = "Hermes Agent",
            tools = emptyList(),  // auto-discovered on connect
        ),
    ),
)
```

`PreferencesStore.kt` 在 settings 初始化时自动将默认 MCP 服务器注入到已保存的配置中（如果不存在）。

## 自启动 (runsv)

通过 Termux services 配置了 runsv 守护进程，路径：

```
~/.termux/services/hermes-mcp/
```

- 崩溃自动重启
- `sv status hermes-mcp` 查看状态
- `sv restart hermes-mcp` 手动重启

## 故障排查

1. **MCP 连接失败** → `sv status hermes-mcp` 检查守护进程，`curl :6789/` 验证 HTTP 响应
2. **工具列表为空** → 直接发 `tools/list` JSON-RPC 请求到 `/mcp` 端点
3. **SSE 握手失败** → 检查 Logcat 过滤 `McpManager`
