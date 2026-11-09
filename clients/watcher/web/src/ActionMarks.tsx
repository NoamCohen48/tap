import { useEffect, useState } from "react";
import type { Action } from "./activity";
import { markFor, marksAt, type Mark, type Point } from "./markers";

type Props = {
  actions: Action[];
  clockId: string;
  ns: bigint;
  width: number;
  height: number;
  live?: boolean;
  selected?: Action;
};

/** A separate, non-interactive layer: never changes decoded or saved video pixels. */
export function ActionMarks({ actions, clockId, ns, width, height, live, selected }: Props) {
  const [, tick] = useState(0);
  const now = Date.now();
  // Live receipt times are a visual hint only. Playback below uses the shared monotonic clock.
  const candidates = live ? actions.filter(({ event }) => {
    const age = now - Number(event.atEpochMs + event.durationMs);
    return event.clockId === clockId && age >= -1000 && age < 1400;
  }).slice(-8) : selected ? [selected] : marksAt(actions, clockId, ns);
  const expires = live ? Math.min(...candidates.map(({ event }) => Number(event.atEpochMs + event.durationMs) + 1400)) : Infinity;
  useEffect(() => {
    if (!Number.isFinite(expires)) return;
    const timer = setTimeout(() => tick((n) => n + 1), Math.max(1, expires - Date.now()));
    return () => clearTimeout(timer);
  }, [expires]);
  const marks = candidates.flatMap((action) => {
    const mark = action.event.clockId === clockId ? markFor(action, width, height) : undefined;
    return mark ? [{ key: action.key, mark }] : [];
  });
  if (!marks.length) return null;
  return <svg className="action-marks" viewBox={`0 0 ${width} ${height}`} aria-hidden="true">
    {marks.map(({ key, mark }) => <svg key={key} width={width} height={height} viewBox={`0 0 ${mark.width} ${mark.height}`}>
      <g className="touch-outline"><Shape mark={mark} /></g>
      <Shape mark={mark} />
    </svg>)}
  </svg>;
}

function Shape({ mark }: { mark: Mark }) {
  const radius = Math.min(mark.width, mark.height) * 0.022;
  const ring = (point: Point, r: number, key: number) => <circle key={key} cx={point.x} cy={point.y} r={r} />;
  if (mark.kind === "path" || mark.kind === "pinch") {
    const paths = mark.kind === "pinch" ? [mark.points.slice(0, 2), mark.points.slice(2)] : [mark.points];
    return <g className="touch-path">{paths.map(([from, to], i) => {
      const angle = Math.atan2(to.y - from.y, to.x - from.x);
      const tip = (turn: number) => `${to.x - radius * Math.cos(angle + turn)} ${to.y - radius * Math.sin(angle + turn)}`;
      return <g key={i}>
        <path d={`M ${from.x} ${from.y} L ${to.x} ${to.y} M ${tip(-0.6)} L ${to.x} ${to.y} L ${tip(0.6)}`} />
        {ring(from, radius * 0.45, i)}
      </g>;
    })}</g>;
  }
  return <g className={`touch-ring ${mark.kind}`}>
    {ring(mark.points[0], radius, 0)}
    {mark.kind !== "tap" && ring(mark.points[0], radius * 1.55, 1)}
    <circle className="touch-dot" cx={mark.points[0].x} cy={mark.points[0].y} r={radius * 0.22} />
  </g>;
}
