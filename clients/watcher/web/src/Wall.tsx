// The wall: every device at once, each tile as large as the window allows. Opening a tile
// investigates that device; the wall then stays beside it as the device pane.

import { useCallback, useEffect, useRef, useState } from "react";
import { DeviceTile, tileSig } from "./DeviceTile";
import type { FleetDevice } from "./fleet";
import { store } from "./hooks";

type Props = {
  devices: FleetDevice[];
  now: number;
  live: boolean;
  /** Serials the back end is recording. */
  recording: Set<string>;
  onLive: (live: boolean) => void;
  onOpen: (serial: string) => void;
};

const GAP = 14;
/** The caption under each screen: state, two lines of step, outcome, connection. */
const CAPTION = 108;
const MIN_TILE = 168;
const MAX_TILE = 300;
const PHONE = 19.5 / 9;

/**
 * The column count and tile width that fit [count] tiles in [width] × [height] without
 * scrolling, or, when none fits at [MIN_TILE], as many columns of it as the width holds. A
 * landscape screen takes two columns, so [wide] of the tiles count twice.
 */
export function wallLayout(count: number, width: number, height: number, wide = 0): { columns: number; tile: number } {
  const slots = count + wide;
  let best = { columns: 1, tile: 0 };
  for (let columns = wide ? 2 : 1; columns <= Math.max(1, slots); columns++) {
    const rows = Math.ceil(slots / columns);
    const byWidth = (width - GAP * (columns - 1)) / columns;
    const byHeight = ((height - GAP * (rows - 1)) / rows - CAPTION) / PHONE;
    const tile = Math.min(byWidth, byHeight, MAX_TILE);
    if (tile >= MIN_TILE && tile > best.tile) best = { columns, tile };
  }
  if (best.tile) return { columns: best.columns, tile: Math.floor(best.tile) };
  const columns = Math.max(wide ? 2 : 1, Math.min(slots, Math.floor((width + GAP) / (MIN_TILE + GAP))));
  return { columns, tile: Math.floor(Math.min(MAX_TILE, (width - GAP * (columns - 1)) / columns)) };
}

export function Wall({ devices, now, live, recording, onLive, onOpen }: Props) {
  const box = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState({ width: 1200, height: 800 });
  useEffect(() => {
    const element = box.current;
    if (!element) return;
    const observer = new ResizeObserver(([entry]) => setSize({ width: entry.contentRect.width, height: entry.contentRect.height }));
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  // Which screens are landscape (a tablet, a rotated phone): known once their video starts.
  const [landscape, setLandscape] = useState<ReadonlySet<string>>(new Set());
  const onShape = useCallback((serial: string, wide: boolean) => {
    setLandscape((current) => {
      if (current.has(serial) === wide) return current;
      const next = new Set(current);
      if (wide) next.add(serial);
      else next.delete(serial);
      return next;
    });
  }, []);
  const wide = devices.filter((entry) => landscape.has(entry.serial)).length;
  const layout = wallLayout(devices.length, size.width, size.height, wide);
  const unseen = devices.reduce((total, entry) => total + store.unseen(entry.serial).length, 0);
  const newestUnseen = devices
    .flatMap((entry) => store.unseen(entry.serial).map((action) => ({ serial: entry.serial, at: action.event.atEpochMs })))
    .sort((a, b) => Number(b.at - a.at))[0];

  return (
    <section className="wall" aria-label="All devices">
      <div className="wall-bar">
        <h2>Devices</h2>
        <span className="wall-count">{devices.length}</span>
        {unseen > 0 && newestUnseen && (
          <button className="pill bad" onClick={() => onOpen(newestUnseen.serial)} title="Open the device with the newest unseen failure">
            <span className="dot" /> {unseen} unseen {unseen === 1 ? "failure" : "failures"}
          </button>
        )}
        <span className="spacer" />
        <label className="switch" title="Streaming every screen runs a video encoder on every device">
          <input type="checkbox" checked={live} onChange={(event) => onLive(event.target.checked)} /> Live screens
        </label>
      </div>
      <div className="wall-scroll" ref={box}>
        {!devices.length ? (
          <p className="empty">No devices. Connect one over ADB.</p>
        ) : (
          <div className="wall-grid" style={{ gridTemplateColumns: `repeat(${layout.columns}, ${layout.tile}px)` }}>
            {devices.map((entry) => (
              <DeviceTile key={entry.serial} sig={tileSig(entry, now, store.unseen(entry.serial))} entry={entry} now={now} unseen={store.unseen(entry.serial)} live={live} recording={recording.has(entry.serial)} size="wall" onOpen={onOpen} onShape={onShape} />
            ))}
          </div>
        )}
      </div>
    </section>
  );
}
