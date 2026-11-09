import { create } from "@bufbuild/protobuf";
import { describe, expect, it } from "vitest";
import type { Action } from "./activity";
import { CommandSchema, Direction, PinchDirection, TouchFeedbackSchema } from "./gen/command_pb";
import { LoggedEventSchema } from "./gen/event_log_pb";
import { markFor, marksAt, MARKER_NS } from "./markers";

function action(op: Parameters<typeof create<typeof CommandSchema>>[1] = {}): Action {
  return {
    key: "connection:1", connectionId: "connection", summary: "Tap", failed: false, search: "tap",
    described: {} as Action["described"],
    event: create(LoggedEventSchema, {
      clockId: "daemon", startedMonotonicNs: 100n, finishedMonotonicNs: 200n,
      call: { case: "command", value: create(CommandSchema, op) },
      touchFeedback: create(TouchFeedbackSchema, {
        displayWidth: 1000, displayHeight: 2000, x: 500, y: 820,
        bounds: { left: 100, top: 100, right: 900, bottom: 900 },
      }),
    }),
  };
}

describe("action marks", () => {
  it("places taps at the resolved point, including scaled video", () => {
    const a = action({ op: { case: "tap", value: {} } });
    expect(markFor(a, 500, 1000)?.points).toEqual([{ x: 500, y: 820 }]);
    expect(markFor(a, 2000, 1000)).toBeUndefined();
    a.event.touchFeedback = undefined;
    expect(markFor(a, 500, 1000)).toBeUndefined();
  });
  it("does not invent touch locations for failed or non-touch calls", () => {
    const a = action({ op: { case: "tap", value: {} } });
    a.failed = true;
    expect(markFor(a, 1000, 2000)).toBeUndefined();
    expect(markFor(action({ op: { case: "pressKey", value: { keyCode: 4 } } }), 1000, 2000)).toBeUndefined();
  });
  it("distinguishes holds and repeated taps", () => {
    expect(markFor(action({ op: { case: "longTap", value: {} } }), 1000, 2000)?.kind).toBe("hold");
    expect(markFor(action({ op: { case: "doubleTap", value: {} } }), 1000, 2000)?.kind).toBe("double");
  });
  it("reverses content-scroll directions, not swipe directions", () => {
    const swipe = markFor(action({ op: { case: "swipe", value: { direction: Direction.DIR_UP } } }), 1000, 2000)!;
    const scroll = markFor(action({ op: { case: "scroll", value: { direction: Direction.DIR_DOWN, distancePercent: 50 } } }), 1000, 2000)!;
    expect(swipe.points[1].y).toBeCloseTo(308);
    expect(scroll.points[1].y).toBe(500);
  });
  it("uses the resolved drag destination", () => {
    const a = action({ op: { case: "drag", value: {} } });
    expect(markFor(a, 1000, 2000)).toBeUndefined();
    a.event.touchFeedback!.endX = 600;
    a.event.touchFeedback!.endY = 1500;
    expect(markFor(a, 1000, 2000)?.points[1]).toEqual({ x: 600, y: 1500 });
  });
  it("shows two outward or inward pinch paths", () => {
    const open = markFor(action({ op: { case: "pinch", value: { direction: PinchDirection.PINCH_OPEN } } }), 1000, 2000)!;
    const close = markFor(action({ op: { case: "pinch", value: { direction: PinchDirection.PINCH_CLOSE } } }), 1000, 2000)!;
    expect(open.points[0].x).toBeGreaterThan(open.points[1].x);
    expect(close.points[0].x).toBeLessThan(close.points[1].x);
  });
  it("matches playback by monotonic clock and bounds marker lifetime", () => {
    const a = action();
    expect(marksAt([a], "other-daemon", 150n)).toEqual([]);
    expect(marksAt([a], "daemon", 99n)).toEqual([]);
    expect(marksAt([a], "daemon", 100n)).toEqual([a]);
    expect(marksAt([a], "daemon", 200n + MARKER_NS + 1n)).toEqual([]);
    a.event.startedMonotonicNs = undefined;
    expect(marksAt([a], "daemon", 150n)).toEqual([]);
  });
});
