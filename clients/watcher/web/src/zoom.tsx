// Zooming a scrubber: the visible window over a track of `total` seconds. Ctrl/⌘ + scroll (or
// a trackpad pinch) zooms around the pointer, scroll pans while zoomed, + / - / 0 zoom around
// the head or fit. Shared by the Live scrubber and the Library player.

import { useEffect, useLayoutEffect, useRef, useState, type RefObject } from "react";
import { duration } from "./format";
import * as Icon from "./icons";

/** A window over the track, in seconds from its start. */
export type View = { start: number; end: number };

/** The narrowest window, in seconds. */
export const MIN_SPAN_S = 1;

/** Each zoom step halves or doubles the window. */
const STEP = 2;

/** Where the head lands when the window pages to follow it, as a fraction of the window. */
const FOLLOW_AT = 0.1;

/**
 * The visible window: everything without a view, else the view's span clamped to the track;
 * pinned to the end while following live.
 */
export function windowOf(view: View | undefined, total: number, pinEnd: boolean): View {
  const length = Math.max(total, 0);
  if (!view) return { start: 0, end: length };
  const span = Math.min(view.end - view.start, length);
  if (pinEnd) return { start: length - span, end: length };
  const start = Math.min(Math.max(view.start, 0), length - span);
  return { start, end: start + span };
}

/** `current` scaled by `factor` (< 1 zooms in) keeping `anchor` in place; undefined when it
 * would show the whole track. */
export function zoomView(current: View, total: number, anchor: number, factor: number, minSpan = MIN_SPAN_S): View | undefined {
  const span = current.end - current.start;
  const next = Math.min(total, Math.max(Math.min(minSpan, total), span * factor));
  if (next >= total) return undefined;
  const ratio = span > 0 ? Math.min(1, Math.max(0, (anchor - current.start) / span)) : 1;
  const start = Math.min(Math.max(anchor - ratio * next, 0), total - next);
  return { start, end: start + next };
}

/** `current` moved by `delta` seconds, kept on the track. */
export function panView(current: View, total: number, delta: number): View {
  const span = current.end - current.start;
  const start = Math.min(Math.max(current.start + delta, 0), Math.max(0, total - span));
  return { start, end: start + span };
}

/** A window that pages to keep `head` in view, or `current` when it already shows it. */
export function followView(current: View, total: number, head: number): View {
  if (head >= current.start && head <= current.end) return current;
  const span = current.end - current.start;
  const start = Math.min(Math.max(head - span * FOLLOW_AT, 0), Math.max(0, total - span));
  return { start, end: start + span };
}

export type Zoom = {
  /** The visible window. */
  shown: View;
  zoomed: boolean;
  canZoomIn: boolean;
  total: number;
  /** A track time as a fraction of the window (outside [0, 1]: not shown). */
  fraction: (seconds: number) => number;
  /** The track time at a fraction of the window. */
  at: (ratio: number) => number;
  /** Zooms by `factor` around `anchor` (default: the head). */
  zoom: (factor: number, anchor?: number) => void;
  fit: () => void;
};

/**
 * Zoom state for a track of `total` seconds with its head at `head`. `pinEnd`: the track is
 * following live, so the window keeps to its end. Wheel and keys are bound to `element`.
 */
export function useZoom(element: RefObject<HTMLElement | null>, total: number, head: number, pinEnd: boolean): Zoom {
  const [view, setView] = useState<View>();
  const shown = windowOf(view, total, pinEnd);
  const zoomed = view !== undefined && shown.end - shown.start < total;
  const latest = useRef({ total, head, pinEnd, shown, zoomed });
  latest.current = { total, head, pinEnd, shown, zoomed };

  // Leaving live keeps the window where it was on screen (not where the view started).
  const wasPinned = useRef(pinEnd);
  useLayoutEffect(() => {
    if (wasPinned.current && !pinEnd && view) setView(latest.current.shown);
    wasPinned.current = pinEnd;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [pinEnd]);

  // A moving head (replay) that leaves the window pages it along. Panning moves the window,
  // not the head, so it is not undone.
  useEffect(() => {
    if (!view || pinEnd) return;
    const next = followView(shown, total, head);
    if (next !== shown) setView(next);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [head]);

  const zoom = (factor: number, anchor?: number) => {
    const now = latest.current;
    setView(zoomView(now.shown, now.total, anchor ?? now.head, factor));
  };
  const fit = () => setView(undefined);

  useEffect(() => {
    const target = element.current;
    if (!target) return;
    const onWheel = (event: WheelEvent) => {
      const now = latest.current;
      const box = target.getBoundingClientRect();
      const span = now.shown.end - now.shown.start;
      if (event.ctrlKey || event.metaKey) {
        // Also a trackpad pinch. Without preventDefault the browser zooms the page.
        event.preventDefault();
        const ratio = Math.min(1, Math.max(0, (event.clientX - box.left) / box.width));
        setView(zoomView(now.shown, now.total, now.shown.start + ratio * span, Math.exp(event.deltaY * 0.004)));
      } else if (now.zoomed && !now.pinEnd) {
        event.preventDefault();
        const delta = Math.abs(event.deltaX) > Math.abs(event.deltaY) ? event.deltaX : event.deltaY;
        setView(panView(now.shown, now.total, (delta / box.width) * span));
      }
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.target instanceof HTMLElement && event.target.closest("input, textarea, select, dialog")) return;
      if (event.metaKey || event.ctrlKey || event.altKey) return;
      if (event.key === "+" || event.key === "=") zoom(1 / STEP);
      else if (event.key === "-" || event.key === "_") zoom(STEP);
      else if (event.key === "0") fit();
    };
    target.addEventListener("wheel", onWheel, { passive: false });
    window.addEventListener("keydown", onKey);
    return () => {
      target.removeEventListener("wheel", onWheel);
      window.removeEventListener("keydown", onKey);
    };
    // The handlers read `latest`; binding once per element is enough.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [element]);

  const span = shown.end - shown.start;
  return {
    shown,
    zoomed,
    canZoomIn: span > Math.min(MIN_SPAN_S, total) + 1e-9,
    total,
    fraction: (seconds) => (span > 0 ? (seconds - shown.start) / span : 1),
    at: (ratio) => shown.start + ratio * span,
    zoom,
    fit,
  };
}

/** − / span / + beside a track; the span fits the track back when clicked. */
export function ZoomControls({ zoom }: { zoom: Zoom }) {
  const span = zoom.shown.end - zoom.shown.start;
  return (
    <div className="zoom" role="group" aria-label="Zoom the timeline">
      <button className="icon-button" disabled={!zoom.zoomed} onClick={() => zoom.zoom(STEP)} aria-label="Zoom out" title="Zoom out (-)">
        <Icon.Minus />
      </button>
      <button className="zoom-level" disabled={!zoom.zoomed} onClick={zoom.fit} title={zoom.zoomed ? "Show everything (0)" : "Zoom in with +, or ctrl + scroll on the timeline"}>
        {zoom.zoomed ? duration(span) : "All"}
      </button>
      <button className="icon-button" disabled={!zoom.canZoomIn} onClick={() => zoom.zoom(1 / STEP)} aria-label="Zoom in" title="Zoom in (+, or ctrl + scroll)">
        <Icon.Plus />
      </button>
    </div>
  );
}

/** Under a zoomed track: which part of the whole the window shows. */
export function Overview({ zoom }: { zoom: Zoom }) {
  if (!zoom.zoomed || zoom.total <= 0) return null;
  const left = (zoom.shown.start / zoom.total) * 100;
  const width = ((zoom.shown.end - zoom.shown.start) / zoom.total) * 100;
  return (
    <div className="overview" aria-hidden="true">
      <span style={{ left: `${left}%`, width: `${width}%` }} />
    </div>
  );
}
