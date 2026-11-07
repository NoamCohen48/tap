import { create } from "@bufbuild/protobuf";
import { describe, expect, it, vi } from "vitest";
import { ActivityStore, MAX_ACTIONS, NOTIFY_MS } from "./activity";
import { ConnectionEntrySchema } from "./gen/client_connection_pb";
import { ErrorCode } from "./gen/command_pb";
import { ActivitySchema, WatchResponseSchema, type Activity } from "./gen/watch_pb";

function event(seq: bigint, serial: string, failed = false, startedNs?: bigint, atEpochMs = 0n): Activity {
  return create(ActivitySchema, {
    seq,
    clientConnectionId: "c1",
    kind: {
      case: "event",
      value: { seq, serial, atEpochMs, startedMonotonicNs: startedNs, call: { case: "command", value: {} }, error: failed ? { code: ErrorCode.ERR_NOT_FOUND } : undefined },
    },
  });
}

function started(seq: bigint, serial: string, startedNs: bigint, connection = "c1"): Activity {
  return create(ActivitySchema, {
    seq,
    clientConnectionId: connection,
    kind: { case: "callStarted", value: { call: { serial, atEpochMs: 1000n, startedMonotonicNs: startedNs, call: { case: "command", value: { op: { case: "tap", value: {} } } } } } },
  });
}

const response = (activities: Activity[], connections = [] as ReturnType<typeof create<typeof ConnectionEntrySchema>>[]) => create(WatchResponseSchema, { activities, connections });

describe("ActivityStore", () => {
  it("applies a snapshot, then connection activity", () => {
    const store = new ActivityStore();
    store.receive(response([], [create(ConnectionEntrySchema, { clientConnectionId: "c0", name: "pytest 1 /a/old" })]), true);
    store.receive(
      response([
        create(ActivitySchema, { seq: 1n, clientConnectionId: "c1", kind: { case: "connectionOpened", value: { name: "junit 2 /a/app" } } }),
        create(ActivitySchema, { seq: 2n, clientConnectionId: "c1", kind: { case: "deviceAttached", value: { attachedDeviceId: "d1", serial: "s1", generation: 1n } } }),
      ]),
      false,
    );
    expect(store.owner("s1")?.clientConnectionId).toBe("c1");
    expect(store.label("c1")).toBe("JUnit · app");
    store.receive(response([create(ActivitySchema, { seq: 3n, clientConnectionId: "c1", kind: { case: "connectionClosed", value: { reason: "closed" } } })]), false);
    expect(store.owner("s1")).toBeUndefined();
    // A closed connection keeps its name for its actions.
    expect(store.label("c1")).toBe("JUnit · app");
  });

  it("files actions per device, skips repeats and counts failures", () => {
    const store = new ActivityStore();
    store.receive(response([event(1n, "a"), event(2n, "b", true), event(3n, "a", true)]), true);
    store.receive(response([event(3n, "a", true), event(4n, "a")]), true);
    expect(store.device("a").actions.map((action) => action.event.seq)).toEqual([1n, 3n, 4n]);
    expect(store.device("a").failures).toBe(1);
    expect(store.device("b").failures).toBe(1);
    expect(store.lastSeq).toBe(4n);
  });

  it("keeps the newest actions and their failure count", () => {
    const store = new ActivityStore();
    const activities = Array.from({ length: MAX_ACTIONS + 10 }, (_, i) => event(BigInt(i + 1), "a", i < 15));
    store.receive(response(activities), true);
    expect(store.device("a").actions).toHaveLength(MAX_ACTIONS);
    expect(store.device("a").failures).toBe(5);
  });

  it("shows a started call as running until its event, a detach or the connection's close", () => {
    const store = new ActivityStore();
    store.receive(response([started(1n, "a", 10n)]), true);
    expect(store.device("a").running?.summary).toBe("Tap");
    expect(store.device("a").running?.sinceMs).toBe(1000);
    // Another call's event (an earlier start) leaves it running.
    store.receive(response([event(2n, "a", false, 5n)]), false);
    expect(store.device("a").running).toBeDefined();
    store.receive(response([event(3n, "a", false, 10n)]), false);
    expect(store.device("a").running).toBeUndefined();
    expect(store.device("a").actions).toHaveLength(2);

    // A cancelled call is never logged: its device detaching ends it.
    store.receive(response([started(4n, "a", 20n), started(5n, "b", 21n)]), false);
    store.receive(response([create(ActivitySchema, { seq: 6n, clientConnectionId: "c1", kind: { case: "deviceDetached", value: { attachedDeviceId: "d", serial: "a" } } })]), false);
    expect(store.device("a").running).toBeUndefined();
    expect(store.device("b").running).toBeDefined();
    store.receive(response([create(ActivitySchema, { seq: 7n, clientConnectionId: "c1", kind: { case: "connectionClosed", value: { reason: "done" } } })]), false);
    expect(store.device("b").running).toBeUndefined();
  });

  it("counts failures as unseen until the device is opened", () => {
    const kept = new Map<string, string>();
    vi.stubGlobal("localStorage", { getItem: (key: string) => kept.get(key) ?? null, setItem: (key: string, value: string) => kept.set(key, value) });
    const store = new ActivityStore();
    store.receive(response([event(1n, "a", true, undefined, 100n), event(2n, "a", false, undefined, 200n)]), true);
    expect(store.unseen("a").map((action) => action.event.seq)).toEqual([1n]);
    store.markSeen("a");
    expect(store.unseen("a")).toEqual([]);
    store.receive(response([event(3n, "a", true, undefined, 300n)]), false);
    expect(store.unseen("a").map((action) => action.event.seq)).toEqual([3n]);
    // The marks outlive the page: a reload replays the history, and only the new one is unseen.
    const reloaded = new ActivityStore();
    reloaded.receive(response([event(1n, "a", true, undefined, 100n), event(3n, "a", true, undefined, 300n)]), true);
    expect(reloaded.unseen("a").map((action) => action.event.seq)).toEqual([3n]);
    vi.unstubAllGlobals();
  });

  it("tells views about a burst of changes once", () => {
    vi.useFakeTimers();
    const store = new ActivityStore();
    let heard = 0;
    store.subscribe(() => heard++);
    store.receive(response([event(1n, "a")]), true);
    store.receive(response([event(2n, "a"), started(3n, "a", 9n)]), false);
    // The data is there at once; the version views render by moves only when they are told.
    expect(store.device("a").actions).toHaveLength(2);
    expect(heard).toBe(0);
    expect(store.getVersion()).toBe(0);
    vi.advanceTimersByTime(NOTIFY_MS);
    expect(heard).toBe(1);
    expect(store.getVersion()).toBe(store.version);
    vi.useRealTimers();
  });

  it("keeps where each session on a device began and ended", () => {
    const store = new ActivityStore();
    store.receive(
      response([
        create(ActivitySchema, { seq: 1n, atEpochMs: 100n, clientConnectionId: "c1", kind: { case: "deviceAttached", value: { attachedDeviceId: "d1", serial: "s1" } } }),
        event(2n, "s1", false, 5n, 150n),
        create(ActivitySchema, { seq: 3n, atEpochMs: 200n, clientConnectionId: "c1", kind: { case: "deviceDetached", value: { attachedDeviceId: "d1", serial: "s1", reason: "connection closed" } } }),
      ]),
      true,
    );
    const device = store.device("s1");
    expect(device.marks.map((mark) => [mark.kind, mark.atEpochMs, mark.reason])).toEqual([
      ["attached", 100, ""],
      ["detached", 200, "connection closed"],
    ]);
    expect(device.actions).toHaveLength(1);
    expect(store.device("other").marks).toEqual([]);
  });
});
