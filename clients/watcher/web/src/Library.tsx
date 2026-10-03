// Saved recordings and clips: find, select and delete them, see how full the library is, and
// play one (its parts back to back) with its actions beside it.

import { fromJson, type JsonValue } from "@bufbuild/protobuf";
import { useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";
import { api, errorText } from "./api";
import { connectionLabel, describe, failed, summary } from "./describe";
import { bytes, dateTime, duration, plural } from "./format";
import { LoggedEventSchema } from "./gen/event_log_pb";
import { RecordingKind, type ListRecordingsResponse, type Recording } from "./gen/watcher_pb";
import * as Icon from "./icons";
import { Overview, useZoom, ZoomControls } from "./zoom";

type Kind = "all" | "recordings" | "clips";

type Props = {
  library?: ListRecordingsResponse;
  error: string;
  selected: string;
  onSelect: (id: string) => void;
  onChanged: () => void;
  notify: (message: string, failed?: boolean) => void;
};

export function Library({ library, error, selected, onSelect, onChanged, notify }: Props) {
  const recordings = useMemo(() => library?.recordings ?? [], [library]);
  const [query, setQuery] = useState("");
  const [kind, setKind] = useState<Kind>("all");
  const [checked, setChecked] = useState<Set<string>>(new Set());
  const [confirming, setConfirming] = useState<Recording[]>();

  const shown = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return recordings.filter(
      (entry) =>
        (kind === "all" || (kind === "clips") === (entry.kind === RecordingKind.CLIP)) &&
        (!needle || [entry.serial, ...entry.connectionNames.map(connectionLabel)].some((text) => text.toLowerCase().includes(needle))),
    );
  }, [recordings, query, kind]);
  const recording = recordings.find((entry) => entry.id === selected) ?? shown[0];

  // Forget checks on entries that are gone (deleted here, elsewhere, or expired).
  useEffect(() => {
    setChecked((current) => {
      const ids = new Set(recordings.map((entry) => entry.id));
      const kept = new Set([...current].filter((id) => ids.has(id)));
      return kept.size === current.size ? current : kept;
    });
  }, [recordings]);

  const toggle = (id: string) =>
    setChecked((current) => {
      const next = new Set(current);
      if (!next.delete(id)) next.add(id);
      return next;
    });
  const allShown = shown.length > 0 && shown.every((entry) => checked.has(entry.id));
  const checkedEntries = recordings.filter((entry) => checked.has(entry.id));

  const remove = async (entries: Recording[]) => {
    setConfirming(undefined);
    try {
      await api.deleteRecordings({ ids: entries.map((entry) => entry.id) });
      setChecked((current) => new Set([...current].filter((id) => !entries.some((entry) => entry.id === id))));
      onChanged();
      notify(entries.length === 1 ? `${kindName(entries[0])} deleted` : `${entries.length} saved videos deleted`);
    } catch (failure) {
      notify(`Could not delete: ${errorText(failure)}`, true);
    }
  };

  return (
    <div className="library">
      <nav className="library-list" aria-label="Saved recordings">
        <div className="library-head">
          <h2>Library</h2>
          {library && <Usage library={library} />}
          <div className="library-filters">
            <label className="search">
              <Icon.Search />
              <span className="sr-only">Search by device or connection</span>
              <input type="search" placeholder="Device or connection" value={query} onChange={(event) => setQuery(event.target.value)} />
            </label>
            <div className="segmented" role="group" aria-label="Show">
              {(["all", "recordings", "clips"] as const).map((value) => (
                <button key={value} aria-pressed={kind === value} onClick={() => setKind(value)}>
                  {value[0].toUpperCase() + value.slice(1)}
                </button>
              ))}
            </div>
          </div>
          {shown.length > 0 && (
            <div className={`selection-bar${checked.size ? " active" : ""}`}>
              <label className="check">
                <input
                  type="checkbox"
                  checked={allShown}
                  ref={(input) => {
                    if (input) input.indeterminate = checked.size > 0 && !allShown;
                  }}
                  onChange={() => setChecked(allShown ? new Set() : new Set(shown.map((entry) => entry.id)))}
                />
                {checked.size ? `${checked.size} selected · ${bytes(checkedEntries.reduce((sum, entry) => sum + Number(entry.bytes), 0))}` : "Select all"}
              </label>
              {checked.size > 0 && (
                <button className="button danger" onClick={() => setConfirming(checkedEntries)}>
                  <Icon.Trash /> Delete
                </button>
              )}
            </div>
          )}
        </div>
        {error && <p className="note bad">{error}</p>}
        {library && !recordings.length && (
          <div className="empty">
            <p>Nothing saved yet. Start a recording, or save a clip of the last two minutes, from the Live view.</p>
            <a className="button" href="#/live">
              <Icon.Live /> Go to Live
            </a>
          </div>
        )}
        {recordings.length > 0 && !shown.length && (
          <div className="empty">
            <p>{query.trim() ? `No ${kind === "all" ? "saved videos" : kind} match “${query.trim()}”.` : `No ${kind} yet.`}</p>
            <button
              className="link"
              onClick={() => {
                setQuery("");
                setKind("all");
              }}
            >
              Clear the filters
            </button>
          </div>
        )}
        {byDay(shown).map(([day, entries]) => (
          <section key={day} className="library-day" aria-label={day}>
            <h3>{day}</h3>
            <ul>
              {entries.map((entry) => (
                <li key={entry.id} className={`entry-row${entry === recording ? " selected" : ""}`}>
                  <input type="checkbox" aria-label={`Select the ${kindName(entry).toLowerCase()} of ${entry.serial} from ${dateTime(entry.createdEpochMs)}`} checked={checked.has(entry.id)} onChange={() => toggle(entry.id)} />
                  <button className="entry" aria-current={entry === recording} onClick={() => onSelect(entry.id)}>
                    <span className="entry-top">
                      <span className="kind">{kindName(entry)}</span>
                      <span className="serial">{entry.serial}</span>
                      {entry.failures > 0 && (
                        <span className="fail-count" title={`${plural(entry.failures, "failed action")}`}>
                          {entry.failures}
                        </span>
                      )}
                    </span>
                    <span className="entry-meta">
                      {timeOfDay(entry.createdEpochMs)} · {duration(entry.durationSeconds)} · {plural(entry.actions, "action")} · {bytes(entry.bytes)}
                    </span>
                    {entry.connectionNames.length > 0 && <span className="entry-meta">{entry.connectionNames.map(connectionLabel).join(", ")}</span>}
                  </button>
                </li>
              ))}
            </ul>
          </section>
        ))}
        {library?.directory && (
          <p className="hint" title={library.directory}>
            Saved in <code>{library.directory}</code>
          </p>
        )}
      </nav>
      {recording ? <Viewer key={recording.id} recording={recording} onDelete={() => setConfirming([recording])} /> : (
        <div className="viewer empty-viewer">
          <p>{recordings.length ? "Select a recording or clip to play it." : library ? "Saved recordings and clips play here." : ""}</p>
        </div>
      )}
      {confirming && <ConfirmDelete entries={confirming} onCancel={() => setConfirming(undefined)} onConfirm={() => remove(confirming)} />}
    </div>
  );
}

/** How full the library is, and what happens to old entries. */
function Usage({ library }: { library: ListRecordingsResponse }) {
  const used = Number(library.usedBytes);
  const max = Number(library.maxBytes) || 1;
  const ratio = Math.min(1, used / max);
  const tone = ratio >= 0.95 ? "bad" : ratio >= 0.8 ? "warn" : "ok";
  return (
    <div className="usage">
      <div className="usage-line">
        <span>
          <strong>{bytes(used)}</strong> of {bytes(max)} used
        </span>
        <span>{Math.round(ratio * 100)}%</span>
      </div>
      <div className={`meter ${tone}`} role="meter" aria-label="Library space used" aria-valuemin={0} aria-valuemax={max} aria-valuenow={used} aria-valuetext={`${bytes(used)} of ${bytes(max)}`}>
        <span style={{ width: `${ratio * 100}%` }} />
      </div>
      {tone === "ok" ? (
        <p className="note">{library.keepDays ? `Deleted automatically after ${plural(library.keepDays, "day")}.` : "Kept until you delete them."}</p>
      ) : (
        <p className={`note ${tone}`}>{ratio >= 1 ? "Full: new recordings and clips are refused until you delete some." : "Nearly full: delete recordings you no longer need."}</p>
      )}
    </div>
  );
}

function kindName(entry: Recording): string {
  return entry.kind === RecordingKind.CLIP ? "Clip" : "Recording";
}

function timeOfDay(epochMs: bigint): string {
  return new Date(Number(epochMs)).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
}

/** Entries grouped by the day they were made (they arrive newest first). */
function byDay(entries: Recording[]): [string, Recording[]][] {
  const today = new Date().toDateString();
  const yesterday = new Date(Date.now() - 86_400_000).toDateString();
  const groups = new Map<string, Recording[]>();
  for (const entry of entries) {
    const date = new Date(Number(entry.createdEpochMs));
    const key = date.toDateString();
    const label = key === today ? "Today" : key === yesterday ? "Yesterday" : date.toLocaleDateString(undefined, { weekday: "short", day: "numeric", month: "short", year: "numeric" });
    groups.set(label, [...(groups.get(label) ?? []), entry]);
  }
  return [...groups];
}


type Part = { file: string; width: number; height: number; start: number; duration: number };
type Step = { part: number; partOffset: number; offset: number; summary: string; failed: boolean; connection: string; partial: boolean };

type StepsJson = {
  parts: { file: string; width: number; height: number; startUs: string; durationUs: string }[];
  actions: { connection: string; event: JsonValue; part: number; videoOffsetUs: string; offsetUs: string; partial: boolean }[];
};

/** One saved entry: its parts played back to back on one timeline, and its actions beside it. */
function Viewer({ recording, onDelete }: { recording: Recording; onDelete: () => void }) {
  const video = useRef<HTMLVideoElement>(null);
  const list = useRef<HTMLOListElement>(null);
  const [loaded, setLoaded] = useState<{ parts: Part[]; steps: Step[] }>();
  const [stepsError, setStepsError] = useState("");
  const [part, setPart] = useState(0);
  const [time, setTime] = useState(0);
  const [playing, setPlaying] = useState(false);
  // Where to go once the part's file has loaded, and whether to keep playing there.
  const pendingSeek = useRef<{ seconds: number; play: boolean }>(undefined);
  const base = `/recordings/${encodeURIComponent(recording.id)}`;

  useEffect(() => {
    const abort = new AbortController();
    fetch(`${base}/steps.json`, { signal: abort.signal })
      .then((response) => (response.ok ? response.json() : Promise.reject(new Error(`steps.json: HTTP ${response.status}`))))
      .then((json: StepsJson) =>
        setLoaded({
          parts: json.parts.map((entry) => ({ file: entry.file, width: entry.width, height: entry.height, start: Number(entry.startUs) / 1e6, duration: Number(entry.durationUs) / 1e6 })),
          steps: json.actions.map((action) => {
            const event = fromJson(LoggedEventSchema, action.event, { ignoreUnknownFields: true });
            return {
              part: action.part,
              partOffset: Number(action.videoOffsetUs) / 1e6,
              offset: Number(action.offsetUs) / 1e6,
              summary: summary(describe(event)),
              failed: failed(event),
              connection: connectionLabel(action.connection),
              partial: action.partial,
            };
          }),
        }),
      )
      .catch((failure) => {
        if (!abort.signal.aborted) setStepsError(errorText(failure));
      });
    return () => abort.abort();
  }, [base]);

  // The playhead moves every frame while playing (timeupdate alone is ~4 Hz).
  useEffect(() => {
    if (!playing) return;
    let frame = requestAnimationFrame(function tick() {
      if (video.current) setTime(video.current.currentTime);
      frame = requestAnimationFrame(tick);
    });
    return () => cancelAnimationFrame(frame);
  }, [playing]);

  const parts = loaded?.parts ?? [];
  const steps = loaded?.steps;
  const last = parts[parts.length - 1];
  const total = last ? last.start + last.duration : recording.durationSeconds;
  // The position on the whole recording's timeline (the parts back to back).
  const position = (parts[part]?.start ?? 0) + time;
  const current = useMemo(() => {
    let index = -1;
    steps?.forEach((step, i) => {
      if (step.offset <= position + 0.05) index = i;
    });
    return index;
  }, [steps, position]);

  useEffect(() => {
    list.current?.querySelector('[aria-current="true"]')?.scrollIntoView({ block: "nearest" });
  }, [current]);

  /** Shows `seconds` into part `index`, loading that part's file first when needed. */
  const go = (index: number, seconds: number, play: boolean) => {
    const element = video.current;
    if (!element) return;
    if (index === part) {
      element.currentTime = seconds;
      setTime(seconds);
      if (play) void element.play().catch(() => undefined);
      else element.pause();
    } else {
      pendingSeek.current = { seconds, play };
      setPart(index);
      setTime(seconds);
    }
  };
  /** Seeks the whole recording: a time between two parts shows the start of the next. */
  const seek = (seconds: number, play = playing) => {
    if (!parts.length) return;
    const at = Math.min(Math.max(0, seconds), total);
    let index = Math.max(0, parts.findLastIndex((entry) => entry.start <= at));
    if (at >= parts[index].start + parts[index].duration && index + 1 < parts.length) index += 1;
    go(index, Math.min(Math.max(0, at - parts[index].start), parts[index].duration), play);
  };
  const toggle = () => {
    const element = video.current;
    if (!element) return;
    if (!element.paused) element.pause();
    else if (position >= total - 0.1) seek(0, true);
    else void element.play().catch(() => undefined);
  };
  const step = (index: number) => {
    const target = steps?.[index];
    if (target) go(target.part, target.partOffset, false);
  };

  // Keyboard, as in the Live view: space, ←/→ (shift: 5 s), j/k for the next / previous action.
  const keys = useRef<(event: KeyboardEvent) => void>(undefined);
  keys.current = (event) => {
    if (event.target instanceof HTMLElement && event.target.closest("input, textarea, select, dialog")) return;
    if (event.metaKey || event.ctrlKey || event.altKey) return;
    if (event.key === " ") {
      if (event.target instanceof HTMLElement && event.target.closest("button, summary, a")) return;
      event.preventDefault();
      toggle();
    } else if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
      event.preventDefault();
      seek(position + (event.key === "ArrowLeft" ? -1 : 1) * (event.shiftKey ? 5 : 1));
    } else if (event.key === "j") {
      step(current + 1);
    } else if (event.key === "k" && steps?.length) {
      // At an action, k goes to the one before; between two, back to the one just passed.
      step(current >= 0 && position - steps[current].offset < 0.3 ? current - 1 : current);
    }
  };
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => keys.current?.(event);
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const file = parts[part]?.file ?? "video-1.mp4";
  return (
    <section className="viewer" aria-label={`${kindName(recording)} of ${recording.serial}`}>
      <header className="viewer-head">
        <div className="viewer-title">
          <h2>
            {kindName(recording)} · <span className="serial">{recording.serial}</span>
          </h2>
          <p className="entry-meta">
            {dateTime(recording.createdEpochMs)} · {duration(recording.durationSeconds)} · {plural(recording.actions, "action")} · {bytes(recording.bytes)}
            {parts.length > 1 && ` · ${parts.length} parts`}
            {recording.ended && recording.ended !== "stopped" && ` · ended: ${recording.ended}`}
          </p>
        </div>
        <span className="spacer" />
        <a className="button primary" href={`${base}.zip`} download>
          <Icon.Download /> Download
        </a>
        <a className="button" href={`${base}/steps.json`} download={`${recording.id}-steps.json`} title="The actions and their video offsets, as JSON">
          steps.json
        </a>
        <button className="icon-button danger" aria-label={`Delete this ${kindName(recording).toLowerCase()}`} title="Delete" onClick={onDelete}>
          <Icon.Trash />
        </button>
      </header>
      <div className="viewer-body">
        <div className="viewer-screen">
          <video
            ref={video}
            className="viewer-video"
            src={`${base}/${file}`}
            preload="auto"
            aria-label="Recorded screen (click to play or pause)"
            onClick={toggle}
            onPlay={() => setPlaying(true)}
            onPause={() => setPlaying(false)}
            onLoadedMetadata={(event) => {
              const seek = pendingSeek.current;
              pendingSeek.current = undefined;
              if (!seek) return;
              event.currentTarget.currentTime = seek.seconds;
              if (seek.play) void event.currentTarget.play().catch(() => undefined);
            }}
            onTimeUpdate={(event) => setTime(event.currentTarget.currentTime)}
            onEnded={() => {
              if (part + 1 < parts.length) go(part + 1, 0, true);
            }}
          />
          <div className="transport">
            <button className="icon-button" aria-label={playing ? "Pause" : "Play"} title={playing ? "Pause (space)" : "Play (space)"} onClick={toggle} disabled={!loaded}>
              {playing ? <Icon.Pause /> : <Icon.Play />}
            </button>
            <span className="time">
              {clockTime(position)} / {clockTime(total)}
            </span>
            <RecordingTrack total={total} position={position} parts={parts} steps={steps ?? []} current={current} onSeek={(seconds) => seek(seconds)} onStep={step} />
          </div>
        </div>
        <ol ref={list} className="rows viewer-steps" aria-label="Actions in this recording">
          {!loaded && !stepsError && <li className="empty">Loading…</li>}
          {stepsError && <li className="empty bad">{stepsError}</li>}
          {steps?.length === 0 && <li className="empty">No actions ran during this {kindName(recording).toLowerCase()}.</li>}
          {steps?.map((entry, index) => (
            <li key={index}>
              <button className={`row${entry.failed ? " failed" : ""}${index === current ? " selected" : ""}`} aria-current={index === current} onClick={() => step(index)}>
                <span className="row-time">{clockTime(entry.offset)}</span>
                <span className="row-main">
                  <span className="row-line" title={entry.summary}>
                    {entry.summary}
                  </span>
                  <span className="row-meta">
                    {entry.connection}
                    {entry.partial && " · partly outside the video"}
                  </span>
                </span>
              </button>
            </li>
          ))}
        </ol>
      </div>
    </section>
  );
}

type TrackProps = {
  total: number;
  position: number;
  parts: Part[];
  steps: Step[];
  current: number;
  onSeek: (seconds: number) => void;
  onStep: (index: number) => void;
};

/** The recording (or the zoomed part of it): actions as markers, where the stream changed as
 * dashed ticks. */
function RecordingTrack({ total, position, parts, steps, current, onSeek, onStep }: TrackProps) {
  const track = useRef<HTMLDivElement>(null);
  const zoom = useZoom(track, total, position, false);
  const fraction = (seconds: number) => (total > 0 ? zoom.fraction(seconds) : 0);
  const shown = (seconds: number) => fraction(seconds) >= 0 && fraction(seconds) <= 1;
  const at = (seconds: number) => Math.min(1, Math.max(0, fraction(seconds))) * 100;
  const seekTo = (event: ReactPointerEvent) => {
    const box = track.current?.getBoundingClientRect();
    if (box) onSeek(zoom.at(Math.min(1, Math.max(0, (event.clientX - box.left) / box.width))));
  };
  return (
    <>
    <div
      ref={track}
      className={`track${zoom.zoomed ? " zoomed" : ""}`}
      role="slider"
      tabIndex={-1}
      aria-label="Position in the recording"
      aria-valuemin={0}
      aria-valuemax={Math.round(total)}
      aria-valuenow={Math.round(position)}
      aria-valuetext={`${clockTime(position)} of ${clockTime(total)}`}
      onPointerDown={(event) => {
        if ((event.target as HTMLElement).closest(".marker")) return;
        event.currentTarget.setPointerCapture(event.pointerId);
        seekTo(event);
      }}
      onPointerMove={(event) => {
        if (event.currentTarget.hasPointerCapture(event.pointerId)) seekTo(event);
      }}
    >
      <div className="rail" />
      <div className="played" style={{ width: `${at(position)}%` }} />
      {parts.slice(1).map((entry, index) => {
        if (!shown(entry.start)) return null;
        const label = streamChange(parts[index], entry);
        return <span key={entry.file} className="stream-change" style={{ left: `${at(entry.start)}%` }} data-tip={label} aria-label={label} role="img" />;
      })}
      {steps.map((entry, index) => shown(entry.offset) && (
        <button
          key={index}
          className={`marker${entry.failed ? " failed" : ""}${index === current ? " selected" : ""}`}
          style={{ left: `${at(entry.offset)}%` }}
          data-tip={entry.summary}
          aria-label={entry.summary}
          tabIndex={-1}
          onClick={() => onStep(index)}
        />
      ))}
      {shown(position) && <div className="head" style={{ left: `${at(position)}%` }} />}
      <Overview zoom={zoom} />
    </div>
    <ZoomControls zoom={zoom} />
    </>
  );
}

function streamChange(previous: Part, next: Part): string {
  const rotated = previous.width === next.height && previous.height === next.width;
  return rotated ? `Rotated to ${next.width > next.height ? "landscape" : "portrait"}` : `Video restarted (${next.width}×${next.height})`;
}

/** 0:07, 1:05, 1:02:03: a player clock. */
function clockTime(seconds: number): string {
  const total = Math.max(0, Math.floor(seconds));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = String(total % 60).padStart(2, "0");
  return h ? `${h}:${String(m).padStart(2, "0")}:${s}` : `${m}:${s}`;
}

/** Asks before deleting: what goes, how much space it frees, and that it cannot be undone. */
function ConfirmDelete({ entries, onCancel, onConfirm }: { entries: Recording[]; onCancel: () => void; onConfirm: () => void }) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const element = dialog.current;
    if (element && !element.open) element.showModal();
  }, []);
  const size = bytes(entries.reduce((sum, entry) => sum + Number(entry.bytes), 0));
  const clips = entries.filter((entry) => entry.kind === RecordingKind.CLIP).length;
  const single = entries.length === 1 ? entries[0] : undefined;
  const title = single ? `Delete this ${kindName(single).toLowerCase()}?` : `Delete ${entries.length} saved videos?`;
  const detail = single
    ? `${single.serial}, ${dateTime(single.createdEpochMs)}, ${size}.`
    : `${[entries.length - clips && plural(entries.length - clips, "recording"), clips && plural(clips, "clip")].filter(Boolean).join(" and ")}, ${size}.`;
  return (
    <dialog ref={dialog} className="dialog" aria-labelledby="confirm-delete-title" onClose={onCancel}>
      <h2 id="confirm-delete-title">{title}</h2>
      <p>
        {detail} The video files and their steps are removed from disk; this cannot be undone.
      </p>
      <div className="dialog-actions">
        <button className="button" autoFocus onClick={() => dialog.current?.close()}>
          Cancel
        </button>
        <button className="button danger" onClick={onConfirm}>
          <Icon.Trash /> Delete
        </button>
      </div>
    </dialog>
  );
}
