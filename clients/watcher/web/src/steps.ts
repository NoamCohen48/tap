// Moving through a device's steps: failure to failure, what opening a device selects, the step
// list with its session and connection separators, and the scrubber's time axis. Pure, so the
// keys, the buttons and the list agree, and unit-tested.

import type { Action, SessionMark } from "./activity";

/**
 * The failure after (dir 1) or before (dir -1) [selected]; with nothing selected, the newest
 * failure either way. Undefined when there is none that way.
 */
export function stepFailure(actions: Action[], selected: Action | undefined, dir: 1 | -1): Action | undefined {
  const from = selected ? actions.indexOf(selected) : -1;
  if (from < 0) return actions.findLast((action) => action.failed);
  for (let i = from + dir; i >= 0 && i < actions.length; i += dir) if (actions[i].failed) return actions[i];
  return undefined;
}

/** The failure nearest to [selected] in the list (the newer one on a tie), for the Failures filter. */
export function nearestFailure(actions: Action[], selected: Action): Action | undefined {
  const at = actions.indexOf(selected);
  let best: Action | undefined;
  let bestDistance = Infinity;
  actions.forEach((action, index) => {
    const distance = Math.abs(index - at);
    if (action.failed && distance <= bestDistance) {
      best = action;
      bestDistance = distance;
    }
  });
  return best;
}

/**
 * What opening a device selects: its newest unseen failure whose video the watcher still keeps
 * ([keptMs] back from [now]), else the newest unseen one (its video is gone, but it is the news).
 */
export function openingFailure(unseen: Action[], now: number, keptMs: number): Action | undefined {
  return unseen.findLast((action) => now - Number(action.event.atEpochMs) < keptMs) ?? unseen.at(-1);
}

export type Item =
  | { kind: "action"; key: string; action: Action }
  | { kind: "mark"; key: string; mark: SessionMark }
  /** The actions below are another connection's, without an attach mark to say so. */
  | { kind: "connection"; key: string; connectionId: string };

/**
 * The step list: actions with the session marks between them, in time order (with [marks]
 * false, a filtered list: actions only). A connection line goes wherever the connection changes
 * without an attach mark saying so, so a row never repeats its connection.
 */
export function listItems(actions: Action[], marks: SessionMark[], withMarks: boolean): Item[] {
  const items: Item[] = [];
  let connection: string | undefined;
  let next = 0;
  const shown = withMarks ? marks : [];
  for (const action of actions) {
    while (next < shown.length && shown[next].atEpochMs <= Number(action.event.atEpochMs)) {
      const mark = shown[next++];
      items.push({ kind: "mark", key: `mark:${mark.key}`, mark });
      connection = mark.kind === "attached" ? mark.connectionId : undefined;
    }
    if (action.connectionId !== connection) {
      connection = action.connectionId;
      items.push({ kind: "connection", key: `conn:${action.key}`, connectionId: connection });
    }
    items.push({ kind: "action", key: action.key, action });
  }
  for (; next < shown.length; next++) items.push({ kind: "mark", key: `mark:${shown[next].key}`, mark: shown[next] });
  return items;
}

/**
 * Where no connection held the device, as [from, to] epoch ms: from each detach to the next
 * attach, or to now ([to] undefined) when none came after it.
 */
export function unheldSpans(marks: SessionMark[]): { from: number; to?: number }[] {
  const spans: { from: number; to?: number }[] = [];
  let open: number | undefined;
  for (const mark of marks) {
    if (mark.kind === "detached") open ??= mark.atEpochMs;
    else if (open !== undefined) {
      spans.push({ from: open, to: mark.atEpochMs });
      open = undefined;
    }
  }
  if (open !== undefined) spans.push({ from: open });
  return spans;
}

const AXIS_STEPS_S = [1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 1800, 3600];

/**
 * Labels for a scrubber window: seconds back from the newest frame ([newest], in track seconds)
 * at a round step that gives at most [most] of them. `at` is in track seconds.
 */
export function axisTicks(start: number, end: number, newest: number, most = 5): { at: number; label: string }[] {
  const span = end - start;
  if (!(span > 0)) return [];
  const step = AXIS_STEPS_S.find((candidate) => span / candidate < most) ?? AXIS_STEPS_S.at(-1)!;
  const ticks: { at: number; label: string }[] = [];
  // Back from the newest: the first multiple of [step] inside the window, then every step.
  for (let back = Math.max(0, Math.ceil((newest - end) / step - 1e-9) * step); newest - back >= start - 1e-9; back += step) {
    ticks.push({ at: newest - back, label: back === 0 ? "now" : `−${back < 60 ? `${back} s` : `${Math.floor(back / 60)}:${String(back % 60).padStart(2, "0")}`}` });
  }
  return ticks.reverse();
}
