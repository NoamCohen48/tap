// Encoded video on the page: a buffer mirroring the back end's retained history, and a player
// that decodes it with WebCodecs (live, seek to one frame, or continuous playback).

import type { VideoFrame as EncodedFrame, VideoHeader } from "./gen/watch_pb";

export type Limits = { maxSeconds: number; maxBytes: number };

/**
 * A run of one stream's frames, starting at a key frame. A rotation or a capture restart (a new
 * header) starts the next segment; the previous one stays until it ages out.
 */
export type Segment = {
  header: VideoHeader;
  /** The absolute index of its first frame when it began (it may since have been evicted). */
  start: number;
  /** Where its first frame sits on the media timeline, in µs (see [FrameBuffer.mediaUs]). */
  mediaBaseUs: number;
  firstPts: bigint;
};

type Entry = { frame: EncodedFrame; segment: Segment };

/** The pause between segments when playing back (the real gap can be a daemon restart). */
const MAX_SEGMENT_GAP_US = 1_000_000;

/**
 * Segments of whole GOPs, within one time and byte budget for all of them. Indices are absolute
 * (they count from the first frame the buffer kept and never shift on eviction or at a new
 * segment), so a paused position or a running player stays valid while old frames leave.
 *
 * Two clocks: the *timeline* (ns) orders frames by host receipt — monotonic within one daemon
 * clock, joined across clocks by wall time — and places actions; *media* time (µs) follows the
 * encoder's pts within a segment and is what playback and the decoder see.
 */
export class FrameBuffer {
  private entries: Entry[] = [];
  private head = 0;
  /** The absolute index of entries[head]. */
  first = 0;
  bytes = 0;
  /** Retained segments, oldest first. */
  segments: Segment[] = [];
  /** A new stream's header whose first key frame has not arrived. */
  private pending?: VideoHeader;
  /** Frames of an older stream that a reconnect replays are already here. */
  private skipping = false;
  private awaitingKey = false;
  /** Per clock, what to add to its monotonic times to place them on the timeline. */
  private offsets = new Map<string, bigint>();

  constructor(public limits: Limits = { maxSeconds: 120, maxBytes: 32 * 1024 * 1024 }) {}

  /** The absolute index after the newest frame. */
  get end(): number {
    return this.first + this.entries.length - this.head;
  }

  get empty(): boolean {
    return this.end === this.first;
  }

  /** The newest stream's header, including one still waiting for its first frame. */
  get header(): VideoHeader | undefined {
    return this.pending ?? this.segments.at(-1)?.header;
  }

  private entry(index: number): Entry {
    return this.entries[this.head + index - this.first];
  }

  at(index: number): EncodedFrame {
    return this.entry(index).frame;
  }

  segmentAt(index: number): Segment {
    return this.entry(index).segment;
  }

  headerAt(index: number): VideoHeader {
    return this.entry(index).segment.header;
  }

  newest(): EncodedFrame | undefined {
    return this.empty ? undefined : this.at(this.end - 1);
  }

  oldest(): EncodedFrame | undefined {
    return this.empty ? undefined : this.at(this.first);
  }

  /** Frame [index]'s host receipt on the joint timeline. */
  timeline(index: number): bigint {
    const { frame, segment } = this.entry(index);
    return frame.receivedMonotonicNs + this.offsets.get(segment.header.clockId)!;
  }

  /** [ns] on clock [clockId] on the timeline; undefined for a clock no retained frame uses. */
  timelineOf(clockId: string, ns: bigint | undefined): bigint | undefined {
    const offset = this.offsets.get(clockId);
    return ns === undefined || offset === undefined ? undefined : ns + offset;
  }

  /** Frame [index]'s presentation time: unique and increasing across the whole buffer. */
  mediaUs(index: number): number {
    const { frame, segment } = this.entry(index);
    return segment.mediaBaseUs + Number(frame.ptsUs - segment.firstPts);
  }

  /** True for a new stream (its first key frame starts a segment). */
  setHeader(header: VideoHeader): boolean {
    if (this.segments.some((segment) => same(segment.header, header))) {
      this.skipping = !same(this.segments.at(-1)!.header, header);
      this.pending = undefined;
      return false;
    }
    this.skipping = false;
    if (this.pending && same(this.pending, header)) return false;
    this.pending = header;
    return true;
  }

  /** False for a frame that cannot be kept: a repeat, a delta frame after a gap, or a frame of an older stream. */
  append(frame: EncodedFrame): boolean {
    if (this.skipping) return false;
    const last = this.newest();
    if (this.pending) {
      if (!frame.keyFrame) return false;
      this.start(this.pending, frame);
      this.pending = undefined;
    } else if (!last) {
      return false;
    } else {
      if (frame.seq <= last.seq) return false;
      if (frame.seq !== last.seq + 1n) this.awaitingKey = true;
      if (this.awaitingKey && !frame.keyFrame) return false;
      if (frame.ptsUs <= last.ptsUs) {
        // Time went backwards inside one stream: keep it apart so media time keeps increasing.
        if (!frame.keyFrame) {
          this.awaitingKey = true;
          return false;
        }
        this.start(this.segments.at(-1)!.header, frame);
      } else {
        this.entries.push({ frame, segment: this.segments.at(-1)! });
      }
    }
    this.awaitingKey = false;
    this.bytes += frame.data.length;
    this.evict();
    return true;
  }

  private start(header: VideoHeader, frame: EncodedFrame) {
    const previous = this.empty ? undefined : this.end - 1;
    if (!this.offsets.has(header.clockId)) {
      let offset = 0n;
      if (previous !== undefined) {
        const newest = this.at(previous);
        let gapNs = (frame.receivedEpochMs - newest.receivedEpochMs) * 1_000_000n;
        if (gapNs < 1n) gapNs = 1n;
        offset = this.timeline(previous) + gapNs - frame.receivedMonotonicNs;
      }
      this.offsets.set(header.clockId, offset);
    }
    let mediaBaseUs = 0;
    if (previous !== undefined) {
      const gapUs = Number(frame.receivedMonotonicNs + this.offsets.get(header.clockId)! - this.timeline(previous)) / 1000;
      // Whole µs: WebCodecs timestamps are integers, and a seek matches its frame by timestamp.
      mediaBaseUs = this.mediaUs(previous) + Math.round(Math.min(MAX_SEGMENT_GAP_US, Math.max(1000, gapUs)));
    }
    const segment = { header, start: this.end, mediaBaseUs, firstPts: frame.ptsUs };
    this.segments.push(segment);
    this.entries.push({ frame, segment });
  }

  private evict() {
    const maxNs = BigInt(this.limits.maxSeconds) * 1_000_000_000n;
    const newestNs = this.timeline(this.end - 1);
    const over = () => this.bytes > this.limits.maxBytes || newestNs - this.timeline(this.first) > maxNs;
    while (over()) {
      let next = this.first + 1;
      while (next < this.end && !this.at(next).keyFrame) next++;
      if (next >= this.end) break; // keep the newest GOP whatever its size
      for (; this.first < next; this.first++, this.head++) this.bytes -= this.entries[this.head].frame.data.length;
    }
    const oldest = this.segmentAt(this.first);
    if (this.segments[0] !== oldest) {
      this.segments = this.segments.slice(this.segments.indexOf(oldest));
      const clocks = new Set(this.segments.map((segment) => segment.header.clockId));
      for (const clock of [...this.offsets.keys()]) if (!clocks.has(clock)) this.offsets.delete(clock);
    }
    if (this.head > 1024 && this.head * 2 > this.entries.length) {
      this.entries = this.entries.slice(this.head);
      this.head = 0;
    }
  }

  /** The key frame at or before [index] (never in an earlier segment: each opens on one). */
  keyBefore(index: number): number {
    let i = Math.min(index, this.end - 1);
    while (i > this.first && !this.at(i).keyFrame) i--;
    return i;
  }

  /** The last frame with media time ≤ [us] (the oldest when none is). */
  indexAtMedia(us: number): number {
    return this.search((index) => this.mediaUs(index) <= us);
  }

  /** The last frame received at or before timeline [ns] (the oldest when none was). */
  indexAtTimeline(ns: bigint): number {
    return this.search((index) => this.timeline(index) <= ns);
  }

  /** Binary search for the last index where [before] holds; frames are ordered. */
  private search(before: (index: number) => boolean): number {
    let low = this.first;
    let high = this.end - 1;
    while (low < high) {
      const middle = (low + high + 1) >> 1;
      if (before(middle)) low = middle;
      else high = middle - 1;
    }
    return low;
  }

  /** Whether [ns] on [clockId] falls inside the retained frames. */
  covers(clockId: string, ns: bigint | undefined): boolean {
    const at = this.timelineOf(clockId, ns);
    return at !== undefined && !this.empty && at >= this.timeline(this.first) && at <= this.timeline(this.end - 1);
  }
}

function same(a: VideoHeader, b: VideoHeader): boolean {
  return a.streamId === b.streamId && equal(a.configuration, b.configuration);
}

function equal(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}

export function nals(data: Uint8Array): Uint8Array[] {
  const starts: { offset: number; size: number }[] = [];
  for (let i = 0; i < data.length - 2; i++) {
    if (data[i] === 0 && data[i + 1] === 0) {
      const size = data[i + 2] === 1 ? 3 : data[i + 2] === 0 && data[i + 3] === 1 ? 4 : 0;
      if (size) {
        starts.push({ offset: i, size });
        i += size - 1;
      }
    }
  }
  return starts.map((start, index) => data.subarray(start.offset + start.size, starts[index + 1]?.offset ?? data.length));
}

function config(header: VideoHeader): VideoDecoderConfig {
  return { codec: codecString(header), codedWidth: header.width, codedHeight: header.height, optimizeForLatency: true };
}

export function codecString(header: VideoHeader): string {
  const sps = nals(header.configuration).find((nal) => (nal[0] & 31) === 7);
  if (!sps || sps.length < 4) throw new Error("The video header has no H.264 SPS");
  return "avc1." + Array.from(sps.subarray(1, 4), (byte) => byte.toString(16).padStart(2, "0")).join("");
}

const MAX_DECODE_QUEUE = 8;

/**
 * Draws a [FrameBuffer] onto a canvas. One decoder at a time; every decoded frame is drawn or
 * closed at once except in playback, where at most a few wait for their time.
 */
export class Player {
  private decoder?: VideoDecoder;
  private generation = 0;
  private source?: FrameBuffer;
  private segment?: Segment;
  private next = 0;
  private target?: number;
  private playing?: { originUs: number; startedAt: number; onTick: (mediaUs: number) => void; onEnd: () => void };
  private waiting: VideoFrame[] = [];
  private raf = 0;

  constructor(
    private canvas: HTMLCanvasElement,
    private onError: (message: string) => void,
  ) {}

  /** Stops decoding and drops anything queued. The canvas keeps its last picture. */
  stop() {
    this.generation++;
    cancelAnimationFrame(this.raf);
    this.playing = undefined;
    this.waiting.forEach((frame) => frame.close());
    this.waiting = [];
    if (this.decoder && this.decoder.state !== "closed") this.decoder.close();
    this.decoder = undefined;
  }

  private open(buffer: FrameBuffer, from: number) {
    this.stop();
    if (typeof VideoDecoder === "undefined") throw new Error("This browser cannot decode H.264 (WebCodecs); use Chrome or Edge on localhost");
    const generation = this.generation;
    this.source = buffer;
    this.segment = buffer.segmentAt(from);
    this.next = from;
    this.decoder = new VideoDecoder({
      output: (frame) => this.output(frame, generation),
      error: (error) => {
        if (generation === this.generation) this.onError(error.message);
      },
    });
    this.decoder.configure(config(this.segment.header));
  }

  private output(frame: VideoFrame, generation: number) {
    if (generation !== this.generation) return frame.close();
    if (this.playing) {
      if (frame.timestamp < this.playing.originUs) return frame.close(); // key frame pre-roll
      this.waiting.push(frame);
      return;
    }
    if (this.target !== undefined && frame.timestamp !== this.target) return frame.close();
    this.draw(frame);
  }

  private draw(frame: VideoFrame) {
    try {
      if (this.canvas.width !== frame.displayWidth) this.canvas.width = frame.displayWidth;
      if (this.canvas.height !== frame.displayHeight) this.canvas.height = frame.displayHeight;
      this.canvas.getContext("2d")?.drawImage(frame, 0, 0);
    } finally {
      frame.close();
    }
  }

  private feed(buffer: FrameBuffer) {
    const index = this.next++;
    const frame = buffer.at(index);
    const segment = buffer.segmentAt(index);
    if (segment !== this.segment) {
      // A new stream (it opens on a key frame): queued after what was fed of the previous one.
      this.segment = segment;
      this.decoder!.configure(config(segment.header));
    }
    const prefix = frame.keyFrame ? segment.header.configuration : new Uint8Array();
    const data = new Uint8Array(prefix.length + frame.data.length);
    data.set(prefix);
    data.set(frame.data, prefix.length);
    this.decoder!.decode(new EncodedVideoChunk({ type: frame.keyFrame ? "key" : "delta", timestamp: buffer.mediaUs(index), data }));
  }

  private ready(): boolean {
    return this.decoder?.state === "configured";
  }

  /** Follows the newest frame: decodes what arrived since the last call. */
  live(buffer: FrameBuffer) {
    if (buffer.empty) return;
    const fresh = this.ready() && !this.playing && this.target === undefined && this.source === buffer && this.next >= buffer.first;
    if (!fresh || this.decoder!.decodeQueueSize > 30) {
      this.open(buffer, buffer.keyBefore(buffer.end - 1));
      this.target = undefined;
    }
    while (this.next < buffer.end) this.feed(buffer);
  }

  /** Shows exactly frame [index]. */
  async seek(buffer: FrameBuffer, index: number) {
    if (buffer.empty) return;
    this.open(buffer, buffer.keyBefore(index));
    this.target = buffer.mediaUs(index);
    const decoder = this.decoder!;
    while (this.next <= index) {
      this.feed(buffer);
      // flush() would demand a key frame next; yield instead while the decoder catches up.
      while (decoder === this.decoder && this.ready() && decoder.decodeQueueSize > 30) await new Promise((resolve) => setTimeout(resolve, 4));
      if (decoder !== this.decoder || !this.ready()) return;
    }
    await decoder.flush().catch(() => undefined);
  }

  /** Plays from [index] in real time; [onEnd] when it reaches the newest frame. */
  play(buffer: FrameBuffer, index: number, onTick: (mediaUs: number) => void, onEnd: () => void) {
    if (buffer.empty) return;
    this.open(buffer, buffer.keyBefore(index));
    this.target = undefined;
    this.playing = { originUs: buffer.mediaUs(index), startedAt: performance.now(), onTick, onEnd };
    const generation = this.generation;
    const step = () => {
      if (generation !== this.generation || !this.playing) return;
      const { originUs, startedAt } = this.playing;
      const due = originUs + (performance.now() - startedAt) * 1000;
      let shown: VideoFrame | undefined;
      while (this.waiting.length && this.waiting[0].timestamp <= due) {
        shown?.close();
        shown = this.waiting.shift();
      }
      if (shown) {
        this.playing.onTick(shown.timestamp);
        this.draw(shown);
      }
      if (this.next < buffer.first) return this.onError("Playback fell behind the retained video");
      while (this.ready() && this.next < buffer.end && this.decoder!.decodeQueueSize < MAX_DECODE_QUEUE && this.waiting.length < MAX_DECODE_QUEUE) this.feed(buffer);
      if (this.next >= buffer.end && !this.waiting.length && this.decoder!.decodeQueueSize === 0) {
        const done = this.playing.onEnd;
        this.playing = undefined;
        return done();
      }
      this.raf = requestAnimationFrame(step);
    };
    this.raf = requestAnimationFrame(step);
  }
}
