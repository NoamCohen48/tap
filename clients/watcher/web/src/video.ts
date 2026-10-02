import type { VideoFrame, VideoHeader } from './gen/video_pb';

export type VideoWindow = { header: VideoHeader; frames: VideoFrame[] };
export const MAX_BYTES = 32 * 1024 * 1024;
export const MAX_SECONDS = 120;
export function seconds(frame: VideoFrame, origin: VideoFrame): number { return Number(frame.ptsUs - origin.ptsUs) / 1e6; }
export function precedingKey(frames: VideoFrame[], index: number): number {
  while (index > 0 && !frames[index].keyFrame) index--;
  return index;
}
export function frameAt(frames: VideoFrame[], position: number): number {
  let index = 0;
  while (index + 1 < frames.length && seconds(frames[index + 1], frames[0]) <= position) index++;
  return index;
}
/** Only receipt clocks in the same domain can correlate actions. Never infer device input time. */
export function actionPosition(window: VideoWindow, clockId: string, ns: bigint | undefined): number | undefined {
  if (clockId !== window.header.clockId || ns === undefined || !window.frames.length) return;
  const frames = window.frames;
  if (ns < frames[0].receivedMonotonicNs || ns > frames.at(-1)!.receivedMonotonicNs) return;
  let index = 0;
  while (index + 1 < frames.length && frames[index + 1].receivedMonotonicNs <= ns) index++;
  return seconds(frames[index], frames[0]);
}
export class VideoBuffer {
  header?: VideoHeader;
  frames: VideoFrame[] = [];
  bytes = 0;
  gaps = 0;
  evicted = 0;
  setHeader(header: VideoHeader): boolean {
    if (this.header?.streamId === header.streamId && this.header.configuration.toString() === header.configuration.toString()) return false;
    this.header = header; this.frames = []; this.bytes = 0; this.gaps = 0; this.evicted = 0; return true;
  }
  append(frame: VideoFrame): boolean {
    if (!this.header || frame.seq <= (this.frames.at(-1)?.seq ?? 0n)) return false;
    const last = this.frames.at(-1);
    if (last && (frame.seq !== last.seq + 1n || frame.ptsUs <= last.ptsUs)) {
      this.gaps++; this.frames = []; this.bytes = 0;
    }
    if (!this.frames.length && !frame.keyFrame) return false;
    this.frames.push(frame); this.bytes += frame.data.length;
    while (this.frames.length > 1 && (this.bytes > MAX_BYTES || Number(frame.receivedMonotonicNs - this.frames[0].receivedMonotonicNs) / 1e9 > MAX_SECONDS)) {
      this.evicted++; this.bytes -= this.frames.shift()!.data.length;
      while (this.frames.length && !this.frames[0].keyFrame) { this.evicted++; this.bytes -= this.frames.shift()!.data.length; }
    }
    return true;
  }
  snapshot(): VideoWindow | undefined { return this.header && this.frames.length ? {header: this.header, frames: [...this.frames]} : undefined; }
}
export function nals(data: Uint8Array): Uint8Array[] {
  const starts: {offset: number; size: number}[] = [];
  for (let i = 0; i < data.length - 2; i++) {
    if (data[i] === 0 && data[i + 1] === 0) {
      const size = data[i + 2] === 1 ? 3 : data[i + 2] === 0 && data[i + 3] === 1 ? 4 : 0;
      if (size) { starts.push({offset: i, size}); i += size - 1; }
    }
  }
  return starts.map((start, index) => data.subarray(start.offset + start.size, starts[index + 1]?.offset ?? data.length));
}
function codec(header: VideoHeader): string {
  const sps = nals(header.configuration).find(nal => (nal[0] & 31) === 7);
  if (!sps || sps.length < 4) throw new Error('Missing H.264 SPS');
  return 'avc1.' + Array.from(sps.subarray(1, 4), byte => byte.toString(16).padStart(2, '0')).join('');
}
/** Keeps only encoded history. Every decoded frame is drawn and closed immediately. */
export class CanvasPlayer {
  private decoder?: VideoDecoder;
  private generation = 0;
  private origin = 0n;
  private target = Infinity;
  private config?: VideoHeader;
  constructor(private canvas: HTMLCanvasElement, private onError: (message: string) => void) {}
  close() { this.generation++; if (this.decoder && this.decoder.state !== 'closed') this.decoder.close(); this.decoder = undefined; }
  private open(header: VideoHeader, origin: bigint, target = Infinity) {
    this.close(); const generation = this.generation;
    this.origin = origin; this.target = target; this.config = header;
    if (typeof VideoDecoder === 'undefined') throw new Error('This browser does not support H.264 WebCodecs; use Chromium on localhost');
    this.decoder = new VideoDecoder({
      output: frame => {
        try {
          if (generation !== this.generation || frame.timestamp > this.target) return;
          this.canvas.width = frame.displayWidth; this.canvas.height = frame.displayHeight;
          this.canvas.getContext('2d')?.drawImage(frame, 0, 0);
        } finally { frame.close(); }
      },
      error: error => { if (generation === this.generation) this.onError(error.message); },
    });
    this.decoder.configure({codec: codec(header), codedWidth: header.width, codedHeight: header.height, optimizeForLatency: true});
  }
  private feed(frame: VideoFrame) {
    if (!this.decoder || this.decoder.state !== 'configured' || !this.config) return;
    const prefix = frame.keyFrame ? this.config.configuration : new Uint8Array();
    const data = new Uint8Array(prefix.length + frame.data.length); data.set(prefix); data.set(frame.data, prefix.length);
    this.decoder.decode(new EncodedVideoChunk({type: frame.keyFrame ? 'key' : 'delta', timestamp: Number(frame.ptsUs - this.origin), data}));
  }
  async seek(window: VideoWindow, index: number) {
    if (!window.frames.length) return;
    const start = precedingKey(window.frames, index);
    const origin = window.frames[start].ptsUs;
    this.open(window.header, origin, Number(window.frames[index].ptsUs - origin));
    const decoder = this.decoder!;
    for (let i = start; i <= index; i++) {
      this.feed(window.frames[i]);
      // Bound pending decoder work even on a long GOP.
      if (decoder.decodeQueueSize > 30) await decoder.flush();
      if (decoder !== this.decoder) return;
    }
    await decoder.flush();
  }
  live(window: VideoWindow, frame: VideoFrame) {
    if (!this.decoder || this.decoder.state !== 'configured' || this.config?.streamId !== window.header.streamId || this.target !== Infinity) {
      this.open(window.header, window.frames[0].ptsUs);
      for (const buffered of window.frames.slice(precedingKey(window.frames, window.frames.length - 1))) this.feed(buffered);
    } else if (this.decoder.decodeQueueSize < 30) this.feed(frame);
    else { this.close(); } // resynchronize from retained key frame on the next packet
  }
}
export function base64(bytes: Uint8Array): string {
  let result = '';
  for (let i = 0; i < bytes.length; i += 8192) result += String.fromCharCode(...bytes.subarray(i, i + 8192));
  return btoa(result);
}
