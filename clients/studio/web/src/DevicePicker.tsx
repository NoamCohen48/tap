import { useCallback, useEffect, useState } from "react";
import type { StudioClient } from "./api";
import { errorMessage } from "./frames";
import { DeviceState } from "./gen/device_pb";
import type { DeviceChoice, Session } from "./gen/studio_pb";

const STATE_NOTES: Partial<Record<DeviceState, string>> = {
  [DeviceState.DEVICE_LEASED]: "In use by another test or tool",
  [DeviceState.DEVICE_QUARANTINED]: "Quarantined",
  [DeviceState.DEVICE_OFFLINE]: "Offline",
  [DeviceState.DEVICE_UNAUTHORIZED]: "Accept the USB debugging prompt on the device",
};

/** How often the list is read again while a device is not ready to attach (or there is none). */
const RECHECK_MS = 3000;

/** A device the picker can choose: free, or quarantined and not being inspected by someone else. */
const choosable = (d: DeviceChoice) =>
  d.state === DeviceState.DEVICE_FREE || (d.state === DeviceState.DEVICE_QUARANTINED && !d.underInspection);

/** Quarantined for a reason only a person can clear: attaching inspects it. */
const inspectOnly = (d: DeviceChoice | undefined) => !!d && d.state === DeviceState.DEVICE_QUARANTINED && !d.retryOnAttach;

const quarantineNote = (d: DeviceChoice) =>
  d.underInspection
    ? `Being inspected: ${d.quarantineReason}`
    : d.retryOnAttach
      ? `Quarantined: ${d.quarantineReason}. Attaching retries its settings.`
      : `Quarantined: ${d.quarantineReason}. Inspect it, then unquarantine it to use it again.`;

/** Choose a device and attach it. */
export function DevicePicker({
  client,
  onSession,
  disabled = false,
}: {
  client: StudioClient;
  onSession: (session: Session) => void;
  /** Another tab controls the studio. */
  disabled?: boolean;
}) {
  const [devices, setDevices] = useState<DeviceChoice[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [serial, setSerial] = useState("");
  const [attaching, setAttaching] = useState(false);
  const [unquarantined, setUnquarantined] = useState<string[] | null>(null);

  const list = useCallback(() => {
    client.listDevices({}).then(
      (r) => {
        setError(null);
        setDevices(r.devices);
        const free = r.devices.filter((d) => d.state === DeviceState.DEVICE_FREE);
        setSerial((current) => (r.devices.some((d) => d.serial === current && choosable(d)) ? current : (free[0]?.serial ?? "")));
      },
      (e: unknown) => setError(errorMessage(e)),
    );
  }, [client]);

  useEffect(list, [list]);

  // A device just released, an emulator still booting and a phone being plugged in all change by
  // themselves, so a list with nothing to attach does not wait for Refresh.
  const waiting = error !== null || (devices !== null && !devices.some((d) => d.state === DeviceState.DEVICE_FREE));
  const held = !!devices?.some((d) => d.state !== DeviceState.DEVICE_FREE);
  useEffect(() => {
    if (!waiting && !held) return;
    const timer = setInterval(list, RECHECK_MS);
    return () => clearInterval(timer);
  }, [waiting, held, list]);

  const chosen = devices?.find((d) => d.serial === serial);
  const inspecting = inspectOnly(chosen);

  const attach = async () => {
    setAttaching(true);
    setError(null);
    setUnquarantined(null);
    try {
      const response = await client.attach({ serial, inspectQuarantined: inspecting });
      onSession(response.session!);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setAttaching(false);
    }
  };

  const unquarantine = async () => {
    setError(null);
    try {
      const r = await client.unquarantine({ serial });
      setUnquarantined([
        r.quarantineReason ? `${serial} is back in service.` : `${serial} was not quarantined.`,
        ...r.cleaned,
        ...r.remaining.map((line) => `Still off: ${line}`),
      ]);
      list();
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  return (
    <section className="picker" aria-label="Attach a device">
      <div className="start-h">
        <h2>Attach a device</h2>
        <button type="button" className="btn small ghost" onClick={list}>
          Refresh
        </button>
      </div>
      <form
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
        {unquarantined && (
          <ul className="start-note unquarantined" role="status">
            {unquarantined.map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ul>
        )}
        <fieldset>
          <legend className="sr-only">Device</legend>
          {devices === null ? (
            !error && <p className="start-note">Asking the Tap server…</p>
          ) : devices.length === 0 ? (
            <p className="start-note">No devices yet. Start an emulator, or connect a phone with USB debugging on.</p>
          ) : (
            <ul className="devices">
              {devices.map((d) => {
                const free = d.state === DeviceState.DEVICE_FREE;
                const quarantined = d.state === DeviceState.DEVICE_QUARANTINED;
                return (
                  <li key={d.serial}>
                    <label className={choosable(d) ? "" : "off"}>
                      <input type="radio" name="serial" value={d.serial} checked={serial === d.serial} disabled={!choosable(d)} onChange={() => setSerial(d.serial)} />
                      <span className="device-about">
                        <span className={d.model ? "device-name" : "device-name mono"}>{d.model || d.serial}</span>
                        <span className="device-facts">
                          {d.model && <span className="mono">{d.serial}</span>}
                          {d.apiLevel > 0 && <span>API {d.apiLevel}</span>}
                          {d.emulator && <span>Emulator</span>}
                        </span>
                        {!free && (
                          <span className="device-note">{quarantined ? quarantineNote(d) : STATE_NOTES[d.state] || "Unavailable"}</span>
                        )}
                      </span>
                    </label>
                  </li>
                );
              })}
            </ul>
          )}
        </fieldset>
        <button type="submit" className="btn primary attach" disabled={!serial || attaching || disabled}>
          {attaching ? "Attaching…" : inspecting ? "Inspect" : "Attach"}
        </button>
        {inspecting && (
          <button type="button" className="btn attach" disabled={attaching || disabled} onClick={() => void unquarantine()}>
            Unquarantine
          </button>
        )}
        {attaching && <p className="start-note">Starting the driver on the device. The first time on a device can take a minute.</p>}
      </form>
    </section>
  );
}
