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
type Saved = {id: string; serial: string; createdEpochMs: number; durationSeconds: number; path: string};
export function VideoPane({client, serial, events, selection}: {client: Client<typeof WatcherService>; serial: string; events: RecordedEvent[]; selection?: RecordedEvent}) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const player = useRef<CanvasPlayer>(undefined);
  const buffer = useRef(new VideoBuffer());
  const tape = useRef<Tape>(undefined);
  const recording = useRef(false);
  const live = useRef(true);
  const recordedView = useRef(false);
  const position = useRef(0);
  const frozen = useRef<VideoWindow>(undefined);
  const items = useRef(events); items.current = events;
  const [status, setStatus] = useState('');
  const [error, setError] = useState('');
  const [revision, update] = useState(0);
  const [playing, setPlaying] = useState(false);
  const [inspect, setInspect] = useState(false);
  const [exporting, setExporting] = useState(false);
  const [saveFailed, setSaveFailed] = useState(false);
  const [saved, setSaved] = useState<Saved[]>([]);
  const [savedDirectory, setSavedDirectory] = useState('');
  const [savedSelection, setSavedSelection] = useState('');
  const [libraryRevision, refreshLibrary] = useState(0);
  const [trim, setTrim] = useState<[number | undefined, number | undefined]>([undefined, undefined]);
  function windowNow() { return recordedView.current && tape.current ? tape.current : !live.current && frozen.current ? frozen.current : buffer.current.snapshot(); }
  useEffect(() => {
    const abort = new AbortController();
    void fetch('/recordings', {signal: abort.signal}).then(async response => {
      if (!response.ok) throw new Error(await response.text());
      const library = await response.json();
      setSaved(library.recordings); setSavedDirectory(library.directory);
    }).catch(error => {if (!abort.signal.aborted) setError(String(error));});
    return () => abort.abort();
  }, [libraryRevision]);
  function redraw() { update(value => value + 1); }
  function pauseLive() { if (live.current && !recordedView.current) frozen.current = buffer.current.snapshot(); live.current = false; }
  function show(value: number) {
    const window = windowNow(); if (!window?.frames.length) return;
    if (live.current && !recordedView.current) frozen.current = window;
    const end = seconds(window.frames.at(-1)!, window.frames[0]);
    position.current = Math.max(0, Math.min(end, value)); live.current = false;
    setError('');
    const index = frameAt(window.frames, position.current);
    void player.current?.seek(window, index).catch(error => {
      if (!(error instanceof DOMException && error.name === 'AbortError')) setError(String(error));
    });
    redraw();
  }
  function stopRecording(message = '') {
    if (!recording.current || !tape.current) return;
    recording.current = false; tape.current.stoppedAt = Date.now();
    const stopped = {...tape.current, frames: [...tape.current.frames]};
    void exportClip(true, stopped);
    recordedView.current = true; live.current = false; setPlaying(false);
    if (message) setStatus(message);
    const window = windowNow(); if (window) show(seconds(window.frames.at(-1)!, window.frames[0]));
    redraw();
  }
  useEffect(() => {
    const abort = new AbortController();
    buffer.current = new VideoBuffer(); tape.current = undefined; recording.current = false;
    recordedView.current = false; live.current = true; position.current = 0; frozen.current = undefined;
    setSavedSelection(''); setSaveFailed(false);
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
    pauseLive(); let last = performance.now();
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
    recording.current = true; recordedView.current = false; live.current = true; frozen.current = undefined;
    setSavedSelection('');
    setTrim([undefined, undefined]); setError(''); setPlaying(false); redraw();
  }
  async function exportClip(save = false, stopped?: Tape) {
    const window = stopped ?? windowNow(); if (!window?.frames.length) return;
    const first = frameAt(window.frames, save ? 0 : trim[0] ?? 0);
    const start = precedingKey(window.frames, first);
    const last = frameAt(window.frames, (!save && trim[1] !== undefined) ? trim[1] : seconds(window.frames.at(-1)!, window.frames[0]));
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
      const response = await fetch(save ? '/recordings' : '/export', {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify({
        serial, header: {streamId: window.header.streamId, clockId: window.header.clockId, width: window.header.width, height: window.header.height, configuration: base64(window.header.configuration)},
        requestedTrim: {inSeconds: save ? 0 : trim[0] ?? 0, outSeconds: save ? undefined : trim[1]},
        actualStartSeconds: seconds(origin, window.frames[0]),
        recording: stopped ? {requestedAtEpochMs: stopped.requestedAt, stoppedAtEpochMs: stopped.stoppedAt} : recordedView.current && tape.current ? {requestedAtEpochMs: tape.current.requestedAt, stoppedAtEpochMs: tape.current.stoppedAt} : null,
        gapsObserved: buffer.current.gaps, evictedFrames: buffer.current.evicted,
        retention: {maxSeconds: MAX_SECONDS, maxEncodedBytes: MAX_BYTES, policy: 'whole GOP eviction; static display may emit no new frames'},
        actions, frames: frames.map(frame => ({seq: frame.seq.toString(), ptsUs: frame.ptsUs.toString(), keyFrame: frame.keyFrame, data: base64(frame.data), receivedMonotonicNs: frame.receivedMonotonicNs.toString(), receivedEpochMs: frame.receivedEpochMs.toString()})),
      })});
      if (!response.ok) throw new Error(await response.text());
      if (save) {
        setSaveFailed(false);
        const result: Saved = await response.json();
        setStatus(`Saved · ${result.path}`); refreshLibrary(value => value + 1);
        return;
      }
      const url = URL.createObjectURL(await response.blob());
      const link = document.createElement('a'); link.href = url; link.download = `tap-${serial.replace(/[^a-z0-9_-]/gi, '_')}-clip.zip`; link.click();
      setTimeout(() => URL.revokeObjectURL(url), 30_000);
    } catch (error) { if (save) setSaveFailed(true); setError(error instanceof Error ? error.message : 'Export failed'); }
    finally { setExporting(false); }
  }
  const window = windowNow();
  const end = window ? seconds(window.frames.at(-1)!, window.frames[0]) : 0;
  const available = Boolean(window?.frames.length);
  const playhead = Math.min(end, position.current);
  const markers = window ? events.map(item => ({item, at: actionPosition(window, item.event.clockId, item.event.startedMonotonicNs)})).filter(marker => marker.at !== undefined) : [];
  void revision;
  return <>
    <div className="video-tools"><button onClick={recording.current ? () => stopRecording() : startRecording} disabled={!buffer.current.frames.length || exporting || saveFailed}>{recording.current ? 'Stop & save' : tape.current ? 'Resume recording' : 'Start recording'}</button>
      {saveFailed && <button disabled={exporting} onClick={() => void exportClip(true, tape.current)}>Retry save</button>}
      <button onClick={() => void exportClip()} disabled={!available || recording.current || exporting || Boolean(savedSelection)}>{exporting ? 'Exporting…' : 'Export clip'}</button>
      {tape.current && !recording.current && <button onClick={() => {setSavedSelection(''); recordedView.current = true; setPlaying(false); setTrim([undefined, undefined]); show(0);}}>Recording</button>}
      <button aria-pressed={inspect} disabled={!available || Boolean(savedSelection)} onClick={() => setInspect(value => !value)}>Action frames</button>
      <span className="status" role="status">{recording.current ? `● Recording · ${Math.floor((Date.now() - tape.current!.requestedAt) / 1000)} s` : status}</span></div>
    <div className="stage video-stage" style={savedSelection ? {display: 'none'} : undefined}><canvas ref={canvas} aria-label="Passive device video" hidden={!available || inspect}/>{inspect && <ActionFrames window={window} selection={selection}/>}{!available && <div className="unavailable"><h2>{status || 'Waiting for video'}</h2></div>}</div>
    {savedSelection && <div className="stage video-stage"><video aria-label="Saved recording playback" src={`/recordings/${savedSelection}/video.mp4`} controls/></div>}
    <div className="playback" hidden={Boolean(savedSelection)}><div className="playback-row"><button disabled={!available} onClick={() => {pauseLive(); setPlaying(value => !value);}}>{playing ? 'Pause' : 'Play'}</button>
      <span className="mono">{playhead.toFixed(1)} / {end.toFixed(1)} s</span>
      <button aria-pressed={live.current} disabled={!buffer.current.frames.length} onClick={() => {recordedView.current = false; frozen.current = undefined; live.current = true; setPlaying(false); setTrim([undefined, undefined]); setError(''); const next = buffer.current.snapshot(); if (next) {player.current?.close(); player.current?.live(next, next.frames.at(-1)!); position.current = seconds(next.frames.at(-1)!, next.frames[0]);} redraw();}}>Live</button></div>
      <input onPointerDown={() => {if (live.current) frozen.current = windowNow(); live.current = false; setPlaying(false); redraw();}} aria-label="Video position" type="range" min="0" max={Math.max(end, 0.001)} step="0.01" value={playhead} disabled={!available} onChange={event => {setPlaying(false); show(Number(event.target.value));}}/>
      <div className="markers" aria-label="Action video markers">{markers.map(({item, at}) => <button key={key(item)} style={{left: `${end ? at! / end * 100 : 0}%`}} aria-label={`Seek action ${item.event.seq}`} title={`Action #${item.event.seq}`} onClick={() => {setPlaying(false); show(at!);}}/>)}</div>
      <div className="playback-row"><button disabled={!available} onClick={() => setTrim([playhead, trim[1]])}>Set in</button><button disabled={!available} onClick={() => setTrim([trim[0], playhead])}>Set out</button><span className="mono">{trim[0]?.toFixed(1) ?? '0.0'} — {trim[1]?.toFixed(1) ?? end.toFixed(1)} s</span><button onClick={() => setTrim([undefined, undefined])}>Reset trim</button></div>
      {available && <p className="video-note">Approximate frame correlation · cuts include keyframe pre-roll{buffer.current.gaps ? ` · ${buffer.current.gaps} gaps` : ''}{buffer.current.evicted ? ' · earlier frames expired' : ''}</p>}
      {error && <p className="error" role="alert">{error}</p>}
    </div>
    <div className="saved-recordings"><div className="playback-row"><label htmlFor="saved-recording">Saved recordings</label><select id="saved-recording" value={savedSelection} onChange={event => {pauseLive(); setSavedSelection(event.target.value); setPlaying(false);}}><option value="">Current device</option>{saved.filter(record => record.serial === serial).map(record => <option key={record.id} value={record.id}>{new Date(record.createdEpochMs).toLocaleString()} · {record.durationSeconds.toFixed(1)} s</option>)}</select>{savedSelection && <span><a href={`/recordings/${savedSelection}/clip.zip`} download>Download ZIP</a> · <a href={`/recordings/${savedSelection}/steps.json`} download>JSON</a></span>}</div><p className="video-note">Saved on stop · {savedDirectory || 'Loading save location…'} · resume creates a separate clip</p></div>
  </>;
}
