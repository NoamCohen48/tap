import { clone, create, type MessageInitShape } from "@bufbuild/protobuf";
import { Code, ConnectError, createClient, createRouterTransport, type HandlerContext } from "@connectrpc/connect";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";
import { ScreenNodeSchema, DeviceState, SelectorKind, type ScreenNode } from "./gen/device_pb";
import { BoundsSchema } from "./gen/command_pb";
import { NodeFlag, SelectorSchema } from "./gen/selector_pb";
import {
  FramesResponseSchema,
  OutcomeSchema,
  PerformResponseSchema,
  RecordingSchema,
  SessionSchema,
  StepSchema,
  StudioService,
  type PerformRequest,
  type Session,
} from "./gen/studio_pb";

const WIDTH = 1080;
const HEIGHT = 2400;

const resSelector = (name: string) => create(SelectorSchema, { node: { kind: { case: "resource", value: { name, autPackage: true } } } });

function node(ref: string, [left, top, right, bottom]: [number, number, number, number], extra: MessageInitShape<typeof ScreenNodeSchema> = {}): ScreenNode {
  const made = create(ScreenNodeSchema, { ref, windowPackage: "com.example", interactive: true, depth: 1, ...extra });
  made.bounds = create(BoundsSchema, { left, top, right, bottom });
  return made;
}

const SEARCH = node("e2", [100, 200, 980, 320], { className: "android.widget.EditText", resourceName: "com.example:id/search", selector: resSelector("search") });
const BUTTON = node("e3", [100, 400, 500, 520], {
  className: "android.widget.Button",
  text: "Go",
  resourceName: "com.example:id/go",
  flags: [NodeFlag.FLAG_ENABLED, NodeFlag.FLAG_CLICKABLE],
  selector: resSelector("go"),
  candidates: [{ selector: resSelector("go"), kind: SelectorKind.PLAIN }],
});
const GAP = node("e4", [600, 400, 700, 520], { className: "android.view.View" });

/** A fake StudioService: one device, frames on demand, performs recorded in order. */
function fakeStudio() {
  const state = {
    session: create(SessionSchema, { recording: true }) as Session,
    performed: [] as PerformRequest[],
    steps: 0,
    failNext: null as string | null,
  };
  const transport = createRouterTransport(({ service }) => {
    service(StudioService, {
      info: () => ({ recorder: "tap-studio 0.0.1", format: "tap-recording/1" }),
      getSession: () => ({ session: state.session }),
      listDevices: () => ({
        devices: [
          { serial: "emulator-5554", state: DeviceState.DEVICE_FREE },
          { serial: "85e49002", state: DeviceState.DEVICE_LEASED },
        ],
      }),
      attach: (request) => {
        state.session = create(SessionSchema, {
          recording: state.session.recording,
          device: { serial: request.serial, autPackage: request.autPackage, apiLevel: 34, model: "sdk_gphone64" },
        });
        return { session: state.session };
      },
      release: () => {
        state.session = create(SessionSchema, { recording: state.session.recording });
        return { session: state.session };
      },
      async *frames(_request, context: HandlerContext) {
        yield create(FramesResponseSchema, { sequence: 1n, png: new Uint8Array([1]), width: WIDTH, height: HEIGHT, nodes: [SEARCH, BUTTON, GAP] });
        await new Promise((resolve) => context.signal.addEventListener("abort", resolve));
      },
      perform: (request) => {
        state.performed.push(request);
        const step = clone(StepSchema, request.step!);
        if (state.failNext) {
          const message = state.failNext;
          state.failNext = null;
          return create(PerformResponseSchema, { step, recorded: false, message });
        }
        step.id = `s${++state.steps}`;
        step.outcome = create(OutcomeSchema, { durationMs: 42 });
        return create(PerformResponseSchema, { step, recorded: state.session.recording });
      },
      setRecording: (request) => {
        state.session = clone(SessionSchema, state.session);
        state.session.recording = request.recording;
        return { session: state.session };
      },
      newRecording: () => ({ session: state.session }),
      getRecording: () => {
        if (!state.steps) throw new ConnectError("nothing recorded yet", Code.NotFound);
        return { recording: create(RecordingSchema, { autPackage: "com.example" }), document: '{\n  "format": "tap-recording/1"\n}\n' };
      },
    });
  });
  return { state, client: createClient(StudioService, transport) };
}

beforeEach(() => {
  URL.createObjectURL = vi.fn(() => "blob:frame");
  URL.revokeObjectURL = vi.fn();
  // The screen is drawn at device size, so client pixels are device pixels.
  vi.spyOn(HTMLElement.prototype, "getBoundingClientRect").mockReturnValue({
    left: 0,
    top: 0,
    width: WIDTH,
    height: HEIGHT,
    right: WIDTH,
    bottom: HEIGHT,
    x: 0,
    y: 0,
    toJSON: () => ({}),
  });
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

function click(x: number, y: number, init: { altKey?: boolean } = {}) {
  const overlay = screen.getByTestId("overlay");
  fireEvent.pointerDown(overlay, { button: 0, clientX: x, clientY: y, pointerId: 1, ...init });
  fireEvent.pointerUp(overlay, { button: 0, clientX: x, clientY: y, pointerId: 1, ...init });
}

async function attached() {
  const fake = fakeStudio();
  render(<App client={fake.client} />);
  const picker = await screen.findByRole("region", { name: "Attach a device" });
  await within(picker).findByText("emulator-5554");
  expect((within(picker).getByRole("radio", { name: /85e49002/ }) as HTMLInputElement).disabled).toBe(true);
  fireEvent.change(within(picker).getByLabelText(/App under test/), { target: { value: "com.example" } });
  fireEvent.click(within(picker).getByRole("button", { name: "Attach" }));
  await screen.findByRole("img", { name: "The device's screen" });
  return fake;
}

describe("App", () => {
  it("shows which studio it is connected to", async () => {
    render(<App client={fakeStudio().client} />);
    expect(await screen.findByText("tap-studio 0.0.1 · writes tap-recording/1")).toBeTruthy();
  });

  it("says when the studio cannot be reached", async () => {
    const transport = createRouterTransport(({ service }) => {
      service(StudioService, {
        info() {
          throw new ConnectError("down", Code.Unavailable);
        },
        getSession() {
          throw new ConnectError("down", Code.Unavailable);
        },
      });
    });
    render(<App client={createClient(StudioService, transport)} />);
    expect((await screen.findByRole("status")).textContent).toBe("Cannot reach tap-studio: down");
  });

  it("attaches a device and shows its screen with the overlay", async () => {
    const fake = await attached();
    expect(fake.state.session.device?.autPackage).toBe("com.example");
    expect(screen.getByText(/2 interactive|3 interactive/)).toBeTruthy();
    const boxes = screen.getByTestId("overlay").querySelectorAll(".ob");
    expect(boxes).toHaveLength(3);
    expect((boxes[1] as HTMLElement).style.left).toBe(`${(100 / WIDTH) * 100}%`);
    expect(screen.getByTestId("overlay").querySelectorAll(".ob.gap")).toHaveLength(1);
  });

  it("records a tap on the clicked node, with the step in the list", async () => {
    const fake = await attached();
    act(() => click(300, 450));
    await screen.findByText('element(res("go")).tap()');
    expect(fake.state.performed).toHaveLength(1);
    const step = fake.state.performed[0]!.step!;
    expect(step.kind.case === "action" && step.kind.value.command?.op.case).toBe("tap");
    expect(screen.getByText("1 step")).toBeTruthy();
  });

  it("Alt-click long-taps, and a node without a selector is only selected", async () => {
    const fake = await attached();
    act(() => click(300, 450, { altKey: true }));
    await screen.findByText('element(res("go")).longTap()');
    act(() => click(650, 450));
    const inspector = screen.getByRole("region", { name: "Inspector" });
    expect(await within(inspector).findByText("@e4")).toBeTruthy();
    expect(within(inspector).getByText(/No selector finds only this element/)).toBeTruthy();
    expect(screen.getByText("No selector finds only this element: see the Element panel")).toBeTruthy();
    expect(fake.state.performed).toHaveLength(1);
  });

  it("asks for the text of an editable node, and keeps a secret out of the step", async () => {
    const fake = await attached();
    act(() => click(500, 250));
    const dialog = await screen.findByRole("dialog", { name: /Text for/ });
    fireEvent.click(within(dialog).getByLabelText("Secret"));
    fireEvent.change(within(dialog).getByLabelText("Text to enter"), { target: { value: "hunter2" } });
    fireEvent.keyDown(within(dialog).getByLabelText("Text to enter"), { key: "Enter" });
    await screen.findByText('element(res("search")).setText(${secret})');
    const request = fake.state.performed[0]!;
    expect(request.secretValue).toBe("hunter2");
    expect(JSON.stringify(request.step, (_k, v) => (typeof v === "bigint" ? String(v) : v))).not.toContain("hunter2");
  });

  it("records an assertion in assert mode", async () => {
    const fake = await attached();
    fireEvent.keyDown(document, { key: "2" });
    act(() => click(300, 450));
    const dialog = await screen.findByRole("dialog", { name: /Check/ });
    fireEvent.click(within(dialog).getByRole("button", { name: "Text is “Go”" }));
    await screen.findByText('await(res("go")).textEquals("Go")');
    expect(fake.state.performed).toHaveLength(1);
  });

  it("inspect mode and right-click select without running anything", async () => {
    const fake = await attached();
    fireEvent.contextMenu(screen.getByTestId("overlay"), { clientX: 300, clientY: 450 });
    const inspector = screen.getByRole("region", { name: "Inspector" });
    expect(await within(inspector).findByText("@e3")).toBeTruthy();
    expect(within(inspector).getByText('res("go")')).toBeTruthy();
    fireEvent.keyDown(document, { key: "3" });
    act(() => click(500, 250));
    expect(await within(inspector).findByText("@e2")).toBeTruthy();
    expect(fake.state.performed).toHaveLength(0);
  });

  it("a drag swipes the node it started on", async () => {
    const fake = await attached();
    const overlay = screen.getByTestId("overlay");
    act(() => {
      fireEvent.pointerDown(overlay, { button: 0, clientX: 450, clientY: 450, pointerId: 1 });
      fireEvent.pointerUp(overlay, { button: 0, clientX: 120, clientY: 460, pointerId: 1 });
    });
    await screen.findByText('element(res("go")).swipe(LEFT)');
    expect(fake.state.performed).toHaveLength(1);
  });

  it("a failed step is reported and not added", async () => {
    const fake = await attached();
    fake.state.failNext = "Timed out after 10008 ms waiting for res(go)";
    act(() => click(300, 450));
    expect((await screen.findByRole("alert")).textContent).toContain("Not recorded");
    expect(screen.getByText("0 steps")).toBeTruthy();
  });

  it("paused recording runs steps without adding them", async () => {
    const fake = await attached();
    fireEvent.click(screen.getByRole("button", { name: "Recording" }));
    await screen.findByText(/Recording paused/);
    act(() => click(300, 450));
    expect(await screen.findByText(/not recorded\./)).toBeTruthy();
    expect(fake.state.performed).toHaveLength(1);
  });

  it("exports the document the studio writes", async () => {
    await attached();
    act(() => click(300, 450));
    await screen.findByText("1 step");
    fireEvent.click(screen.getByRole("button", { name: "Export" }));
    expect((await screen.findByTestId("recording-json")).textContent).toContain('"format": "tap-recording/1"');
  });

  it("the Back button records a key press", async () => {
    await attached();
    fireEvent.click(screen.getByRole("button", { name: "Back" }));
    await screen.findByText("pressBack()");
  });

  it("releasing the device returns to the picker", async () => {
    await attached();
    fireEvent.click(screen.getByRole("button", { name: "Release the device" }));
    await waitFor(() => expect(screen.getByRole("region", { name: "Attach a device" })).toBeTruthy());
  });
});
