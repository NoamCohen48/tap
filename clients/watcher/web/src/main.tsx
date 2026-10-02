import { StrictMode, useEffect, useMemo, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { create, toJson } from '@bufbuild/protobuf';
import { createClient, ConnectError, Code } from '@connectrpc/connect';
import { createConnectTransport } from '@connectrpc/connect-web';
import { WatcherService, ListDevicesResponseSchema } from './gen/watcher_pb';
import { DeviceState } from './gen/device_pb';
import { LoggedEventSchema } from './gen/event_log_pb';
import { actionName, key, mergeEvents, type RecordedEvent } from './events';
import { VideoPane } from './VideoPane';
import './style.css';

const client = createClient(WatcherService, createConnectTransport({ baseUrl: window.location.origin }));
function errorText(error: unknown): string { return error instanceof Error ? error.message : 'Request failed'; }
function eventTime(value: bigint): string { return new Date(Number(value)).toLocaleTimeString([], {hour12: false}); }

function App() {
  const [inventory, setInventory] = useState(() => create(ListDevicesResponseSchema));
  const [serial, setSerial] = useState('');
  const [inventoryError, setInventoryError] = useState('');
  const [streamStatus, setStreamStatus] = useState('');
  const [items, setItems] = useState<RecordedEvent[]>([]);
  const [query, setQuery] = useState('');
  const [failures, setFailures] = useState(false);
  const [selectedKey, setSelectedKey] = useState('');
  const [panel, setPanel] = useState<'screen' | 'actions'>('screen');
  const cursors = useRef(new Map<string, bigint>());
  const lastSerial = useRef('');

  useEffect(() => {
    const abort = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    async function refresh() {
      try {
        const next = await client.listDevices({}, {signal: abort.signal});
        if (abort.signal.aborted) return;
        setInventory(next); setInventoryError('');
        setSerial(current => current || next.devices[0]?.serial || '');
      } catch (error) { if (!abort.signal.aborted) setInventoryError(errorText(error)); }
      if (!abort.signal.aborted) timer = setTimeout(refresh, 3000);
    }
    void refresh();
    return () => { abort.abort(); clearTimeout(timer); };
  }, []);

  const device = inventory.devices.find(d => d.serial === serial);
  const owner = inventory.connections.find(c => c.clientConnectionId === device?.clientConnectionId);
  const ownerId = owner?.clientConnectionId ?? '';
  useEffect(() => {
    const abort = new AbortController();
    let timer: ReturnType<typeof setTimeout>;
    if (lastSerial.current !== serial) {
      lastSerial.current = serial; cursors.current.clear(); setItems([]); setSelectedKey('');
    }
    if (!ownerId) { setStreamStatus(''); return; }
    let retryMs = 1000;
    async function watch() {
      try {
        setStreamStatus('Connecting…');
        for await (const response of client.watchEvents({observedConnectionId: ownerId, afterSeq: cursors.current.get(ownerId) ?? 0n}, {signal: abort.signal})) {
          if (abort.signal.aborted) return;
          const update = response.update?.update;
          if (update?.case === 'closing') { setStreamStatus(update.value.reason); return; }
          if (update?.case !== 'events') continue;
          retryMs = 1000;
          const cursor = cursors.current.get(ownerId) ?? 0n;
          const batch = update.value;
          const gap = batch.events.length > 0 && batch.events[0].seq > cursor + 1n;
          const newest = batch.events.at(-1)?.seq;
          if (newest !== undefined && newest > cursor) cursors.current.set(ownerId, newest);
          setStreamStatus(gap ? 'Earlier actions were evicted' : 'Live');
          const incoming = batch.events.filter(event => event.serial === serial).map(event => ({connectionId: ownerId, event}));
          if (incoming.length) setItems(current => mergeEvents(current, incoming));
        }
        if (!abort.signal.aborted) setStreamStatus('Stream ended');
      } catch (error) {
        if (abort.signal.aborted) return;
        setStreamStatus(errorText(error));
        if (error instanceof ConnectError && [Code.Unimplemented, Code.Unauthenticated, Code.PermissionDenied, Code.InvalidArgument].includes(error.code)) return;
      }
      if (!abort.signal.aborted) { timer = setTimeout(watch, retryMs); retryMs = Math.min(10_000, retryMs * 2); }
    }
    void watch();
    return () => { abort.abort(); clearTimeout(timer); };
  }, [ownerId, serial]);

  const visible = useMemo(() => items.filter(item => (!failures || item.event.error || item.event.failure) &&
    (actionName(item.event) + JSON.stringify(toJson(LoggedEventSchema, item.event))).toLowerCase().includes(query.toLowerCase())), [items, query, failures]);
  const selected = items.find(item => key(item) === selectedKey) ?? items.at(-1);
  const detail = selected?.event;
  const payload = detail ? JSON.stringify(toJson(LoggedEventSchema, detail), null, 2) : '';
  function choose(next: string) { setSerial(next); setQuery(''); setFailures(false); }

  return <><header><div className="brand"><span className="wordmark">tap</span><h1>Watch</h1></div><nav aria-label="App panels"><button onClick={() => setPanel('screen')} aria-pressed={panel === 'screen'}>Screen</button><button onClick={() => setPanel('actions')} aria-pressed={panel === 'actions'}>Actions</button></nav></header>
    <main className={`layout ${panel === 'actions' ? 'show-actions' : ''}`}>
      <aside aria-label="Devices"><h2>Devices <span>{inventory.devices.length}</span></h2>
        {inventoryError && <p className="error" role="status">{inventoryError}</p>}
        {!inventory.devices.length && !inventoryError && <p className="empty">No devices connected</p>}
        {inventory.devices.map(d => {
          const holder = inventory.connections.find(c => c.clientConnectionId === d.clientConnectionId);
          return <button className={`device ${serial === d.serial ? 'selected' : ''}`} key={d.serial} onClick={() => choose(d.serial)} aria-pressed={serial === d.serial}>
            <strong>{d.serial}</strong><span className="meta">{DeviceState[d.state]?.replaceAll('_', ' ').toLowerCase() ?? 'unknown'}</span>
            <span className="owner">{holder?.name || (d.clientConnectionId ? 'Another connection' : 'No owner')}</span>
          </button>;
        })}
      </aside>
      <section className="viewer" aria-label="Device screen"><div className="viewer-head"><div><h2>{serial || 'Choose a device'}</h2><p>{owner?.name || 'No attached owner'}</p></div><select aria-label="Choose device" value={serial} onChange={e => choose(e.target.value)}><option value="">Choose device</option>{inventory.devices.map(d => <option key={d.serial}>{d.serial}</option>)}</select></div>
        <VideoPane client={client} serial={serial} events={items} selection={items.find(item => key(item) === selectedKey)}/>
      </section>
      <section className="timeline" aria-label="Action timeline"><div className="timeline-head"><div className="section-head"><h2>Action timeline</h2><span className="status" role="status">{streamStatus}</span></div><label htmlFor="search">Find an action or selector</label><input id="search" type="search" value={query} onChange={e => setQuery(e.target.value)} placeholder="Search this device…"/><div className="filters"><button aria-pressed={!failures} onClick={() => setFailures(false)}>All actions</button><button aria-pressed={failures} onClick={() => setFailures(true)}>Failures</button></div></div>
        <div className="events">{visible.map(item => {const e = item.event; const failed = Boolean(e.error || e.failure); return <button key={key(item)} className={`event ${key(selected!) === key(item) ? 'selected' : ''} ${failed ? 'failed' : ''}`} onClick={() => setSelectedKey(key(item))} aria-pressed={selected && key(selected) === key(item)}><span className="outcome">{failed ? '!' : '✓'}</span><span><strong>{actionName(e)}</strong><span className="meta mono">{eventTime(e.atEpochMs)} · #{e.seq.toString()}</span></span><span className="duration">{e.durationMs.toString()} ms</span></button>; })}
          {!visible.length && <p className="empty">{query || failures ? 'No matching actions' : ownerId ? 'Waiting for actions…' : 'No attached connection'}</p>}
        </div>
        {detail && <section className="details" aria-label="Selected action"><div className="section-head"><h2>{actionName(detail)}</h2><span className={`status ${detail.error || detail.failure ? 'failed' : ''}`}>{detail.error || detail.failure ? 'Failed' : 'Succeeded'}</span></div><dl><dt>Started</dt><dd className="mono">{new Date(Number(detail.atEpochMs)).toISOString()}</dd><dt>Duration</dt><dd className="mono">{detail.durationMs.toString()} ms</dd><dt>Start ns</dt><dd className="mono">{detail.startedMonotonicNs?.toString() ?? 'unavailable'}</dd><dt>End ns</dt><dd className="mono">{detail.finishedMonotonicNs?.toString() ?? 'unavailable'}</dd><dt>Connection</dt><dd className="mono">{selected!.connectionId}</dd><dt>Device</dt><dd className="mono">{detail.serial}</dd></dl><pre>{payload}</pre></section>}
      </section>
    </main></>;
}
const root = document.querySelector('#root');
if (!root) throw new Error('missing root element');
createRoot(root).render(<StrictMode><App/></StrictMode>);
