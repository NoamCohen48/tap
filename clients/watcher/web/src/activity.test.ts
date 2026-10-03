import { create } from "@bufbuild/protobuf";
import { describe, expect, it } from "vitest";
import { ActivityStore, MAX_ACTIONS } from "./activity";
import { ConnectionEntrySchema } from "./gen/client_connection_pb";
import { ErrorCode } from "./gen/command_pb";
import { ActivitySchema, WatchResponseSchema, type Activity } from "./gen/watch_pb";

function event(seq: bigint, serial: string, failed = false): Activity {
  return create(ActivitySchema, {
    seq,
    clientConnectionId: "c1",
    kind: { case: "event", value: { seq, serial, call: { case: "command", value: {} }, error: failed ? { code: ErrorCode.ERR_NOT_FOUND } : undefined } },
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
});
