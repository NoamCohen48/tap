import { create } from "@bufbuild/protobuf";
import { describe, expect, it } from "vitest";
import { VideoFrameSchema, VideoHeaderSchema } from "./gen/watch_pb";
import { codecString, FrameBuffer } from "./video";

const SPS = [0, 0, 0, 1, 0x67, 0x42, 0xc0, 0x1f, 0xaa];
const header = (streamId = "s1", clockId = "clock", width = 32, height = 48) =>
  create(VideoHeaderSchema, { streamId, clockId, width, height, configuration: new Uint8Array([...SPS, 0, 0, 0, 1, 0x68, 0xce]) });

/** Frame [seq] at seq × 100 ms; a key frame every [gop]. */
const frame = (seq: number, gop = 3, size = 10) =>
  create(VideoFrameSchema, { seq: BigInt(seq), ptsUs: BigInt(seq * 100_000), keyFrame: (seq - 1) % gop === 0, data: new Uint8Array(size), receivedMonotonicNs: BigInt(seq * 100_000_000), receivedEpochMs: BigInt(seq * 100) });

function filled(count: number, limits = { maxSeconds: 120, maxBytes: 1 << 20 }) {
  const buffer = new FrameBuffer(limits);
  buffer.setHeader(header());
  for (let seq = 1; seq <= count; seq++) buffer.append(frame(seq));
  return buffer;
}

describe("FrameBuffer", () => {
  it("finds frames by time", () => {
    const buffer = filled(9);
    expect(buffer.indexAtTimeline(450_000_000n)).toBe(3);
    expect(buffer.indexAtTimeline(1n)).toBe(0);
    expect(buffer.indexAtMedia(600_000)).toBe(6);
    expect(buffer.mediaUs(0)).toBe(0);
    expect(buffer.keyBefore(5)).toBe(3);
    expect(buffer.covers("clock", 300_000_000n)).toBe(true);
    expect(buffer.covers("other", 300_000_000n)).toBe(false);
  });

  it("evicts whole GOPs and keeps indices absolute", () => {
    const buffer = filled(9, { maxSeconds: 120, maxBytes: 65 });
    expect(buffer.first).toBe(3);
    expect(buffer.at(3).keyFrame).toBe(true);
    expect(buffer.at(8).seq).toBe(9n);
    expect(buffer.end).toBe(9);
  });

  it("evicts by age", () => {
    const buffer = filled(9, { maxSeconds: 0, maxBytes: 1 << 20 });
    expect(buffer.first).toBe(6);
  });

  it("keeps what it has across a gap and waits for a key frame", () => {
    const buffer = filled(4);
    expect(buffer.append(frame(6))).toBe(false);
    expect(buffer.end).toBe(4);
    expect(buffer.append(frame(7))).toBe(true);
    expect(buffer.append(frame(7))).toBe(false);
    expect(buffer.end).toBe(5);
  });

  it("continues a new stream as a segment without moving earlier positions", () => {
    const buffer = filled(4);
    expect(buffer.setHeader(header())).toBe(false);
    expect(buffer.setHeader(header("s2", "clock", 48, 32))).toBe(true);
    expect(buffer.end).toBe(4);
    expect(buffer.append(frame(5))).toBe(false); // a new stream opens on a key frame
    expect(buffer.append(frame(7))).toBe(true);
    expect(buffer.segments.map((segment) => [segment.header.streamId, segment.start])).toEqual([
      ["s1", 0],
      ["s2", 4],
    ]);
    expect(buffer.headerAt(3).streamId).toBe("s1");
    expect(buffer.headerAt(4).width).toBe(48);
    // Media time keeps increasing across the change; playback pauses at most a second there.
    expect(buffer.mediaUs(4)).toBeGreaterThan(buffer.mediaUs(3));
    expect(buffer.mediaUs(4) - buffer.mediaUs(3)).toBeLessThanOrEqual(1_000_000);
    expect(Number.isInteger(buffer.mediaUs(4))).toBe(true);
    // A reconnect replays the older stream: skipped, the newest continues.
    expect(buffer.setHeader(header())).toBe(false);
    expect(buffer.append(frame(4))).toBe(false);
    expect(buffer.setHeader(header("s2", "clock", 48, 32))).toBe(false);
    expect(buffer.append(frame(8))).toBe(true);
  });

  it("joins a restarted daemon's clock after the old one by wall time", () => {
    const buffer = filled(3);
    buffer.setHeader(header("s2", "restarted"));
    // Its monotonic clock reads lower than the old one; 2 s of wall time passed.
    const first = create(VideoFrameSchema, { seq: 1n, ptsUs: 0n, keyFrame: true, data: new Uint8Array(10), receivedMonotonicNs: 5n, receivedEpochMs: 2300n });
    expect(buffer.append(first)).toBe(true);
    expect(buffer.timeline(3) - buffer.timeline(2)).toBe(2_000_000_000n);
    expect(buffer.covers("restarted", 5n)).toBe(true);
    expect(buffer.covers("clock", 200_000_000n)).toBe(true);
    // Playback does not wait the whole outage.
    expect(buffer.mediaUs(3) - buffer.mediaUs(2)).toBe(1_000_000);
  });

  it("drops a segment once its frames age out", () => {
    const buffer = filled(3, { maxSeconds: 1, maxBytes: 1 << 20 });
    buffer.setHeader(header("s2"));
    for (let seq = 4; seq <= 20; seq++) buffer.append(frame(seq));
    expect(buffer.segments.map((segment) => segment.header.streamId)).toEqual(["s2"]);
  });

  it("derives the codec string from the SPS", () => {
    expect(codecString(header())).toBe("avc1.42c01f");
  });
});
