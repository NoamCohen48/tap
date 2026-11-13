import { useEffect, useRef, useState } from "react";
import { api, errorText } from "./api";
import type { TouchIndicators } from "./gen/watch_pb";
import * as Icon from "./icons";

/** Explicit Android settings, not the browser's Marks preference. Mount keyed by serial. */
export function PhoneIndicators({ serial }: { serial: string }) {
  const [open, setOpen] = useState(false);
  const [settings, setSettings] = useState<TouchIndicators>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [revision, reload] = useState(0);
  const writing = useRef(false);
  useEffect(() => {
    if (!open || !serial || writing.current) return;
    const controller = new AbortController();
    setBusy(true);
    setSettings(undefined);
    setError("");
    api.getTouchIndicators({ serial }, { signal: controller.signal }).then(
      (value) => {
        if (controller.signal.aborted) return;
        if (value.indicators) setSettings(value.indicators);
        else setError("The daemon returned no touch-indicator settings. Update the daemon and reload.");
      },
      (failure) => { if (!controller.signal.aborted) setError(errorText(failure)); },
    ).finally(() => { if (!controller.signal.aborted) setBusy(false); });
    return () => controller.abort();
  }, [open, serial, revision]);

  const change = async (key: "showTaps" | "pointerLocation", enabled: boolean) => {
    if (busy || !settings || writing.current) return;
    writing.current = true;
    setBusy(true);
    setError("");
    try {
      // One explicit write. Never retry a transmitted setting change.
      const response = await api.setTouchIndicators({ serial, [key]: enabled });
      if (!response.indicators) throw new Error("The daemon returned no touch-indicator settings");
      setSettings(response.indicators);
    } catch (failure) {
      setSettings(undefined);
      setError(`${errorText(failure)}. Settings may have changed. Reload to check.`);
    } finally { writing.current = false; setBusy(false); }
  };

  return <details className="phone-indicators" onToggle={(event) => setOpen(event.currentTarget.open)}
    onKeyDown={(event) => {
      if (event.key === "Escape") {
        event.stopPropagation();
        event.currentTarget.open = false;
        event.currentTarget.querySelector("summary")?.focus();
      }
    }}>
    <summary className={`button${open ? " on" : ""}`} aria-label="On-phone touch indicators" title="Android touch indicators on the physical device">
      <Icon.Logo /> <span className="label">On phone</span>
    </summary>
    <div className="phone-indicators-panel" role="group" aria-label="Android touch indicators" aria-busy={busy}>
      <strong>On the phone</strong>
      <p>Device-wide Android settings. They affect everyone using this device and stay as you leave them, even after closing the watcher.</p>
      {!serial && <p>Select a device first.</p>}
      <label><input type="checkbox" checked={settings?.showTaps ?? false} disabled={busy || !settings} onChange={(event) => void change("showTaps", event.target.checked)} />
        <span>Tap circles<small>Android’s Show taps: physical finger touches only, not injected test taps</small></span>
      </label>
      <label><input type="checkbox" checked={settings?.pointerLocation ?? false} disabled={busy || !settings} onChange={(event) => void change("pointerLocation", event.target.checked)} />
        <span>Swipe trails<small>Android’s Pointer location: touch crosshairs and trails, including a debug bar and coordinates</small></span>
      </label>
      {busy && <p role="status">Updating settings…</p>}
      {error && <p className="phone-indicators-error" role="alert">{error}</p>}
      <button className="button" disabled={busy || !serial} onClick={() => reload((n) => n + 1)}>Reload settings</button>
    </div>
  </details>;
}
