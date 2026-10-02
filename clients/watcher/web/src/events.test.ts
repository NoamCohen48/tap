import { describe, expect, it } from 'vitest';
import { create } from '@bufbuild/protobuf';
import { LoggedEventSchema } from './gen/event_log_pb';
import { mergeEvents, MAX_EVENTS, key } from './events';
const item = (connectionId: string, seq: bigint, atEpochMs = seq) => ({connectionId, event: create(LoggedEventSchema, {seq, atEpochMs})});

describe('event retention', () => {
  it('deduplicates replay without confusing connections with the same sequence', () => {
    const merged = mergeEvents([item('a', 1n)], [item('a', 1n), item('a', 2n), item('b', 1n)]);
    expect(new Set(merged.map(key)).size).toBe(3);
  });
  it('preserves daemon sequence order even if wall clock moves backwards', () => {
    const merged = mergeEvents([], [item('a', 2n, 1n), item('a', 1n, 3n)]);
    expect(merged.map(i => i.event.seq)).toEqual([1n, 2n]);
  });
  it('bounds the retained event window', () => {
    const batch = Array.from({length: MAX_EVENTS + 10}, (_, i) => item('a', BigInt(i + 1)));
    const merged = mergeEvents([], batch);
    expect(merged.length).toBe(MAX_EVENTS);
    expect(merged[0].event.seq).toBe(11n);
  });
});
