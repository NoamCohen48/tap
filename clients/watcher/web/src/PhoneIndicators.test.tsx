// @vitest-environment jsdom
import { create } from "@bufbuild/protobuf";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { PhoneIndicators } from "./PhoneIndicators";
import { TouchIndicatorsSchema } from "./gen/watch_pb";

const calls = vi.hoisted(() => ({ get: vi.fn(), set: vi.fn() }));
vi.mock("./api", () => ({
  api: { getTouchIndicators: calls.get, setTouchIndicators: calls.set },
  errorText: (error: Error) => error.message,
}));

beforeEach(() => {
  vi.clearAllMocks();
  calls.get.mockResolvedValue({ indicators: create(TouchIndicatorsSchema, { pointerLocation: true }) });
  calls.set.mockResolvedValue({ indicators: create(TouchIndicatorsSchema, { showTaps: true, pointerLocation: true }) });
});
afterEach(cleanup);

function open(container: HTMLElement) {
  const details = container.querySelector("details")!;
  act(() => { details.open = true; fireEvent(details, new Event("toggle")); });
  return details;
}

it("does nothing until opened, reads actual settings, and writes only the chosen switch", async () => {
  const view = render(<PhoneIndicators serial="chosen" />);
  expect(calls.get).not.toHaveBeenCalled();
  expect(calls.set).not.toHaveBeenCalled();
  const details = open(view.container);
  const taps = screen.getByRole("checkbox", { name: /Tap circles/ }) as HTMLInputElement;
  const trails = screen.getByRole("checkbox", { name: /Swipe trails/ }) as HTMLInputElement;
  await waitFor(() => expect(taps.disabled).toBe(false));
  expect(taps.checked).toBe(false);
  expect(trails.checked).toBe(true);
  expect(screen.getByRole("checkbox", { name: /physical finger touches only, not injected test taps/ })).toBe(taps);
  expect(calls.get.mock.calls[0][0]).toEqual({ serial: "chosen" });
  fireEvent.click(taps);
  await waitFor(() => expect(taps.disabled).toBe(false));
  expect(calls.set).toHaveBeenCalledExactlyOnceWith({ serial: "chosen", showTaps: true });
  expect(taps.checked).toBe(true);
  fireEvent.keyDown(details, { key: "Escape" });
  expect(details.open).toBe(false);
  expect(document.activeElement).toBe(details.querySelector("summary"));
  view.unmount();
  expect(calls.set).toHaveBeenCalledTimes(1); // no restore on close
});

it("leaves failures explicit and never retries an uncertain setting change", async () => {
  calls.set.mockRejectedValue(new Error("Connection lost"));
  const { container } = render(<PhoneIndicators serial="device" />);
  open(container);
  const taps = screen.getByRole("checkbox", { name: /Tap circles/ }) as HTMLInputElement;
  await waitFor(() => expect(taps.disabled).toBe(false));
  fireEvent.click(taps);
  await waitFor(() => expect(screen.getByRole("alert").textContent).toContain("Settings may have changed"));
  expect(taps.disabled).toBe(true);
  expect(calls.set).toHaveBeenCalledTimes(1);
  fireEvent.click(screen.getByRole("button", { name: "Reload settings" }));
  await waitFor(() => expect(taps.disabled).toBe(false));
  expect(calls.get).toHaveBeenCalledTimes(2);
  expect(calls.set).toHaveBeenCalledTimes(1);
});

it("does not borrow stale read responses when switching devices", async () => {
  let resolveOld!: (value: ReturnType<typeof create<typeof TouchIndicatorsSchema>>) => void;
  calls.get.mockReturnValueOnce(new Promise((resolve) => { resolveOld = resolve; }).then((indicators) => ({ indicators })));
  const view = render(<PhoneIndicators key="old" serial="old" />);
  open(view.container);
  view.rerender(<PhoneIndicators key="new" serial="new" />);
  open(view.container);
  const taps = screen.getByRole("checkbox", { name: /Tap circles/ }) as HTMLInputElement;
  await waitFor(() => expect(taps.disabled).toBe(false));
  await act(async () => resolveOld(create(TouchIndicatorsSchema, { showTaps: true })));
  expect(taps.checked).toBe(false);
  fireEvent.click(taps);
  await waitFor(() => expect(calls.set).toHaveBeenCalledExactlyOnceWith({ serial: "new", showTaps: true }));
});

it("allows dismissing and reopening a pending write without starting a competing read", async () => {
  let finish!: (value: ReturnType<typeof create<typeof TouchIndicatorsSchema>>) => void;
  calls.set.mockReturnValue(new Promise((resolve) => { finish = resolve; }).then((indicators) => ({ indicators })));
  const { container } = render(<PhoneIndicators serial="device" />);
  const details = open(container);
  const taps = screen.getByRole("checkbox", { name: /Tap circles/ }) as HTMLInputElement;
  await waitFor(() => expect(taps.disabled).toBe(false));
  fireEvent.click(taps);
  fireEvent.keyDown(details, { key: "Escape" });
  open(container);
  expect(calls.get).toHaveBeenCalledTimes(1);
  await act(async () => finish(create(TouchIndicatorsSchema, { showTaps: true })));
  expect(taps.checked).toBe(true);
  expect(taps.disabled).toBe(false);
  expect(calls.set).toHaveBeenCalledTimes(1);
});
