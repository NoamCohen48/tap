import { describe, expect, it, vi } from 'vitest';
import { create } from '@bufbuild/protobuf';
import { VideoFrameSchema, VideoHeaderSchema } from './gen/video_pb';
import { actionPosition, CanvasPlayer, frameAt, precedingKey, VideoBuffer } from './video';
const header = () => create(VideoHeaderSchema, {streamId:'stream',clockId:'clock'});
function frame(seq: number, seconds: number, keyFrame = false) { return create(VideoFrameSchema, {seq: BigInt(seq), ptsUs: BigInt(Math.round(seconds * 1e6)), receivedMonotonicNs: BigInt(Math.round(seconds * 1e9)), keyFrame, data: new Uint8Array([1])}); }
describe('encoded video window', () => {
  it('deduplicates replay and retains whole GOPs within the time limit', () => {
    const buffer = new VideoBuffer(); buffer.setHeader(header());
    expect(buffer.append(frame(1, 0, true))).toBe(true);
    buffer.append(frame(2, 1)); buffer.append(frame(3, 2, true));
    expect(buffer.append(frame(2, 1))).toBe(false);
    buffer.append(frame(4, 121));
    expect(buffer.frames.map(value => value.seq)).toEqual([3n,4n]);
    expect(buffer.bytes).toBe(2);
  });
  it('keeps a paused snapshot stable while live frames arrive and expire', () => {
    const buffer = new VideoBuffer(); buffer.setHeader(header());
    buffer.append(frame(1, 0, true)); buffer.append(frame(2, 1));
    const paused = buffer.snapshot()!;
    buffer.append(frame(3, 2, true)); buffer.append(frame(4, 121));
    expect(paused.frames.map(value => value.seq)).toEqual([1n, 2n]);
    expect(buffer.snapshot()!.frames.map(value => value.seq)).toEqual([3n, 4n]);
  });
  it('starts new epochs only with a key frame and resets on header change', () => {
    const buffer = new VideoBuffer(); buffer.setHeader(header());
    expect(buffer.append(frame(1, 0))).toBe(false);
    buffer.append(frame(2, 1, true));
    expect(buffer.setHeader(header())).toBe(false);
    buffer.setHeader(create(VideoHeaderSchema,{streamId:'next',clockId:'clock'}));
    expect(buffer.frames).toHaveLength(0);
  });
  it('does not correlate unknown clocks, missing boundaries or out-of-window actions', () => {
    const window = {header:header(),frames:[frame(1, 1, true),frame(2, 2),frame(3, 3)]};
    expect(actionPosition(window,'other',2000000000n)).toBeUndefined();
    expect(actionPosition(window,'clock',undefined)).toBeUndefined();
    expect(actionPosition(window,'clock',4000000000n)).toBeUndefined();
    expect(actionPosition(window,'clock',2500000000n)).toBe(1);
    expect(frameAt(window.frames,1.5)).toBe(1);
    expect(precedingKey(window.frames,2)).toBe(0);
  });
  it('yields rather than flushing inside a long GOP', async () => {
    let flushes = 0;
    class Decoder {
      state = 'unconfigured'; decodeQueueSize = 0; needsKey = true;
      configure() {this.state = 'configured'; this.needsKey = true;}
      decode(chunk: {type: string}) {
        if (this.needsKey && chunk.type !== 'key') throw new Error('key frame required after flush');
        this.needsKey = false; this.decodeQueueSize++;
        setTimeout(() => this.decodeQueueSize--, 1);
      }
      async flush() {flushes++; this.needsKey = true;}
      close() {this.state = 'closed';}
    }
    vi.stubGlobal('VideoDecoder', Decoder);
    vi.stubGlobal('EncodedVideoChunk', class {type: string; constructor(init: {type: string}) {this.type = init.type;}});
    try {
      const source = create(VideoHeaderSchema, {streamId: 'long', width: 32, height: 48, configuration: new Uint8Array([0,0,0,1,0x67,0x42,0,0x1e])});
      const player = new CanvasPlayer({} as HTMLCanvasElement, message => {throw new Error(message);});
      await player.seek({header: source, frames: Array.from({length: 80}, (_, i) => frame(i + 1, i / 10, i === 0))}, 79);
      expect(flushes).toBe(1);
      player.close();
    } finally {vi.unstubAllGlobals();}
  });
});
