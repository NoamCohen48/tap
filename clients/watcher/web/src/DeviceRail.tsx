// Devices: the daemon's inventory plus any serial with activity, in use first.

import { useMemo } from "react";
import { ago, plural } from "./format";
import { DeviceState, type DeviceEntry } from "./gen/device_pb";
import { store } from "./hooks";

type Props = {
  devices: DeviceEntry[];
  recording: Set<string>;
  selected: string;
  onSelect: (serial: string) => void;
  now: number;
};

const STATE: Record<number, { text: string; tone: string }> = {
  [DeviceState.DEVICE_FREE]: { text: "Free", tone: "idle" },
  [DeviceState.DEVICE_LEASED]: { text: "In use", tone: "busy" },
  [DeviceState.DEVICE_QUARANTINED]: { text: "Quarantined", tone: "warn" },
  [DeviceState.DEVICE_OFFLINE]: { text: "Offline", tone: "bad" },
  [DeviceState.DEVICE_UNAUTHORIZED]: { text: "Unauthorized", tone: "bad" },
};

export function DeviceRail({ devices, recording, selected, onSelect, now }: Props) {
  const rows = useMemo(() => {
    const serials = new Map<string, DeviceEntry | undefined>(devices.map((device) => [device.serial, device]));
    for (const connection of store.connections.values()) {
      for (const attached of connection.attachedDevices) if (!serials.has(attached.serial)) serials.set(attached.serial, undefined);
    }
    return [...serials].map(([serial, device]) => {
      const owner = store.owner(serial);
      const activity = store.device(serial);
      const state = owner ? STATE[DeviceState.DEVICE_LEASED] : (STATE[device?.state ?? 0] ?? { text: "Unknown", tone: "idle" });
      return { serial, owner, activity, state, last: activity.actions.at(-1), device };
    }).sort((a, b) => Number(Boolean(b.owner)) - Number(Boolean(a.owner)) || a.serial.localeCompare(b.serial));
    // store.version is read by the caller, which re-renders this on every change.
  }, [devices, store.version]);

  return (
    <nav className="rail-devices" aria-label="Devices">
      <h2>Devices</h2>
      {!rows.length && <p className="empty">No devices. Connect one over ADB.</p>}
      <ul>
        {rows.map(({ serial, owner, activity, state, last, device }) => (
          <li key={serial}>
            <button className={`device ${state.tone}${serial === selected ? " selected" : ""}`} aria-current={serial === selected} onClick={() => onSelect(serial)}>
              <span className="device-top">
                <span className={`status-dot ${state.tone}`} aria-hidden="true" />
                <span className="serial">{serial}</span>
                {recording.has(serial) && <span className="rec-tag">REC</span>}
                {activity.failures > 0 && (
                  <span className="fail-count" title={plural(activity.failures, "failure")}>
                    {activity.failures}
                  </span>
                )}
              </span>
              <span className="device-owner">{owner ? store.label(owner.clientConnectionId) : device?.quarantineReason || state.text}</span>
              {last && (
                <span className="device-last">
                  <span className={last.failed ? "bad" : ""}>{last.summary}</span> · {ago(last.event.atEpochMs, now)}
                </span>
              )}
            </button>
          </li>
        ))}
      </ul>
    </nav>
  );
}
