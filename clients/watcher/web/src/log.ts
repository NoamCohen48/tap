// The page's view of one device's log: rows built once from the back end's records (live) or
// from a saved `log.json` (Library), with the filters and lookups the log panel needs.

import { fromJson, type JsonValue } from "@bufbuild/protobuf";
import { LogEntrySchema, LogPriority, type LogEntry } from "./gen/device_log_pb";
import type { WatchLogResponse } from "./gen/watcher_pb";

/** Rows kept per device on the page: what the back end itself retains. */
export const MAX_LOG = 50_000;
/** Rows over [MAX_LOG] before the oldest are cut, so the cut is not paid on every batch. */
const SLACK = 5_000;

export type LogRow = {
  /** Increasing in the list: the back end's per-serial seq, or the position in a saved log. */
  seq: bigint;
  kind: "line" | "marker" | "action" | "gap";
  /** When the daemon got it: its wall clock, and its monotonic clock [clockId]. */
  epochMs: bigint;
  clockId: string;
  ns: bigint;
  /** Lines only; UNSPECIFIED for the rest. */
  priority: LogPriority;
  /** V, D, I, W, E, F for a line; empty otherwise. */
  level: string;
  tag: string;
  text: string;
  /** An error or fatal line, a failed action or a gap. */
  bad: boolean;
  /** Worth a tick on the scrubber: a marker, or anything [bad]. */
  notable: boolean;
  /** Lower-cased text the search box matches. */
  search: string;
  entry: LogEntry;
  /** A saved log: where the entry sits in the recording, in seconds. */
  video?: { part: number; partOffset: number; offset: number };
};

const LEVELS: Record<number, string> = {
  [LogPriority.VERBOSE]: "V",
  [LogPriority.DEBUG]: "D",
  [LogPriority.INFO]: "I",
  [LogPriority.WARN]: "W",
  [LogPriority.ERROR]: "E",
  [LogPriority.FATAL]: "F",
};

export function toRow(seq: bigint, clockId: string, entry: LogEntry): LogRow {
  const base = { seq, epochMs: entry.hostTimeMs, clockId, ns: entry.hostMonotonicNs, entry, priority: LogPriority.UNSPECIFIED, level: "", tag: "" };
  const kind = entry.kind;
  let row: Omit<LogRow, "search" | "notable">;
  switch (kind.case) {
    case "line": {
      const { priority, tag, message } = kind.value;
      row = { ...base, kind: "line", priority, level: LEVELS[priority] ?? "?", tag, text: message, bad: priority >= LogPriority.ERROR };
      break;
    }
    case "marker":
      row = { ...base, kind: "marker", text: kind.value, bad: false };
      break;
    case "action": {
      const { operation, target, finished, outcome, durationMs } = kind.value;
      const what = target ? `${operation} ${target}` : operation;
      const took = durationMs === undefined ? "" : ` · ${durationMs} ms`;
      row = { ...base, kind: "action", text: finished ? `${what} → ${outcome ?? "done"}${took}` : `${what} …`, bad: finished && outcome !== undefined && outcome !== "ok" };
      break;
    }
    case "gap":
      row = { ...base, kind: "gap", text: `Lines may be missing: ${kind.value}`, bad: true };
      break;
    default:
      row = { ...base, kind: "gap", text: "Unknown entry", bad: false };
  }
  return { ...row, notable: row.bad || row.kind === "marker", search: `${row.tag} ${row.text}`.toLowerCase() };
}

/** One device's rows, oldest first. Mutable with a version number, like the frame buffer. */
export class LogStore {
  rows: LogRow[] = [];
  version = 0;
  /** The last back-end seq received; the stream resumes after it. */
  lastSeq = 0n;
  /** Entries the back end no longer had when this page first read the log. */
  dropped = 0n;
  /** Why the device's current attachment has no log; empty otherwise. */
  unavailable = "";

  /** Folds one response in; true when rows changed. */
  receive(response: WatchLogResponse, first: boolean): boolean {
    if (first) {
      this.dropped += response.dropped;
      this.unavailable = response.unavailable;
    }
    let added = false;
    for (const record of response.records) {
      if (record.seq <= this.lastSeq || !record.entry) continue;
      this.lastSeq = record.seq;
      this.rows.push(toRow(record.seq, record.clockId, record.entry));
      added = true;
    }
    if (this.rows.length > MAX_LOG + SLACK) this.rows = this.rows.slice(this.rows.length - MAX_LOG);
    if (added || first) this.version++;
    return added || first;
  }
}

type SavedLog = { entries?: { part: number; videoOffsetUs: string; offsetUs: string; entry: JsonValue }[] };

/** The rows of a saved `log.json`. */
export function savedRows(json: SavedLog): LogRow[] {
  return (json.entries ?? []).map((saved, index) => ({
    ...toRow(BigInt(index + 1), "", fromJson(LogEntrySchema, saved.entry, { ignoreUnknownFields: true })),
    video: { part: saved.part, partOffset: Number(saved.videoOffsetUs) / 1e6, offset: Number(saved.offsetUs) / 1e6 },
  }));
}

export type LogFilter = { query: string; minPriority: LogPriority };

/** Rows that pass [filter]. The priority applies to lines only: markers, actions and gaps stay. */
export function filtered(rows: LogRow[], filter: LogFilter): LogRow[] {
  const needle = filter.query.trim().toLowerCase();
  if (!needle && filter.minPriority <= LogPriority.VERBOSE) return rows;
  return rows.filter((row) => (row.kind !== "line" || row.priority >= filter.minPriority) && (!needle || row.search.includes(needle)));
}

/** The last row the daemon got at or before [ns] on [clockId]; undefined when none. */
export function rowAt(rows: LogRow[], clockId: string, ns: bigint): LogRow | undefined {
  for (let index = rows.length - 1; index >= 0; index--) {
    const row = rows[index];
    if (row.clockId === clockId && row.ns <= ns) return row;
  }
  return undefined;
}

/** The index of the last row with seq ≤ [seq] (rows are in seq order); -1 when none. */
export function indexAtSeq(rows: LogRow[], seq: bigint): number {
  let low = 0;
  let high = rows.length;
  while (low < high) {
    const middle = (low + high) >> 1;
    if (rows[middle].seq <= seq) low = middle + 1;
    else high = middle;
  }
  return low - 1;
}

export type Tick<T> = { at: number; row: T; count: number };

/**
 * One tick per [slots]-th of the track: the rows whose position ([fraction], 0 to 1 across the
 * visible track; others are off screen) falls in the same slot share a tick, which names the
 * first and counts the rest. A bad row wins the slot over a marker.
 */
export function ticks<T extends { bad: boolean }>(rows: T[], fraction: (row: T) => number, slots = 240): Tick<T>[] {
  const bySlot = new Map<number, Tick<T>>();
  for (const row of rows) {
    const at = fraction(row);
    if (!(at >= 0 && at <= 1)) continue;
    const slot = Math.min(slots - 1, Math.floor(at * slots));
    const tick = bySlot.get(slot);
    if (!tick) bySlot.set(slot, { at, row, count: 1 });
    else {
      tick.count++;
      if (row.bad && !tick.row.bad) Object.assign(tick, { at, row });
    }
  }
  return [...bySlot.values()];
}

export function tickLabel(tick: Tick<LogRow>): string {
  const { row, count } = tick;
  const text = row.kind === "marker" ? `Marker: ${row.text}` : row.kind === "line" ? `${row.level}/${row.tag}: ${row.text}` : row.text;
  return count > 1 ? `${text} (+${count - 1} more)` : text;
}
