// @vitest-environment jsdom
import { create } from "@bufbuild/protobuf";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { Stage } from "./Stage";
import { ActionMarks } from "./ActionMarks";
import type { Action } from "./activity";
import { LoggedEventSchema } from "./gen/event_log_pb";
import { VideoFrameSchema, VideoHeaderSchema } from "./gen/watch_pb";
import { FrameBuffer } from "./video";

const fixture = vi.hoisted(() => ({ buffer: undefined as unknown as FrameBuffer }));
vi.mock("./hooks", () => ({ useVideo: () => ({ buffer: fixture.buffer, version: 1, state: "streaming" }) }));
vi.mock("./video", async (original) => ({
  ...await original<typeof import("./video")>(),
  Player: class { stop() {} live() {} async seek() {} },
}));
vi.mock("./zoom", () => ({
  useZoom: () => ({ fraction: (n: number) => n, at: (n: number) => n }),
  Overview: () => null, ZoomControls: () => null,
}));

function action(): Action {
  return { key: "a", connectionId: "c", summary: "tap", failed: false, search: "tap", described: {} as Action["described"],
    event: create(LoggedEventSchema, {
      clockId: "clock", atEpochMs: BigInt(Date.now()), startedMonotonicNs: 100n, finishedMonotonicNs: 200n,
      call: { case: "command", value: { op: { case: "tap", value: {} } } },
      touchFeedback: { displayWidth: 100, displayHeight: 200, x: 40, y: 80 },
    }),
  };
}

beforeEach(() => {
  localStorage.clear();
  vi.useFakeTimers();
  fixture.buffer = new FrameBuffer();
  fixture.buffer.setHeader(create(VideoHeaderSchema, { streamId: "s", clockId: "clock", width: 100, height: 200 }));
  fixture.buffer.append(create(VideoFrameSchema, { seq: 1n, keyFrame: true, receivedMonotonicNs: 100n }));
  vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue({ clearRect() {} } as unknown as CanvasRenderingContext2D);
});
afterEach(() => { cleanup(); vi.useRealTimers(); vi.restoreAllMocks(); });

it("toggles markers immediately and remembers the choice on remount", () => {
  const props = { serial: "device", actions: [action()], onSelect: vi.fn(), onRecordingChanged: vi.fn(), onSaved: vi.fn(), notify: vi.fn() };
  const first = render(<Stage {...props} />);
  const toggle = screen.getByRole("button", { name: "Action marks" });
  expect(toggle.getAttribute("aria-pressed")).toBe("true");
  expect(first.container.querySelector(".action-marks circle")).not.toBeNull();
  fireEvent.click(toggle);
  expect(first.container.querySelector(".action-marks")).toBeNull();
  expect(localStorage.getItem("tap-watcher.action-marks.v1")).toBe("off");
  first.unmount();
  const second = render(<Stage {...props} />);
  expect(screen.getByRole("button", { name: "Action marks" }).getAttribute("aria-pressed")).toBe("false");
  fireEvent.click(screen.getByRole("button", { name: "Action marks" }));
  expect(second.container.querySelector(".action-marks circle")).not.toBeNull();
});

it("expires live marks even when the device sends no new frames", () => {
  const { container } = render(<ActionMarks actions={[action()]} clockId="clock" ns={100n} width={100} height={200} live />);
  expect(container.querySelector("circle")).not.toBeNull();
  act(() => vi.advanceTimersByTime(1401));
  expect(container.querySelector("svg")).toBeNull();
});

it("keeps a selected mark while paused but never crosses a clock boundary", () => {
  const selected = action();
  const { container, rerender } = render(<ActionMarks actions={[]} selected={selected} clockId="clock" ns={0n} width={100} height={200} />);
  expect(container.querySelector("circle")).not.toBeNull();
  rerender(<ActionMarks actions={[]} selected={selected} clockId="new-daemon" ns={0n} width={100} height={200} />);
  expect(container.querySelector("svg")).toBeNull();
});
