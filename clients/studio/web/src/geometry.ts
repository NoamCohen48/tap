// Where nodes are on the frame, and which node a point means (decision 4: a click is hit-tested
// against the snapshot of the frame it was made on).
//
// Node bounds and the screenshot are both in the display's current orientation, in device
// pixels, so a node's box is its bounds as fractions of the frame: the overlay scales with the
// picture and follows rotation without knowing about it.

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

/** The frame's windows in dump order: a depth-0 node (a window root) starts the next one. */
function windows(nodes: readonly ScreenNode[]): ScreenNode[][] {
  const out: ScreenNode[][] = [];
  for (const node of nodes) {
    const last = out[out.length - 1];
    if (node.depth === 0 || !last) out.push([node]);
    else last.push(node);
  }
  return out;
}

/** The nodes of the window a point is on. The dump has no z-order (it lists the active window
 *  first, then the rest top-down), so of the windows whose root contains the point this is the
 *  smallest: a dialog, popup, keyboard or status bar over the activity behind it; the earlier one
 *  on a tie. Nodes before any root count as one window that contains every point. */
function windowAt(nodes: readonly ScreenNode[], p: Point): ScreenNode[] {
  let best: ScreenNode[] = [];
  let bestArea = Number.POSITIVE_INFINITY;
  for (const window of windows(nodes)) {
    const root = window[0]!;
    if (root.depth !== 0) {
      if (best.length === 0) best = window;
      continue;
    }
    if (!inside(root, p)) continue;
    if (best.length === 0 || area(root) < bestArea) {
      best = window;
      bestArea = area(root);
    }
  }
  return best;
}

/** The node a point on the frame means: in the window on top there (never one behind a dialog),
 *  the smallest node containing it that `accepts` takes, the deeper one on a tie (a button over
 *  its row, a leaf over its container). `nodes` is the whole frame, in dump order. */
export function hit(nodes: readonly ScreenNode[], p: Point, accepts: (node: ScreenNode) => boolean = () => true): ScreenNode | null {
  let best: ScreenNode | null = null;
  for (const node of windowAt(nodes, p)) {
    if (!accepts(node) || !inside(node, p)) continue;
    if (!best || area(node) < area(best) || (area(node) === area(best) && node.depth > best.depth)) best = node;
  }
  return best;
}

/** A label to tap `row` through when its own selector is only an index pick (a preference or
 *  list row: a layout with nothing of its own). The first non-interactive node inside it with a
 *  selector that is not an index pick, whose centre means the row itself (`hit` among interactive
 *  nodes): a tap there goes to the row, and the step names the row's title instead of a position
 *  that changes when the list scrolls. `null` when it has none. */
export function tapLabel(nodes: readonly ScreenNode[], row: ScreenNode): ScreenNode | null {
  const start = nodes.indexOf(row);
  if (start < 0) return null;
  for (let i = start + 1; i < nodes.length && nodes[i]!.depth > row.depth; i++) {
    const node = nodes[i]!;
    if (node.interactive || !node.selector || node.byIndex || !node.bounds) continue;
    const b = node.bounds;
    const centre = { x: (b.left + b.right) / 2, y: (b.top + b.bottom) / 2 };
    if (hit(nodes, centre, (n) => n.interactive) === row) return node;
  }
  return null;
}

/** The nodes a container holds: their centre is inside its bounds (a row half scrolled out still
 *  counts). The container itself is not among them. */
export function within(nodes: readonly ScreenNode[], container: ScreenNode): ScreenNode[] {
  const c = container.bounds;
  if (!c) return [];
  return nodes.filter((n) => {
    if (n.ref === container.ref || !n.bounds) return false;
    const x = (n.bounds.left + n.bounds.right) / 2;
    const y = (n.bounds.top + n.bounds.bottom) / 2;
    return x >= c.left && x < c.right && y >= c.top && y < c.bottom;
  });
}

/** The smallest scrollable node holding `node` (its centre inside), so a row leads to its list. */
export function scrollableAround(nodes: readonly ScreenNode[], node: ScreenNode): ScreenNode | null {
  const b = node.bounds;
  if (!b) return null;
  const centre = { x: (b.left + b.right) / 2, y: (b.top + b.bottom) / 2 };
  return hit(nodes, centre, (n) => n.ref !== node.ref && isScrollable(n));
}

export const isScrollable = (node: ScreenNode) => node.flags.includes(NodeFlag.FLAG_SCROLLABLE);

export const isEditable = (node: ScreenNode) => /EditText$/.test(node.className ?? "");

export const isCheckable = (node: ScreenNode) => node.flags.includes(NodeFlag.FLAG_CHECKABLE);

/** A point on the element in client pixels, as a point on the frame in device pixels. */
export function toFrame(client: Point, rect: { left: number; top: number; width: number; height: number }, width: number, height: number): Point {
  return {
    x: ((client.x - rect.left) / rect.width) * width,
    y: ((client.y - rect.top) / rect.height) * height,
  };
}

/** The node's name in the UI: its text, description, id or class. */
export function label(node: ScreenNode): string {
  if (node.text) return node.text;
  if (node.contentDescription) return node.contentDescription;
  if (node.resourceName) return node.resourceName.replace(/^.*:id\//, "");
  return shortClass(node);
}

export const shortClass = (node: ScreenNode) => (node.className ?? "node").split(".").pop()!;
