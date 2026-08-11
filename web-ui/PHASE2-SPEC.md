项目: RikkaHub Web UI - Workflow Studio (Phase 2)
笔记本: leejee@192.168.1.100
源目录: ~/web-ui/
架构: React 19 + React Router 7 + ReactFlow + shadcn/ui + Tailwind v4 + Zustand

## 任务说明

在现有 web-ui 基础上，新增 Workflow Studio 板块:
1. 技能浏览页 — 显示 RikkaHub 所有可用 skill（调用 GET /api/skills）
2. 工作流编辑器 — 拖拽式 DAG 编辑器（ReactFlow）
3. 工作流列表页 — 管理已有工作流

## 需要创建的文件

### 1. app/types/workflow.ts
工作流和技能的 TypeScript 类型定义，与 Kotlin DTO 对齐。

### 2. app/types/skill.ts  
Skill API 返回的类型定义。

### 3. app/routes/workflows.tsx
工作流列表页（/workflows 路由）
- 显示已有工作流列表（调用 GET /api/workflows）
- 新建工作流按钮
- 每个工作流卡片：名称、更新时间、操作（编辑/删除）

### 4. app/routes/workflows_.$id.tsx
工作流编辑器页（/workflows/:id 路由）
- ReactFlow 画布
- 左侧技能面板（可拖入画布）
- 顶部操作栏（保存/运行/导出）
- 右侧节点配置面板

### 5. app/services/workflow-api.ts
Workflow API 调用封装（基于现有 api.ts）

### 6. app/components/workflow/
- SkillPalette.tsx — 左侧技能选择面板
- WorkflowCanvas.tsx — ReactFlow 画布组件  
- NodeConfig.tsx — 节点配置面板
- WorkflowToolbar.tsx — 顶部工具栏

### 7. 路由注册
更新 app/routes.ts 添加新路由

## API 对接

现有后端 API（Phase 1 已完成）:
- GET /api/skills — 列出所有技能
- GET /api/workflows — 列出工作流
- GET /api/workflows/{name} — 获取工作流内容
- POST /api/workflows/{name} — 保存工作流
- DELETE /api/workflows/{name} — 删除工作流

开发时代理已在 vite.config.ts 中配置: /api → localhost:8080

## 技术选型

- ReactFlow v12（npm i @xyflow/react）
- shadcn/ui 已有组件: Button, Card, Dialog, Input, ScrollArea
- Zustand 管理工作流编辑状态
- Tailwind v4 样式

## 执行步骤

1. npm install @xyflow/react （ReactFlow 核心库）
2. 创建类型文件
3. 创建组件
4. 创建路由页面
5. 注册路由
6. 验证 pnpm run build 能通过

## 节点类型设计

工作流 DAG 节点类型:
- skill_node: 执行一个 skill
  - data: { skillName: string, inputs: Record<string, any> }
- condition_node: 条件分支
  - data: { expression: string }
- output_node: 输出结果
  - data: { format: "text" | "json" }

## 质量要求

- TypesScript 严格模式
- 与现有代码风格一致（参照 conversations.tsx）
- 使用现有 api.ts 服务层
- 国际化 i18n 支持（中英文）
