// Where nodes are on the frame, and which node a point or a gesture means (decision 4: a click is
// hit-tested against the snapshot of the frame it was made on).
//
// Node bounds and the screenshot are both in the display's current orientation, in device
// pixels, so a node's box is its bounds as fractions of the frame: the overlay scales with the
// picture and follows rotation without knowing about it.

import { Direction } from "./gen/command_pb";
import type { ScreenNode } from "./gen/device_pb";
import { NodeFlag } from "./gen/selector_pb";

export type Point = { x: number; y: number };

export type Box = { left: number; top: number; width: number; height: number };

/** The node's box in percent of the frame; `null` when it has no area on it. */
export function box(node: ScreenNode, width: number, height: number): Box | null {
  const b = node.bounds;
  if (!b || width <= 0 || height <= 0) return null;
  const left = Math.max(0, b.left);
  const top = Math.max(0, b.top);
  const right = Math.min(width, b.right);
  const bottom = Math.min(height, b.bottom);
  if (right <= left || bottom <= top) return null;
  return {
    left: (left / width) * 100,
    top: (top / height) * 100,
    width: ((right - left) / width) * 100,
    height: ((bottom - top) / height) * 100,
  };
}

function inside(node: ScreenNode, p: Point): boolean {
  const b = node.bounds;
  return !!b && p.x >= b.left && p.x < b.right && p.y >= b.top && p.y < b.bottom;
}

function area(node: ScreenNode): number {
  const b = node.bounds!;
  return (b.right - b.left) * (b.bottom - b.top);
}

/** The node a point on the frame means among `nodes`: the smallest one containing it, the
 *  deeper one on a tie (a button over its row, a leaf over its container). */
export function hit(nodes: readonly ScreenNode[], p: Point): ScreenNode | null {
  let best: ScreenNode | null = null;
  for (const node of nodes) {
    if (!inside(node, p)) continue;
    if (!best || area(node) < area(best) || (area(node) === area(best) && node.depth > best.depth)) best = node;
  }
  return best;
}

export const isScrollable = (node: ScreenNode) => node.flags.includes(NodeFlag.FLAG_SCROLLABLE);

export const isEditable = (node: ScreenNode) => /EditText$/.test(node.className ?? "");

export const isCheckable = (node: ScreenNode) => node.flags.includes(NodeFlag.FLAG_CHECKABLE);

/** The innermost scrollable node under a point: what the wheel over it scrolls. */
export function scrollableAt(nodes: readonly ScreenNode[], p: Point): ScreenNode | null {
  return hit(nodes.filter(isScrollable), p);
}

/** A point on the element in client pixels, as a point on the frame in device pixels. */
export function toFrame(client: Point, rect: { left: number; top: number; width: number; height: number }, width: number, height: number): Point {
  return {
    x: ((client.x - rect.left) / rect.width) * width,
    y: ((client.y - rect.top) / rect.height) * height,
  };
}

/** The swipe a drag means (the direction the finger moved), or `null` for a click: a drag must
 *  go at least `minimum` along its main axis. */
export function dragDirection(from: Point, to: Point, minimum: number): Direction | null {
  const dx = to.x - from.x;
  const dy = to.y - from.y;
  if (Math.max(Math.abs(dx), Math.abs(dy)) < minimum) return null;
  if (Math.abs(dx) >= Math.abs(dy)) return dx < 0 ? Direction.DIR_LEFT : Direction.DIR_RIGHT;
  return dy < 0 ? Direction.DIR_UP : Direction.DIR_DOWN;
}

/** The node's name in the UI: its text, description, id or class. */
export function label(node: ScreenNode): string {
  if (node.text) return node.text;
  if (node.contentDescription) return node.contentDescription;
  if (node.resourceName) return node.resourceName.replace(/^.*:id\//, "");
  return shortClass(node);
}

export const shortClass = (node: ScreenNode) => (node.className ?? "node").split(".").pop()!;
