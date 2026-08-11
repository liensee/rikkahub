import api from "./api";
import type { SkillMetadata, WorkflowSummary } from "~/types/workflow";

/** SKILLS API */
export async function listSkills(): Promise<SkillMetadata[]> {
  return api.get<SkillMetadata[]>("skills");
}

export async function getSkill(name: string): Promise<{ metadata: SkillMetadata; content: string }> {
  return api.get<{ metadata: SkillMetadata; content: string }>(`skills/${encodeURIComponent(name)}`);
}

/** WORKFLOWS API */
export async function listWorkflows(): Promise<WorkflowSummary[]> {
  const res = await api.get<{ workflows: WorkflowSummary[] }>("workflows");
  return res.workflows;
}

export async function getWorkflow(name: string): Promise<object> {
  return api.get<object>(`workflows/${encodeURIComponent(name)}`);
}

export async function saveWorkflow(name: string, content: object): Promise<void> {
  await api.post(`workflows/${encodeURIComponent(name)}`, content);
}

export async function deleteWorkflow(name: string): Promise<void> {
  await api.delete(`workflows/${encodeURIComponent(name)}`);
}
