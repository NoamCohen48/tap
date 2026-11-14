import { create } from "@bufbuild/protobuf";
import { describe, expect, it } from "vitest";
import { LogEntrySchema, LogPriority, type LogEntry } from "./gen/device_log_pb";
import { LogRecordSchema, WatchLogResponseSchema } from "./gen/watcher_pb";
import { filtered, indexAtSeq, LogStore, MAX_LOG, rowAt, savedRows, tickLabel, ticks, toRow } from "./log";

const line = (ns: bigint, priority: LogPriority, message: string, tag = "App"): LogEntry =>
  create(LogEntrySchema, { hostMonotonicNs: ns, hostTimeMs: 1_700_000_000_000n + ns / 1_000_000n, kind: { case: "line", value: { priority, tag, message } } });
const marker = (ns: bigint, name: string) => create(LogEntrySchema, { hostMonotonicNs: ns, kind: { case: "marker", value: name } });
const response = (entries: [bigint, LogEntry][], extra: { dropped?: bigint; unavailable?: string } = {}) =>
  create(WatchLogResponseSchema, { ...extra, records: entries.map(([seq, entry]) => create(LogRecordSchema, { seq, clockId: "clock", entry })) });

describe("toRow", () => {
  it("describes lines, markers, actions and gaps", () => {
    const error = toRow(1n, "clock", line(5n, LogPriority.ERROR, "Boom", "Crash"));
    expect([error.kind, error.level, error.tag, error.text, error.bad, error.notable]).toEqual(["line", "E", "Crash", "Boom", true, true]);
    expect(error.search).toBe("crash boom");
    const info = toRow(2n, "clock", line(6n, LogPriority.INFO, "ok"));
    expect([info.bad, info.notable]).toEqual([false, false]);
    const mark = toRow(3n, "clock", marker(7n, "checkout"));
    expect([mark.kind, mark.text, mark.bad, mark.notable]).toEqual(["marker", "checkout", false, true]);
    const started = toRow(4n, "clock", create(LogEntrySchema, { kind: { case: "action", value: { operation: "tap", target: 'text="Pay"' } } }));
    expect([started.text, started.bad]).toEqual(['tap text="Pay" …', false]);
    const failed = toRow(5n, "clock", create(LogEntrySchema, { kind: { case: "action", value: { operation: "tap", target: 'text="Pay"', finished: true, outcome: "NOT_FOUND", durationMs: 40n } } }));
    expect([failed.text, failed.bad]).toEqual(['tap text="Pay" → NOT_FOUND · 40 ms', true]);
    const gap = toRow(6n, "clock", create(LogEntrySchema, { kind: { case: "gap", value: "logcat ended" } }));
    expect([gap.kind, gap.bad]).toEqual(["gap", true]);
  });
});

describe("LogStore", () => {
  it("appends in seq order, skips repeats and keeps the newest rows", () => {
    const store = new LogStore();
    expect(store.receive(response([[1n, line(1n, LogPriority.INFO, "a")]], { dropped: 4n, unavailable: "" }), true)).toBe(true);
    // A reconnect replays what the page already has.
    expect(store.receive(response([[1n, line(1n, LogPriority.INFO, "a")]]), false)).toBe(false);
    store.receive(response([[2n, line(2n, LogPriority.INFO, "b")]]), false);
    expect(store.rows.map((row) => row.text)).toEqual(["a", "b"]);
    expect([store.lastSeq, store.dropped]).toEqual([2n, 4n]);
    const many: [bigint, LogEntry][] = [];
    for (let seq = 3n; seq <= BigInt(MAX_LOG + 6000); seq++) many.push([seq, line(seq, LogPriority.DEBUG, "x")]);
    store.receive(response(many), false);
    expect(store.rows.length).toBe(MAX_LOG);
    expect(store.rows.at(-1)!.seq).toBe(BigInt(MAX_LOG + 6000));
  });

  it("says why a device has no log", () => {
    const store = new LogStore();
    store.receive(response([], { unavailable: "Log capture is off" }), true);
    expect(store.unavailable).toBe("Log capture is off");
  });
});

describe("filters and lookups", () => {
  const rows = [
    toRow(1n, "clock", line(10n, LogPriority.DEBUG, "starting")),
    toRow(2n, "clock", marker(20n, "flow")),
    toRow(3n, "clock", line(30n, LogPriority.ERROR, "Payment failed", "Checkout")),
    toRow(4n, "other", line(5n, LogPriority.WARN, "slow frame")),
  ];

  it("filters lines by priority and everything by text", () => {
    expect(filtered(rows, { query: "", minPriority: LogPriority.UNSPECIFIED })).toBe(rows);
    expect(filtered(rows, { query: "", minPriority: LogPriority.WARN }).map((row) => row.seq)).toEqual([2n, 3n, 4n]);
    expect(filtered(rows, { query: "CHECKOUT", minPriority: LogPriority.UNSPECIFIED }).map((row) => row.seq)).toEqual([3n]);
  });

  it("finds the row at a time on one clock, and a position by seq", () => {
    expect(rowAt(rows, "clock", 25n)?.seq).toBe(2n);
    expect(rowAt(rows, "clock", 5n)).toBeUndefined();
    expect(rowAt(rows, "other", 25n)?.seq).toBe(4n);
    expect(indexAtSeq(rows, 3n)).toBe(2);
    expect(indexAtSeq(rows, 0n)).toBe(-1);
    const hidden = filtered(rows, { query: "", minPriority: LogPriority.ERROR });
    expect(indexAtSeq(hidden, 4n)).toBe(1);
  });

  it("shares a scrubber tick between rows that are close", () => {
    const notable = rows.filter((row) => row.notable);
    const shared = ticks(notable, () => 0.5, 10);
    expect(shared.length).toBe(1);
    // The error wins the slot over the marker.
    expect(tickLabel(shared[0])).toBe("E/Checkout: Payment failed (+1 more)");
    const apart = ticks(notable, (row) => (row.kind === "marker" ? 0.1 : row.clockId === "other" ? 2 : 0.9), 10);
    expect(apart.map(tickLabel)).toEqual(["Marker: flow", "E/Checkout: Payment failed"]);
  });

  it("reads a saved log with its video offsets", () => {
    const saved = savedRows({ entries: [{ part: 1, videoOffsetUs: "250000", offsetUs: "1250000", entry: { cursor: "7", marker: "flow" } }] });
    expect(saved[0].video).toEqual({ part: 1, partOffset: 0.25, offset: 1.25 });
    expect([saved[0].seq, saved[0].kind, saved[0].text]).toEqual([1n, "marker", "flow"]);
    expect(savedRows({})).toEqual([]);
  });
});
