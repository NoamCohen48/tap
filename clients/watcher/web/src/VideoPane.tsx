import { useEffect, useRef, useState } from 'react';
import './video.css';
import { ActionFrames } from './ActionFrames';
import type { Client } from '@connectrpc/connect';
import { Code, ConnectError } from '@connectrpc/connect';
import { toJson } from '@bufbuild/protobuf';
import { WatcherService } from './gen/watcher_pb';
import { LoggedEventSchema } from './gen/event_log_pb';
import type { RecordedEvent } from './events';
import { key } from './events';
import { actionPosition, base64, CanvasPlayer, frameAt, MAX_BYTES, MAX_SECONDS, precedingKey, seconds, VideoBuffer, type VideoWindow } from './video';

type Tape = VideoWindow & { bytes: number; requestedAt: number; stoppedAt?: number };
export function VideoPane({client, serial, events, selection}: {client: Client<typeof WatcherService>; serial: string; events: RecordedEvent[]; selection?: RecordedEvent}) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const player = useRef<CanvasPlayer>(undefined);
  const buffer = useRef(new VideoBuffer());
  const tape = useRef<Tape>(undefined);
  const recording = useRef(false);
  const live = useRef(true);
  const recordedView = useRef(false);
  const position = useRef(0);
  const items = useRef(events); items.current = events;
  const [status, setStatus] = useState('');
  const [error, setError] = useState('');
  const [revision, update] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [inspect, setInspect] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [trim, setTrim] = useState<[number | undefined, number | undefined]>([undefined, undefined]);
  function windowNow() { return recordedView.current && tape.current ? tape.current : buffer.current.snapshot(); }
  function redraw() { update(value => value + 1); }
  function show(value: number) {
    const window = windowNow(); if (!window?.frames.length) return;
    const end = seconds(window.frames.at(-1)!, window.frames[0]);
    position.current = Math.max(0, Math.min(end, value)); live.current = false;
    const index = frameAt(window.frames, position.current);
    void player.current?.seek(window, index).catch(error => {
      if (!(error instanceof DOMException && error.name === 'AbortError')) setError(String(error));
    });
    redraw();
  }
  function stopRecording(message = '') {
    recording.current = false; if (tape.current) tape.current.stoppedAt = Date.now();
    recordedView.current = true; live.current = false; setPlaying(false);
    if (message) setStatus(message);
    const window = windowNow(); if (window) show(seconds(window.frames.at(-1)!, window.frames[0]));
    redraw();
  }
  useEffect(() => {
    const abort = new AbortController();
    buffer.current = new VideoBuffer(); tape.current = undefined; recording.current = false;
    recordedView.current = false; live.current = true; position.current = 0;
    setPlaying(false); setInspect(false); setTrim([undefined, undefined]); setError(''); setStatus(serial ? 'Connecting video…' : 'Choose a device');
    player.current = canvas.current ? new CanvasPlayer(canvas.current, setError) : undefined;
    let timer: ReturnType<typeof setTimeout>;
    const tick = setInterval(() => {
      if (recording.current && tape.current && Date.now() - tape.current.requestedAt >= MAX_SECONDS * 1000) stopRecording('Recording reached 2-minute limit');
      redraw();
    }, 100);
    async function watch() {
      if (!serial) return;
      try {
        for await (const response of client.watchVideo({serial}, {signal: abort.signal})) {
          if (abort.signal.aborted) return;
          if (response.update.case === 'header') {
            if (buffer.current.setHeader(response.update.value)) {
              player.current?.close();
              if (recording.current) stopRecording('Recording stopped: encoder changed');
              if (!recordedView.current) { position.current = 0; setTrim([undefined, undefined]); }
            }
          } else if (response.update.case === 'frame') {
            const frame = response.update.value;
            if (!buffer.current.append(frame)) continue;
            if (recording.current && tape.current && frame.seq > (tape.current.frames.at(-1)?.seq ?? 0n)) {
              if (tape.current.bytes + frame.data.length > MAX_BYTES || seconds(frame, tape.current.frames[0]) > MAX_SECONDS) stopRecording('Recording reached buffer limit');
              else { tape.current.frames.push(frame); tape.current.bytes += frame.data.length; }
            }
            const window = windowNow();
            if (live.current && window) {
              position.current = seconds(window.frames.at(-1)!, window.frames[0]);
              try { player.current?.live(window, frame); } catch (error) { setError(String(error)); }
            }
            setStatus('Live');
          }
        }
        if (!abort.signal.aborted) setStatus('Video stream ended');
      } catch (error) {
        if (abort.signal.aborted) return;
        setStatus(error instanceof Error ? error.message : 'Video unavailable');
        if (error instanceof ConnectError && [Code.Unimplemented, Code.InvalidArgument, Code.Unauthenticated, Code.PermissionDenied].includes(error.code)) return;
      }
      if (!abort.signal.aborted) timer = setTimeout(watch, 1500);
    }
    void watch();
    return () => { abort.abort(); clearTimeout(timer); clearInterval(tick); player.current?.close(); };
  }, [client, serial]);

  const selectionId = selection ? key(selection) : '';
  useEffect(() => {
    if (!selection) return;
    setPlaying(false); recordedView.current = Boolean(tape.current && !recording.current);
    const window = windowNow();
    const at = window ? actionPosition(window, selection.event.clockId, selection.event.startedMonotonicNs) : undefined;
    if (at === undefined) { setError('No retained video at this action boundary'); return; }
    setError(''); show(at);
  }, [selectionId]);

  useEffect(() => {
    if (!playing) return;
    live.current = false; let last = performance.now();
    const interval = setInterval(() => {
      const now = performance.now(); const next = position.current + (now - last) / 1000; last = now;
      const window = windowNow(); if (!window) return;
      const end = seconds(window.frames.at(-1)!, window.frames[0]);
      show(next); if (next >= end) setPlaying(false);
    }, 100);
    return () => clearInterval(interval);
  }, [playing]);

  function startRecording() {
    const window = buffer.current.snapshot(); if (!window) return;
    const start = precedingKey(window.frames, window.frames.length - 1);
    const frames = window.frames.slice(start);
    tape.current = {header: window.header, frames, bytes: frames.reduce((sum, frame) => sum + frame.data.length, 0), requestedAt: Date.now()};
    recording.current = true; recordedView.current = false; live.current = true;
    setTrim([undefined, undefined]); setError(''); setPlaying(false); redraw();
  }
  async function exportClip() {
    const window = windowNow(); if (!window?.frames.length) return;
    const first = frameAt(window.frames, trim[0] ?? 0);
    const start = precedingKey(window.frames, first);
    const last = frameAt(window.frames, trim[1] ?? seconds(window.frames.at(-1)!, window.frames[0]));
    if (last < start) { setError('Trim out must follow trim in'); return; }
    const frames = window.frames.slice(start, last + 1); const origin = frames[0];
    const finalNs = frames.at(-1)!.receivedMonotonicNs;
    const actions = items.current.filter(({event}) => event.clockId === window.header.clockId && event.startedMonotonicNs !== undefined &&
      (event.finishedMonotonicNs ?? event.startedMonotonicNs) >= origin.receivedMonotonicNs && event.startedMonotonicNs <= finalNs).map(item => {
        const ns = item.event.startedMonotonicNs!;
        let index = 0; while (index + 1 < frames.length && frames[index + 1].receivedMonotonicNs <= ns) index++;
        return {connectionId: item.connectionId, event: toJson(LoggedEventSchema, item.event),
          hostClipOffsetUs: ((ns - origin.receivedMonotonicNs) / 1000n).toString(),
          videoClipOffsetUs: (frames[index].ptsUs - origin.ptsUs).toString(),
          partial: ns < origin.receivedMonotonicNs || (item.event.finishedMonotonicNs ?? ns) > finalNs};
      });
    setExporting(true); setError('');
    try {
      const response = await fetch('/export', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({
        serial, header: {streamId: window.header.streamId, clockId: window.header.clockId, width: window.header.width, height: window.header.height, configuration: base64(window.header.configuration)},
        requestedTrim: {inSeconds: trim[0] ?? 0, outSeconds: trim[1]},
        actualStartSeconds: seconds(origin, window.frames[0]),
        recording: recordedView.current && tape.current ? {requestedAtEpochMs: tape.current.requestedAt, stoppedAtEpochMs: tape.current.stoppedAt} : null,
        gapsObserved: buffer.current.gaps, evictedFrames: buffer.current.evicted,
        retention: {maxSeconds: MAX_SECONDS, maxEncodedBytes: MAX_BYTES, policy: 'whole GOP eviction; static display may emit no new frames'},
        actions, frames: frames.map(frame => ({seq: frame.seq.toString(), ptsUs: frame.ptsUs.toString(), keyFrame: frame.keyFrame, data: base64(frame.data), receivedMonotonicNs: frame.receivedMonotonicNs.toString(), receivedEpochMs: frame.receivedEpochMs.toString()})),
      })});
      if (!response.ok) throw new Error(await response.text());
      const url = URL.createObjectURL(await response.blob());
      const link = document.createElement('a'); link.href = url; link.download = `tap-${serial.replace(/[^a-z0-9_-]/gi, '_')}-clip.zip`; link.click();
      setTimeout(() => URL.revokeObjectURL(url), 30_000);
    } catch (error) { setError(error instanceof Error ? error.message : 'Export failed'); }
    finally { setExporting(false); }
  }
  const window = windowNow();
  const end = window ? seconds(window.frames.at(-1)!, window.frames[0]) : 0;
  const available = Boolean(window?.frames.length);
  const playhead = Math.min(end, position.current);
  const markers = window ? events.map(item => ({item, at: actionPosition(window, item.event.clockId, item.event.startedMonotonicNs)})).filter(marker => marker.at !== undefined) : [];
  void revision;
  return <>
    <div className="video-tools"><button onClick={recording.current ? () => stopRecording() : startRecording} disabled={!buffer.current.frames.length}>{recording.current ? 'Stop recording' : 'New recording'}</button>
      <button onClick={() => void exportClip()} disabled={!available || recording.current || exporting}>{exporting ? 'Exporting…' : 'Export clip'}</button>
      {tape.current && !recording.current && <button onClick={() => {recordedView.current = true; setPlaying(false); setTrim([undefined, undefined]); show(0);}}>Recording</button>}
      <button aria-pressed={inspect} disabled={!available} onClick={() => setInspect(value => !value)}>Action frames</button>
      <span className="status" role="status">{recording.current ? `● Recording · ${Math.floor((Date.now() - tape.current!.requestedAt) / 1000)} s` : status}</span></div>
    <div className="stage video-stage"><canvas ref={canvas} aria-label="Passive device video" hidden={!available || inspect}/>{inspect && <ActionFrames window={window} selection={selection}/>}{!available && <div className="unavailable"><h2>{status || 'Waiting for video'}</h2></div>}</div>
    <div className="playback"><div className="playback-row"><button disabled={!available} onClick={() => {live.current = false; setPlaying(value => !value);}}>{playing ? 'Pause' : 'Play'}</button>
      <span className="mono">{playhead.toFixed(1)} / {end.toFixed(1)} s</span>
      <button aria-pressed={live.current} disabled={!buffer.current.frames.length} onClick={() => {recordedView.current = false; live.current = true; setPlaying(false); setTrim([undefined, undefined]); setError(''); const next = buffer.current.snapshot(); if (next) {player.current?.close(); player.current?.live(next, next.frames.at(-1)!); position.current = seconds(next.frames.at(-1)!, next.frames[0]);} redraw();}}>Live</button></div>
      <input aria-label="Video position" type="range" min="0" max={Math.max(end, 0.001)} step="0.01" value={playhead} disabled={!available} onChange={event => {setPlaying(false); show(Number(event.target.value));}}/>
      <div className="markers" aria-label="Action video markers">{markers.map(({item, at}) => <button key={key(item)} style={{left: `${end ? at! / end * 100 : 0}%`}} aria-label={`Seek action ${item.event.seq}`} title={`Action #${item.event.seq}`} onClick={() => {setPlaying(false); show(at!);}}/>)}</div>
      <div className="playback-row"><button disabled={!available} onClick={() => setTrim([playhead, trim[1]])}>Set in</button><button disabled={!available} onClick={() => setTrim([trim[0], playhead])}>Set out</button><span className="mono">{trim[0]?.toFixed(1) ?? '0.0'} — {trim[1]?.toFixed(1) ?? end.toFixed(1)} s</span><button onClick={() => setTrim([undefined, undefined])}>Reset trim</button></div>
      {available && <p className="video-note">Approximate frame correlation · cuts include keyframe pre-roll{buffer.current.gaps ? ` · ${buffer.current.gaps} gaps` : ''}{buffer.current.evicted ? ' · earlier frames expired' : ''}</p>}
      {error && <p className="error" role="alert">{error}</p>}
    </div>
  </>;
}
