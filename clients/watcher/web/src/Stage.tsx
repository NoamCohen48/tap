// The device's screen: live, paused or playing back the retained video, with a Before / After
// pair for the selected action, a transport bar, clips and recordings.

import { useCallback, useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from "react";
import type { Action } from "./activity";
import { api, errorText } from "./api";
import { bytes, clock, duration } from "./format";
import type { ActiveRecording, Recording } from "./gen/watcher_pb";
import { useVideo } from "./hooks";
import * as Icon from "./icons";
import { FrameBuffer, Player, type Limits } from "./video";
import { Overview, useZoom, ZoomControls } from "./zoom";

type Mode = { kind: "live" } | { kind: "paused"; index: number } | { kind: "playing"; index: number };

/** How long after an action ends its "After" frame may be. */
const AFTER_NS = 1_000_000_000n;
const CLIP_DEFAULT_NS = 10_000_000_000n;

type Props = {
  serial: string;
  limits?: Limits;
  actions: Action[];
  selected?: Action;
  onSelect: (key: string | undefined) => void;
  recording?: ActiveRecording;
  onRecordingChanged: () => void;
  onSaved: (recording: Recording) => void;
  notify: (message: string, failed?: boolean) => void;
};

export function Stage({ serial, limits, actions, selected, onSelect, recording, onRecordingChanged, onSaved, notify }: Props) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const beforeCanvas = useRef<HTMLCanvasElement>(null);
  const afterCanvas = useRef<HTMLCanvasElement>(null);
  const player = useRef<Player>(undefined);
  const beforePlayer = useRef<Player>(undefined);
  const afterPlayer = useRef<Player>(undefined);
  const [mode, setModeState] = useState<Mode>({ kind: "live" });
  const modeRef = useRef(mode);
  const [clip, setClip] = useState<{ from: number; to: number }>();
  // Compare: the selected action's Before and After frames side by side, each a fixed frame
  // with its own canvas. Moving through the video (scrub, play, step, live) leaves it.
  const [comparing, setComparing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [decodeError, setDecodeError] = useState("");

  const setMode = useCallback((next: Mode) => {
    modeRef.current = next;
    setModeState(next);
  }, []);

  // Positions are absolute indices, so a new stream (a rotation) keeps a pause, a replay and a
  // clip where they are; only the live player follows it.
  const feed = useVideo(serial, limits, (buffer) => {
    if (modeRef.current.kind === "live") {
      try {
        player.current?.live(buffer);
      } catch (error) {
        setDecodeError(errorText(error));
      }
    }
  });
  const buffer = feed.buffer;

  useEffect(() => {
    if (!canvas.current || !beforeCanvas.current || !afterCanvas.current) return;
    player.current = new Player(canvas.current, setDecodeError);
    beforePlayer.current = new Player(beforeCanvas.current, setDecodeError);
    afterPlayer.current = new Player(afterCanvas.current, setDecodeError);
    return () => {
      player.current?.stop();
      beforePlayer.current?.stop();
      afterPlayer.current?.stop();
    };
  }, []);

  // A new device starts live with a blank screen.
  useEffect(() => {
    setMode({ kind: "live" });
    setClip(undefined);
    setDecodeError("");
    for (const element of [canvas.current, beforeCanvas.current, afterCanvas.current]) element?.getContext("2d")?.clearRect(0, 0, element.width, element.height);
  }, [serial, setMode]);

  // Seeks are coalesced: a drag asks for many, the decoder shows the latest.
  const seeking = useRef<{ running: boolean; pending?: number }>({ running: false });
  const seek = useCallback(
    async (index: number) => {
      const state = seeking.current;
      state.pending = index;
      if (state.running) return;
      state.running = true;
      try {
        while (state.pending !== undefined) {
          const target = state.pending;
          state.pending = undefined;
          await player.current?.seek(buffer, Math.max(buffer.first, Math.min(target, buffer.end - 1)));
        }
      } catch (error) {
        setDecodeError(errorText(error));
      } finally {
        state.running = false;
      }
    },
    [buffer],
  );

  const pause = useCallback(
    (index: number) => {
      if (buffer.empty) return;
      const clamped = Math.max(buffer.first, Math.min(index, buffer.end - 1));
      setMode({ kind: "paused", index: clamped });
      void seek(clamped);
    },
    [buffer, seek, setMode],
  );

  const goLive = useCallback(() => {
    player.current?.stop();
    setMode({ kind: "live" });
    try {
      player.current?.live(buffer);
    } catch (error) {
      setDecodeError(errorText(error));
    }
  }, [buffer, setMode]);

  const lastTick = useRef(0);
  const play = useCallback(
    (from: number) => {
      if (buffer.empty) return;
      const start = from >= buffer.end - 1 ? buffer.first : Math.max(from, buffer.first);
      setMode({ kind: "playing", index: start });
      try {
        player.current?.play(
          buffer,
          start,
          (mediaUs) => {
            const now = performance.now();
            if (now - lastTick.current < 250) return;
            lastTick.current = now;
            setMode({ kind: "playing", index: buffer.indexAtMedia(mediaUs) });
          },
          goLive,
        );
      } catch (error) {
        setDecodeError(errorText(error));
      }
    },
    [buffer, goLive, setMode],
  );

  const position = mode.kind === "live" ? buffer.end - 1 : Math.max(buffer.first, Math.min(mode.index, buffer.end - 1));
  const current = buffer.empty ? undefined : buffer.at(position);

  // The selected action's Before / After frames, when the video still has them.
  const compare = useMemo(() => {
    const event = selected?.event;
    if (!event || event.startedMonotonicNs === undefined) return undefined;
    if (!buffer.covers(event.clockId, event.startedMonotonicNs)) return undefined;
    const finished = event.finishedMonotonicNs ?? event.startedMonotonicNs;
    const next = actions.find((action) => action.event.clockId === event.clockId && (action.event.startedMonotonicNs ?? 0n) > finished)?.event.startedMonotonicNs;
    let afterNs = finished + AFTER_NS;
    if (next !== undefined && next < afterNs) afterNs = next;
    const before = buffer.indexAtTimeline(buffer.timelineOf(event.clockId, event.startedMonotonicNs)!);
    const after = Math.max(before, buffer.indexAtTimeline(buffer.timelineOf(event.clockId, afterNs)!));
    return { before, after, afterMs: Number(buffer.timeline(after) - buffer.timelineOf(event.clockId, finished)!) / 1e6 };
    // feed.version: the buffer changed under the same objects.
  }, [selected, actions, buffer, feed.version]);
  const compareKey = compare && selected ? `${selected.key}:${compare.before}:${compare.after}` : "";

  // Selecting an action moves the view to its start, once per selection (frames arriving
  // later must not pull the user back after they moved on).
  const jumped = useRef<string>(undefined);
  useEffect(() => {
    if (!selected) jumped.current = undefined;
    if (!compare || !selected || jumped.current === selected.key) return;
    jumped.current = selected.key;
    pause(compare.before);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [compareKey]);

  const showCompare = comparing && compare !== undefined;
  useEffect(() => {
    if (!comparing || !compare) {
      beforePlayer.current?.stop();
      afterPlayer.current?.stop();
      return;
    }
    const fail = (error: unknown) => setDecodeError(errorText(error));
    void beforePlayer.current?.seek(buffer, compare.before).catch(fail);
    void afterPlayer.current?.seek(buffer, compare.after).catch(fail);
    // Only when the pair (or compare) changes, not on every new frame.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [compareKey, comparing]);

  /** Moving through the video shows it, not the comparison. */
  const browse = () => setComparing(false);

  // Keyboard: space, ←/→, L, Esc (clip mode). j/k and Esc for the selection live in the timeline.
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.target instanceof HTMLElement && event.target.closest("input, textarea, select")) return;
      if (event.metaKey || event.ctrlKey || event.altKey || buffer.empty) return;
      const current = modeRef.current;
      const at = current.kind === "live" ? buffer.end - 1 : current.index;
      if (event.key === "c" || event.key === "C") {
        setComparing((on) => !on);
        return;
      }
      if (event.key === " ") {
        if (event.target instanceof HTMLElement && event.target.closest("button, summary, a")) return;
        event.preventDefault();
        setComparing(false);
        if (current.kind === "playing") pause(at);
        else if (current.kind === "paused") play(at);
        else pause(at);
      } else if (event.key === "ArrowLeft" || event.key === "ArrowRight") {
        event.preventDefault();
        setComparing(false);
        pause(at + (event.key === "ArrowLeft" ? -1 : 1) * (event.shiftKey ? 15 : 1));
      } else if (event.key === "l" || event.key === "L") {
        setComparing(false);
        goLive();
      } else if (event.key === "Escape" && clip) {
        setClip(undefined);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [buffer, clip, goLive, pause, play]);

  const startClip = () => {
    if (buffer.empty) return;
    setComparing(false);
    const to = position;
    const from = buffer.indexAtTimeline(buffer.timeline(to) - CLIP_DEFAULT_NS);
    setClip({ from, to });
    if (mode.kind !== "paused") pause(to);
  };

  const saveClip = async () => {
    if (!clip || buffer.empty) return;
    const from = Math.max(clip.from, buffer.first);
    const to = Math.min(clip.to, buffer.end - 1);
    if (from > to) return notify("The clip's frames are no longer retained", true);
    setBusy(true);
    try {
      const response = await api.saveClip({
        serial,
        fromStreamId: buffer.headerAt(from).streamId,
        fromSeq: buffer.at(from).seq,
        toStreamId: buffer.headerAt(to).streamId,
        toSeq: buffer.at(to).seq,
      });
      setClip(undefined);
      if (response.recording) onSaved(response.recording);
    } catch (error) {
      notify(`Could not save the clip: ${errorText(error)}`, true);
    } finally {
      setBusy(false);
    }
  };

  const toggleRecording = async () => {
    setBusy(true);
    try {
      if (recording) {
        const response = await api.stopRecording({ serial });
        if (response.recording) onSaved(response.recording);
      } else {
        await api.startRecording({ serial });
      }
      onRecordingChanged();
    } catch (error) {
      notify(errorText(error), true);
      onRecordingChanged();
    } finally {
      setBusy(false);
    }
  };

  const empty = buffer.empty;
  const behindS = current ? Number(buffer.timeline(buffer.end - 1) - buffer.timeline(position)) / 1e9 : 0;
  const placeholder = !serial
    ? "Pick a device to watch its screen."
    : feed.state === "unavailable"
      ? `No video: ${feed.error}`
      : feed.state === "connecting"
        ? "Connecting to the device's screen…"
        : empty
          ? "Waiting for the first frame…"
          : "";

  return (
    <section className="stage" aria-label="Device screen">
      <div className={`screens${showCompare ? " comparing" : ""}`}>
        <figure className="screen" hidden={showCompare}>
          <canvas ref={canvas} hidden={empty} aria-label="Device screen" />
        </figure>
        <figure className="screen" hidden={!showCompare}>
          <canvas ref={beforeCanvas} aria-label="Screen before the action" />
          <figcaption>Before{compare && ` · ${clock(buffer.at(compare.before).receivedEpochMs)}`}</figcaption>
        </figure>
        <figure className="screen" hidden={!showCompare}>
          <canvas ref={afterCanvas} aria-label="Screen after the action" />
          <figcaption>After{compare && ` · +${duration(Math.max(0, compare.afterMs) / 1000)}`}</figcaption>
        </figure>
        {placeholder && <p className="placeholder">{placeholder}</p>}
        {!empty && (
          <div className="badges">
            {mode.kind === "live" ? (
              <span className="badge live">
                <span className="dot" /> Live
              </span>
            ) : (
              <button
                className="badge paused"
                onClick={() => {
                  browse();
                  goLive();
                }}
                title="Go live (L)"
              >
                {mode.kind === "playing" ? "Replay" : "Paused"} −{duration(behindS)} · Go live
              </button>
            )}
            {recording && (
              <span className="badge rec" role="status">
                <span className="dot" /> REC {duration(recording.seconds)} · {bytes(recording.bytes)}
              </span>
            )}
          </div>
        )}
        {comparing && !selected && serial && !empty && (
          <p className="note stage-note">Select an action (click it, or press j / k) to compare the screen before and after it.</p>
        )}
        {selected && !compare && serial && (
          <p className="note stage-note">
            {pending(buffer, selected)
              ? "Waiting for the video of this action…"
              : `The video of this action is not retained (the watcher keeps the last ${limits?.maxSeconds ?? 120} s while a page watches the device).`}
          </p>
        )}
        {decodeError && (
          <p className="note stage-error" role="alert">
            <Icon.Alert /> {decodeError}
          </p>
        )}
      </div>
      {showCompare && selected && (
        <p className="note compare-note">
          <span className="compare-what">{selected.summary}</span>
          <span title="Frames are matched to the daemon's send and reply times, so they are approximate.">
            <Icon.Info />
          </span>
          <button className="link" onClick={browse}>
            Close
          </button>
        </p>
      )}
      <div className="transport stacked">
        <button
          className="icon-button"
          disabled={empty}
          aria-label={mode.kind === "playing" ? "Pause (space)" : "Play (space)"}
          title={mode.kind === "playing" ? "Pause (space)" : "Play (space)"}
          onClick={() => {
            browse();
            if (mode.kind === "paused") play(position);
            else pause(position);
          }}
        >
          {mode.kind === "playing" ? <Icon.Pause /> : mode.kind === "paused" ? <Icon.Play /> : <Icon.Pause />}
        </button>
        <span className="time" aria-live="off">
          {current ? clock(current.receivedEpochMs) : "--:--:--"}
        </span>
        <Track
          buffer={buffer}
          version={feed.version}
          live={mode.kind === "live"}
          position={position}
          actions={actions}
          selected={selected}
          clip={clip}
          onSeek={(index) => {
            browse();
            pause(index);
          }}
          onSelect={onSelect}
        />
        <button
          className="button"
          disabled={empty || mode.kind === "live"}
          onClick={() => {
            browse();
            goLive();
          }}
          title="Go live (L)"
          aria-label="Live"
        >
          <Icon.Live /> <span className="label">Live</span>
        </button>
        <button
          className={`button${comparing ? " on" : ""}`}
          disabled={empty}
          aria-pressed={comparing}
          onClick={() => setComparing((on) => !on)}
          title="Compare the screen before and after the selected action (C)"
          aria-label="Compare"
        >
          <Icon.Compare /> <span className="label">Compare</span>
        </button>
        <button className={`button${clip ? " on" : ""}`} disabled={empty} aria-pressed={Boolean(clip)} onClick={() => (clip ? setClip(undefined) : startClip())} title="Save part of the retained video" aria-label="Clip">
          <Icon.Scissors /> <span className="label">Clip</span>
        </button>
        <button className={`button${clip ? "" : " primary"}${recording ? " primary recording" : ""}`} disabled={!serial || busy || (feed.state !== "streaming" && !recording)}
          onClick={toggleRecording}
          title={recording ? "Stop and save the recording" : "Record the device until you stop"}
          aria-label={recording ? "Stop recording" : "Record"}
        >
          {recording ? <Icon.Stop /> : <Icon.Record />} <span className="label">{recording ? "Stop" : "Record"}</span>
        </button>
      </div>
      {clip && !buffer.empty && (
        <div className="clip-bar" role="group" aria-label="Clip">
          <span>
            From <strong>{clock(buffer.at(Math.max(clip.from, buffer.first)).receivedEpochMs)}</strong> to{" "}
            <strong>{clock(buffer.at(Math.min(clip.to, buffer.end - 1)).receivedEpochMs)}</strong> ·{" "}
            {duration(Math.max(0, Number(buffer.timeline(Math.min(clip.to, buffer.end - 1)) - buffer.timeline(Math.max(clip.from, buffer.first))) / 1e9))}
          </span>
          <button className="button" onClick={() => setClip({ from: Math.min(position, clip.to), to: clip.to })}>
            Set start here
          </button>
          <button className="button" onClick={() => setClip({ from: clip.from, to: Math.max(position, clip.from) })}>
            Set end here
          </button>
          <span className="spacer" />
          <button className="button" onClick={() => setClip(undefined)}>
            Cancel
          </button>
          <button className="button primary" disabled={busy} onClick={saveClip}>
            Save clip
          </button>
        </div>
      )}
    </section>
  );
}

/** Whether [action] is newer than the newest frame (its video has not arrived yet). */
function pending(buffer: FrameBuffer, action: Action): boolean {
  const newest = buffer.newest();
  const started = action.event.startedMonotonicNs;
  return Boolean(newest && started !== undefined && buffer.headerAt(buffer.end - 1).clockId === action.event.clockId && started > newest.receivedMonotonicNs);
}

type TrackProps = {
  buffer: FrameBuffer;
  version: number;
  // Live: the track spans the whole history and grows with it. Otherwise its scale is frozen
  // where live was left, so the head and markers stay where the user put them.
  live: boolean;
  position: number;
  actions: Action[];
  selected?: Action;
  clip?: { from: number; to: number };
  onSeek: (index: number) => void;
  onSelect: (key: string) => void;
};

/** The scrubber: time-proportional (a still screen sends no frames), with action markers and a
 * tick where the stream changed (a rotation or a capture restart). */
function Track({ buffer, version, live, position, actions, selected, clip, onSeek, onSelect }: TrackProps) {
  const track = useRef<HTMLDivElement>(null);
  const empty = buffer.empty;
  const frozen = useRef<{ startNs: bigint; endNs: bigint }>(undefined);
  if (live || empty) frozen.current = undefined;
  else {
    const headNs = buffer.timeline(Math.max(buffer.first, Math.min(position, buffer.end - 1)));
    // A replay that runs past the frozen end stretches it.
    if (frozen.current) frozen.current.endNs = headNs > frozen.current.endNs ? headNs : frozen.current.endNs;
    else frozen.current = { startNs: buffer.timeline(buffer.first), endNs: buffer.timeline(buffer.end - 1) };
  }
  const startNs = empty ? 0n : (frozen.current?.startNs ?? buffer.timeline(buffer.first));
  const endNs = empty ? 0n : (frozen.current?.endNs ?? buffer.timeline(buffer.end - 1));
  const span = Number(endNs - startNs);
  const secondsOf = (ns: bigint) => Number(ns - startNs) / 1e9;
  const clampIndex = (index: number) => Math.max(buffer.first, Math.min(index, buffer.end - 1));
  // While live, a zoomed window keeps to the live end; paused, it stays where it is.
  const zoom = useZoom(track, span / 1e9, empty ? 0 : secondsOf(buffer.timeline(clampIndex(position))), live);
  // Fractions of the visible window: outside [0, 1] is off screen.
  const fractionAt = (ns: bigint) => zoom.fraction(secondsOf(ns));
  const at = (index: number) => (empty ? 0 : fractionAt(buffer.timeline(clampIndex(index))));
  const shown = (fraction: number) => fraction >= 0 && fraction <= 1;
  const clamp = (fraction: number) => Math.min(1, Math.max(0, fraction));

  // version: the retained range moved.
  const markers = useMemo(
    () =>
      actions.filter((action) => {
        if (!buffer.covers(action.event.clockId, action.event.startedMonotonicNs)) return false;
        const ns = buffer.timelineOf(action.event.clockId, action.event.startedMonotonicNs)!;
        return ns >= startNs && ns <= endNs;
      }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [actions, buffer, version, startNs, endNs],
  );
  const changes = useMemo(
    () =>
      buffer.segments.flatMap((segment, index) => {
        if (index === 0 || segment.start < buffer.first || buffer.timeline(segment.start) > endNs) return [];
        const previous = buffer.segments[index - 1].header;
        const { width, height } = segment.header;
        const rotated = previous.width === height && previous.height === width;
        return [{ index: segment.start, label: rotated ? `Rotated to ${width > height ? "landscape" : "portrait"}` : `Video restarted (${width}×${height})` }];
      }),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [buffer, version, endNs],
  );

  const seekTo = (event: ReactPointerEvent) => {
    const box = track.current?.getBoundingClientRect();
    if (!box || empty) return;
    const ratio = Math.min(1, Math.max(0, (event.clientX - box.left) / box.width));
    onSeek(buffer.indexAtTimeline(startNs + BigInt(Math.round(zoom.at(ratio) * 1e9))));
  };

  const secondsBack = empty ? 0 : Number(buffer.timeline(buffer.end - 1) - buffer.timeline(Math.min(position, buffer.end - 1))) / 1e9;
  const railStart = clamp(at(buffer.first));
  return (
    <>
    <div
      ref={track}
      className={`track${zoom.zoomed ? " zoomed" : ""}`}
      role="slider"
      tabIndex={-1}
      aria-label="Position in the retained video"
      aria-valuemin={0}
      aria-valuemax={Math.round(span / 1e9)}
      aria-valuenow={empty ? 0 : Math.round(Number(buffer.timeline(Math.max(buffer.first, Math.min(position, buffer.end - 1))) - startNs) / 1e9)}
      aria-valuetext={secondsBack < 0.05 ? "Live" : `${duration(secondsBack)} behind live`}
      onPointerDown={(event) => {
        if ((event.target as HTMLElement).closest(".marker")) return;
        event.currentTarget.setPointerCapture(event.pointerId);
        seekTo(event);
      }}
      onPointerMove={(event) => {
        if (event.currentTarget.hasPointerCapture(event.pointerId)) seekTo(event);
      }}
    >
      {/* While frozen, history evicted from the start leaves the left of the rail empty. */}
      <div className="rail" style={{ left: `${railStart * 100}%` }} />
      {clip && <div className="clip-range" style={{ left: `${clamp(at(clip.from)) * 100}%`, width: `${Math.max(0, clamp(at(clip.to)) - clamp(at(clip.from))) * 100}%` }} />}
      <div className="played" style={{ left: `${railStart * 100}%`, width: `${Math.max(0, clamp(at(position)) - railStart) * 100}%` }} />
      {changes.filter((change) => shown(at(change.index))).map((change) => (
        <span key={change.index} className="stream-change" style={{ left: `${at(change.index) * 100}%` }} data-tip={change.label} aria-label={change.label} role="img" />
      ))}
      {markers.filter((action) => shown(fractionAt(buffer.timelineOf(action.event.clockId, action.event.startedMonotonicNs)!))).map((action) => (
        <button
          key={action.key}
          className={`marker${action.failed ? " failed" : ""}${action.key === selected?.key ? " selected" : ""}`}
          style={{ left: `${fractionAt(buffer.timelineOf(action.event.clockId, action.event.startedMonotonicNs)!) * 100}%` }}
          data-tip={action.summary}
          aria-label={action.summary}
          onClick={() => onSelect(action.key)}
        />
      ))}
      {shown(at(position)) && <div className="head" style={{ left: `${at(position) * 100}%` }} />}
      <Overview zoom={zoom} />
    </div>
    <ZoomControls zoom={zoom} />
    </>
  );
}
