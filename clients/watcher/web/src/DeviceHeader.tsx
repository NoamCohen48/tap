// Above the opened device's screen: which device this is, its state and who holds it, the way
// back to the wall and to the previous or next device, and the keyboard shortcuts.

import { useEffect, useRef } from "react";
import type { FleetDevice } from "./fleet";
import { store } from "./hooks";
import * as Icon from "./icons";

type Props = {
  entry?: FleetDevice;
  serial: string;
  /** Devices on the wall, for "2 of 10". */
  count: number;
  index: number;
  onWall: () => void;
  onStep: (dir: 1 | -1) => void;
};

const KEYS: [string, string][] = [
  ["j / k", "Next / previous action"],
  ["n / Shift+N", "Next / previous failure"],
  ["c", "Before / after the selected action"],
  ["Esc", "Clear the selection"],
  ["space", "Play / pause"],
  ["← / →", "Frame back / forward (Shift: 15)"],
  ["l", "Go live"],
  ["+ / − / 0", "Zoom the scrubber in / out / fit"],
  ["[ / ]", "Previous / next device"],
  ["g", "Back to the wall"],
];

export function DeviceHeader({ entry, serial, count, index, onWall, onStep }: Props) {
  const keys = useRef<HTMLDetailsElement>(null);
  // "?" opens the list; a click elsewhere closes it.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.target instanceof HTMLElement && event.target.closest("input, textarea, select")) return;
      if (event.key === "?" && keys.current) keys.current.open = !keys.current.open;
      else if (event.key === "Escape" && keys.current?.open) keys.current.open = false;
    };
    const onClick = (event: MouseEvent) => {
      if (keys.current?.open && !keys.current.contains(event.target as Node)) keys.current.open = false;
    };
    window.addEventListener("keydown", onKey);
    window.addEventListener("click", onClick);
    return () => {
      window.removeEventListener("keydown", onKey);
      window.removeEventListener("click", onClick);
    };
  }, []);

  const properties = entry?.device?.properties;
  const facts = [serial !== entry?.name ? serial : "", properties?.apiLevel ? `API ${properties.apiLevel}` : "", properties?.emulator ? "emulator" : ""].filter(Boolean);
  return (
    <header className="device-header">
      <button className="button" onClick={onWall} title="Back to the wall (G)">
        <Icon.ChevronLeft /> <span className="label">Wall</span>
      </button>
      <div className="device-id">
        <h2>
          <span className={`status-dot ${entry?.state.tone ?? "free"}`} aria-hidden="true" />
          {entry?.name ?? serial}
        </h2>
        <p>
          <span className={`device-state tone-${entry?.state.tone ?? "free"}`}>{entry?.state.text ?? "Unknown"}</span>
          {facts.map((fact) => (
            <span key={fact}>{fact}</span>
          ))}
          <span className="device-owner">{entry?.owner ? `held by ${store.label(entry.owner.clientConnectionId)}` : "no connection holds it"}</span>
        </p>
      </div>
      <span className="spacer" />
      {count > 1 && (
        <span className="device-step" role="group" aria-label="Device">
          <button className="icon-button" onClick={() => onStep(-1)} title="Previous device ([)" aria-label="Previous device">
            <Icon.ChevronLeft />
          </button>
          <span className="device-index">
            {index + 1} of {count}
          </span>
          <button className="icon-button" onClick={() => onStep(1)} title="Next device (])" aria-label="Next device">
            <Icon.ChevronRight />
          </button>
        </span>
      )}
      <details className="keys" ref={keys}>
        <summary className="icon-button" title="Keyboard shortcuts (?)" aria-label="Keyboard shortcuts">
          <Icon.Keyboard />
        </summary>
        <dl>
          {KEYS.map(([key, what]) => (
            <div key={key}>
              <dt>
                {key.split(" / ").map((part, i) => (
                  <span key={part}>
                    {i > 0 && " / "}
                    <kbd>{part}</kbd>
                  </span>
                ))}
              </dt>
              <dd>{what}</dd>
            </div>
          ))}
        </dl>
      </details>
    </header>
  );
}
