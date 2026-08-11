// Workflow / Skill types aligned with Kotlin DTOs

export interface SkillMetadata {
  name: string;
  description: string;
  compatibility?: string;
  allowedTools?: string[];
}

export interface SkillDetail {
  metadata: SkillMetadata;
  content: string;
}

export interface SkillFileList {
  files: string[];
}

export interface SkillFile {
  path: string;
  content: string;
}

export interface WorkflowSummary {
  name: string;
  updatedAt: number;
}

export interface WorkflowListResponse {
  workflows: WorkflowSummary[];
}

// ReactFlow node types
export type WorkflowNodeType = "skill_node" | "condition_node" | "output_node";

// Add index signature to satisfy ReactFlow's Record<string, unknown> constraint
export interface SkillNodeData extends Record<string, unknown> {
  label: string;
  skillName: string;
  inputs: Record<string, string>;
  description?: string;
}

export interface ConditionNodeData extends Record<string, unknown> {
  label: string;
  expression: string;
}

export interface OutputNodeData extends Record<string, unknown> {
  label: string;
  format: "text" | "json";
}

export type WorkflowNodeData = SkillNodeData | ConditionNodeData | OutputNodeData;

export interface WorkflowGraph {
  nodes: WorkflowNode[];
  edges: WorkflowEdge[];
}

export interface WorkflowNode {
  id: string;
  type: WorkflowNodeType;
  position: { x: number; y: number };
  data: WorkflowNodeData;
}

export interface WorkflowEdge {
  id: string;
  source: string;
  target: string;
  sourceHandle?: string;
  targetHandle?: string;
  label?: string;
}
