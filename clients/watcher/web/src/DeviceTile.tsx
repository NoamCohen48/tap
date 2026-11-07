// One device on the wall or in the device pane: its live screen, and under it (never over it)
// the state, the step it runs and how the last one ended. A failure stays on the tile until the
// device is opened.

import { memo, useContext, useEffect, useRef, useState, type RefObject } from "react";
import type { Action } from "./activity";
import { errorText } from "./api";
import { readout, type FleetDevice } from "./fleet";
import { clock } from "./format";
import { store, usePageVisible } from "./hooks";
import { ScreensContext } from "./screens";
import { Player, type PlayerOptions } from "./video";

/**
 * A tile draws at its own size and at most five times a second, like a camera wall's
 * substreams: with ten devices at 15 fps, full-size draws were most of the page's CPU. The
 * opened device's stage keeps the full rate and size.
 */
const TILE_PLAYER: PlayerOptions = { fit: true, minFrameMs: 200 };

/**
 * Whether [element] is on screen in a visible tab. A tile scrolled out of view or in a hidden
 * tab stops decoding; it picks up again from the newest key frame the hub keeps.
 */
function useShown(element: RefObject<HTMLElement | null>): boolean {
  const [inView, setInView] = useState(true);
  const visible = usePageVisible();
  useEffect(() => {
    const target = element.current;
    if (!target || typeof IntersectionObserver === "undefined") return;
    const observer = new IntersectionObserver(([entry]) => setInView(entry.isIntersecting), { rootMargin: "100px" });
    observer.observe(target);
    return () => observer.disconnect();
  }, [element]);
  return inView && visible;
}

type ScreenProps = { serial: string; label: string; onShape: (landscape: boolean) => void };

/** The device's screen, live, from the wall's shared stream (see [ScreensContext]). */
export function TileScreen({ serial, label, onShape }: ScreenProps) {
  const hub = useContext(ScreensContext);
  const canvas = useRef<HTMLCanvasElement>(null);
  const box = useRef<HTMLDivElement>(null);
  const [view, setView] = useState({ empty: true, error: "", width: 0, height: 0 });
  const shown = useShown(box);
  useEffect(() => {
    if (!canvas.current || !hub || !shown) return;
    const player = new Player(canvas.current, (message) => setView((current) => ({ ...current, error: message })), TILE_PLAYER);
    const show = (screen: ReturnType<typeof hub.get>) => {
      let error = screen.error;
      try {
        if (!screen.buffer.empty) player.live(screen.buffer);
      } catch (failure) {
        error = errorText(failure);
      }
      const header = screen.buffer.header;
      const next = { empty: screen.buffer.empty, error, width: header?.width ?? 0, height: header?.height ?? 0 };
      setView((current) => (current.empty === next.empty && current.error === next.error && current.width === next.width && current.height === next.height ? current : next));
    };
    show(hub.get(serial));
    const unsubscribe = hub.subscribe(serial, show);
    return () => {
      unsubscribe();
      player.stop();
    };
  }, [hub, serial, shown]);
  const landscape = view.width > view.height;
  useEffect(() => onShape(landscape), [landscape, onShape]);
  const note = view.error && view.empty ? "No video" : view.empty ? "Connecting…" : "";
  return (
    <div className="tile-screen" ref={box} style={view.width ? { aspectRatio: `${view.width} / ${view.height}` } : undefined}>
      <canvas ref={canvas} aria-label={label} hidden={view.empty} />
      {note && (
        <span className="tile-note" title={view.error}>
          {note}
        </span>
      )}
    </div>
  );
}

type Props = {
  /** Everything the tile shows, as text: the tile re-renders only when it changes. */
  sig: string;
  entry: FleetDevice;
  now: number;
  /** The device's failures the viewer has not looked at. */
  unseen: Action[];
  /** Stream the screen (the wall's "Live screens" switch). */
  live: boolean;
  recording: boolean;
  /** "wall": full caption; "pane": the compact one beside an opened device. */
  size: "wall" | "pane";
  current?: boolean;
  onOpen: (serial: string) => void;
  /** Told whether the screen is landscape, once its video says so (the wall widens it). */
  onShape?: (serial: string, landscape: boolean) => void;
};

function TileImpl({ entry, now, unseen, live, recording, size, current, onOpen, onShape }: Props) {
  const [landscape, setLandscape] = useState(false);
  const serial = entry.serial;
  useEffect(() => onShape?.(serial, landscape), [serial, landscape, onShape]);
  const said = readout(entry, now);
  const newest = unseen.at(-1);
  const owner = entry.owner ? store.label(entry.owner.clientConnectionId) : "";
  const label = `${entry.name}, ${entry.state.text}. ${said.step}${said.outcome ? `. ${said.outcome}` : ""}${unseen.length ? `. ${unseen.length} unseen ${unseen.length === 1 ? "failure" : "failures"}` : ""}`;
  return (
    <button
      className={`tile ${size} tone-${entry.state.tone}${unseen.length ? " unseen" : ""}${landscape && size === "wall" ? " landscape" : ""}${current ? " current" : ""}`}
      aria-current={current || undefined}
      aria-label={label}
      title={`${entry.name} (${entry.serial})${owner ? `, ${owner}` : ""}`}
      onClick={() => onOpen(entry.serial)}
    >
      <span className="tile-video">
        {live && entry.streamable && !current ? (
          <TileScreen serial={entry.serial} label={`${entry.name} screen`} onShape={setLandscape} />
        ) : (
          <span className="tile-screen off">
            <span className="tile-note">{current ? "Open" : entry.streamable ? "Screens off" : entry.state.text}</span>
          </span>
        )}
      </span>
      {newest && (
        <span className="tile-band">
          <span>✕ {newest.described.outcome}</span>
          {size === "wall" && <span>{clock(newest.event.atEpochMs)}</span>}
          {unseen.length > 1 && <span className="more">+{unseen.length - 1}</span>}
        </span>
      )}
      <span className="tile-cap">
        <span className="tile-l1">
          <span className={`status-dot ${entry.state.tone}`} aria-hidden="true" />
          {size === "wall" && <span className="tile-state">{entry.state.text}</span>}
          <b>{entry.name}</b>
          {recording && <span className="rec-tag">REC</span>}
          {unseen.length > 0 && size === "pane" && <span className="tile-count">{unseen.length}</span>}
        </span>
        {size === "wall" && <span className={`tile-step${said.quiet ? " quiet" : ""}`}>{said.step}</span>}
        <span className={`tile-outcome ${said.tone}`}>{size === "pane" && !said.outcome ? entry.state.text : said.outcome}</span>
        {size === "wall" && owner && <span className="tile-owner">{owner}</span>}
      </span>
    </button>
  );
}

/** What a tile shows at [now], as one string: its memo key. */
export function tileSig(entry: FleetDevice, now: number, unseen: Action[]): string {
  const said = readout(entry, now);
  const owner = entry.owner ? store.label(entry.owner.clientConnectionId) : "";
  return [entry.name, entry.state.text, entry.state.tone, entry.streamable, said.step, said.quiet, said.outcome, said.tone, owner, unseen.length, unseen.at(-1)?.key ?? ""].join("\u0000");
}

export const DeviceTile = memo(
  TileImpl,
  (a, b) => a.sig === b.sig && a.live === b.live && a.recording === b.recording && a.size === b.size && a.current === b.current && a.onOpen === b.onOpen && a.onShape === b.onShape,
);
