// Diagnostic touch geometry: never resolve selectors or infer positions from their text.
import { Direction, PinchDirection } from "./gen/command_pb";
import type { Action } from "./activity";

export const MARKER_NS = 1_400_000_000n;
export type Point = { x: number; y: number };
export type Mark = { kind: "tap" | "hold" | "double" | "path" | "pinch"; points: Point[]; width: number; height: number };

/** A schematic mark, not a recording of individual input events. */
export function markFor(action: Action, width: number, height: number): Mark | undefined {
  const feedback = action.event.touchFeedback;
  if (action.failed || !feedback || feedback.displayWidth <= 0 || feedback.displayHeight <= 0) return;
  // A rotation between resolution and capture cannot be mapped safely.
  if (Math.abs(feedback.displayWidth / feedback.displayHeight - width / height) > 0.02) return;
  const call = action.event.call;
  if (call.case !== "command") return;
  const op = call.value.op;
  const { x, y, bounds } = feedback;
  const mark: Mark = { kind: "tap", points: [{ x, y }], width: feedback.displayWidth, height: feedback.displayHeight };
  switch (op.case) {
    case "tap": return mark;
    case "longTap": case "hold": return { ...mark, kind: "hold" };
    case "doubleTap": case "multiTap": return { ...mark, kind: "double" };
    case "drag":
      if (feedback.endX === undefined || feedback.endY === undefined) return;
      return { ...mark, kind: "path", points: [{ x, y }, { x: feedback.endX, y: feedback.endY }] };
    case "swipe": case "scroll": case "fling": {
      if (!bounds) return;
      const distance = op.case === "fling" ? 1 : (op.value.distancePercent ?? 80) / 100;
      let dx = 0, dy = 0;
      switch (op.value.direction) {
        case Direction.DIR_UP: dy = -1; break;
        case Direction.DIR_DOWN: dy = 1; break;
        case Direction.DIR_LEFT: dx = -1; break;
        case Direction.DIR_RIGHT: dx = 1; break;
        default: return;
      }
      if (op.case !== "swipe") { dx *= -1; dy *= -1; }
      return { ...mark, kind: "path", points: [{ x, y }, {
        x: x + dx * (bounds.right - bounds.left) * 0.8 * distance,
        y: y + dy * (bounds.bottom - bounds.top) * 0.8 * distance,
      }] };
    }
    case "pinch": {
      if (!bounds) return;
      const spread = (bounds.right - bounds.left) * 0.4 * (op.value.percent ?? 80) / 100;
      const left = { x: x - spread, y }, right = { x: x + spread, y };
      const innerLeft = { x: x - spread * 0.15, y }, innerRight = { x: x + spread * 0.15, y };
      return { ...mark, kind: "pinch", points: op.value.direction === PinchDirection.PINCH_OPEN
        ? [innerLeft, left, innerRight, right] : [left, innerLeft, right, innerRight] };
    }
  }
}

/** Playback uses only the daemon's shared monotonic clock, never wall-time conversion. */
export function marksAt(actions: Action[], clockId: string, ns: bigint): Action[] {
  return actions.filter(({ event }) => {
    const start = event.startedMonotonicNs;
    return clockId !== "" && event.clockId === clockId && start !== undefined &&
      ns >= start && ns <= (event.finishedMonotonicNs ?? start) + MARKER_NS;
  }).slice(-8);
}
