import { useCallback, useEffect, useState } from "react";
import type { StudioClient } from "./api";
import { errorMessage } from "./frames";
import { DeviceState } from "./gen/device_pb";
import type { DeviceChoice, Session } from "./gen/studio_pb";

const STATE_NOTES: Partial<Record<DeviceState, string>> = {
  [DeviceState.DEVICE_LEASED]: "in use by another client",
  [DeviceState.DEVICE_QUARANTINED]: "quarantined",
  [DeviceState.DEVICE_OFFLINE]: "offline",
  [DeviceState.DEVICE_UNAUTHORIZED]: "unauthorized: accept the USB debugging prompt",
};

/** Choose a device and attach it. */
export function DevicePicker({
  client,
  onSession,
}: {
  client: StudioClient;
  onSession: (session: Session) => void;
}) {
  const [devices, setDevices] = useState<DeviceChoice[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [serial, setSerial] = useState("");
  const [attaching, setAttaching] = useState(false);

  const list = useCallback(() => {
    setError(null);
    client.listDevices({}).then(
      (r) => {
        setDevices(r.devices);
        const free = r.devices.filter((d) => d.state === DeviceState.DEVICE_FREE);
        setSerial((current) => (free.some((d) => d.serial === current) ? current : (free[0]?.serial ?? "")));
      },
      (e: unknown) => setError(errorMessage(e)),
    );
  }, [client]);

  useEffect(list, [list]);

  const attach = async () => {
    setAttaching(true);
    setError(null);
    try {
      const response = await client.attach({ serial });
      onSession(response.session!);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setAttaching(false);
    }
  };

  return (
    <section className="panel picker" aria-label="Attach a device">
      <div className="panel-h">
        <h2>Attach a device</h2>
        <span className="spacer" />
        <button type="button" className="btn small" onClick={list}>
          Refresh
        </button>
      </div>
      <form
        className="panel-b"
        onSubmit={(e) => {
          e.preventDefault();
          void attach();
        }}
      >
        {error && (
          <div className="error" role="alert">
            {error}
          </div>
        )}
        <fieldset style={{ border: 0, padding: 0, margin: 0 }}>
          <legend className="sr-only">Device</legend>
          {devices === null ? (
            !error && <div className="empty">Asking the Tap server…</div>
          ) : devices.length === 0 ? (
            <div className="empty">No devices. Connect one with USB debugging on, or start an emulator.</div>
          ) : (
            <ul className="devices">
              {devices.map((d) => {
                const free = d.state === DeviceState.DEVICE_FREE;
                return (
                  <li key={d.serial}>
                    <label className={free ? "" : "off"}>
                      <input type="radio" name="serial" value={d.serial} checked={serial === d.serial} disabled={!free} onChange={() => setSerial(d.serial)} />
                      <span className="mono">{d.serial}</span>
                      {!free && <span className="tag">{d.quarantineReason || STATE_NOTES[d.state] || "unavailable"}</span>}
                    </label>
                  </li>
                );
              })}
            </ul>
          )}
        </fieldset>
        <div>
          <button type="submit" className="btn primary" disabled={!serial || attaching}>
            {attaching ? "Attaching…" : "Attach"}
          </button>
        </div>
      </form>
    </section>
  );
}
