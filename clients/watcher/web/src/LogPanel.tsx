// A device log: one line per entry (only the rows on screen are rendered), a search box and a
// minimum priority, the full text of the picked entry, and a line at the video's position.
// Picking an entry asks the owner to show the video at that moment.

import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import { LogPriority } from "./gen/device_log_pb";
import * as Icon from "./icons";
import { filtered, indexAtSeq, type LogRow } from "./log";

const ROW_PX = 22;
const OVERSCAN = 12;

const PRIORITIES: [LogPriority, string][] = [
  [LogPriority.UNSPECIFIED, "All levels"],
  [LogPriority.DEBUG, "Debug +"],
  [LogPriority.INFO, "Info +"],
  [LogPriority.WARN, "Warn +"],
  [LogPriority.ERROR, "Error +"],
];

type Props = {
  rows: LogRow[];
  /** Bumped when [rows] changed in place. */
  version: number;
  /** The text of a row's time column, and what that time is. */
  time: (row: LogRow) => string;
  timeLabel: string;
  /** The seq of the last entry at or before the video's position (0 before them all);
   * undefined while the video is live, when the list follows the newest entry instead. */
  at?: bigint;
  selected?: bigint;
  onPick: (row: LogRow | undefined) => void;
  /** Shown instead of the list while there are no rows. */
  empty: string;
  /** A line above the list (what is missing and why). */
  note?: string;
  hidden?: boolean;
};

export function LogPanel({ rows, version, time, timeLabel, at, selected, onPick, empty, note, hidden }: Props) {
  const [query, setQuery] = useState("");
  const [minPriority, setMinPriority] = useState(LogPriority.UNSPECIFIED);
  const box = useRef<HTMLDivElement>(null);
  const [view, setView] = useState({ top: 0, height: 0 });
  const following = useRef(true);

  // eslint-disable-next-line react-hooks/exhaustive-deps
  const visible = useMemo(() => filtered(rows, { query, minPriority }), [rows, version, query, minPriority]);
  const picked = useMemo(() => (selected === undefined ? undefined : visible.find((row) => row.seq === selected)), [visible, selected]);
  const head = at === undefined ? -1 : indexAtSeq(visible, at);

  // The list's height changes with the window, the details and the tab.
  useEffect(() => {
    const element = box.current;
    if (!element) return;
    const observer = new ResizeObserver(() => setView((current) => ({ ...current, height: element.clientHeight })));
    observer.observe(element);
    return () => observer.disconnect();
  }, []);

  // Live: stay on the newest entry while the list is scrolled to the bottom.
  useLayoutEffect(() => {
    const element = box.current;
    if (element && at === undefined && following.current && !hidden) element.scrollTop = element.scrollHeight;
  }, [visible, at, hidden, view.height]);

  // Away from live: keep the video's position in view as it moves.
  useLayoutEffect(() => {
    const element = box.current;
    if (!element || at === undefined || hidden) return;
    const top = Math.max(0, head) * ROW_PX;
    if (top < element.scrollTop || top + ROW_PX > element.scrollTop + element.clientHeight) {
      element.scrollTop = Math.max(0, top - element.clientHeight / 2);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [head, at === undefined, hidden]);

  const from = Math.max(0, Math.floor(view.top / ROW_PX) - OVERSCAN);
  const to = Math.min(visible.length, Math.ceil((view.top + view.height) / ROW_PX) + OVERSCAN);

  return (
    <section className="log" aria-label="Device log" hidden={hidden}>
      <div className="timeline-head">
        <label className="search">
          <Icon.Search />
          <span className="sr-only">Search the log</span>
          <input type="search" placeholder="Search the log" value={query} onChange={(event) => setQuery(event.target.value)} />
        </label>
        <label className="level">
          <span className="sr-only">Minimum priority</span>
          <select value={minPriority} onChange={(event) => setMinPriority(Number(event.target.value))}>
            {PRIORITIES.map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
      </div>
      {note && <p className="note">{note}</p>}
      <div
        ref={box}
        className="log-rows"
        onScroll={(event) => {
          const element = event.currentTarget;
          following.current = element.scrollHeight - element.scrollTop - element.clientHeight < ROW_PX;
          setView({ top: element.scrollTop, height: element.clientHeight });
        }}
      >
        {visible.length ? (
          <ol style={{ height: visible.length * ROW_PX }} aria-label="Log entries">
            {visible.slice(from, to).map((row, offset) => {
              const index = from + offset;
              return (
                <li key={String(row.seq)} style={{ top: index * ROW_PX }}>
                  <button
                    className={`log-row ${row.kind}${row.bad ? " bad" : ""}${row.priority === LogPriority.WARN ? " warn" : ""}${row.seq === selected ? " selected" : ""}${index === head ? " head" : ""}`}
                    aria-current={index === head ? "true" : undefined}
                    title={row.text}
                    onClick={() => onPick(row.seq === selected ? undefined : row)}
                  >
                    <span className="log-time">{time(row)}</span>
                    <span className="log-level">{row.kind === "line" ? row.level : row.kind === "marker" ? "⚑" : row.kind === "action" ? "▸" : "!"}</span>
                    <span className="log-text">
                      {row.tag && <span className="log-tag">{row.tag}</span>}
                      {row.text}
                    </span>
                  </button>
                </li>
              );
            })}
          </ol>
        ) : (
          <p className="empty">{rows.length ? "No entry matches." : empty}</p>
        )}
      </div>
      <p className="hint">
        {visible.length === rows.length ? `${rows.length} entries` : `${visible.length} of ${rows.length} entries`} · click one to show the screen at that moment
      </p>
      {picked && <Entry row={picked} time={time(picked)} timeLabel={timeLabel} onClose={() => onPick(undefined)} />}
    </section>
  );
}

function Entry({ row, time, timeLabel, onClose }: { row: LogRow; time: string; timeLabel: string; onClose: () => void }) {
  const line = row.entry.kind.case === "line" ? row.entry.kind.value : undefined;
  return (
    <section className="details" aria-label="Selected log entry">
      <header>
        <h2>{row.kind === "line" ? `${row.level}/${row.tag}` : row.kind === "marker" ? "Marker" : row.kind === "action" ? "Tap action" : "Gap"}</h2>
        <button className="icon-button" aria-label="Close" onClick={onClose}>
          <Icon.Close />
        </button>
      </header>
      <pre className="log-message">{row.text}</pre>
      <dl>
        <dt>{timeLabel}</dt>
        <dd>{time}</dd>
        {line && (
          <>
            <dt>Process</dt>
            <dd>
              pid {line.pid} · tid {line.tid}
              {line.uid !== undefined && ` · uid ${line.uid}`}
            </dd>
            <dt>Device time</dt>
            <dd>{new Date(Number(line.deviceTimeMs)).toLocaleTimeString([], { hour12: false })}</dd>
          </>
        )}
      </dl>
    </section>
  );
}
