import { describe, expect, it } from "vitest";
import { runSchema } from "./schema";
import { buildGraph, stateTitle, type Run } from "./model";
const run: Run = {
  live: false,
  current: null,
  halted: null,
  observation: null,
  initial_error: null,
  next: { action: null, reason: "frontier_resolved" },
  interpretations: {},
  graph: {
    format: "tap-exploration/1",
    context: {},
    states: {
      home: { depth: 0, signature: "home", observations: ["o1"] },
      detail: { depth: 1, signature: "detail", observations: ["o2"] },
    },
    observations: { o1: { evidence: {} }, o2: { evidence: {} } },
    actions: {
      open: {
        source: "home",
        verb: "tap",
        status: "attempted",
        target: { label: "Open" },
        scenario: {},
        approval: "operator",
      },
      loop: {
        source: "detail",
        verb: "tap",
        status: "attempted",
        target: { label: "Loop" },
        scenario: {},
        approval: "operator",
      },
    },
    attempts: {
      a: {
        action: "open",
        before: "o1",
        after: "o2",
        destination: "detail",
        status: "succeeded",
        note: "",
      },
      b: {
        action: "open",
        before: "o1",
        after: "o2",
        destination: "detail",
        status: "succeeded",
        note: "",
      },
      c: {
        action: "loop",
        before: "o2",
        after: "o2",
        destination: "detail",
        status: "succeeded",
        note: "",
      },
      d: {
        action: "open",
        before: "o1",
        after: null,
        destination: null,
        status: "indeterminate",
        note: "",
      },
    },
  },
};
describe("observed graph projection", () => {
  it("groups visual edges without losing executions or self-loops", () => {
    const graph = buildGraph(run);
    expect(graph.nodes).toHaveLength(2);
    expect(graph.edges).toHaveLength(2);
    expect(graph.edges.find((e) => e.source === "home")?.label).toBe("Open ×2");
    expect(
      graph.edges.find((e) => e.source === "home")?.data?.attempts,
    ).toEqual(["a", "b"]);
    expect(graph.edges.some((e) => e.source === e.target)).toBe(true);
  });
  it("never makes indeterminate input an observed edge", () => {
    expect(
      buildGraph(run).edges.flatMap((e) => e.data?.attempts ?? []),
    ).not.toContain("d");
  });
  it("lays out deterministically", () => {
    expect(buildGraph(run)).toEqual(buildGraph(run));
  });
  it("labels AI titles as suggestions, not state identity", () => {
    const next = structuredClone(run);
    next.interpretations.o1 = {
      status: "valid",
      proposal: { title: "Library", summary: "Example", actions: [] },
    };
    expect(stateTitle(next, "home")).toEqual({
      title: "Library",
      suggested: true,
    });
    expect(next.graph.states.home.signature).toBe("home");
  });
  it("validates the full boundary without pretending partial metadata is a graph", () => {
    expect(runSchema.safeParse(run).success).toBe(true);
    expect(runSchema.safeParse({ graph: {}, live: false }).success).toBe(false);
    expect(
      runSchema.safeParse({
        ...run,
        graph: {
          ...run.graph,
          states: { home: { observations: "not an array" } },
        },
      }).success,
    ).toBe(false);
  });
  it("retains unknown selector provenance and tolerates an unclassified capture", () => {
    const next = structuredClone(run);
    next.graph.observations.o1.evidence.features = null;
    const parsed = runSchema.parse(next);
    expect(parsed.graph.observations.o1.evidence.features).toBeNull();
    expect(stateTitle(parsed, "home").title).toBe("home");
  });
});
