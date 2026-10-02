import { useEffect, useRef, useState } from 'react';
import type { RecordedEvent } from './events';
import { CanvasPlayer, type VideoWindow } from './video';

/** Inspection only. No captures, replay or owner configuration calls. */
export function ActionFrames({window, selection}: {window?: VideoWindow; selection?: RecordedEvent}) {
  const [mode, setMode] = useState<'before' | 'after' | 'compare'>('compare');
  const before = useRef<HTMLCanvasElement>(null);
  const after = useRef<HTMLCanvasElement>(null);
  const [error, setError] = useState('');
  const event = selection?.event;
  const aligned = window && event?.clockId === window.header.clockId && event.startedMonotonicNs !== undefined && event.finishedMonotonicNs !== undefined;
  let beforeIndex = -1;
  if (aligned && window.frames.length && event.startedMonotonicNs! <= window.frames.at(-1)!.receivedMonotonicNs) for (let index = 0; index < window.frames.length; index++) {
    if (window.frames[index].receivedMonotonicNs <= event.startedMonotonicNs!) beforeIndex = index;
  }
  const afterIndex = aligned && window.frames.length && event.finishedMonotonicNs! >= window.frames[0].receivedMonotonicNs ? window.frames.findIndex(frame => frame.receivedMonotonicNs >= event.finishedMonotonicNs!) : -1;
  const beforeFrame = window?.frames[beforeIndex]; const afterFrame = window?.frames[afterIndex];
  useEffect(() => {
    setError('');
    const players: CanvasPlayer[] = [];
    if (window) for (const [canvas, index] of [[before.current, beforeIndex], [after.current, afterIndex]] as const) {
      if (canvas && index >= 0) {
        const player = new CanvasPlayer(canvas, setError); players.push(player);
        void player.seek(window, index).catch(error => {if (!(error instanceof DOMException && error.name === 'AbortError')) setError(String(error));});
      }
    }
    return () => players.forEach(player => player.close());
  }, [window?.header.streamId, beforeFrame?.seq, afterFrame?.seq, mode]);
  return <div className="inspection"><div className="inspection-tools">{(['before','after','compare'] as const).map(value => <button key={value} aria-pressed={mode === value} onClick={() => setMode(value)}>{value[0].toUpperCase()+value.slice(1)}</button>)}</div>
    <div className={`comparison ${mode}`}>
      <figure hidden={mode === 'after'}><canvas ref={before} hidden={!beforeFrame}/><figcaption>Before · {beforeFrame && event?.startedMonotonicNs !== undefined ? `${(Number(beforeFrame.receivedMonotonicNs - event.startedMonotonicNs) / 1e6).toFixed(0)} ms` : 'unavailable'}</figcaption></figure>
      <figure hidden={mode === 'before'}><canvas ref={after} hidden={!afterFrame}/><figcaption>After · {afterFrame && event?.finishedMonotonicNs !== undefined ? `+${(Number(afterFrame.receivedMonotonicNs - event.finishedMonotonicNs) / 1e6).toFixed(0)} ms` : 'unavailable'}</figcaption></figure>
    </div><p className="video-note">Approximate receipt samples{!selection ? ' · choose an action' : ''}</p>{error && <p className="error">{error}</p>}
  </div>;
}
