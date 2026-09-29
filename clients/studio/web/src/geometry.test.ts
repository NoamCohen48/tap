import { create, type MessageInitShape } from "@bufbuild/protobuf";
import { describe, expect, it } from "vitest";
import { BoundsSchema, Direction } from "./gen/command_pb";
import { ScreenNodeSchema, type ScreenNode } from "./gen/device_pb";
import { NodeFlag } from "./gen/selector_pb";
import { box, dragDirection, hit, scrollableAt, toFrame } from "./geometry";

function node(ref: string, [left, top, right, bottom]: [number, number, number, number], extra: MessageInitShape<typeof ScreenNodeSchema> = {}): ScreenNode {
  const made = create(ScreenNodeSchema, { ref, ...extra });
  made.bounds = create(BoundsSchema, { left, top, right, bottom });
  return made;
}

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

  it("finds the scrollable node the wheel scrolls", () => {
    expect(scrollableAt(nodes, { x: 800, y: 300 })?.ref).toBe("e1");
    expect(scrollableAt(nodes, { x: 800, y: 2100 })).toBeNull();
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
