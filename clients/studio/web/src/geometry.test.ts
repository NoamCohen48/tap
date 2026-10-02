import { create, type MessageInitShape } from "@bufbuild/protobuf";
import { describe, expect, it } from "vitest";
import { BoundsSchema, Direction } from "./gen/command_pb";
import { ScreenNodeSchema, type ScreenNode } from "./gen/device_pb";
import { NodeFlag, SelectorSchema, TextProperty } from "./gen/selector_pb";
import { box, dragDirection, hit, scrollableAt, tapLabel, toFrame } from "./geometry";

function node(ref: string, [left, top, right, bottom]: [number, number, number, number], extra: MessageInitShape<typeof ScreenNodeSchema> = {}): ScreenNode {
  const made = create(ScreenNodeSchema, { ref, ...extra });
  made.bounds = create(BoundsSchema, { left, top, right, bottom });
  return made;
}

const text = (value: string) => create(SelectorSchema, { node: { kind: { case: "match", value: { property: TextProperty.PROPERTY_TEXT, value } } } });
const at = (index: number) =>
  create(SelectorSchema, { node: { kind: { case: "match", value: { property: TextProperty.PROPERTY_CLASS_NAME, value: "android.widget.LinearLayout" } } }, pick: { case: "at", value: { index } } });

describe("box", () => {
  it("is the bounds in percent of the frame, portrait and landscape alike", () => {
    const button = node("e1", [108, 240, 540, 480]);
    expect(box(button, 1080, 2400)).toEqual({ left: 10, top: 10, width: 40, height: 10 });
    // The same node after rotation: bounds and screenshot both follow the display.
    const rotated = node("e1", [240, 108, 1200, 540]);
    expect(box(rotated, 2400, 1080)).toEqual({ left: 10, top: 10, width: 40, height: 40 });
  });

  it("clips to the frame and drops nodes off it", () => {
    expect(box(node("e1", [-100, 0, 540, 240]), 1080, 2400)).toEqual({ left: 0, top: 0, width: 50, height: 10 });
    expect(box(node("e2", [0, 2400, 1080, 2600]), 1080, 2400)).toBeNull();
    expect(box(create(ScreenNodeSchema, { ref: "e3" }), 1080, 2400)).toBeNull();
  });
});

describe("hit", () => {
  const list = node("e1", [0, 200, 1080, 2000], { depth: 1, flags: [NodeFlag.FLAG_SCROLLABLE] });
  const row = node("e2", [0, 200, 1080, 500], { depth: 2 });
  const add = node("e3", [700, 250, 1000, 450], { depth: 3 });
  const nodes = [list, row, add];

  it("picks the smallest node under the point", () => {
    expect(hit(nodes, { x: 800, y: 300 })?.ref).toBe("e3");
    expect(hit(nodes, { x: 100, y: 300 })?.ref).toBe("e2");
    expect(hit(nodes, { x: 100, y: 900 })?.ref).toBe("e1");
    expect(hit(nodes, { x: 100, y: 100 })).toBeNull();
  });

  it("prefers the deeper node of two with the same bounds", () => {
    const wrapper = node("e4", [700, 250, 1000, 450], { depth: 2 });
    expect(hit([add, wrapper], { x: 800, y: 300 })?.ref).toBe("e3");
  });

  describe("over several windows", () => {
    // Dump order: the active window (a dialog) first, then the activity under it, then the status bar.
    const dialog = node("d0", [100, 800, 980, 1400], { depth: 0 });
    const ok = node("d1", [700, 1250, 950, 1380], { depth: 1, interactive: true });
    const activity = node("a0", [0, 0, 1080, 2400], { depth: 0 });
    const rowBehind = node("a1", [0, 1000, 1080, 1150], { depth: 1, interactive: true });
    const rowBelow = node("a2", [0, 1600, 1080, 1750], { depth: 1, interactive: true });
    const statusBar = node("s0", [0, 0, 1080, 80], { depth: 0 });
    const clock = node("s1", [20, 10, 200, 70], { depth: 1 });
    const frame = [dialog, ok, activity, rowBehind, rowBelow, statusBar, clock];
    const interactive = (n: ScreenNode) => n.interactive;

    it("never picks a node behind a dialog", () => {
      expect(hit(frame, { x: 500, y: 1050 })?.ref).toBe("d0");
      expect(hit(frame, { x: 800, y: 1300 }, interactive)?.ref).toBe("d1");
      // Nothing interactive on the dialog there: nothing, not the row under it.
      expect(hit(frame, { x: 500, y: 1050 }, interactive)).toBeNull();
    });

    it("reaches the activity outside the dialog and the status bar over an edge-to-edge activity", () => {
      expect(hit(frame, { x: 500, y: 1650 }, interactive)?.ref).toBe("a2");
      expect(hit(frame, { x: 100, y: 40 })?.ref).toBe("s1");
    });
  });

  it("finds the scrollable node the wheel scrolls", () => {
    expect(scrollableAt(nodes, { x: 800, y: 300 })?.ref).toBe("e1");
    expect(scrollableAt(nodes, { x: 800, y: 2100 })).toBeNull();
  });
});

describe("tapLabel", () => {
  const settings = node("w0", [0, 0, 1080, 2400], { depth: 0 });
  const row = node("r1", [0, 300, 1080, 500], { depth: 1, interactive: true, byIndex: true, selector: at(1) });
  const icon = node("r2", [20, 350, 120, 450], { depth: 2 });
  const title = node("r3", [150, 320, 900, 400], { depth: 2, selector: text("Apps") });
  const summary = node("r4", [150, 410, 900, 480], { depth: 2, selector: text("Recent apps") });
  const toggle = node("r5", [950, 350, 1060, 450], { depth: 2, interactive: true, selector: text("On") });
  const toggleText = node("r6", [960, 360, 1050, 440], { depth: 3, selector: text("Off") });
  const next = node("n1", [0, 500, 1080, 700], { depth: 1, interactive: true, byIndex: true, selector: at(2) });
  const frame = [settings, row, icon, title, summary, toggle, toggleText, next];

  it("is the row's first label with a selector of its own", () => {
    expect(tapLabel(frame, row)?.ref).toBe("r3");
  });

  it("skips a label whose centre is on another interactive node, and a row's index-picked parts", () => {
    const indexTitle = node("r3", [150, 320, 900, 400], { depth: 2, byIndex: true, selector: at(5) });
    expect(tapLabel([settings, row, icon, indexTitle, toggle, toggleText, next], row)).toBeNull();
    expect(tapLabel([settings, row, indexTitle, summary, next], row)?.ref).toBe("r4");
  });

  it("is nothing outside the row's subtree", () => {
    expect(tapLabel(frame, next)).toBeNull();
  });
});

describe("pointer", () => {
  it("maps a client point on the scaled picture to device pixels", () => {
    expect(toFrame({ x: 60, y: 130 }, { left: 10, top: 30, width: 270, height: 600 }, 1080, 2400)).toEqual({ x: 200, y: 400 });
  });

  it("reads a drag as the direction the finger moved, and a short one as a click", () => {
    expect(dragDirection({ x: 900, y: 500 }, { x: 200, y: 520 }, 40)).toBe(Direction.DIR_LEFT);
    expect(dragDirection({ x: 200, y: 500 }, { x: 900, y: 450 }, 40)).toBe(Direction.DIR_RIGHT);
    expect(dragDirection({ x: 500, y: 1500 }, { x: 520, y: 400 }, 40)).toBe(Direction.DIR_UP);
    expect(dragDirection({ x: 500, y: 400 }, { x: 480, y: 1500 }, 40)).toBe(Direction.DIR_DOWN);
    expect(dragDirection({ x: 500, y: 400 }, { x: 520, y: 430 }, 40)).toBeNull();
  });
});
