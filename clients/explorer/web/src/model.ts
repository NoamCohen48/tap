import dagre from "@dagrejs/dagre";
import { runSchema } from "./schema";
import { MarkerType, type Edge, type Node } from "@xyflow/react";

export interface Action {
  source: string;
  verb: string;
  status: "blocked" | "pending" | "attempted" | "uncertain";
  target: {
    label?: string;
    resource?: string;
    selector?: unknown;
    blocked_reason?: string | null;
    risk?: string;
  };
  scenario: { value?: string };
  approval: string | null;
}
export interface Attempt {
  action: string;
  before: string;
  after: string | null;
  destination: string | null;
  status: string;
  note: string;
}
export interface State {
  depth: number;
  signature: string;
  observations: string[];
}
export interface Observation {
  evidence: {
    screenshot?: string;
    snapshot?: string;
    moving?: boolean;
    settle_error?: string | null;
    features?: {
      nodes?: { text?: string | null; class?: string | null }[];
    } | null;
  };
}
export interface Graph {
  format: string;
  context: Record<string, unknown>;
  states: Record<string, State>;
  actions: Record<string, Action>;
  attempts: Record<string, Attempt>;
  observations: Record<string, Observation>;
}
export interface Interpretation {
  status: string;
  model?: string;
  error?: string;
  proposal?: {
    title: string;
    summary: string;
    actions: {
      candidate: string;
      intent: string;
      risk: string;
      reason: string;
    }[];
  };
}
export interface Run {
  graph: Graph;
  live: boolean;
  current: string | null;
  halted: string | null;
  observation: string | null;
  initial_error: string | null;
  next: { action: string | null; reason: string };
  interpretations: Record<string, Interpretation>;
}
export type StateData = {
  id: string;
  title: string;
  observation: string | null;
  actionCount: number;
  pendingCount: number;
  current: boolean;
  suggested: boolean;
};
export type StateNode = Node<StateData, "state">;

export function imageUrl(observation: string): string {
  return `/api/image/${encodeURIComponent(observation)}`;
}
export function lastObservation(graph: Graph, state: string): string | null {
  return graph.states[state]?.observations.at(-1) ?? null;
}
export function stateTitle(
  run: Run,
  id: string,
): { title: string; suggested: boolean } {
  const observation = lastObservation(run.graph, id);
  const proposal = observation
    ? run.interpretations[observation]?.proposal
    : undefined;
  if (proposal) return { title: proposal.title, suggested: true };
  if (!id.startsWith("s-"))
    return { title: id.replaceAll("-", " "), suggested: false };
  const texts = observation
    ? (run.graph.observations[observation]?.evidence.features?.nodes ?? [])
    : [];
  const heading = texts.find(
    (node) => node.text?.trim() && node.text.length <= 60,
  );
  return {
    title: heading?.text ?? `Observed state ${id.slice(2, 8)}`,
    suggested: false,
  };
}
export function buildGraph(run: Run): { nodes: StateNode[]; edges: Edge[] } {
  const groups = new Map<
    string,
    {
      source: string;
      target: string;
      action: string;
      count: number;
      attempts: string[];
    }
  >();
  for (const [id, attempt] of Object.entries(run.graph.attempts)) {
    const action = run.graph.actions[attempt.action];
    if (attempt.status !== "succeeded" || !attempt.destination || !action)
      continue;
    const key = JSON.stringify([
      action.source,
      attempt.action,
      attempt.destination,
    ]);
    const previous = groups.get(key);
    if (previous) {
      previous.count++;
      previous.attempts.push(id);
    } else
      groups.set(key, {
        source: action.source,
        target: attempt.destination,
        action: attempt.action,
        count: 1,
        attempts: [id],
      });
  }
  const layout = new dagre.graphlib.Graph().setDefaultEdgeLabel(() => ({}));
  layout.setGraph({
    rankdir: "LR",
    nodesep: 36,
    ranksep: 105,
    marginx: 24,
    marginy: 24,
  });
  const nodes: StateNode[] = Object.keys(run.graph.states)
    .sort()
    .map((id) => {
      layout.setNode(id, { width: 188, height: 246 });
      const actions = Object.values(run.graph.actions).filter(
        (action) => action.source === id,
      );
      return {
        id,
        type: "state",
        position: { x: 0, y: 0 },
        data: {
          id,
          ...stateTitle(run, id),
          observation: lastObservation(run.graph, id),
          actionCount: actions.length,
          pendingCount: actions.filter((action) => action.status === "pending")
            .length,
          current: id === run.current,
          suggested: stateTitle(run, id).suggested,
        },
      };
    });
  const edges: Edge[] = Array.from(groups.entries())
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([id, group]) => {
      layout.setEdge(group.source, group.target);
      const action = run.graph.actions[group.action];
      return {
        id,
        source: group.source,
        target: group.target,
        type: group.source === group.target ? "smoothstep" : "default",
        label: `${action.target.label ?? action.target.resource ?? group.action.split("/").at(-1)}${group.count > 1 ? ` ×${group.count}` : ""}`,
        data: { action: group.action, attempts: group.attempts },
        markerEnd: { type: MarkerType.ArrowClosed },
        style: { strokeWidth: 1.4 },
        labelStyle: { fontSize: 10 },
      };
    });
  if (nodes.length) dagre.layout(layout);
  for (const node of nodes) {
    const point = layout.node(node.id);
    node.position = { x: point.x - 94, y: point.y - 123 };
  }
  return { nodes, edges };
}

export async function request(
  operation?: string,
  body: Record<string, unknown> = {},
): Promise<Run> {
  const token =
    document.querySelector<HTMLMetaElement>('meta[name="explorer-token"]')
      ?.content ?? "";
  const response = await fetch(
    operation ? `/api/${operation}` : "/api/run",
    operation
      ? {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
            "X-Explorer-Token": token,
          },
          body: JSON.stringify(body),
        }
      : { cache: "no-store" },
  );
  const data: unknown = await response.json();
  if (!response.ok)
    throw new Error(
      typeof data === "object" && data && "error" in data
        ? String(data.error)
        : `Request failed (${response.status})`,
    );
  if (
    !data ||
    typeof data !== "object" ||
    !("graph" in data) ||
    !("live" in data)
  )
    throw new Error("Invalid workbench response");
  const parsed = runSchema.safeParse(data);
  if (!parsed.success) throw new Error("Invalid workbench graph or metadata");
  return parsed.data;
}
