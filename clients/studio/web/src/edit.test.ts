import { create } from "@bufbuild/protobuf";
import { describe, expect, it } from "vitest";
import { ScreenNodeSchema } from "./gen/device_pb";
import { NodeFlag, type Selector } from "./gen/selector_pb";
import { Direction } from "./gen/command_pb";
import { countLine, stepMovement, withMovement } from "./edit";
import * as steps from "./steps";
import { assertsFor, waitsFor } from "./nodes";
import { parseSelector } from "./parse";

function sel(text: string): Selector {
  const parsed = parseSelector(text);
  if ("error" in parsed) throw new Error(parsed.error);
  return parsed.selector;
}

describe("countLine", () => {
  it("wants exactly one match for an action without a pick", () => {
    expect(countLine(1, sel('res("go")'), true)).toEqual({ text: "Matches 1 element now.", warn: false });
    expect(countLine(3, sel('res("go")'), true).warn).toBe(true);
    expect(countLine(0, sel('res("go")'), true).warn).toBe(true);
  });

  it("wants enough matches for the pick, since the count ignores it", () => {
    expect(countLine(2, sel('text("Add").at(1)'), true)).toEqual({ text: "Matches 2 elements now; the step uses element 2.", warn: false });
    expect(countLine(1, sel('text("Add").at(1)'), true)).toEqual({ text: "Matches 1 element now: .at(1) needs at least 2.", warn: true });
    expect(countLine(4, sel('text("Add").first()'), true)).toEqual({ text: "Matches 4 elements now; the step uses the first.", warn: false });
    expect(countLine(0, sel('text("Add").first()'), true).warn).toBe(true);
  });

  it("does not ask a wait or an exists check for exactly one", () => {
    expect(countLine(3, sel('res("row")'), false)).toEqual({ text: "Matches 3 elements now.", warn: false });
  });
});

describe("waitsFor and assertsFor", () => {
  const node = create(ScreenNodeSchema, { ref: "e1", text: "Add", flags: [NodeFlag.FLAG_ENABLED, NodeFlag.FLAG_CHECKABLE] });

  it("offers waits for either state, and exactly one only for a selector without a pick", () => {
    expect(waitsFor(node, sel('text("Add")')).map((o) => o.label)).toEqual([
      "Visible",
      "Exactly one",
      "Gone",
      "Enabled",
      "Disabled",
      "Checked",
      "Unchecked",
    ]);
    expect(waitsFor(node, sel('text("Add").at(1)')).map((o) => o.label)).not.toContain("Exactly one");
  });

  it("offers assertions for the state the node is in now", () => {
    expect(assertsFor(node).map((o) => o.label)).toEqual(["Exists", "Is enabled", "Is unchecked"]);
  });
});

describe("stepMovement and withMovement", () => {
  const list = steps.synthesized(sel('res("list")'));
  const step = (request: ReturnType<typeof steps.scroll>) => request.step!;

  it("reads a scroll until's budget, the SDK defaults where it leaves them out", () => {
    const until = step(steps.scrollUntil(list, sel('text("Row 40")'), Direction.DIR_UP));
    expect(stepMovement(until)).toEqual({ direction: Direction.DIR_UP, distance: 80, maxScrolls: 20 });
    const moved = withMovement(until, { direction: Direction.DIR_DOWN, distance: 50, maxScrolls: 30 });
    expect(moved.kind.case === "scrollUntil" && [moved.kind.value.direction, moved.kind.value.distancePercent, moved.kind.value.maxScrolls]).toEqual([
      Direction.DIR_DOWN,
      50,
      30,
    ]);
  });

  it("changes a scroll or a swipe, and nothing else", () => {
    const scroll = step(steps.scroll(list, Direction.DIR_DOWN, 40));
    expect(stepMovement(scroll)).toEqual({ direction: Direction.DIR_DOWN, distance: 40 });
    const moved = withMovement(scroll, { direction: Direction.DIR_RIGHT, distance: 60 });
    const op = moved.kind.case === "action" ? moved.kind.value.command?.op : undefined;
    expect(op?.case === "scroll" && [op.value.direction, op.value.distancePercent]).toEqual([Direction.DIR_RIGHT, 60]);
    expect(stepMovement(step(steps.gesture(list, "tap")))).toBeNull();
  });
});
