import { useCallback, useEffect, useMemo, useState } from "react";
import { useParams, useNavigate } from "react-router";
import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  type Node,
  type Edge,
  type Connection,
  useNodesState,
  useEdgesState,
  addEdge,
  type NodeTypes,
  type NodeProps,
} from "@xyflow/react";
import "@xyflow/react/dist/style.css";
import { Button } from "~/components/ui/button";
import { ScrollArea } from "~/components/ui/scroll-area";
import { Input } from "~/components/ui/input";
import { Card, CardContent, CardHeader, CardTitle } from "~/components/ui/card";
import {
  getWorkflow,
  saveWorkflow,
  listSkills,
} from "~/services/workflow-api";
import type {
  SkillMetadata,
  WorkflowNodeData,
  SkillNodeData,
  ConditionNodeData,
  OutputNodeData,
  WorkflowGraph,
  WorkflowNode,
  WorkflowEdge,
} from "~/types/workflow";

// Type aliases for ReactFlow
type RFNode = Node<WorkflowNodeData, string>;
type RFEdge = Edge;

/** Skill Node component rendered on the ReactFlow canvas */
function SkillFlowNode({ data }: NodeProps<RFNode>) {
  const d = data as SkillNodeData;
  return (
    <div className="rounded-lg border bg-card px-4 py-2 shadow-sm">
      <div className="font-medium text-sm">{d.label}</div>
      {d.description && (
        <div className="text-muted-foreground text-xs mt-1">{d.description}</div>
      )}
    </div>
  );
}

/** Condition Flow Node */
function ConditionFlowNode({ data }: NodeProps<RFNode>) {
  const d = data as ConditionNodeData;
  return (
    <div className="rounded-lg border border-amber-300 bg-amber-50 px-4 py-2 shadow-sm dark:border-amber-700 dark:bg-amber-950">
      <div className="font-medium text-sm">{d.label}</div>
    </div>
  );
}

/** Output Flow Node */
function OutputFlowNode({ data }: NodeProps<RFNode>) {
  const d = data as OutputNodeData;
  return (
    <div className="rounded-lg border border-green-300 bg-green-50 px-4 py-2 shadow-sm dark:border-green-700 dark:bg-green-950">
      <div className="font-medium text-sm">{d.label}</div>
      {d.format && (
        <div className="text-muted-foreground text-xs mt-1">{d.format}</div>
      )}
    </div>
  );
}

const nodeTypes: NodeTypes = {
  skill_node: SkillFlowNode,
  condition_node: ConditionFlowNode,
  output_node: OutputFlowNode,
};

let nodeIdCounter = 0;
function getNodeId() {
  return `node_${++nodeIdCounter}_${Date.now()}`;
}

export default function WorkflowEditorPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [name, setName] = useState(id ?? "untitled");
  const [skills, setSkills] = useState<SkillMetadata[]>([]);
  const [nodes, setNodes, onNodesChange] = useNodesState<RFNode>([]);
  const [edges, setEdges, onEdgesChange] = useEdgesState<RFEdge>([]);
  const [selectedNode, setSelectedNode] = useState<RFNode | null>(null);
  const [saving, setSaving] = useState(false);

  // Load skills
  useEffect(() => {
    listSkills()
      .then(setSkills)
      .catch((e) => console.error("Failed to load skills", e));
  }, []);

  // Load workflow
  useEffect(() => {
    if (!id) return;
    getWorkflow(id)
      .then((data) => {
        const graph = data as unknown as WorkflowGraph;
        if (graph.nodes) {
          setNodes(graph.nodes as RFNode[]);
          setEdges(graph.edges as RFEdge[]);
        }
      })
      .catch((e) => console.error("Failed to load workflow", e));
  }, [id, setNodes, setEdges]);

  const onConnect = useCallback(
    (connection: Connection) => {
      setEdges((eds) => addEdge(connection, eds));
    },
    [setEdges],
  );

  const onNodeClick = useCallback(
    (_: React.MouseEvent, node: RFNode) => {
      setSelectedNode(node);
    },
    [],
  );

  const onPaneClick = useCallback(() => {
    setSelectedNode(null);
  }, []);

  const addSkillNode = useCallback(
    (skill: SkillMetadata) => {
      const newNode: RFNode = {
        id: getNodeId(),
        type: "skill_node",
        position: { x: 250, y: nodes.length * 120 + 50 },
        data: {
          label: skill.name,
          skillName: skill.name,
          inputs: {},
          description: skill.description,
        } as SkillNodeData,
      };
      setNodes((nds) => [...nds, newNode]);
    },
    [nodes.length, setNodes],
  );

  const addOutputNode = useCallback(() => {
    const newNode: RFNode = {
      id: getNodeId(),
      type: "output_node",
      position: { x: 550, y: nodes.length * 120 + 50 },
      data: { label: "Output", format: "text" } as OutputNodeData,
    };
    setNodes((nds) => [...nds, newNode]);
  }, [nodes.length, setNodes]);

  const handleSave = async () => {
    if (!name.trim()) return;
    setSaving(true);
    try {
      const graph: WorkflowGraph = {
        nodes: nodes as unknown as WorkflowNode[],
        edges: edges as unknown as WorkflowEdge[],
      };
      await saveWorkflow(name.trim(), graph as unknown as object);
      alert("Workflow saved!");
    } catch (e) {
      console.error("Failed to save workflow", e);
      alert("Failed to save workflow");
    } finally {
      setSaving(false);
    }
  };

  const selectedNodeData = useMemo(() => {
    if (!selectedNode) return null;
    return selectedNode.data as WorkflowNodeData;
  }, [selectedNode]);

  return (
    <div className="flex h-screen">
      {/* Left sidebar - Skill Palette */}
      <div className="w-72 border-r bg-card flex flex-col">
        <div className="p-4 border-b">
          <Input
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="Workflow name"
            className="font-medium"
          />
        </div>
        <ScrollArea className="flex-1 p-3">
          <div className="mb-4">
            <Button
              variant="outline"
              size="sm"
              className="w-full"
              onClick={addOutputNode}
            >
              + Output Node
            </Button>
          </div>
          <h3 className="text-sm font-medium mb-2 px-1">Skills</h3>
          <div className="space-y-2">
            {skills.map((skill) => (
              <Card
                key={skill.name}
                className="cursor-grab active:cursor-grabbing hover:bg-accent/50 transition-colors"
                onClick={() => addSkillNode(skill)}
              >
                <CardHeader className="p-3">
                  <CardTitle className="text-sm">{skill.name}</CardTitle>
                  {skill.description && (
                    <p className="text-muted-foreground text-xs line-clamp-2">
                      {skill.description}
                    </p>
                  )}
                </CardHeader>
              </Card>
            ))}
          </div>
        </ScrollArea>
      </div>

      {/* Main canvas */}
      <div className="flex-1 relative">
        <ReactFlow
          nodes={nodes}
          edges={edges}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onConnect={onConnect}
          onNodeClick={onNodeClick}
          onPaneClick={onPaneClick}
          nodeTypes={nodeTypes}
          fitView
          deleteKeyCode="Delete"
          className="bg-background"
        >
          <Background />
          <Controls />
          <MiniMap
            nodeStrokeColor="#666"
            nodeColor="#fff"
            nodeBorderRadius={4}
          />
        </ReactFlow>

        {/* Top toolbar */}
        <div className="absolute top-4 right-4 flex gap-2 z-10">
          <Button
            variant="outline"
            size="sm"
            onClick={() => navigate("/workflows")}
          >
            Back
          </Button>
          <Button size="sm" onClick={handleSave} disabled={saving}>
            {saving ? "Saving..." : "Save"}
          </Button>
        </div>
      </div>

      {/* Right sidebar - Node config */}
      {selectedNode && selectedNodeData && (
        <div className="w-72 border-l bg-card p-4">
          <h3 className="font-medium mb-3">Node Configuration</h3>
          <div className="space-y-3">
            <div>
              <label className="text-xs text-muted-foreground block mb-1">
                Type
              </label>
              <Input
                value={selectedNode.type ?? ""}
                readOnly
                className="bg-muted"
              />
            </div>
            <div>
              <label className="text-xs text-muted-foreground block mb-1">
                Label
              </label>
              <Input
                value={selectedNodeData.label ?? ""}
                readOnly
                className="bg-muted"
              />
            </div>
            {selectedNode.type === "skill_node" && (
              <div>
                <label className="text-xs text-muted-foreground block mb-1">
                  Skill Name
                </label>
                <Input
                  value={
                    (selectedNodeData as SkillNodeData)?.skillName ?? ""
                  }
                  readOnly
                  className="bg-muted"
                />
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
