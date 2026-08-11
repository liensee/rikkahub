import { useState, useEffect, useCallback } from "react";
import { useNavigate } from "react-router";
import { Button } from "~/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "~/components/ui/card";
import { listWorkflows, deleteWorkflow } from "~/services/workflow-api";
import type { WorkflowSummary } from "~/types/workflow";

export default function WorkflowsPage() {
  const navigate = useNavigate();
  const [workflows, setWorkflows] = useState<WorkflowSummary[]>([]);
  const [loading, setLoading] = useState(true);

  const loadWorkflows = useCallback(async () => {
    setLoading(true);
    try {
      const data = await listWorkflows();
      setWorkflows(data);
    } catch (e) {
      console.error("Failed to load workflows", e);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadWorkflows();
  }, [loadWorkflows]);

  const handleDelete = async (name: string) => {
    if (!confirm(`Delete workflow "${name}"?`)) return;
    try {
      await deleteWorkflow(name);
      setWorkflows((prev) => prev.filter((w) => w.name !== name));
    } catch (e) {
      console.error("Failed to delete workflow", e);
    }
  };

  const handleCreate = () => {
    const name = prompt("Workflow name:");
    if (name?.trim()) {
      navigate(`/workflows/${encodeURIComponent(name.trim())}`);
    }
  };

  if (loading) {
    return (
      <div className="flex h-screen items-center justify-center">
        <p className="text-muted-foreground">Loading workflows...</p>
      </div>
    );
  }

  return (
    <div className="container mx-auto p-6">
      <div className="mb-6 flex items-center justify-between">
        <div>
          <h1 className="text-3xl font-bold">Workflows</h1>
          <p className="text-muted-foreground mt-1">
            Manage your automated skill workflows
          </p>
        </div>
        <Button onClick={handleCreate}>New Workflow</Button>
      </div>

      {workflows.length === 0 ? (
        <div className="flex h-64 items-center justify-center">
          <div className="text-center">
            <p className="text-muted-foreground mb-4 text-lg">
              No workflows yet
            </p>
            <Button onClick={handleCreate} variant="outline">
              Create your first workflow
            </Button>
          </div>
        </div>
      ) : (
        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
          {workflows.map((wf) => (
            <Card key={wf.name} className="hover:bg-accent/50 cursor-pointer transition-colors">
              <CardHeader
                onClick={() => navigate(`/workflows/${encodeURIComponent(wf.name)}`)}
              >
                <CardTitle className="text-lg">{wf.name}</CardTitle>
                <CardDescription>
                  Updated {new Date(wf.updatedAt).toLocaleString()}
                </CardDescription>
              </CardHeader>
              <CardContent>
                <div className="flex gap-2">
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => navigate(`/workflows/${encodeURIComponent(wf.name)}`)}
                  >
                    Edit
                  </Button>
                  <Button
                    size="sm"
                    variant="destructive"
                    onClick={() => handleDelete(wf.name)}
                  >
                    Delete
                  </Button>
                </div>
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
