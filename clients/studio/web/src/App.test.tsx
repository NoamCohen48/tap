import { clone, create, fromJsonString, toJsonString, type MessageInitShape } from "@bufbuild/protobuf";
import { Code, ConnectError, createClient, createRouterTransport, type HandlerContext } from "@connectrpc/connect";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";
import { describeSelector } from "./describe";
import { ScreenNodeSchema, DeviceState, SelectorKind, type ScreenNode } from "./gen/device_pb";
import { BoundsSchema, Direction, RangeType, StandardAction } from "./gen/command_pb";
import { NodeFlag, SelectorSchema, TextProperty } from "./gen/selector_pb";
import {
  FramesResponseSchema,
  OutcomeSchema,
  ParameterSchema,
  PerformResponseSchema,
  RecordingSchema,
  SessionSchema,
  StepSchema,
  Check,
  Condition,
  DeviceCheck,
  SelectorOrigin,
  StudioService,
  type AddStepRequest,
  type Parameter,
  type PerformRequest,
  type ReplayRequest,
  type Session,
  type Step,
  type UpdateStepRequest,
} from "./gen/studio_pb";

const WIDTH = 1080;
const HEIGHT = 2400;

const resSelector = (name: string) => create(SelectorSchema, { node: { kind: { case: "resource", value: { name } } } });

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
const LIST = node("e5", [100, 1000, 980, 2000], {
  className: "androidx.recyclerview.widget.RecyclerView",
  resourceName: "com.example:id/list",
  flags: [NodeFlag.FLAG_ENABLED, NodeFlag.FLAG_SCROLLABLE],
  selector: resSelector("list"),
});
const ROW = node("e6", [100, 1100, 980, 1200], {
  className: "android.widget.TextView",
  text: "Row 40",
  interactive: false,
  selector: create(SelectorSchema, { node: { kind: { case: "match", value: { property: TextProperty.PROPERTY_TEXT, value: "Row 40" } } } }),
});

/** A fake StudioService: one device, frames on demand, a recording kept as the studio keeps it. */
function fakeStudio() {
  const state = {
    session: create(SessionSchema, { recording: true }) as Session,
    performed: [] as PerformRequest[],
    steps: [] as Step[],
    missing: [] as string[],
    nextId: 1,
    failNext: null as string | null,
    failAt: null as { stepId: string; message: string } | null,
    updates: [] as UpdateStepRequest[],
    added: [] as AddStepRequest[],
    failUpdate: null as string | null,
    parameters: [] as Parameter[],
    failParameters: null as string | null,
    undone: 0,
    redone: 0,
    replays: [] as ReplayRequest[],
    counted: [] as string[],
    /** While set, each replayed step waits for it (or for the call to be cancelled). */
    replayGate: null as Promise<void> | null,
    /** Sessions `WatchSession` has yet to send; `watch` sends one. */
    watched: [] as Session[],
    wake: (() => undefined) as () => void,
    takeControlError: null as string | null,
  };
  const recording = () => create(RecordingSchema, { steps: state.steps, secrets: secretsOf(state.steps), parameters: state.parameters });
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
      async *watchSession(_request, context: HandlerContext) {
        while (!context.signal.aborted) {
          const next = state.watched.shift();
          if (next) yield { session: next };
          else
            await new Promise<void>((resolve) => {
              state.wake = resolve;
              context.signal.addEventListener("abort", () => resolve());
            });
        }
      },
      takeControl: (request) => {
        if (state.takeControlError) throw new ConnectError(state.takeControlError, Code.FailedPrecondition);
        state.session = clone(SessionSchema, state.session);
        state.session.controllerTab = request.tab;
        state.session.version += 1n;
        return { session: state.session };
      },
      listDevices: () => ({
        devices: [
          { serial: "emulator-5554", state: DeviceState.DEVICE_FREE },
          { serial: "85e49002", state: DeviceState.DEVICE_LEASED },
        ],
      }),
      attach: (request) => {
        state.session = create(SessionSchema, {
          recording: state.session.recording,
          device: { serial: request.serial, apiLevel: 34, model: "sdk_gphone64" },
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
          nodes: [SEARCH, BUTTON, GAP, LIST, ROW],
        });
        await new Promise((resolve) => context.signal.addEventListener("abort", resolve));
      },
      describeElement: (request) =>
        describeSelector(request.selector) === 'res("go")'
          ? { actions: [StandardAction.A11Y_FOCUS], customActions: ["Archive"], range: { type: RangeType.RANGE_INT, min: 0, max: 10, current: 4 } }
          : { actions: [], customActions: [] },
      getDeviceStatus: () => ({
        info: { screenOn: true, displayRotation: 0, autoRotate: true, animationsEnabled: true, fontScale: 1, densityDpi: 420, systemLocales: ["en-US"] },
        foregroundPackage: "com.example",
        foregroundActivity: "com.example.MainActivity",
      }),
      listNotifications: () => ({
        notifications: [{ packageName: "com.example", title: "New message", text: "from Ada", actions: ["Mark as read"], clearable: true }],
      }),
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
        if (!state.session.recording || request.skipRecording) return create(PerformResponseSchema, { step, recorded: false });
        step.id = `s${state.nextId++}`;
        const at = request.beforeStepId ? index(request.beforeStepId) : state.steps.length;
        state.steps.splice(at, 0, step);
        return create(PerformResponseSchema, { step, recorded: true });
      },
      addStep: (request) => {
        state.added.push(request);
        const step = clone(StepSchema, request.step!);
        step.id = `s${state.nextId++}`;
        state.steps.splice(request.beforeStepId ? index(request.beforeStepId) : state.steps.length, 0, step);
        return edited();
      },
      updateStep: (request) => {
        if (state.failUpdate) throw new ConnectError(state.failUpdate, Code.InvalidArgument);
        state.updates.push(request);
        state.steps[index(request.step!.id)] = clone(StepSchema, request.step!);
        return edited();
      },
      setParameters: (request) => {
        if (state.failParameters) throw new ConnectError(state.failParameters, Code.InvalidArgument);
        state.parameters = request.parameters;
        return edited();
      },
      duplicateStep: (request) => {
        const at = index(request.stepId);
        const copy = clone(StepSchema, state.steps[at]!);
        copy.id = `s${state.nextId++}`;
        copy.outcome = undefined;
        state.steps.splice(at + 1, 0, copy);
        return edited();
      },
      undoRecording: () => {
        state.undone += 1;
        state.steps.pop();
        return edited();
      },
      redoRecording: () => {
        state.redone += 1;
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
          const failed = state.failNext ?? (state.failAt?.stepId === step.id ? state.failAt.message : null);
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
        // As the server does: parameters declared before the first step are a recording too.
        if (!state.steps.length && !state.parameters.length) throw new ConnectError("nothing recorded yet", Code.NotFound);
        return { ...edited(), document: toJsonString(RecordingSchema, recording(), { prettySpaces: 2 }) };
      },
    });
  });
  /** The server's session changed: the page hears it on its stream. */
  const watch = (change: Partial<Session>) => {
    state.session = Object.assign(clone(SessionSchema, state.session), { version: state.session.version + 1n }, change);
    state.watched.push(state.session);
    state.wake();
  };
  return { state, watch, client: createClient(StudioService, transport) };
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
  window.localStorage.clear();
  vi.restoreAllMocks();
});

/** A left click on the screen: it selects, and runs nothing. */
function click(x: number, y: number) {
  const overlay = screen.getByTestId("overlay");
  fireEvent.pointerDown(overlay, { button: 0, clientX: x, clientY: y, pointerId: 1 });
  fireEvent.pointerUp(overlay, { button: 0, clientX: x, clientY: y, pointerId: 1 });
}

const composer = () => screen.getByRole("region", { name: "Composer" });
const deviceBar = () => screen.getByRole("toolbar", { name: "Device" });
const tab = (name: string) => within(composer()).getByRole("tab", { name: new RegExp(`^${name}`) });
/** Opens the composer's App tab and returns its controls. */
function appPanel() {
  fireEvent.click(tab("App"));
  return screen.getByRole("group", { name: "App" });
}

/** Selects the node at the point, then presses the composer's button for it. */
async function perform(x: number, y: number, button: string) {
  act(() => click(x, y));
  const control = await within(composer()).findByRole("button", { name: button });
  fireEvent.click(control);
}

/** Attaches emulator-5554 and, unless `app` is null, enters the package in the App tab. */
async function attached(app: string | null = "com.example") {
  const fake = fakeStudio();
  render(<App client={fake.client} />);
  const picker = await screen.findByRole("region", { name: "Attach a device" });
  await within(picker).findByText("emulator-5554");
  expect((within(picker).getByRole("radio", { name: /85e49002/ }) as HTMLInputElement).disabled).toBe(true);
  fireEvent.click(within(picker).getByRole("button", { name: "Attach" }));
  await screen.findByRole("img", { name: "The device's screen" });
  if (app !== null) {
    fireEvent.change(within(appPanel()).getByLabelText("App package"), { target: { value: app } });
    fireEvent.click(tab("Act"));
  }
  return fake;
}

const opOf = (request: PerformRequest | undefined) => {
  const kind = request?.step?.kind;
  return kind?.case === "action" ? kind.value.command?.op : undefined;
};

describe("selector-first checks", () => {
  const typeSelector = (value: string) => fireEvent.change(within(composer()).getByLabelText("Selector"), { target: { value } });

  it("waits for an element that is not on the screen, with nothing selected", async () => {
    const fake = await attached();
    fireEvent.click(tab("Wait"));
    typeSelector('res("later")');
    fireEvent.click(await within(composer()).findByRole("button", { name: "Visible" }));
    await waitFor(() => expect(fake.state.performed).toHaveLength(1));
    const kind = fake.state.performed[0]!.step!.kind;
    expect(kind.case).toBe("wait");
    if (kind.case === "wait") {
      expect(describeSelector(kind.value.selector)).toBe('res("later")');
      expect(kind.value.selectorOrigin).toBe(SelectorOrigin.EDITED);
    }
  });

  it("asserts that nothing matches, and a count of zero is sent as zero", async () => {
    const fake = await attached();
    fireEvent.click(tab("Assert"));
    typeSelector('res("gone")');
    fireEvent.click(await within(composer()).findByRole("button", { name: "None match" }));
    await waitFor(() => expect(fake.state.performed).toHaveLength(1));
    const first = fake.state.performed[0]!.step!.kind;
    expect(first.case === "assertion" && first.value.check).toBe(Check.COUNT);
    expect(first.case === "assertion" && first.value.value).toEqual({ case: "count", value: 0 });
    fireEvent.change(within(composer()).getByLabelText("Count"), { target: { value: "0" } });
    fireEvent.click(within(within(composer()).getByRole("form", { name: "Assert count" })).getByRole("button", { name: "Assert" }));
    await waitFor(() => expect(fake.state.performed).toHaveLength(2));
    const second = fake.state.performed[1]!.step!.kind;
    expect(second.case === "assertion" && second.value.value).toEqual({ case: "count", value: 0 });
  });

  it("refuses a selector that does not parse, and says where", async () => {
    const fake = await attached();
    fireEvent.click(tab("Wait"));
    typeSelector('res("unfinished');
    expect(await within(composer()).findByText(/\(at \d+\)/)).toBeTruthy();
    expect(within(composer()).queryByRole("button", { name: "Visible" })).toBeNull();
    expect(fake.state.performed).toHaveLength(0);
  });

  it("offers checks on a selected element that has left the screen", async () => {
    const fake = await attached();
    act(() => click(300, 450));
    fireEvent.click(tab("Wait"));
    const gone = await within(composer()).findByRole("button", { name: "Gone" });
    expect((gone as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(gone);
    await waitFor(() => expect(fake.state.performed).toHaveLength(1));
  });

  it("adds a check without running it, for a screen that is not here yet", async () => {
    const fake = await attached();
    fireEvent.click(tab("Wait"));
    typeSelector('res("later")');
    fireEvent.click(within(composer()).getByRole("checkbox", { name: "Add to the recording without running it now" }));
    fireEvent.click(within(composer()).getByRole("button", { name: "Visible" }));
    await waitFor(() => expect(fake.state.added).toHaveLength(1));
    expect(fake.state.performed).toHaveLength(0);
    expect(await screen.findByText(/res\("later"\)/, { selector: "code" })).toBeTruthy();
  });

  it("names the format the export really is", async () => {
    await attached();
    expect(screen.getByRole("button", { name: "Open" }).title).toBe("Open a Tap recording file");
  });
});

describe("editing every value of a step", () => {
  /** Opens a recording of one step, selects it and returns its editor. */
  async function editing(step: MessageInitShape<typeof StepSchema>) {
    const fake = await attached();
    fake.state.steps = [create(StepSchema, { id: "s1", ...step })];
    fake.state.nextId = 2;
    await act(async () => fake.watch({ recordingRevision: 9n, canUndo: true, canRedo: true }));
    fireEvent.click(await within(screen.getByRole("region", { name: "Steps" })).findByRole("button", { expanded: false }));
    return { fake, editor: await screen.findByRole("form", { name: "Edit the step" }) };
  }
  const save = async (fake: ReturnType<typeof fakeStudio>, editor: HTMLElement) => {
    fireEvent.click(within(editor).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(fake.state.updates).toHaveLength(1));
    return fake.state.updates[0]!.step!;
  };
  const action = (op: unknown) => ({ kind: { case: "action", value: { command: { op } } } }) as MessageInitShape<typeof StepSchema>;
  const selector = resSelector("x");

  it("edits an app call's own fields, and only those", async () => {
    const { fake, editor } = await editing({ kind: { case: "app", value: { operation: "open_link", packageName: "com.a", uri: "a://b" } } });
    expect(within(editor).queryByLabelText("Permission")).toBeNull();
    fireEvent.change(within(editor).getByLabelText("Uri"), { target: { value: "a://c" } });
    const saved = await save(fake, editor);
    expect(saved.kind.case === "app" && saved.kind.value.uri).toBe("a://c");
  });

  it("edits a launch's extras as rows", async () => {
    const { fake, editor } = await editing({ kind: { case: "app", value: { operation: "launch", packageName: "com.a" } } });
    fireEvent.click(within(editor).getByRole("button", { name: "Add" }));
    fireEvent.change(within(editor).getByLabelText("Key"), { target: { value: "k" } });
    fireEvent.change(within(editor).getByLabelText("Value"), { target: { value: "intValue" } });
    fireEvent.change(within(editor).getByLabelText("Int value"), { target: { value: "7" } });
    const saved = await save(fake, editor);
    const extra = saved.kind.case === "app" ? saved.kind.value.extras[0] : undefined;
    expect(extra?.key).toBe("k");
    expect(extra?.value).toEqual({ case: "intValue", value: 7 });
  });

  it("edits a device condition, with an unset choice for a value it leaves alone", async () => {
    const { fake, editor } = await editing({ kind: { case: "device", value: { operation: "set_network", wifi: true } } });
    fireEvent.change(within(editor).getByLabelText("Wifi"), { target: { value: "false" } });
    fireEvent.change(within(editor).getByLabelText("Mobile data"), { target: { value: "true" } });
    const saved = await save(fake, editor);
    expect(saved.kind.case === "device" && [saved.kind.value.wifi, saved.kind.value.mobileData, saved.kind.value.airplaneMode]).toEqual([false, true, undefined]);
  });

  it("edits what a key press, a fling and a progress hold", async () => {
    const key = await editing(action({ case: "pressKey", value: { keyCode: 4 } }));
    fireEvent.change(within(key.editor).getByLabelText("Key code"), { target: { value: "66" } });
    const saved = await save(key.fake, key.editor);
    const op = saved.kind.case === "action" ? saved.kind.value.command?.op : undefined;
    expect(op?.case === "pressKey" && op.value.keyCode).toBe(66);
    cleanup();
    const progress = await editing(action({ case: "setProgress", value: { selector, value: 2 } }));
    fireEvent.change(within(progress.editor).getByLabelText("Value"), { target: { value: "2.5" } });
    const savedProgress = await save(progress.fake, progress.editor);
    const setProgress = savedProgress.kind.case === "action" ? savedProgress.kind.value.command?.op : undefined;
    expect(setProgress?.case === "setProgress" && setProgress.value.value).toBe(2.5);
    cleanup();
    const fling = await editing(action({ case: "fling", value: { selector, direction: Direction.DIR_UP } }));
    fireEvent.change(within(fling.editor).getByLabelText("Direction"), { target: { value: String(Direction.DIR_DOWN) } });
    const savedFling = await save(fling.fake, fling.editor);
    const flung = savedFling.kind.case === "action" ? savedFling.kind.value.command?.op : undefined;
    expect(flung?.case === "fling" && flung.value.direction).toBe(Direction.DIR_DOWN);
  });

  it("edits a drag's destination as a selector", async () => {
    const { fake, editor } = await editing(action({ case: "drag", value: { selector, target: resSelector("old") } }));
    fireEvent.change(within(editor).getByLabelText("Target"), { target: { value: 'res("new")' } });
    const saved = await save(fake, editor);
    const op = saved.kind.case === "action" ? saved.kind.value.command?.op : undefined;
    expect(op?.case === "drag" && describeSelector(op.value.target)).toBe('res("new")');
  });

  it("edits a notification's match", async () => {
    const { fake, editor } = await editing(action({ case: "dismissNotification", value: { match: { title: "Old" } } }));
    fireEvent.change(within(editor).getByLabelText("Title"), { target: { value: "New" } });
    const saved = await save(fake, editor);
    const op = saved.kind.case === "action" ? saved.kind.value.command?.op : undefined;
    expect(op?.case === "dismissNotification" && op.value.match?.title).toBe("New");
  });

  it("changes a wait's condition, and the value goes with it", async () => {
    const { fake, editor } = await editing({ kind: { case: "wait", value: { selector, condition: Condition.VISIBLE } } });
    fireEvent.change(within(editor).getByLabelText("Condition"), { target: { value: String(Condition.COUNT) } });
    fireEvent.change(within(editor).getByLabelText("Expected count"), { target: { value: "0" } });
    const saved = await save(fake, editor);
    expect(saved.kind.case === "wait" && saved.kind.value.condition).toBe(Condition.COUNT);
    expect(saved.kind.case === "wait" && saved.kind.value.value).toEqual({ case: "count", value: 0 });
  });

  it("changes an assertion's expected text, and a check that takes none drops it", async () => {
    const { fake, editor } = await editing({ kind: { case: "assertion", value: { selector, check: Check.TEXT_EQUALS, value: { case: "text", value: "a" } } } });
    fireEvent.change(within(editor).getByLabelText("Check"), { target: { value: String(Check.EXISTS) } });
    const saved = await save(fake, editor);
    expect(saved.kind.case === "assertion" && saved.kind.value.value.case).toBeUndefined();
  });

  it("edits a device wait and a device check", async () => {
    const wait = await editing({ kind: { case: "deviceWait", value: { command: { op: { case: "awaitToast", value: { text: "Old" } } } } } });
    fireEvent.change(within(wait.editor).getByLabelText("Text"), { target: { value: "New" } });
    const saved = await save(wait.fake, wait.editor);
    const toast = saved.kind.case === "deviceWait" ? saved.kind.value.command?.op : undefined;
    expect(toast?.case === "awaitToast" && toast.value.text).toBe("New");
    cleanup();
    const check = await editing({ kind: { case: "deviceAssertion", value: { check: DeviceCheck.CLIPBOARD_EQUALS, text: "a" } } });
    fireEvent.change(within(check.editor).getByLabelText("Text"), { target: { value: "b" } });
    const savedCheck = await save(check.fake, check.editor);
    expect(savedCheck.kind.case === "deviceAssertion" && savedCheck.kind.value.text).toBe("b");
  });

  it("does not call a step changed just for looking at it", async () => {
    const { editor } = await editing({ kind: { case: "deviceWait", value: { command: { op: { case: "awaitNotification", value: {} } } } } });
    expect((within(editor).getByRole("button", { name: "Save" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("keeps the draft when the studio refuses the save", async () => {
    const { fake, editor } = await editing(action({ case: "pressKey", value: { keyCode: 4 } }));
    fake.state.failUpdate = "keyCode is out of range";
    const input = within(editor).getByLabelText("Key code") as HTMLInputElement;
    fireEvent.change(input, { target: { value: "99999" } });
    fireEvent.click(within(editor).getByRole("button", { name: "Save" }));
    expect((await screen.findByRole("alert")).textContent).toContain("out of range");
    expect(input.value).toBe("99999");
  });

  it("duplicates a step, undoes and redoes the recording", async () => {
    const { fake } = await editing(action({ case: "pressKey", value: { keyCode: 4 } }));
    fireEvent.click(screen.getByRole("button", { name: "Duplicate" }));
    await waitFor(() => expect(fake.state.steps).toHaveLength(2));
    expect(fake.state.steps[1]!.id).not.toBe(fake.state.steps[0]!.id);
    fireEvent.click(screen.getByRole("button", { name: "Undo" }));
    await waitFor(() => expect(fake.state.undone).toBe(1));
    fireEvent.click(screen.getByRole("button", { name: "Redo" }));
    await waitFor(() => expect(fake.state.redone).toBe(1));
    fireEvent.keyDown(document.body, { key: "z", ctrlKey: true });
    await waitFor(() => expect(fake.state.undone).toBe(2));
    fireEvent.keyDown(document.body, { key: "z", ctrlKey: true, shiftKey: true });
    await waitFor(() => expect(fake.state.redone).toBe(2));
  });

  it("leaves Ctrl+Z to a text field", async () => {
    const { fake, editor } = await editing(action({ case: "pressKey", value: { keyCode: 4 } }));
    fireEvent.keyDown(within(editor).getByLabelText("Key code"), { key: "z", ctrlKey: true });
    await act(async () => undefined);
    expect(fake.state.undone).toBe(0);
  });
});

describe("parameters", () => {
  const typed = () => create(StepSchema, { id: "s1", kind: { case: "type", value: { selector: resSelector("x"), input: { case: "text", value: "ada" } } } });

  async function withStep(step = typed(), parameters: { name: string; value: string }[] = [{ name: "user", value: "ada" }]) {
    const fake = await attached();
    fake.state.steps = [step];
    fake.state.parameters = parameters.map((p) => create(ParameterSchema, p));
    fake.state.nextId = 2;
    await act(async () => fake.watch({ recordingRevision: 9n }));
    await screen.findByText("Parameters (" + parameters.length + ")");
    return fake;
  }

  it("keeps parameters declared before the first step when the recording is read again", async () => {
    const fake = await attached();
    fireEvent.click(screen.getByText("Parameters (0)"));
    fireEvent.click(screen.getByRole("button", { name: "Add parameter" }));
    const row = screen.getByRole("group", { name: "Parameter 1" });
    fireEvent.change(within(row).getByLabelText("Name"), { target: { value: "user" } });
    fireEvent.change(within(row).getByLabelText("Value"), { target: { value: "ada" } });
    fireEvent.click(screen.getByRole("button", { name: "Save parameters" }));
    await screen.findByText("Parameters (1)");
    await act(async () => fake.watch({ recordingRevision: fake.state.session.recordingRevision + 1n }));
    await act(async () => undefined);
    expect(screen.getByText("Parameters (1)")).toBeTruthy();
  });

  it("declares a parameter and sends what was typed", async () => {
    const fake = await withStep(typed(), []);
    fireEvent.click(screen.getByText("Parameters (0)"));
    fireEvent.click(screen.getByRole("button", { name: "Add parameter" }));
    const row = screen.getByRole("group", { name: "Parameter 1" });
    fireEvent.change(within(row).getByLabelText("Name"), { target: { value: " user " } });
    fireEvent.change(within(row).getByLabelText("Value"), { target: { value: "ada" } });
    fireEvent.click(screen.getByRole("button", { name: "Save parameters" }));
    await screen.findByText("Parameters (1)");
    expect(fake.state.parameters.map((p) => [p.name, p.value])).toEqual([["user", "ada"]]);
  });

  it("keeps the rows when the studio refuses them", async () => {
    const fake = await withStep();
    fake.state.failParameters = "the step uses the parameter 'user'";
    fireEvent.click(screen.getByRole("button", { name: "Remove parameter 1" }));
    fireEvent.click(screen.getByRole("button", { name: "Save parameters" }));
    expect((await screen.findByRole("alert")).textContent).toContain("user");
    expect(screen.queryByRole("group", { name: "Parameter 1" })).toBeNull();
  });

  it("takes a typed step's text from a parameter, and shows the name", async () => {
    const fake = await withStep();
    fireEvent.click(within(screen.getByRole("region", { name: "Steps" })).getByRole("button", { expanded: false }));
    const editor = await screen.findByRole("form", { name: "Edit the step" });
    fireEvent.change(within(editor).getByLabelText("From a parameter"), { target: { value: "user" } });
    expect(within(editor).queryByRole("checkbox", { name: /Secret/ })).toBeNull();
    fireEvent.click(within(editor).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(fake.state.updates).toHaveLength(1));
    const kind = fake.state.updates[0]!.step!.kind;
    expect(kind.case === "type" && kind.value.input).toEqual({ case: "parameter", value: "user" });
    await screen.findByText('screen.element(res("x")).typeText(${user})');
  });

  it("takes an expected text from a parameter", async () => {
    const fake = await withStep(create(StepSchema, { id: "s1", kind: { case: "assertion", value: { selector: resSelector("x"), check: Check.TEXT_EQUALS, value: { case: "text", value: "a" } } } }));
    fireEvent.click(within(screen.getByRole("region", { name: "Steps" })).getByRole("button", { expanded: false }));
    const editor = await screen.findByRole("form", { name: "Edit the step" });
    fireEvent.change(within(editor).getByLabelText("From a parameter"), { target: { value: "user" } });
    expect(within(editor).queryByLabelText("Expected text")).toBeNull();
    fireEvent.click(within(editor).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(fake.state.updates).toHaveLength(1));
    const kind = fake.state.updates[0]!.step!.kind;
    expect(kind.case === "assertion" && kind.value.value).toEqual({ case: "parameter", value: "user" });
  });
});

describe("not losing work", () => {
  async function dirtyStep() {
    const fake = await attached();
    fake.state.steps = [
      create(StepSchema, { id: "s1", kind: { case: "action", value: { command: { op: { case: "pressKey", value: { keyCode: 4 } } } } } }),
      create(StepSchema, { id: "s2", kind: { case: "action", value: { command: { op: { case: "pressKey", value: { keyCode: 5 } } } } } }),
    ];
    fake.state.nextId = 3;
    await act(async () => fake.watch({ recordingRevision: 9n }));
    const steps = screen.getByRole("region", { name: "Steps" });
    fireEvent.click((await within(steps).findAllByRole("button", { expanded: false }))[0]!);
    const editor = await screen.findByRole("form", { name: "Edit the step" });
    fireEvent.change(within(editor).getByLabelText("Key code"), { target: { value: "66" } });
    return { fake, steps, editor };
  }

  it("asks before leaving a step with unsaved edits, and keeps them on cancel", async () => {
    const { steps, editor } = await dirtyStep();
    fireEvent.click(within(steps).getAllByRole("button", { expanded: false })[0]!);
    const dialog = await screen.findByRole("dialog", { name: "Discard your changes to this step?" });
    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
    expect((within(editor).getByLabelText("Key code") as HTMLInputElement).value).toBe("66");
    fireEvent.click(within(steps).getAllByRole("button", { expanded: false })[0]!);
    fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Discard" }));
    await waitFor(() => expect(within(steps).getAllByRole("button", { expanded: true })[0]!.textContent).toContain("5"));
  });

  it("asks before releasing the device with unsaved edits", async () => {
    const { fake } = await dirtyStep();
    fireEvent.click(screen.getByRole("button", { name: "Release the device" }));
    await screen.findByRole("dialog", { name: "Discard your changes to this step?" });
    expect(fake.state.session.device).toBeDefined();
  });

  it("warns the browser only while there are unsaved edits", async () => {
    const leaving = () => {
      const event = new Event("beforeunload", { cancelable: true });
      window.dispatchEvent(event);
      return event.defaultPrevented;
    };
    await attached();
    expect(leaving()).toBe(false);
    cleanup();
    const { editor } = await dirtyStep();
    await waitFor(() => expect(leaving()).toBe(true));
    fireEvent.click(within(editor).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(leaving()).toBe(false));
  });

  it("confirms Clear data before it runs, and says undo cannot bring it back", async () => {
    const fake = await attached();
    fireEvent.click(within(appPanel()).getByRole("button", { name: "Clear data" }));
    const dialog = await screen.findByRole("dialog", { name: "Clear the data of com.example?" });
    expect(dialog.textContent).toContain("cannot be undone");
    expect(fake.state.performed).toHaveLength(0);
    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(fake.state.performed).toHaveLength(0);
    fireEvent.click(within(screen.getByRole("group", { name: "App" })).getByRole("button", { name: "Clear data" }));
    fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Clear data" }));
    await waitFor(() => expect(fake.state.performed).toHaveLength(1));
    const kind = fake.state.performed[0]!.step!.kind;
    expect(kind.case === "app" && kind.value.operation).toBe("clear_data");
  });
});

describe("dialogs and the keyboard", () => {
  async function confirmDialog() {
    const fake = await attached();
    const opener = within(appPanel()).getByRole("button", { name: "Clear data" });
    opener.focus();
    fireEvent.click(opener);
    return { fake, opener, dialog: await screen.findByRole("dialog") };
  }

  it("moves focus in, keeps it in, makes the page inert, and puts focus back", async () => {
    const { opener, dialog } = await confirmDialog();
    const buttons = within(dialog).getAllByRole("button");
    expect(dialog.contains(document.activeElement)).toBe(true);
    expect(composer().closest("[inert]")).not.toBeNull();
    buttons[buttons.length - 1]!.focus();
    fireEvent.keyDown(document.activeElement!, { key: "Tab" });
    expect(document.activeElement).toBe(buttons[0]);
    fireEvent.keyDown(document.activeElement!, { key: "Tab", shiftKey: true });
    expect(document.activeElement).toBe(buttons[buttons.length - 1]);
    fireEvent.keyDown(document.activeElement!, { key: "Escape" });
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(composer().closest("[inert]")).toBeNull();
    expect(document.activeElement).toBe(opener);
  });

  it("does not steal focus from a field when the page re-renders", async () => {
    const { fake, dialog } = await confirmDialog();
    const cancel = within(dialog).getByRole("button", { name: "Cancel" });
    const clear = within(dialog).getByRole("button", { name: "Clear data" });
    clear.focus();
    await act(async () => fake.watch({ recordingRevision: 3n }));
    expect(document.activeElement).toBe(clear);
    expect(cancel).toBeTruthy();
  });

  it("keeps the page's shortcuts out of a dialog", async () => {
    const { dialog } = await confirmDialog();
    fireEvent.keyDown(within(dialog).getByRole("button", { name: "Cancel" }), { key: "2" });
    fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(tab("App").getAttribute("aria-selected")).toBe("true");
  });

  it("makes the export drawer modal too", async () => {
    const fake = await attached();
    await perform(300, 450, "Tap");
    await waitFor(() => expect(fake.state.steps).toHaveLength(1));
    const opener = screen.getByRole("button", { name: "Export" });
    opener.focus();
    fireEvent.click(opener);
    const drawer = await screen.findByRole("dialog", { name: "Export recording" });
    expect(document.activeElement).toBe(within(drawer).getByRole("button", { name: "Close" }));
    fireEvent.keyDown(document.activeElement!, { key: "Escape" });
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    expect(document.activeElement).toBe(opener);
  });

  it("finishes a drag from the screen tree, by keyboard as by mouse", async () => {
    const fake = await attached();
    await perform(300, 450, "Drag to…");
    await screen.findByRole("region", { name: "Drag" });
    fireEvent.click(within(screen.getByRole("region", { name: "Inspector" })).getByRole("tab", { name: "Screen tree" }));
    const tree = screen.getByRole("list", { name: "Elements on the screen" });
    fireEvent.click(within(tree).getByRole("button", { name: /@e2/ }));
    await waitFor(() => expect(fake.state.performed).toHaveLength(1));
    const op = opOf(fake.state.performed[0]);
    expect(op?.case === "drag" && describeSelector(op.value.target)).toBe('res("search")');
  });
});

describe("control of the studio", () => {
  it("watches while another tab controls, and every change is unavailable", async () => {
    const fake = await attached();
    await perform(300, 450, "Tap");
    await waitFor(() => expect(fake.state.steps).toHaveLength(1));
    await act(async () => fake.watch({ controllerTab: "another-tab" }));
    await screen.findByText("Another tab controls this Studio.", undefined, { timeout: 3000 });
    expect((screen.getByRole("button", { name: "Recording" }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole("button", { name: "Replay" }) as HTMLButtonElement).disabled).toBe(true);
    expect((within(composer()).getByRole("button", { name: "Tap" }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole("button", { name: "Release the device" }) as HTMLButtonElement).disabled).toBe(true);
  });

  it("does not warn about another tab in the moment after a reload", async () => {
    const fake = fakeStudio();
    render(<App client={fake.client} />);
    await screen.findByRole("region", { name: "Attach a device" });
    await act(async () => fake.watch({ controllerTab: "the-page-before-the-reload" }));
    expect(screen.queryByText("Another tab controls this Studio.")).toBeNull();
    await screen.findByText("Another tab controls this Studio.", undefined, { timeout: 3000 });
  });

  it("takes control when asked, and keeps the choice to the server", async () => {
    const fake = await attached();
    await act(async () => fake.watch({ controllerTab: "another-tab", operation: "action" }));
    const take = await screen.findByRole("button", { name: "Take control" }, { timeout: 3000 });
    expect((take as HTMLButtonElement).disabled).toBe(true); // a transfer never interrupts an operation
    await act(async () => fake.watch({ operation: "" }));
    await waitFor(() => expect((take as HTMLButtonElement).disabled).toBe(false));
    fake.state.takeControlError = "a Studio action is running";
    fireEvent.click(take);
    expect((await screen.findByRole("alert")).textContent).toContain("a Studio action is running");
    fake.state.takeControlError = null;
    fireEvent.click(take);
    await waitFor(() => expect(screen.queryByText("Another tab controls this Studio.")).toBeNull());
    expect((screen.getByRole("button", { name: "Recording" }) as HTMLButtonElement).disabled).toBe(false);
  });

  it("keeps the page locked while the server finishes a step whose request is gone", async () => {
    const fake = await attached();
    await perform(300, 450, "Tap");
    await waitFor(() => expect(fake.state.steps).toHaveLength(1));
    await act(async () => fake.watch({ operation: "replay" }));
    await screen.findByText(/Finishing what was already sent/);
    expect((screen.getByRole("button", { name: "Replay" }) as HTMLButtonElement).disabled).toBe(true);
    await act(async () => fake.watch({ operation: "" }));
    await waitFor(() => expect((screen.getByRole("button", { name: "Replay" }) as HTMLButtonElement).disabled).toBe(false));
  });

  it("reads the recording again when the server says it changed", async () => {
    const fake = await attached();
    await perform(300, 450, "Tap");
    await waitFor(() => expect(fake.state.steps).toHaveLength(1));
    fake.state.steps = [];
    await act(async () => fake.watch({ recordingRevision: 5n }));
    await screen.findByText(/Select an element on the screen, then act/);
  });

  it("ignores a response older than the session it already has", async () => {
    const fake = await attached();
    await act(async () => fake.watch({ recording: false }));
    await screen.findByRole("button", { name: "Paused" });
    fake.state.session = Object.assign(clone(SessionSchema, fake.state.session), { recording: true, version: 0n });
    fake.state.watched.push(fake.state.session);
    await act(async () => fake.state.wake());
    await screen.findByRole("button", { name: "Paused" });
  });
});

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
    expect(fake.state.session.device?.serial).toBe("emulator-5554");
    expect(screen.getByText(/4 interactive \/ 5 nodes/)).toBeTruthy();
    const boxes = screen.getByTestId("overlay").querySelectorAll(".ob");
    expect(boxes).toHaveLength(4);
    expect((boxes[1] as HTMLElement).style.left).toBe(`${(100 / WIDTH) * 100}%`);
    expect(screen.getByTestId("overlay").querySelectorAll(".ob.gap")).toHaveLength(1);
  });

  it("a click only selects; the composer's Tap records the tap", async () => {
    const fake = await attached();
    act(() => click(300, 450));
    expect(await within(composer()).findByText("@e3")).toBeTruthy();
    expect(screen.getByTestId("overlay").querySelector(".ob.sel")).toBeTruthy();
    expect(fake.state.performed).toHaveLength(0);
    fireEvent.click(within(composer()).getByRole("button", { name: "Tap" }));
    await screen.findByText('screen.element(res("go")).tap()');
    expect(fake.state.performed).toHaveLength(1);
    expect(opOf(fake.state.performed[0])?.case).toBe("tap");
    expect(screen.getByText("1 step")).toBeTruthy();
  });

  it("long-presses and swipes in all four directions", async () => {
    const fake = await attached();
    await perform(300, 450, "Long press");
    await screen.findByText('screen.element(res("go")).longTap()');
    for (const [name, shown] of [
      ["up", "UP"],
      ["down", "DOWN"],
      ["left", "LEFT"],
      ["right", "RIGHT"],
    ]) {
      fireEvent.click(within(composer()).getByRole("button", { name: `Swipe ${name}` }));
      await screen.findByText(`screen.element(res("go")).swipe(${shown})`);
    }
    expect(fake.state.performed.map((r) => opOf(r)?.case)).toEqual(["longTap", "swipe", "swipe", "swipe", "swipe"]);

    // The distance slider applies to the next gestures, and a non-default one is in the step.
    fireEvent.change(within(composer()).getByLabelText("Distance"), { target: { value: "40" } });
    expect(within(composer()).getByText("40 % of the element")).toBeTruthy();
    fireEvent.click(within(composer()).getByRole("button", { name: "Swipe up" }));
    await screen.findByText('screen.element(res("go")).swipe(UP, distancePercent = 40)');
  });

  it("scrolls only a scrollable element", async () => {
    const fake = await attached();
    act(() => click(300, 450));
    expect(((await within(composer()).findByRole("button", { name: "Scroll down" })) as HTMLButtonElement).disabled).toBe(true);
    expect(within(composer()).getByText(/Not scrollable/)).toBeTruthy();
    expect(within(composer()).queryByRole("button", { name: "Find by scrolling down" })).toBeNull();
    // A row of the list (a right-click reaches it: it is not interactive) leads to the list.
    fireEvent.contextMenu(screen.getByTestId("overlay"), { clientX: 500, clientY: 1150 });
    fireEvent.click(await within(composer()).findByRole("button", { name: "Select the RecyclerView around it" }));
    expect(await within(composer()).findByText("@e5")).toBeTruthy();
    fireEvent.click(within(composer()).getByRole("button", { name: "Scroll down" }));
    await screen.findByText('screen.element(res("list")).scroll(DOWN)');
    expect(fake.state.performed).toHaveLength(1);
  });

  it("scroll until scrolls without recording, then records the element picked inside the container", async () => {
    const fake = await attached();
    await perform(500, 1500, "Find by scrolling up");
    const card = await within(composer()).findByRole("region", { name: "Scroll until" });
    await waitFor(() => expect(card.textContent).toContain('Scrolled res("list") up once.'));
    fireEvent.click(within(card).getByRole("button", { name: /Scroll again/ }));
    await waitFor(() => expect(card.textContent).toContain("up 2 times."));
    expect(fake.state.performed.map((r) => [opOf(r)?.case, r.skipRecording])).toEqual([
      ["scroll", true],
      ["scroll", true],
    ]);
    expect(screen.getByText("0 steps")).toBeTruthy();
    // The device bar and the app panel wait; a click outside the container picks nothing.
    expect((within(deviceBar()).getByRole("button", { name: "Back" }) as HTMLButtonElement).disabled).toBe(true);
    act(() => click(300, 450));
    expect(fake.state.performed).toHaveLength(2);

    act(() => click(500, 1150));
    await screen.findByText('screen.element(res("list")).scrollUntil(text("Row 40"), UP)');
    expect(fake.state.performed[2]!.step?.kind.case).toBe("scrollUntil");
    expect(fake.state.performed[2]!.skipRecording).toBe(false);
    expect(within(composer()).queryByRole("region", { name: "Scroll until" })).toBeNull();
    expect(await within(composer()).findByText("@e6")).toBeTruthy(); // the target is selected next
  });

  it("Escape ends a scroll until search without recording anything", async () => {
    const fake = await attached();
    await perform(500, 1500, "Find by scrolling down");
    const card = await within(composer()).findByRole("region", { name: "Scroll until" });
    await waitFor(() => expect(card.textContent).toContain("once."));
    fireEvent.keyDown(document, { key: "Escape" });
    expect(within(composer()).queryByRole("region", { name: "Scroll until" })).toBeNull();
    expect(fake.state.steps).toHaveLength(0);
  });

  it("a node without a selector is selected, explained, and cannot be acted on", async () => {
    const fake = await attached();
    act(() => click(650, 450));
    expect(await within(composer()).findByText("@e4")).toBeTruthy();
    expect(within(composer()).getByText(/No selector finds only this element/)).toBeTruthy();
    expect(within(composer()).queryByRole("button", { name: "Tap" })).toBeNull();
    expect(fake.state.performed).toHaveLength(0);
  });

  it("sets the text of an editable node, and keeps a secret out of the step", async () => {
    const fake = await attached();
    act(() => click(500, 250));
    const text = await within(composer()).findByLabelText("Text to enter");
    fireEvent.click(within(composer()).getByLabelText("Secret"));
    fireEvent.change(text, { target: { value: "hunter2" } });
    fireEvent.click(within(composer()).getByRole("button", { name: "Set text" }));
    await screen.findByText('screen.element(res("search")).setText(${secret})');
    const request = fake.state.performed[0]!;
    expect(request.secretValue).toBe("hunter2");
    expect(JSON.stringify(request.step, (_k, v) => (typeof v === "bigint" ? String(v) : v))).not.toContain("hunter2");
    fireEvent.click(within(composer()).getByRole("button", { name: "Clear" }));
    await screen.findByText('screen.element(res("search")).clearText()');
  });

  it("types keys with or without waiting for focus", async () => {
    const fake = await attached();
    act(() => click(500, 250));
    fireEvent.change(await within(composer()).findByLabelText("Text to enter"), { target: { value: "wool" } });
    fireEvent.click(within(composer()).getByRole("button", { name: "Type keys" }));
    await screen.findByText('screen.element(res("search")).typeText("wool")');
    fireEvent.click(within(composer()).getByLabelText("Wait for focus"));
    fireEvent.click(within(composer()).getByRole("button", { name: "Type keys" }));
    await screen.findByText('screen.element(res("search")).typeText("wool", awaitFocus = false)');
    expect(fake.state.performed.map((r) => r.step?.kind.case === "type" && r.step.kind.value.skipFocusWait)).toEqual([false, true]);
  });

  it("Assert records a check of the screen as it is now", async () => {
    const fake = await attached();
    fireEvent.keyDown(document, { key: "2" });
    expect(within(composer()).getByRole("tab", { name: /Assert/ }).getAttribute("aria-selected")).toBe("true");
    await perform(300, 450, "Is enabled");
    await screen.findByText('assertTrue(screen.element(res("go")).isEnabled())');
    const form = within(composer()).getByRole("form", { name: "Assert text" });
    fireEvent.click(within(form).getByRole("button", { name: "contains" }));
    fireEvent.change(within(form).getByLabelText("Text"), { target: { value: "G" } });
    fireEvent.click(within(form).getByRole("button", { name: "Assert" }));
    await screen.findByText('assertContains(screen.element(res("go")).text().orEmpty(), "G")');
    expect(fake.state.performed.map((r) => r.step?.kind.case)).toEqual(["assertion", "assertion"]);
  });

  it("does not record an empty contains check, which every text would pass", async () => {
    const fake = await attached();
    fireEvent.keyDown(document, { key: "3" });
    act(() => click(300, 450));
    const form = await within(composer()).findByRole("form", { name: "Wait text" });
    fireEvent.change(within(form).getByLabelText("Text"), { target: { value: "" } });
    const wait = within(form).getByRole("button", { name: "Wait" }) as HTMLButtonElement;
    expect(wait.disabled).toBe(false); // equals nothing is a real check: the text is empty
    fireEvent.click(within(form).getByRole("button", { name: "contains" }));
    expect(wait.disabled).toBe(true);
    fireEvent.change(within(form).getByLabelText("Text"), { target: { value: "G" } });
    expect(wait.disabled).toBe(false);
    expect(fake.state.performed).toHaveLength(0);
  });

  it("Wait records an element wait, prefilling the count it matches now", async () => {
    const fake = await attached();
    fireEvent.keyDown(document, { key: "3" });
    act(() => click(650, 450)); // a label: waits reach every node, but this one has no selector
    await perform(300, 450, "Gone");
    await screen.findByText('screen.await(res("go")).gone()');
    const form = within(composer()).getByRole("form", { name: "Wait count" });
    await waitFor(() => expect((within(form).getByLabelText("Count") as HTMLInputElement).value).toBe("1"));
    fireEvent.click(within(form).getByRole("button", { name: "Wait" }));
    await screen.findByText('screen.await(res("go")).count(1)');
    expect(fake.state.performed.map((r) => r.step?.kind.case)).toEqual(["wait", "wait"]);
  });

  it("the App tab waits on the app, and selecting an element leaves it", async () => {
    const fake = await attached();
    const app = appPanel();
    fireEvent.click(within(app).getByRole("button", { name: "In the foreground" }));
    await screen.findByText('app("com.example").awaitVisible()');
    fireEvent.click(within(app).getByRole("button", { name: "Screen stable" }));
    await screen.findByText('app("com.example").awaitScreenStable()');
    expect(fake.state.performed.map((r) => r.step?.kind.case)).toEqual(["appWait", "appWait"]);
    fireEvent.keyDown(document.body, { key: "4" });
    expect(tab("App").getAttribute("aria-selected")).toBe("true");
    act(() => click(300, 450));
    expect(tab("Act").getAttribute("aria-selected")).toBe("true");
    expect(within(composer()).getByRole("button", { name: "Tap" })).toBeTruthy();
  });

  it("double-taps, pinches, flings, submits, and uses what accessibility offers", async () => {
    const fake = await attached();
    await perform(300, 450, "Double tap");
    await screen.findByText('screen.element(res("go")).doubleTap()');
    fireEvent.click(within(composer()).getByRole("button", { name: "Open" }));
    await screen.findByText('screen.element(res("go")).pinchOpen()');
    fireEvent.click(await within(composer()).findByRole("button", { name: "Archive" }));
    await screen.findByText('screen.element(res("go")).performCustomAction("Archive")');
    fireEvent.click(within(composer()).getByRole("button", { name: "Focus" }));
    await screen.findByText('screen.element(res("go")).performAction(StandardAction.FOCUS)');
    fireEvent.change(within(composer()).getByLabelText("Value"), { target: { value: "7" } });
    fireEvent.click(within(composer()).getByRole("button", { name: "Set" }));
    await screen.findByText('screen.element(res("go")).setProgress(7f)');

    await perform(500, 1500, "Fling down");
    await screen.findByText('screen.element(res("list")).fling(DOWN)');
    await perform(500, 250, "Submit");
    await screen.findByText('screen.element(res("search")).imeAction()');
    expect(fake.state.performed.map((r) => opOf(r)?.case)).toEqual([
      "doubleTap",
      "pinch",
      "performAccessibilityAction",
      "performAccessibilityAction",
      "setProgress",
      "fling",
      "performImeAction",
    ]);
  });

  it("Drag to… records a drag onto the element picked next; Escape cancels it", async () => {
    const fake = await attached();
    await perform(300, 450, "Drag to…");
    expect(await within(composer()).findByRole("region", { name: "Drag" })).toBeTruthy();
    expect(screen.getByTestId("overlay").parentElement!.querySelector(".container-box.source")).toBeTruthy();
    fireEvent.keyDown(document, { key: "Escape" });
    await waitFor(() => expect(within(composer()).queryByRole("region", { name: "Drag" })).toBeNull());
    expect(fake.state.performed).toHaveLength(0);

    fireEvent.click(within(composer()).getByRole("button", { name: "Drag to…" }));
    act(() => click(300, 450)); // itself: refused
    expect(await within(composer()).findByText(/the element being dragged/)).toBeTruthy();
    act(() => click(500, 250));
    await screen.findByText('screen.element(res("go")).dragTo(res("search"))');
    expect(within(composer()).queryByRole("region", { name: "Drag" })).toBeNull();
  });

  it("the Device tab reads the device back and records what is chosen", async () => {
    const fake = await attached();
    fireEvent.keyDown(document.body, { key: "5" });
    const device = screen.getByRole("group", { name: "Device" });
    expect(await within(device).findByText("com.example/com.example.MainActivity")).toBeTruthy();
    const animations = within(device).getByRole("group", { name: "Animations" });
    expect(within(animations).getByRole("button", { name: "On" }).getAttribute("aria-pressed")).toBe("true");
    fireEvent.click(within(animations).getByRole("button", { name: "Off" }));
    await screen.findByText("setAnimations(false)");
    fireEvent.click(within(device).getByRole("button", { name: "Landscape" }));
    await screen.findByText("setOrientation(Orientation.LANDSCAPE)");
    fireEvent.click(within(device).getByRole("button", { name: "Wake" }));
    await screen.findByText("wake()");
    fireEvent.click(await within(device).findByRole("button", { name: "Mark as read" }));
    await screen.findByText('openNotification(title = "New message", packageName = "com.example", action = "Mark as read")');
    fireEvent.click(within(device).getByRole("button", { name: "Dialog shown" }));
    await screen.findByText("awaitPermissionPrompt()");
    fireEvent.click(within(device).getByRole("button", { name: "Choose" }));
    await screen.findByText("choosePermission(PermissionChoice.ALLOW_FOREGROUND_ONLY)");
    const front = within(device).getByRole("form", { name: "Assert the foreground activity" });
    fireEvent.click(within(front).getByRole("button", { name: "Assert" }));
    await screen.findByText('assertEquals(ForegroundActivity("com.example", "com.example.MainActivity"), foregroundActivity())');
    const place = within(device).getByRole("form", { name: "Location" });
    fireEvent.change(within(place).getByLabelText("Latitude"), { target: { value: "51.5" } });
    fireEvent.change(within(place).getByLabelText("Longitude"), { target: { value: "-0.12" } });
    fireEvent.click(within(place).getByRole("button", { name: "Set" }));
    await screen.findByText("setLocation(51.5, -0.12)");
    expect(fake.state.performed.map((r) => r.step?.kind.case)).toEqual([
      "device",
      "action",
      "action",
      "action",
      "deviceWait",
      "action",
      "deviceAssertion",
      "device",
    ]);
    // Picking an element leaves the Device tab.
    act(() => click(300, 450));
    expect(tab("Act").getAttribute("aria-selected")).toBe("true");
  });

  it("the App tab brings the app back, opens links, revokes, sets its languages and launches with extras", async () => {
    const fake = await attached();
    const app = appPanel();
    fireEvent.click(within(app).getByRole("button", { name: "Foreground" }));
    await screen.findByText('app("com.example").foreground()');
    fireEvent.click(within(app).getByRole("button", { name: "Background" }));
    await screen.findByText("pressHome()");
    const link = within(app).getByRole("form", { name: "Open a link" });
    fireEvent.change(within(link).getByLabelText("Link"), { target: { value: "app://item/1" } });
    fireEvent.click(within(link).getByRole("button", { name: "Open" }));
    await screen.findByText('app("com.example").openLink("app://item/1")');
    const permission = within(app).getByRole("form", { name: "Grant or revoke a permission" });
    fireEvent.change(within(permission).getByLabelText("Permission"), { target: { value: "android.permission.CAMERA" } });
    fireEvent.click(within(permission).getByRole("button", { name: "Revoke" }));
    await screen.findByText('app("com.example").revokePermission("android.permission.CAMERA")');
    const languages = within(app).getByRole("form", { name: "App languages" });
    fireEvent.change(within(languages).getByLabelText("Languages"), { target: { value: "fr-FR, en-US" } });
    fireEvent.click(within(languages).getByRole("button", { name: "Set" }));
    await screen.findByText('app("com.example").setLocales(listOf("fr-FR", "en-US"))');

    fireEvent.click(within(app).getByRole("button", { name: "Add extra" }));
    const extra = within(app).getByRole("group", { name: "Extra 1" });
    fireEvent.change(within(extra).getByLabelText("Key"), { target: { value: "count" } });
    fireEvent.change(within(extra).getByLabelText("Type"), { target: { value: "int" } });
    fireEvent.change(within(extra).getByLabelText("Value"), { target: { value: "x" } });
    expect((within(app).getByRole("button", { name: "Launch" }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(within(extra).getByLabelText("Value"), { target: { value: "3" } });
    fireEvent.click(within(app).getByRole("button", { name: "Launch" }));
    await screen.findByText('app("com.example").launch(extras = mapOf("count" to 3))');
    expect(fake.state.performed).toHaveLength(6);
  });

  it("a failed step is reported and not added", async () => {
    const fake = await attached();
    fake.state.failNext = "Timed out after 10008 ms waiting for res(go)";
    await perform(300, 450, "Tap");
    expect((await screen.findByRole("alert")).textContent).toContain("Not recorded");
    expect(screen.getByText("0 steps")).toBeTruthy();
  });

  it("paused recording runs steps without adding them", async () => {
    const fake = await attached();
    fireEvent.click(screen.getByRole("button", { name: "Recording" }));
    await screen.findByText(/Recording paused/);
    await perform(300, 450, "Tap");
    expect(await screen.findByText(/not recorded\./)).toBeTruthy();
    expect(fake.state.performed).toHaveLength(1);
  });

  it("exports the document the studio writes", async () => {
    await attached();
    await perform(300, 450, "Tap");
    await screen.findByText("1 step");
    fireEvent.click(screen.getByRole("button", { name: "Export" }));
    expect((await screen.findByTestId("recording-json")).textContent).toContain('"steps": [');

    // The link is in the document when clicked, and its URL outlives the click: the browser
    // fetches it afterwards, so revoking it at once cancels the download.
    const clicked = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(function (this: HTMLAnchorElement) {
      expect(this.isConnected).toBe(true);
      expect(this.download).toMatch(/^recording-\d{8}-\d{4}\.tap-recording\.json$/);
    });
    vi.useFakeTimers({ toFake: ["setTimeout"] });
    try {
      fireEvent.click(screen.getByRole("button", { name: "Download" }));
      expect(clicked).toHaveBeenCalledOnce();
      expect(URL.revokeObjectURL).not.toHaveBeenCalled();
      vi.runAllTimers();
      expect(URL.revokeObjectURL).toHaveBeenCalledOnce();
    } finally {
      vi.useRealTimers();
    }
  });

  it("the device bar records keys and the system panels without a selection", async () => {
    const fake = await attached();
    const device = deviceBar();
    fireEvent.click(within(device).getByRole("button", { name: "Back" }));
    await screen.findByText("pressBack()");
    fireEvent.click(within(device).getByRole("button", { name: "Notifications" }));
    await screen.findByText("openNotifications()");
    fireEvent.click(within(device).getByRole("button", { name: "Quick settings" }));
    await screen.findByText("openQuickSettings()");
    fireEvent.click(within(device).getByRole("button", { name: "Recent apps" }));
    await screen.findByText("pressKey(187 /* Recent apps */)");
    expect(fake.state.performed.map((r) => opOf(r)?.case)).toEqual(["pressKey", "openSystemPanel", "openSystemPanel", "pressKey"]);
  });

  it("the keys menu presses a named key or any key code, and closes", async () => {
    const fake = await attached();
    const keys = () => within(deviceBar()).getByRole("button", { name: "More keys" });
    fireEvent.click(keys());
    fireEvent.click(within(screen.getByRole("group", { name: "Press a key" })).getByRole("button", { name: /^Enter/ }));
    await screen.findByText("pressKey(66 /* Enter */)");
    expect(screen.queryByRole("group", { name: "Press a key" })).toBeNull();
    fireEvent.click(keys());
    const menu = screen.getByRole("group", { name: "Press a key" });
    expect((within(menu).getByRole("button", { name: "Press" }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.change(within(menu).getByLabelText("Key code"), { target: { value: "300" } });
    fireEvent.click(within(menu).getByRole("button", { name: "Press" }));
    await screen.findByText("pressKey(300)");
    fireEvent.click(keys());
    fireEvent.keyDown(screen.getByLabelText("Key code"), { key: "Escape" });
    expect(screen.queryByRole("group", { name: "Press a key" })).toBeNull();
    expect(fake.state.performed.map((r) => (opOf(r)?.case === "pressKey" ? opOf(r)!.value : null))).toMatchObject([{ keyCode: 66 }, { keyCode: 300 }]);
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
      await perform(300, 450, "Tap");
      await screen.findByText('screen.element(res("go")).tap()');
      fireEvent.click(within(deviceBar()).getByRole("button", { name: "Back" }));
      await screen.findByText("pressBack()");
      return fake;
    }

    it("a candidate picked in the composer is what the next step uses", async () => {
      const fake = await attached();
      fireEvent.contextMenu(screen.getByTestId("overlay"), { clientX: 300, clientY: 450 });
      fireEvent.click(await within(composer()).findByRole("radio", { name: 'text("Go")' }));
      fireEvent.click(within(composer()).getByRole("button", { name: "Tap" }));
      await screen.findByText('screen.element(text("Go")).tap()');
      const step = fake.state.performed[0]!.step!;
      expect(step.kind.case === "action" && step.kind.value.selectorOrigin).toBe(SelectorOrigin.ALTERNATIVE);
    });

    it("new steps go after the selected step", async () => {
      const fake = await recordTwo();
      fireEvent.click(screen.getByRole("button", { name: /element\(res\("go"\)\)\.tap\(\)/ }));
      fireEvent.click(within(deviceBar()).getByRole("button", { name: "Home" }));
      await screen.findByText("pressHome()");
      expect(fake.state.performed[2]!.beforeStepId).toBe("s2");
      expect(listed()).toEqual(['screen.element(res("go")).tap()', "pressHome()", "pressBack()"]);
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
      await screen.findByText('screen.element(res("go").andText("Go")).tap()');
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
      await screen.findByText('screen.element(text("Go")).tap()');
      const saved = fake.state.updates[0]!.step!;
      expect(saved.kind.case === "action" && saved.kind.value.selectorOrigin).toBe(SelectorOrigin.ALTERNATIVE);
    });

    it("steps move and are deleted", async () => {
      await recordTwo();
      fireEvent.click(screen.getByRole("button", { name: /pressBack/ }));
      fireEvent.click(screen.getByRole("button", { name: "Move up" }));
      await waitFor(() => expect(listed()).toEqual(["pressBack()", 'screen.element(res("go")).tap()']));
      fireEvent.click(screen.getByRole("button", { name: "Delete the step" }));
      await waitFor(() => expect(listed()).toEqual(['screen.element(res("go")).tap()']));
      expect(screen.getByText("1 step")).toBeTruthy();
    });

    it("the App tab acts on its package, and nothing before there is one", async () => {
      const fake = await attached(null);
      const group = appPanel();
      expect((within(group).getByRole("button", { name: "Force stop" }) as HTMLButtonElement).disabled).toBe(true);
      expect((within(group).getByRole("button", { name: "Grant" }) as HTMLButtonElement).disabled).toBe(true);
      fireEvent.change(within(group).getByLabelText("App package"), { target: { value: " com.example.shop " } });
      fireEvent.click(within(group).getByRole("button", { name: "Force stop" }));
      await waitFor(() => expect(fake.state.performed).toHaveLength(1));
      const step = fake.state.performed[0]!.step!;
      expect(step.kind.case === "app" && [step.kind.value.operation, step.kind.value.packageName]).toEqual(["force_stop", "com.example.shop"]);
      expect(window.localStorage.getItem("tap-studio.package")).toBe("com.example.shop");
    });

    it("the App tab launches an activity when one is named", async () => {
      const fake = await attached();
      const group = appPanel();
      fireEvent.change(within(group).getByLabelText("Activity"), { target: { value: " .ui.SettingsActivity " } });
      fireEvent.click(within(group).getByRole("button", { name: "Launch" }));
      await screen.findByText('app("com.example").launch(".ui.SettingsActivity")');
      const step = fake.state.performed[0]!.step!;
      expect(step.kind.case === "app" && step.kind.value.activity).toBe(".ui.SettingsActivity");
    });

    it("a swipe's direction and distance are edited without running it", async () => {
      const fake = await attached();
      await perform(300, 450, "Swipe up");
      await screen.findByText('screen.element(res("go")).swipe(UP)');
      fireEvent.click(screen.getByRole("button", { name: /swipe\(UP\)/ }));
      const editor = screen.getByRole("form", { name: "Edit the step" });
      fireEvent.change(within(editor).getByLabelText("Direction"), { target: { value: String(Direction.DIR_LEFT) } });
      const distance = within(editor).getByLabelText("Distance (%)");
      fireEvent.change(distance, { target: { value: "120" } });
      expect(within(editor).getByText("The distance is 1 to 100 percent.")).toBeTruthy();
      expect((within(editor).getByRole("button", { name: "Save" }) as HTMLButtonElement).disabled).toBe(true);
      fireEvent.change(distance, { target: { value: "50" } });
      fireEvent.click(within(editor).getByRole("button", { name: "Save" }));
      await screen.findByText('screen.element(res("go")).swipe(LEFT, distancePercent = 50)');
      expect(fake.state.performed).toHaveLength(1);
    });

    it("a replay without an app chosen does not offer a cold launch", async () => {
      const fake = await attached(null);
      await perform(300, 450, "Tap");
      await screen.findByText('screen.element(res("go")).tap()');
      fireEvent.click(screen.getByRole("button", { name: "Replay" }));
      await waitFor(() => expect(fake.state.replays).toHaveLength(1));
      expect(screen.queryByRole("dialog")).toBeNull();
    });

    it("a replay offers a cold launch first and shows how it went", async () => {
      const fake = await recordTwo();
      fireEvent.click(screen.getByRole("button", { name: "Replay" }));
      const dialog = await screen.findByRole("dialog", { name: "Start from a cold launch?" });
      fireEvent.click(within(dialog).getByRole("button", { name: "Cold launch first" }));
      // The cold launch runs before the stream and counts: three steps ran.
      expect(await screen.findByText("All 3 steps passed.")).toBeTruthy();
      const launch = fake.state.performed[2]!;
      expect(launch.step?.kind.case === "app" && launch.step.kind.value.operation).toBe("cold_launch");
      expect(launch.beforeStepId).toBe("s1");
      expect(fake.state.replays[0]!.fromStepId).toBe("s1"); // the launch is not run twice
      expect(listed()[0]).toBe('app("com.example").coldLaunch()');
      expect(screen.getAllByText("✓ 5 ms")).toHaveLength(2);
    });

    it("asks in its own dialog before New or Open replaces the steps", async () => {
      const fake = await recordTwo();
      fireEvent.click(screen.getByRole("button", { name: "New" }));
      let dialog = await screen.findByRole("dialog", { name: "Start a new recording?" });
      fireEvent.click(within(dialog).getByRole("button", { name: "Cancel" }));
      expect(listed()).toHaveLength(2);

      const opened = create(RecordingSchema, {
        format: "tap-recording/1",
        steps: [{ id: "a1", kind: { case: "app", value: { operation: "cold_launch", packageName: "com.example" } } }],
      });
      const file = new File([toJsonString(RecordingSchema, opened)], "flow.tap-recording.json", { type: "application/json" });
      fireEvent.change(screen.getByLabelText("Recording file"), { target: { files: [file] } });
      dialog = await screen.findByRole("dialog", { name: "Open flow.tap-recording.json?" });
      expect(dialog.textContent).toContain("It replaces the 2 recorded steps.");
      fireEvent.click(within(dialog).getByRole("button", { name: "Replace" }));
      await waitFor(() => expect(listed()).toEqual(['app("com.example").coldLaunch()']));

      fireEvent.click(screen.getByRole("button", { name: "New" }));
      dialog = await screen.findByRole("dialog", { name: "Start a new recording?" });
      fireEvent.click(within(dialog).getByRole("button", { name: "Discard and start new" }));
      await waitFor(() => expect(screen.getByText("0 steps")).toBeTruthy());
      expect(fake.state.steps).toHaveLength(0);
    });

    it("names a failed step by its place after a prepended cold launch", async () => {
      const fake = await recordTwo();
      fake.state.failAt = { stepId: "s2", message: "no key" };
      fireEvent.click(screen.getByRole("button", { name: "Replay" }));
      fireEvent.click(within(await screen.findByRole("dialog")).getByRole("button", { name: "Cold launch first" }));
      // The launch is step 1 now, so pressBack() is step 3.
      expect((await screen.findByRole("alert")).textContent).toBe("Step 3 failed. no key");
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
        secrets: ["pin"],
        steps: [
          { id: "a1", kind: { case: "app", value: { operation: "cold_launch", packageName: "com.example" } } },
          { id: "a2", kind: { case: "type", value: { selector: resSelector("search"), input: { case: "secret", value: "pin" } } } },
        ],
      });
      const file = new File([toJsonString(RecordingSchema, opened)], "flow.tap-recording.json", { type: "application/json" });
      fireEvent.change(screen.getByLabelText("Recording file"), { target: { files: [file] } });
      await screen.findByText('screen.element(res("search")).typeText(${pin})');
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
