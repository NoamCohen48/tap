// The wall beside an opened device. Drag its edge: narrow, it is a column of small live screens;
// wide, it is the wall again, captions included. The width is remembered per browser, and
// never takes the room the screen and the actions need.

import { useCallback, useEffect, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";
import { DeviceTile, tileSig } from "./DeviceTile";
import type { FleetDevice } from "./fleet";
import { store } from "./hooks";

const WIDTH_KEY = "tap-watcher.pane-width";
export const PANE_MIN = 120;
export const PANE_MAX = 720;
/** At this width the pane shows wall tiles (full captions) instead of the compact ones. */
const WALL_TILES = 380;
const NARROW = 152;
const WIDE = 520;
/** What the pane leaves the rest of the window: the action list and a usable screen. */
const LEAVE = 400 + 480;

/** The widest the pane may be in a window [windowWidth] wide. */
export function paneLimit(windowWidth: number): number {
  return Math.max(PANE_MIN, Math.min(PANE_MAX, windowWidth - LEAVE));
}

function useWindowWidth(): number {
  const [width, setWidth] = useState(() => globalThis.innerWidth ?? 1440);
  useEffect(() => {
    const onResize = () => setWidth(window.innerWidth);
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, []);
  return width;
}

function storedWidth(): number {
  try {
    const value = Number(globalThis.localStorage?.getItem(WIDTH_KEY));
    return value >= PANE_MIN && value <= PANE_MAX ? value : NARROW;
  } catch {
    return NARROW;
  }
}

type Props = {
  devices: FleetDevice[];
  current: string;
  now: number;
  live: boolean;
  /** Serials the back end is recording. */
  recording: Set<string>;
  onOpen: (serial: string) => void;
  onWidth: (width: number) => void;
};

export function FleetPane({ devices, current, now, live, recording, onOpen, onWidth }: Props) {
  const [stored, setWidthState] = useState(storedWidth);
  const limit = paneLimit(useWindowWidth());
  const width = Math.min(stored, limit);
  const pane = useRef<HTMLElement>(null);
  const drag = useRef<{ x: number; width: number }>(undefined);
  const setWidth = useCallback((next: number) => {
    const clamped = Math.round(Math.max(PANE_MIN, Math.min(paneLimit(window.innerWidth), next)));
    setWidthState(clamped);
    try {
      globalThis.localStorage?.setItem(WIDTH_KEY, String(clamped));
    } catch {
      // Not remembered in a private window.
    }
  }, []);
  useEffect(() => onWidth(width), [width, onWidth]);
  // The opened device's tile stays in view, so the pane shows where you are.
  useEffect(() => {
    pane.current?.querySelector(".tile[aria-current]")?.scrollIntoView({ block: "nearest", inline: "nearest" });
  }, [current]);

  const onDown = (event: ReactPointerEvent<HTMLDivElement>) => {
    drag.current = { x: event.clientX, width };
    event.currentTarget.setPointerCapture(event.pointerId);
  };
  const onMove = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (drag.current) setWidth(drag.current.width + event.clientX - drag.current.x);
  };
  const onUp = () => (drag.current = undefined);
  const size = width >= WALL_TILES ? "wall" : "pane";

  return (
    <nav className={`fleet-pane ${size}`} aria-label="Devices" ref={pane}>
      <div className="fleet-scroll">
        <div className="fleet-grid">
          {devices.map((entry) => (
            <DeviceTile key={entry.serial} sig={tileSig(entry, now, store.unseen(entry.serial))} entry={entry} now={now} unseen={store.unseen(entry.serial)} live={live} recording={recording.has(entry.serial)} size={size} current={entry.serial === current} onOpen={onOpen} />
          ))}
        </div>
      </div>
      <div
        className="fleet-resize"
        role="separator"
        aria-orientation="vertical"
        aria-label="Device pane width"
        aria-valuemin={PANE_MIN}
        aria-valuemax={limit}
        aria-valuenow={width}
        tabIndex={0}
        title="Drag to resize; double-click to switch between a column and the wall"
        onPointerDown={onDown}
        onPointerMove={onMove}
        onPointerUp={onUp}
        onPointerCancel={onUp}
        onDoubleClick={() => setWidth(width >= WALL_TILES ? NARROW : WIDE)}
        onKeyDown={(event) => {
          if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
            event.preventDefault();
            event.stopPropagation();
            setWidth(width + (event.key === "ArrowLeft" ? -40 : 40));
          }
        }}
      />
    </nav>
  );
}
