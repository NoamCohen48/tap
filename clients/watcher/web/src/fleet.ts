// Every device the page knows, in a stable order, and what its tile says: the state in words,
// the step it runs (or ran last) and how that went. Pure, so the wall and the device pane agree.

import type { DeviceActivity } from "./activity";
import { summary } from "./describe";
import { ago, duration } from "./format";
import type { ConnectionEntry } from "./gen/client_connection_pb";
import { DeviceState, type DeviceEntry } from "./gen/device_pb";

export type Tone = "run" | "idle" | "free" | "warn" | "bad";

export type FleetDevice = {
  serial: string;
  /** The model when the daemon reports it ("Pixel 7"), else the serial. */
  name: string;
  device?: DeviceEntry;
  owner?: ConnectionEntry;
  activity: DeviceActivity;
  state: { text: string; tone: Tone };
  /** False when there is no screen to stream: offline, unauthorized, or not in the inventory. */
  streamable: boolean;
};

export type Readout = {
  /** The step, in full; the tile wraps it to two lines. */
  step: string;
  /** No step: the text says why ("No connection holds this device"). */
  quiet: boolean;
  /** "running 3.2 s", "✓ 380 ms", "✕ NOT_FOUND 12 s ago". */
  outcome: string;
  tone: "run" | "slow" | "ok" | "bad" | "";
};

/** A step running this long is shown as slow: most calls end in well under a second. */
export const SLOW_MS = 10_000;

const UNAVAILABLE: Record<number, string> = {
  [DeviceState.DEVICE_OFFLINE]: "Offline",
  [DeviceState.DEVICE_UNAUTHORIZED]: "Unauthorized",
};

/**
 * The inventory plus any serial a connection attached, sorted by name then serial. The order
 * never follows state, so a tile stays where it is while its device runs, fails and idles.
 */
export function fleet(devices: DeviceEntry[], connections: Iterable<ConnectionEntry>, activity: (serial: string) => DeviceActivity, owner: (serial: string) => ConnectionEntry | undefined): FleetDevice[] {
  const serials = new Map<string, DeviceEntry | undefined>(devices.map((device) => [device.serial, device]));
  for (const connection of connections) {
    for (const attached of connection.attachedDevices) if (!serials.has(attached.serial)) serials.set(attached.serial, undefined);
  }
  return [...serials]
    .map(([serial, device]): FleetDevice => {
      const held = owner(serial);
      const unavailable = device ? UNAVAILABLE[device.state] : undefined;
      const state: FleetDevice["state"] = unavailable
        ? { text: unavailable, tone: "bad" }
        : device?.state === DeviceState.DEVICE_QUARANTINED
          ? { text: "Quarantined", tone: "warn" }
          : held
            ? activity(serial).running
              ? { text: "Running", tone: "run" }
              : { text: "In use", tone: "idle" }
            : { text: "Free", tone: "free" };
      const model = device?.properties?.model;
      return { serial, name: model || serial, device, owner: held, activity: activity(serial), state, streamable: Boolean(device) && !unavailable };
    })
    .sort((a, b) => a.name.localeCompare(b.name) || a.serial.localeCompare(b.serial));
}

/** What a tile says under the screen. */
export function readout(entry: FleetDevice, now: number): Readout {
  const { device, activity } = entry;
  if (device && UNAVAILABLE[device.state]) return { step: device.state === DeviceState.DEVICE_UNAUTHORIZED ? "Accept the USB debugging prompt on the device" : "Not connected to adb", quiet: true, outcome: "", tone: "" };
  if (device?.state === DeviceState.DEVICE_QUARANTINED && !entry.owner) return { step: device.quarantineReason || "Quarantined by the daemon", quiet: true, outcome: "", tone: "" };
  const running = activity.running;
  if (running) {
    const ms = Math.max(0, now - running.sinceMs);
    const slow = ms >= SLOW_MS;
    return { step: running.summary, quiet: false, outcome: `${slow ? "slow, " : ""}running ${duration(ms / 1000)}`, tone: slow ? "slow" : "run" };
  }
  const last = activity.actions.at(-1);
  if (!last) return { step: entry.owner ? "Attached, no calls yet" : "No connection holds this device", quiet: true, outcome: "", tone: "" };
  const took = duration(Number(last.event.durationMs) / 1000);
  const when = ago(last.event.atEpochMs, now);
  const step = summary({ ...last.described, outcome: "" });
  if (last.failed) return { step, quiet: false, outcome: `✕ ${last.described.outcome} ${when}`, tone: "bad" };
  return { step, quiet: !entry.owner, outcome: entry.owner ? `✓ ${took}` : `last call ${when}`, tone: entry.owner ? "ok" : "" };
}
