import type { LoggedEvent } from './gen/event_log_pb';

export type RecordedEvent = { connectionId: string; event: LoggedEvent };
export const MAX_EVENTS = 2000;
export function key(item: RecordedEvent): string { return `${item.connectionId}:${item.event.seq}`; }
/** Sequence numbers are per connection, never a global ordering across owners. */
export function mergeEvents(previous: RecordedEvent[], incoming: RecordedEvent[]): RecordedEvent[] {
  const merged = new Map(previous.map(item => [key(item), item]));
  for (const item of incoming) merged.set(key(item), item);
  return [...merged.values()].sort((a, b) => {
    if (a.connectionId === b.connectionId) return a.event.seq < b.event.seq ? -1 : a.event.seq > b.event.seq ? 1 : 0;
    if (a.event.atEpochMs === b.event.atEpochMs) return a.connectionId.localeCompare(b.connectionId);
    return a.event.atEpochMs < b.event.atEpochMs ? -1 : 1;
  }).slice(-MAX_EVENTS);
}
export function actionName(event: LoggedEvent): string {
  if (event.call.case === 'command') return (event.call.value.op.case ?? 'command').replace(/([A-Z])/g, ' $1').replace(/^./, c => c.toUpperCase());
  if (event.call.case === 'app') return event.call.value.operation.replaceAll('_', ' ').replace(/^./, c => c.toUpperCase());
  return 'Action';
}
