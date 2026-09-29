import { clone, create, fromJsonString, toJsonString, type MessageInitShape } from "@bufbuild/protobuf";
import { Code, ConnectError, createClient, createRouterTransport, type HandlerContext } from "@connectrpc/connect";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";
import { describeSelector } from "./describe";
import { ScreenNodeSchema, DeviceState, SelectorKind, type ScreenNode } from "./gen/device_pb";
import { BoundsSchema } from "./gen/command_pb";
import { NodeFlag, SelectorSchema, TextProperty } from "./gen/selector_pb";
import {
  FramesResponseSchema,
  OutcomeSchema,
  PerformResponseSchema,
  RecordingSchema,
  SessionSchema,
  StepSchema,
  SelectorOrigin,
  StudioService,
  type PerformRequest,
  type ReplayRequest,
  type Session,
  type Step,
  type UpdateStepRequest,
} from "./gen/studio_pb";

const WIDTH = 1080;
const HEIGHT = 2400;

const resSelector = (name: string) => create(SelectorSchema, { node: { kind: { case: "resource", value: { name, autPackage: true } } } });

function node(
  ref: string,
  [left, top, right, bottom]: [number, number, number, number],
  extra: MessageInitShape<typeof ScreenNodeSchema> = {},
): ScreenNode {
  const made = create(ScreenNodeSchema, { ref, windowPackage: "com.example", interactive: true, depth: 1, ...extra });
  made.bounds = create(BoundsSchema, { left, top, right, bottom });
  return made;
}

const SEARCH = node("e2", [100, 200, 980, 320], {
  className: "android.widget.EditText",
  resourceName: "com.example:id/search",
  selector: resSelector("search"),
});
const BUTTON = node("e3", [100, 400, 500, 520], {
  className: "android.widget.Button",
  text: "Go",
  resourceName: "com.example:id/go",
  flags: [NodeFlag.FLAG_ENABLED, NodeFlag.FLAG_CLICKABLE],
  selector: resSelector("go"),
  candidates: [
    { selector: resSelector("go"), kind: SelectorKind.PLAIN },
    {
      selector: create(SelectorSchema, { node: { kind: { case: "match", value: { property: TextProperty.PROPERTY_TEXT, value: "Go" } } } }),
      kind: SelectorKind.PLAIN,
    },
  ],
});
const GAP = node("e4", [600, 400, 700, 520], { className: "android.view.View" });

/** A fake StudioService: one device, frames on demand, a recording kept as the studio keeps it. */
function fakeStudio() {
  const state = {
    session: create(SessionSchema, { recording: true }) as Session,
    performed: [] as PerformRequest[],
    steps: [] as Step[],
    missing: [] as string[],
    nextId: 1,
    failNext: null as string | null,
    updates: [] as UpdateStepRequest[],
    replays: [] as ReplayRequest[],
    counted: [] as string[],
    /** While set, each replayed step waits for it (or for the call to be cancelled). */
    replayGate: null as Promise<void> | null,
  };
  const recording = () => create(RecordingSchema, { autPackage: "com.example", steps: state.steps, secrets: secretsOf(state.steps) });
  const edited = () => ({ recording: recording(), missingSecrets: state.missing.filter((name) => secretsOf(state.steps).includes(name)) });
  const index = (stepId: string) => {
    const at = state.steps.findIndex((s) => s.id === stepId);
    if (at < 0) throw new ConnectError(`no step ${stepId}`, Code.NotFound);
    return at;
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
        yield create(FramesResponseSchema, {
          sequence: 1n,
          png: new Uint8Array([1]),
          width: WIDTH,
          height: HEIGHT,
          nodes: [SEARCH, BUTTON, GAP],
        });
        await new Promise((resolve) => context.signal.addEventListener("abort", resolve));
      },
      count: (request) => {
        state.counted.push(describeSelector(request.selector));
        return { count: describeSelector(request.selector).startsWith("className") ? 3 : 1 };
      },
      perform: (request) => {
        state.performed.push(request);
        const step = clone(StepSchema, request.step!);
        if (state.failNext) {
          const message = state.failNext;
          state.failNext = null;
          return create(PerformResponseSchema, { step, recorded: false, message });
        }
        step.outcome = create(OutcomeSchema, { durationMs: 42 });
        if (!state.session.recording) return create(PerformResponseSchema, { step, recorded: false });
        step.id = `s${state.nextId++}`;
        const at = request.beforeStepId ? index(request.beforeStepId) : state.steps.length;
        state.steps.splice(at, 0, step);
        return create(PerformResponseSchema, { step, recorded: true });
      },
      updateStep: (request) => {
        state.updates.push(request);
        state.steps[index(request.step!.id)] = clone(StepSchema, request.step!);
        return edited();
      },
      deleteStep: (request) => {
        state.steps.splice(index(request.stepId), 1);
        return edited();
      },
      moveStep: (request) => {
        const [moved] = state.steps.splice(index(request.stepId), 1);
        state.steps.splice(request.beforeStepId ? index(request.beforeStepId) : state.steps.length, 0, moved!);
        return edited();
      },
      openRecording: (request) => {
        const opened = fromJsonString(RecordingSchema, request.document, { ignoreUnknownFields: true });
        state.steps = opened.steps;
        state.missing = [...opened.secrets];
        state.session = create(SessionSchema, {
          recording: state.session.recording,
          device: state.session.device,
          steps: opened.steps.length,
        });
        return { session: state.session, ...edited() };
      },
      async *replay(request, context: HandlerContext) {
        state.replays.push(request);
        for (const name of Object.keys(request.secretValues)) state.missing = state.missing.filter((m) => m !== name);
        const from = request.fromStepId ? index(request.fromStepId) : 0;
        for (const step of state.steps.slice(from, request.only ? from + 1 : undefined)) {
          yield { stepId: step.id };
          if (state.replayGate) {
            await Promise.race([state.replayGate, new Promise((resolve) => context.signal.addEventListener("abort", resolve))]);
            if (context.signal.aborted) return;
          }
          const failed = state.failNext;
          state.failNext = null;
          const outcome = create(OutcomeSchema, failed ? { durationMs: 7, error: { message: failed } } : { durationMs: 5 });
          step.outcome = outcome;
          yield { stepId: step.id, outcome, message: failed ?? "" };
          if (failed) return;
        }
      },
      setRecording: (request) => {
        state.session = clone(SessionSchema, state.session);
        state.session.recording = request.recording;
        return { session: state.session };
      },
      newRecording: () => {
        state.steps = [];
        return { session: state.session };
      },
      getRecording: () => {
        if (!state.steps.length) throw new ConnectError("nothing recorded yet", Code.NotFound);
        return { ...edited(), document: toJsonString(RecordingSchema, recording(), { prettySpaces: 2 }) };
      },
    });
  });
  return { state, client: createClient(StudioService, transport) };
}

function secretsOf(steps: readonly Step[]): string[] {
  return [
    ...new Set(
      steps.flatMap((s) =>
        s.kind.case === "action" && s.kind.value.secret
          ? [s.kind.value.secret]
          : s.kind.case === "type" && s.kind.value.input.case === "secret"
            ? [s.kind.value.input.value]
            : [],
      ),
    ),
  ];
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
    expect((await screen.findByTestId("recording-json")).textContent).toContain('"autPackage": "com.example"');
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

  describe("editing and replaying", () => {
    const listed = () =>
      within(screen.getByRole("region", { name: "Steps" }))
        .getAllByRole("listitem")
        .map((item) => item.querySelector(".step-main code")?.textContent);

    async function recordTwo() {
      const fake = await attached();
      act(() => click(300, 450));
      await screen.findByText('element(res("go")).tap()');
      fireEvent.click(screen.getByRole("button", { name: "Back" }));
      await screen.findByText("pressBack()");
      return fake;
    }

    it("a candidate picked in the inspector is what the next step uses", async () => {
      const fake = await attached();
      fireEvent.contextMenu(screen.getByTestId("overlay"), { clientX: 300, clientY: 450 });
      const inspector = screen.getByRole("region", { name: "Inspector" });
      fireEvent.click(within(inspector).getByRole("radio", { name: 'text("Go")' }));
      act(() => click(300, 450));
      await screen.findByText('element(text("Go")).tap()');
      const step = fake.state.performed[0]!.step!;
      expect(step.kind.case === "action" && step.kind.value.selectorOrigin).toBe(SelectorOrigin.ALTERNATIVE);
    });

    it("new steps go after the selected step", async () => {
      const fake = await recordTwo();
      fireEvent.click(screen.getByRole("button", { name: /element\(res\("go"\)\)\.tap\(\)/ }));
      fireEvent.click(screen.getByRole("button", { name: "Home" }));
      await screen.findByText("pressHome()");
      expect(fake.state.performed[2]!.beforeStepId).toBe("s2");
      expect(listed()).toEqual(['element(res("go")).tap()', "pressHome()", "pressBack()"]);
      expect(screen.getByText(/New steps go after step 2/)).toBeTruthy();
      fireEvent.click(screen.getByRole("button", { name: "Add at the end" }));
      expect(screen.queryByText(/New steps go after/)).toBeNull();
    });

    it("a typed selector is counted live and saved without running the step", async () => {
      const fake = await recordTwo();
      fireEvent.click(screen.getByRole("button", { name: /element\(res\("go"\)\)\.tap\(\)/ }));
      const editor = screen.getByRole("form", { name: "Edit the step" });
      const input = within(editor).getByLabelText("Or type one");
      fireEvent.change(input, { target: { value: 'res("go"' } });
      expect(within(editor).getByText(/“,” or “\)” was expected/)).toBeTruthy();
      fireEvent.change(input, { target: { value: 'className("android.widget.Button")' } });
      expect(await within(editor).findByText(/Matches 3 elements now/, {}, { timeout: 2000 })).toBeTruthy();
      fireEvent.change(input, { target: { value: 'res("go").andText("Go")' } });
      expect(await within(editor).findByText("Matches 1 element now.", {}, { timeout: 2000 })).toBeTruthy();
      fireEvent.change(within(editor).getByLabelText("Note"), { target: { value: "the search button" } });
      fireEvent.click(within(editor).getByRole("button", { name: "Save" }));
      await screen.findByText('element(res("go").andText("Go")).tap()');
      const saved = fake.state.updates[0]!.step!;
      expect(saved.note).toBe("the search button");
      expect(saved.kind.case === "action" && saved.kind.value.selectorOrigin).toBe(SelectorOrigin.EDITED);
      expect(fake.state.performed).toHaveLength(2); // saving ran nothing
      expect(screen.getByText("typed selector")).toBeTruthy();
    });

    it("another candidate of the step's element can be picked", async () => {
      const fake = await recordTwo();
      fireEvent.click(screen.getByRole("button", { name: /element\(res\("go"\)\)\.tap\(\)/ }));
      const editor = screen.getByRole("form", { name: "Edit the step" });
      fireEvent.click(within(editor).getByRole("radio", { name: 'text("Go")' }));
      fireEvent.click(within(editor).getByRole("button", { name: "Save" }));
      await screen.findByText('element(text("Go")).tap()');
      const saved = fake.state.updates[0]!.step!;
      expect(saved.kind.case === "action" && saved.kind.value.selectorOrigin).toBe(SelectorOrigin.ALTERNATIVE);
    });

    it("steps move and are deleted", async () => {
      await recordTwo();
      fireEvent.click(screen.getByRole("button", { name: /pressBack/ }));
      fireEvent.click(screen.getByRole("button", { name: "Move up" }));
      await waitFor(() => expect(listed()).toEqual(["pressBack()", 'element(res("go")).tap()']));
      fireEvent.click(screen.getByRole("button", { name: "Delete the step" }));
      await waitFor(() => expect(listed()).toEqual(['element(res("go")).tap()']));
      expect(screen.getByText("1 step")).toBeTruthy();
    });

    it("a replay offers a cold launch first and shows how it went", async () => {
      const fake = await recordTwo();
      fireEvent.click(screen.getByRole("button", { name: "Replay" }));
      const dialog = await screen.findByRole("dialog", { name: "Start from a cold launch?" });
      fireEvent.click(within(dialog).getByRole("button", { name: "Cold launch first" }));
      expect(await screen.findByText("All 2 steps passed.")).toBeTruthy();
      const launch = fake.state.performed[2]!;
      expect(launch.step?.kind.case === "app" && launch.step.kind.value.operation).toBe("cold_launch");
      expect(launch.beforeStepId).toBe("s1");
      expect(fake.state.replays[0]!.fromStepId).toBe("s1"); // the launch is not run twice
      expect(listed()[0]).toBe('app("com.example").coldLaunch()');
      expect(screen.getAllByText("✓ 5 ms")).toHaveLength(2);
    });

    it("a replay stops at the first failure, and Stop ends it", async () => {
      const fake = await recordTwo();
      fake.state.failNext = "no node matches res(go)";
      fireEvent.click(screen.getByRole("button", { name: "Replay" }));
      fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Replay as it is" }));
      expect((await screen.findByRole("alert")).textContent).toBe("Step 1 failed. no node matches res(go)");
      expect(screen.getByText("✗ failed")).toBeTruthy();

      fake.state.replayGate = new Promise(() => undefined);
      fireEvent.click(screen.getByRole("button", { name: /pressBack/ }));
      fireEvent.click(screen.getByRole("button", { name: "Run from here" }));
      await screen.findByText("running…");
      expect(fake.state.replays[1]).toMatchObject({ fromStepId: "s2", only: false });
      fireEvent.click(screen.getByRole("button", { name: "Stop" }));
      expect(await screen.findByText("Stopped after 0 steps.")).toBeTruthy();
      expect(screen.queryByText("running…")).toBeNull();
    });

    it("an opened recording asks for its secret values before replaying", async () => {
      const fake = await attached();
      const opened = create(RecordingSchema, {
        format: "tap-recording/1",
        autPackage: "com.example",
        secrets: ["pin"],
        steps: [
          { id: "a1", kind: { case: "app", value: { operation: "cold_launch", packageName: "com.example" } } },
          { id: "a2", kind: { case: "type", value: { selector: resSelector("search"), input: { case: "secret", value: "pin" } } } },
        ],
      });
      const file = new File([toJsonString(RecordingSchema, opened)], "flow.tap-recording.json", { type: "application/json" });
      fireEvent.change(screen.getByLabelText("Recording file"), { target: { files: [file] } });
      await screen.findByText('element(res("search")).typeText(${pin})');
      expect(screen.getByText("${pin} needs a value")).toBeTruthy();
      fireEvent.click(screen.getByRole("button", { name: "Replay" }));
      const dialog = await screen.findByRole("dialog", { name: "Values for the secrets" });
      fireEvent.change(within(dialog).getByLabelText("${pin}"), { target: { value: "1234" } });
      fireEvent.click(within(dialog).getByRole("button", { name: "Replay" }));
      expect(await screen.findByText("All 2 steps passed.")).toBeTruthy();
      expect(fake.state.replays[0]!.secretValues).toEqual({ pin: "1234" });
      expect(screen.queryByText("${pin} needs a value")).toBeNull();
    });
  });
});
