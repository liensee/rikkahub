# 双子星计划 (Project Gemini) — 状态：已冻结 ❄️

## 概述

双子星计划于 2026 年 7 月正式启动，目标是**将 RikkaHub 从纯聊天客户端转变为 Hermes Agent 的交互终端**。该计划已成功达成核心目标，现正式冻结，后续开发转入新的架构方向。

## 已完成的成果

| 阶段 | 内容 | 状态 |
|------|------|------|
| 远程模型部署 | 3×P100 服务器，35B + 9B 双模型 systemd 化 | ✅ |
| model-router | 18888 端口，自动路由 35B/9B | ✅ |
| sse-proxy | 6791 端口，DeepSeek API 中转 | ✅ |
| reasoning_content 修复 | 35B 模型输出修复，content 字段标准化 | ✅ |
| Hermes MCP Bridge **⭐** | `hermes_mcp_server.py` — Stdio→HTTP 桥接，暴露 115 个工具 | ✅ |
| 本地模型 detectLocalModel | 启动时自动探测模型端口 | ✅ |
| 安全审计 | 移除硬编码 Tailscale IP，归零信任 | ✅ |

## 冻结原因

双子星计划的核心目标——**打通 RikkaHub ↔ Hermes Agent 的 Agent 通道**——已经实现。后续不再以此分支名义开发，而是直接继承成果，以更简洁的架构继续演进。

## 替代方案：Hermes MCP Bridge

双子星计划的架构经过迭代后，最终形态简化为：

```
RikkaHub APP
  └── McpManager ──→ :6789 (HTTP StreamableHTTP)
                        └── hermes_mcp_server.py
                              └── hermes mcp serve (stdio)
                                    ├── 10 bridge tools (消息桥接)
                                    └── 105+ skill__* tools (Hermes Skills)
```

关键区别：
- **去中心化**：不再依赖远程 Tailscale 服务器，MCP Bridge 本地 runsv 运行
- **标准化**：使用 MCP 协议（StreamableHTTP），不引入私有协议
- **可扩展**：新增 Skill 自动发现为 MCP 工具，无需改代码

## 技术债 / 已知问题

- `auto/local-llm-integration` 分支保留本地，GitHub 远程已删除
- `detectLocalModel()` 目前只扫 `127.0.0.1`，如需远程调试需手动配置
- `web-ui` 静态资源仍是占位符

## 未来发展

以下功能不再属于"双子星计划"，而是作为 Hermes MCP Bridge 的常规迭代：

1. **Skill 排序与分类** — 在 APP 内对 skill__* 工具排序/分组
2. **串行/并行 Skill 编排** — 允许用户编排 Skill 执行流程
3. **自定义 MCP 服务器地址** — 设置页开放高级配置入口
4. **MCP 连接状态指示器** — UI 上显示连接状态（已连接/断开）
5. **跨设备调试** — 通过 Tailscale 连接远程 Hermes Agent

---

*最后更新: 2026-07-04*
