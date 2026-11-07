import { create, type MessageInitShape } from "@bufbuild/protobuf";
import { describe, expect, it } from "vitest";
import { ActivityStore } from "./activity";
import { ConnectionEntrySchema } from "./gen/client_connection_pb";
import { ErrorCode } from "./gen/command_pb";
import { DeviceEntrySchema, DeviceState } from "./gen/device_pb";
import { ActivitySchema, WatchResponseSchema, type Activity } from "./gen/watch_pb";
import { fleet, readout, SLOW_MS } from "./fleet";

const tap = { case: "command" as const, value: { op: { case: "tap" as const, value: {} } } };

function activity(seq: bigint, kind: MessageInitShape<typeof ActivitySchema>["kind"]): Activity {
  return create(ActivitySchema, { seq, clientConnectionId: "c1", kind });
}

function setup() {
  const store = new ActivityStore();
  const held = create(ConnectionEntrySchema, { clientConnectionId: "c1", name: "junit 1 /a/app", attachedDevices: [{ attachedDeviceId: "d1", serial: "emulator-5554" }] });
  store.receive(create(WatchResponseSchema, { connections: [held] }), true);
  const devices = [
    create(DeviceEntrySchema, { serial: "zz-free", state: DeviceState.DEVICE_FREE, properties: { model: "Pixel 4a" } }),
    create(DeviceEntrySchema, { serial: "emulator-5554", state: DeviceState.DEVICE_LEASED, properties: { model: "Pixel 7" } }),
    create(DeviceEntrySchema, { serial: "aa-off", state: DeviceState.DEVICE_OFFLINE }),
  ];
  const rows = () => fleet(devices, store.connections.values(), (serial) => store.device(serial), (serial) => store.owner(serial));
  return { store, rows };
}

describe("fleet", () => {
  it("lists every device by name, with its state in words", () => {
    const { rows } = setup();
    expect(rows().map((row) => [row.name, row.state.text, row.streamable])).toEqual([
      ["aa-off", "Offline", false],
      ["Pixel 4a", "Free", true],
      ["Pixel 7", "In use", true],
    ]);
  });

  it("reads out the running step, then how it ended, without the outcome in the step", () => {
    const { store, rows } = setup();
    const pixel = () => rows().find((row) => row.serial === "emulator-5554")!;
    expect(readout(pixel(), 0)).toMatchObject({ step: "Attached, no calls yet", quiet: true });

    store.receive(create(WatchResponseSchema, { activities: [activity(1n, { case: "callStarted", value: { call: { serial: "emulator-5554", atEpochMs: 1_000n, startedMonotonicNs: 5n, call: tap } } })] }), false);
    expect(pixel().state.text).toBe("Running");
    expect(readout(pixel(), 2_200)).toEqual({ step: "Tap", quiet: false, outcome: "running 1.2 s", tone: "run" });
    expect(readout(pixel(), 1_000 + SLOW_MS)).toMatchObject({ outcome: "slow, running 10 s", tone: "slow" });

    const failedEvent = { seq: 1n, serial: "emulator-5554", atEpochMs: 1_000n, durationMs: 5_000n, startedMonotonicNs: 5n, call: tap, error: { code: ErrorCode.ERR_NOT_FOUND } };
    store.receive(create(WatchResponseSchema, { activities: [activity(2n, { case: "event", value: failedEvent })] }), false);
    expect(pixel().state.text).toBe("In use");
    expect(readout(pixel(), 13_000)).toEqual({ step: "Tap", quiet: false, outcome: "✕ NOT_FOUND 12 s ago", tone: "bad" });
  });

  it("says why an unavailable or unheld device shows nothing", () => {
    const { rows } = setup();
    expect(readout(rows()[0], 0)).toMatchObject({ step: "Not connected to adb", quiet: true, outcome: "" });
    expect(readout(rows()[1], 0)).toMatchObject({ step: "No connection holds this device", quiet: true });
  });
});
