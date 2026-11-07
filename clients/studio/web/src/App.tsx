import { clone } from "@bufbuild/protobuf";
import { timestampDate } from "@bufbuild/protobuf/wkt";
import { Code, ConnectError } from "@connectrpc/connect";
import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { TAB_ID, type StudioClient } from "./api";
import { describeStep } from "./describe";
import { DevicePicker } from "./DevicePicker";
import { useAppPackage } from "./appPackage";
import { errorMessage, useFrames } from "./frames";
import type { ScreenNode } from "./gen/device_pb";
import { ChoiceDialog, SecretsDialog } from "./Dialogs";
import { nodeFor, stepSelector, stepText } from "./edit";
import {
  PerformRequestSchema,
  SelectorOrigin,
  RunStatus,
  StepSchema,
  type InfoResponse,
  type Outcome,
  type Parameter,
  type PerformRequest,
  type Recording,
  type RunResult,
  type Session,
  type Step,
} from "./gen/studio_pb";
import { Composer, TABS, type DragFrom, type Seek, type Tab } from "./Composer";
import { DeviceBar } from "./DeviceBar";
import type { Direction } from "./gen/command_pb";
import { Eject, Logo } from "./icons";
import { Inspector } from "./Inspector";
import { ScreenView, type OverlayFilter } from "./ScreenView";
import { useReplay, type ReplayRequest } from "./replay";
import * as stepsApi from "./steps";
import { synthesized, type Target } from "./steps";
import { ExportDrawer, StepsPanel, type LastRun } from "./StepsPanel";
import { Library } from "./Library";
import { ExecutionControls, requestBudgets, type Budgets } from "./ExecutionControls";
import { Tools } from "./Tools";

type Status = { kind: "loading" } | { kind: "ready"; info: InfoResponse } | { kind: "failed"; message: string };

export function App({ client }: { client: StudioClient }) {
  const [status, setStatus] = useState<Status>({ kind: "loading" });
  const [session, setSessionState] = useState<Session | null>(null);
  // A response can arrive after a newer watched session; the version decides.
  const setSession = useCallback((next: Session) => setSessionState((now) => (now && next.version < now.version ? now : next)), []);

  useEffect(() => {
    const controller = new AbortController();
    void (async () => {
      while (!controller.signal.aborted) {
        try {
          for await (const r of client.watchSession({ tab: TAB_ID }, { signal: controller.signal })) if (r.session) setSession(r.session);
        } catch {
          // Reported by Info; the next attempt shows when the studio is back.
        }
        if (!controller.signal.aborted) await new Promise((resolve) => setTimeout(resolve, 1000));
      }
    })();
    return () => controller.abort();
  }, [client, setSession]);

  useEffect(() => {
    let cancelled = false;
    client.info({}).then(
      (info) => !cancelled && setStatus({ kind: "ready", info }),
      (error: unknown) => !cancelled && setStatus({ kind: "failed", message: errorMessage(error) }),
    );
    client.getSession({}).then(
      (r) => {
        if (!cancelled && r.session) setSession(r.session);
      },
      () => undefined, // reported by Info
    );
    return () => {
      cancelled = true;
    };
  }, [client, setSession]);

  const observing = !!session && isObserver(session);
  return (
    <div className="app">
      {session && <ControlBanner client={client} session={session} onSession={setSession} />}
      {session?.device ? (
        <Workspace client={client} session={session} onSession={setSession} status={status} />
      ) : (
        <>
          <TopBar status={status} />
          {session && (
            <main className="start">
              <DevicePicker client={client} onSession={setSession} disabled={observing} />
              <Offline client={client} session={session} onSession={setSession} disabled={observing} />
            </main>
          )}
        </>
      )}
    </div>
  );
}

/** Another tab controls this Studio: this one reads, and every change would be refused. */
const isObserver = (session: Session) => session.controllerTab !== "" && session.controllerTab !== TAB_ID;

/** Longer than the session stream's heartbeat (one second), which is how the server learns a page has gone. */
const RELOAD_GRACE_MS = 1500;

function ControlBanner({ client, session, onSession }: { client: StudioClient; session: Session; onSession: (session: Session) => void }) {
  const [error, setError] = useState<string | null>(null);
  // Just after a reload the server still counts the page that went away as the controlling tab,
  // until its next heartbeat. No other tab exists to warn about, so give that a moment.
  const [settled, setSettled] = useState(false);
  useEffect(() => {
    const timer = setTimeout(() => setSettled(true), RELOAD_GRACE_MS);
    return () => clearTimeout(timer);
  }, []);
  if (!isObserver(session) || !settled) return null;
  return (
    <div className="control-banner" role="status">
      <span>
        <b>Another tab controls this Studio.</b> This tab only watches. Taking control keeps what you typed here.
      </span>
      <button
        type="button"
        className="btn small"
        disabled={session.operation !== ""}
        title={session.operation ? "Available when the running operation has finished" : undefined}
        onClick={() => {
          setError(null);
          client.takeControl({ tab: TAB_ID }).then(
            (r) => r.session && onSession(r.session),
            (e: unknown) => setError(errorMessage(e)),
          );
        }}
      >
        Take control
      </button>
      {error && <span role="alert">{error}</span>}
    </div>
  );
}

/** Why a run's failed step failed. */
function failureIn(run: RunResult): string {
  const step = run.steps.find((s) => s.stepId === run.failedStepId);
  const outcome = step?.outcome;
  return step?.message || outcome?.mismatch || outcome?.error?.message || outcome?.failure?.detail || "It did not pass.";
}

/** With no device: the saved recordings, and the recording opened from it to read and export. */
function Offline({ client, session, onSession, disabled }: { client: StudioClient; session: Session; onSession: (session: Session) => void; disabled: boolean }) {
  const [steps, setSteps] = useState<Step[]>([]);
  const [exporting, setExporting] = useState(false);
  const { recordingRevision } = session;
  useEffect(() => {
    let cancelled = false;
    client.getRecording({}).then(
      (r) => !cancelled && setSteps(r.recording?.steps ?? []),
      () => !cancelled && setSteps([]), // nothing yet
    );
    return () => {
      cancelled = true;
    };
  }, [client, recordingRevision]);
  return (
    <section className="offline" aria-label="Recordings">
      <Library client={client} session={session} busy={disabled || session.operation !== ""} open onSession={onSession} />
      {steps.length > 0 && (
        <div className="offline-open">
          <div className="start-h">
            <h2>Open recording</h2>
            <button type="button" className="btn small" onClick={() => setExporting(true)}>
              Export
            </button>
          </div>
          <ol aria-label="Steps of the open recording">
            {steps.map((step) => (
              <li key={step.id}>
                <code>{describeStep(step)}</code>
              </li>
            ))}
          </ol>
        </div>
      )}
      {exporting && <ExportDrawer client={client} onClose={() => setExporting(false)} />}
    </section>
  );
}

function TopBar({ status, children }: { status: Status; children?: ReactNode }) {
  return (
    <header className="topbar">
      <div className="brand">
        <Logo />
        Tap Studio
      </div>
      {children ?? (
        <span className="status" role="status">
          {status.kind === "loading" && "Connecting…"}
          {status.kind === "ready" && status.info.recorder}
          {status.kind === "failed" && `Cannot reach tap-studio: ${status.message}`}
        </span>
      )}
    </header>
  );
}

type RecordingState = { steps: Step[]; missingSecrets: string[]; parameters: Parameter[] };

/** `launched`: the cold launch was just performed as step 1, so the replay starts after it and counts it. */
type Checked = { launch?: boolean; launched?: boolean };
type Dialog =
  | { kind: "launch"; request: ReplayRequest }
  | { kind: "secrets"; names: string[]; request: ReplayRequest; checked: Checked }
  | { kind: "discard"; then: () => void };

function Workspace({
  client,
  session,
  onSession,
  status,
}: {
  client: StudioClient;
  session: Session;
  onSession: (session: Session) => void;
  status: Status;
}) {
  const device = session.device!;
  const { frame, error } = useFrames(client, device.serial);
  const [appPackage, setAppPackage] = useAppPackage();
  const app = appPackage.trim();
  const [tab, setTab] = useState<Tab>("act");
  const [overlay, setOverlay] = useState<OverlayFilter>("interactive");
  const [selected, setSelected] = useState<ScreenNode | null>(null);
  // The candidate picked in the composer for one node; steps on other nodes use their first.
  const [chosen, setChosen] = useState<{ ref: string; index: number } | null>(null);
  const [recording, setRecording] = useState<RecordingState>({ steps: [], missingSecrets: [], parameters: [] });
  const [selectedStep, setSelectedStep] = useState<string | null>(null);
  const [dirty, setDirtyState] = useState(false);
  // Read by the guards: a click can run before the render that this state would give it.
  const dirtyNow = useRef(false);
  const setDirty = useCallback((value: boolean) => {
    dirtyNow.current = value;
    setDirtyState(value);
  }, []);
  const [busy, setBusy] = useState(false);
  const running = useRef(false); // set at once, so a second click cannot start another step
  const [lastRun, setLastRun] = useState<LastRun>(null);
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [distance, setDistance] = useState(stepsApi.DEFAULT_DISTANCE);
  const [budgets, setBudgets] = useState<Budgets>({});
  const [seek, setSeek] = useState<Seek | null>(null);
  const [drag, setDrag] = useState<DragFrom | null>(null);
  const [revision, setRevision] = useState(0);
  const history = useRef({ undo: () => undefined as unknown, redo: () => undefined as unknown }); // for the key handler
  const searching = useRef(false); // for the key handler: the tab cannot change during a search or a drag
  searching.current = seek !== null || drag !== null;
  const { steps } = recording;

  const onOutcome = useCallback((stepId: string, outcome: Outcome) => {
    setRecording((r) => ({ ...r, steps: r.steps.map((s) => (s.id === stepId ? withOutcome(s, outcome) : s)) }));
  }, []);
  const replay = useReplay(client, onOutcome);

  const apply = useCallback((r: { recording?: Recording; missingSecrets: string[] }) => {
    const next = r.recording?.steps ?? [];
    setRecording({ steps: next, missingSecrets: r.missingSecrets, parameters: r.recording?.parameters ?? [] });
    setSelectedStep((id) => (id && next.some((s) => s.id === id) ? id : null));
  }, []);

  // The server's recording is the truth: read it again when another tab changed it, or a step a
  // cancelled request left behind has finished.
  const { recordingRevision } = session;
  useEffect(() => {
    let cancelled = false;
    client.getRecording({}).then(
      (r) => !cancelled && apply(r),
      (e: unknown) => {
        if (cancelled) return;
        if (e instanceof ConnectError && e.code === Code.NotFound) apply({ missingSecrets: [] });
        else setLastRun({ tone: "fail", text: errorMessage(e) });
      },
    );
    return () => {
      cancelled = true;
    };
  }, [client, apply, recordingRevision]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLElement && e.target.closest("input, textarea, select, [contenteditable]")) return;
      if (document.querySelector('[aria-modal="true"]')) return; // the dialog has the keyboard
      if ((e.ctrlKey || e.metaKey) && !e.altKey && e.key.toLowerCase() === "z") {
        e.preventDefault();
        (e.shiftKey ? history.current.redo : history.current.undo)();
        return;
      }
      if (e.altKey || e.ctrlKey || e.metaKey) return;
      if (e.key === "Escape") {
        setSeek(null);
        setDrag(null);
        return;
      }
      const chosen = TABS.find((t) => t.key === e.key);
      if (chosen && !searching.current) setTab(chosen.tab);
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, []);

  const targetOf = useCallback(
    (node: ScreenNode): Target | null => {
      const candidate = chosen?.ref === node.ref ? node.candidates[chosen.index]?.selector : undefined;
      if (candidate && chosen)
        return { selector: candidate, origin: chosen.index === 0 ? SelectorOrigin.SYNTHESIZED : SelectorOrigin.ALTERNATIVE };
      return node.selector ? synthesized(node.selector) : null;
    },
    [chosen],
  );

  /** Runs a step and records it after the selected step (or at the end). Resolves whether it passed. */
  const perform = useCallback(
    async (request: PerformRequest, beforeStepId?: string): Promise<boolean> => {
      if (running.current || replay.running) return false;
      running.current = true;
      setBusy(true);
      replay.clearSummary();
      const index = selectedStep ? steps.findIndex((s) => s.id === selectedStep) : -1;
      const before = beforeStepId ?? (index >= 0 ? (steps[index + 1]?.id ?? "") : "");
      const sent = clone(PerformRequestSchema, request);
      sent.beforeStepId = before;
      try {
        const response = await client.perform(requestBudgets(sent, budgets));
        const step = response.step!;
        if (response.recorded) {
          setRecording((r) => {
            const at = before ? r.steps.findIndex((s) => s.id === before) : -1;
            const next = at >= 0 ? [...r.steps.slice(0, at), step, ...r.steps.slice(at)] : [...r.steps, step];
            return { ...r, steps: next };
          });
          if (selectedStep && beforeStepId === undefined) setSelectedStep(step.id);
          setLastRun(null);
        } else if (response.message) {
          setLastRun({ tone: "fail", text: `Not recorded: ${describeStep(step)} failed. ${response.message}` });
        } else if (!request.skipRecording) {
          setLastRun({ tone: "info", text: `Ran ${describeStep(step)} (${step.outcome?.durationMs ?? 0} ms), not recorded.` });
        }
        return !response.message;
      } catch (e) {
        setLastRun({ tone: "fail", text: errorMessage(e) });
        return false;
      } finally {
        running.current = false;
        setBusy(false);
        setRevision((n) => n + 1);
      }
    },
    [client, replay, selectedStep, steps, budgets],
  );

  const change = async (call: () => Promise<{ session?: Session }>) => {
    try {
      const response = await call();
      if (response.session) onSession(response.session);
    } catch (e) {
      setLastRun({ tone: "fail", text: errorMessage(e) });
    }
  };

  const edit = async (call: () => Promise<{ recording?: Recording; missingSecrets: string[] }>): Promise<boolean> => {
    replay.clearSummary();
    try {
      apply(await call());
      setLastRun(null);
      return true;
    } catch (e) {
      setLastRun({ tone: "fail", text: errorMessage(e) });
      return false;
    }
  };

  const number = (stepId: string) => steps.findIndex((s) => s.id === stepId) + 1;
  const summary = replay.summary && {
    tone: replay.summary.tone,
    text: replay.summary.failedStepId
      ? `Step ${number(replay.summary.failedStepId)} failed. ${replay.summary.text}`
      : replay.summary.text,
  };

  /** Replays, first offering a cold launch of the App menu's app (from the first step) and asking
   * for missing secrets. */
  const startReplay = (request: ReplayRequest, checked: Checked = {}) => {
    setLastRun(null);
    if (app && !request.fromStepId && !checked.launch && steps[0]?.kind.case !== "app") {
      setDialog({ kind: "launch", request });
      return;
    }
    const from = request.fromStepId ? steps.findIndex((s) => s.id === request.fromStepId) : 0;
    const range = steps.slice(from, request.only ? from + 1 : undefined);
    const names = [
      ...new Set(
        range.flatMap((s) => {
          const text = stepText(s);
          return text && "secret" in text && recording.missingSecrets.includes(text.secret) && !request.secretValues?.[text.secret]
            ? [text.secret]
            : [];
        }),
      ),
    ];
    if (names.length) {
      setDialog({ kind: "secrets", names, request, checked });
      return;
    }
    setDialog(null);
    void replay.start(request, checked.launched ? 1 : 0).then(() => client.getRecording({}).then(apply, () => undefined));
  };

  const launchFirst = async (request: ReplayRequest) => {
    setDialog(null);
    const first = steps[0]!.id;
    if (await perform(stepsApi.app("cold_launch", app), first))
      startReplay({ ...request, fromStepId: first }, { launch: true, launched: true });
  };

  /** One probe scroll of a scroll until's container; the search ends if it fails. */
  const probeScroll = async (s: Seek) => {
    const passed = await perform(stepsApi.probe(stepsApi.scroll(s.container, s.direction, s.distance)));
    // A failed probe (the container is gone) ends the search; its message is in the steps panel.
    setSeek((current) => (current && passed ? { ...current, scrolls: current.scrolls + 1, note: undefined } : null));
  };

  const startSeek = (direction: Direction) => {
    const target = inspected && targetOf(inspected);
    if (!inspected || !target) return;
    const s: Seek = { container: target, containerNode: inspected, direction, distance, scrolls: 0 };
    setSeek(s);
    void probeScroll(s);
  };

  /** The element the search was for: record the scroll until, with room for the scrolls it took. */
  const pickTarget = async (s: Seek, node: ScreenNode) => {
    const target = targetOf(node);
    if (!target) {
      setSeek({ ...s, note: "That element has no selector of its own: pick another, or scroll again." });
      return;
    }
    const maxScrolls = Math.max(stepsApi.DEFAULT_MAX_SCROLLS, s.scrolls * 2);
    if (await perform(stepsApi.scrollUntil(s.container, target.selector, s.direction, { distance: s.distance, maxScrolls }))) {
      setSeek(null);
      setSelected(node);
    }
  };

  const startDrag = () => {
    const source = inspected && targetOf(inspected);
    if (inspected && source) setDrag({ source, node: inspected });
  };

  /** Where the drag drops: record it from the source to the picked element. */
  const pickDrop = async (d: DragFrom, node: ScreenNode) => {
    const target = targetOf(node);
    if (!target) return setDrag({ ...d, note: "That element has no selector of its own: pick another." });
    if (node.ref === d.node.ref) return setDrag({ ...d, note: "That is the element being dragged: pick where it drops." });
    if (await perform(stepsApi.drag(d.source, target.selector))) setDrag(null);
  };

  const nodes = frame?.nodes ?? [];
  const packages = [...new Set(nodes.map((n) => n.windowPackage).filter(Boolean))];
  const current = selected ? (nodes.find((n) => n.ref === selected.ref) ?? null) : null;
  const inspected = current ?? selected;
  const observing = isObserver(session);
  const finishing = session.operation !== "" && !busy && !replay.running;
  // A stop drops the replay stream, so the step already sent finishes unseen. Its result is in the
  // run, which may have failed: say that, rather than how many steps the page saw finish.
  const stoppedAfter = replay.summary?.stoppedAfter;
  const settleReplay = replay.settle;
  useEffect(() => {
    if (stoppedAfter === undefined || finishing) return;
    let cancelled = false;
    client.listRuns({}).then(
      ({ runs }) => {
        const run = runs[0];
        // Stopped before it began, no run was kept: the newest one is an earlier replay's.
        if (cancelled || !run?.startedAt || timestampDate(run.startedAt).getTime() < stoppedAfter) return;
        if (run.status === RunStatus.RUNNING) return;
        if (run.status !== RunStatus.FAILED) return settleReplay(run, "");
        // The list holds summaries: why it failed is in the whole run.
        return client.getRun({ id: run.id }).then((whole) => {
          if (!cancelled) settleReplay(run, whole.run ? failureIn(whole.run) : "It did not pass.");
        });
      },
      () => undefined, // the count stands
    );
    return () => {
      cancelled = true;
    };
  }, [client, stoppedAfter, finishing, settleReplay]);
  const locked = busy || replay.running || session.operation !== "" || observing;
  const seeking = seek ? (nodeFor(nodes, seek.container.selector) ?? seek.containerNode) : null;

  // The recording lives in the server, but an open step's unsaved edits only here.
  useEffect(() => {
    if (!dirty) return;
    const warn = (e: BeforeUnloadEvent) => e.preventDefault();
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, [dirty]);

  const unlessDiscarding = (then: () => void) => (dirtyNow.current ? setDialog({ kind: "discard", then }) : then());

  const undo = () => {
    if (session.canUndo && !locked) void edit(() => client.undoRecording({}));
  };
  const redo = () => {
    if (session.canRedo && !locked) void edit(() => client.redoRecording({}));
  };
  history.current = { undo, redo };

  const selectStep = (stepId: string | null) =>
    unlessDiscarding(() => {
      setSelectedStep(stepId);
      const step = steps.find((s) => s.id === stepId);
      const node = step && nodeFor(nodes, stepSelector(step));
      if (node) setSelected(node);
    });

  /** An element picked on the screen or in the tree: the same choice by mouse or keyboard. */
  const pick = (node: ScreenNode) => {
    if (seek) return void pickTarget(seek, node);
    if (drag) return void pickDrop(drag, node);
    setSelected(node);
    // Picking an element is for a step on it: leave the App and Device tabs.
    setTab((t) => (t === "app" || t === "device" ? "act" : t));
  };

  return (
    <>
      <TopBar status={status}>
        <div className="devchip" title={`${device.manufacturer} ${device.model}, ${device.displayWidth}×${device.displayHeight}`}>
          <span className="dot" aria-hidden="true" />
          <span className="mono">{device.serial}</span>
          <span className="sep">·</span>
          <span>API {device.apiLevel}</span>
          <button
            type="button"
            className="iconbtn"
            aria-label="Release the device"
            title="Release the device"
            disabled={locked}
            onClick={() => unlessDiscarding(() => void change(() => client.release({})))}
          >
            <Eject />
          </button>
        </div>
        <span className="spacer" />
        <button
          type="button"
          className="rec"
          aria-pressed={session.recording}
          disabled={locked}
          title={session.recording ? "Pause: steps still run on the device but are not recorded" : "Resume recording"}
          onClick={() => change(() => client.setRecording({ recording: !session.recording }))}
        >
          <span className="led" aria-hidden="true" />
          {session.recording ? "Recording" : "Paused"}
        </button>
      </TopBar>
      <main className="cols">
        <ScreenView
          frame={frame}
          error={error}
          intent={tab === "app" || tab === "device" ? "act" : tab}
          overlay={overlay}
          onOverlay={setOverlay}
          selectedRef={inspected?.ref ?? null}
          busy={locked}
          onSelect={pick}
          targetOf={targetOf}
          seeking={seeking}
          dragging={drag ? (nodes.find((n) => n.ref === drag.node.ref) ?? drag.node) : null}
        >
          <DeviceBar disabled={locked || !!seek || !!drag} onPerform={(request) => void perform(request)} />
        </ScreenView>
        <div className="mid">
          <Composer
            client={client}
            tab={tab}
            onTab={setTab}
            node={inspected}
            onScreen={current !== null}
            nodes={nodes}
            onSelect={setSelected}
            busy={locked}
            target={inspected ? targetOf(inspected) : null}
            onChoose={(node, index) => setChosen({ ref: node.ref, index })}
            onPerform={(request) => void perform(request)}
            onAdd={(request) => {
              const index = selectedStep ? steps.findIndex((s) => s.id === selectedStep) : -1;
              const beforeStepId = index >= 0 ? (steps[index + 1]?.id ?? "") : "";
              const { step, secretValue } = requestBudgets(request, budgets);
              void edit(() => client.addStep({ step, secretValue, beforeStepId }));
            }}
            distance={distance}
            onDistance={setDistance}
            seek={seek}
            onSeek={startSeek}
            onSeekAgain={() => seek && void probeScroll(seek)}
            onSeekCancel={() => setSeek(null)}
            drag={drag}
            onDrag={startDrag}
            onDragCancel={() => setDrag(null)}
            revision={revision}
            appPackage={appPackage}
            onAppPackage={setAppPackage}
            packages={packages}
          >
            <details className="execution-defaults"><summary>Defaults for new steps</summary><ExecutionControls values={budgets} onChange={setBudgets} /></details>
            <Tools client={client} appPackage={appPackage} onAppPackage={setAppPackage} disabled={locked} onBusy={setBusy} />
          </Composer>
          <Inspector node={inspected} nodes={nodes} appPackage={app} onSelect={pick} />
        </div>
        <StepsPanel
          client={client}
          steps={steps}
          missingSecrets={recording.missingSecrets}
          recording={session.recording}
          lastRun={
            replay.running
              ? { tone: "info", text: replay.stopping ? "Stopping: no further step will start." : "Replaying…" }
              : finishing && !observing
                ? { tone: "info", text: "Finishing what was already sent to the device. It cannot be interrupted, and its result is not known yet." }
                : (summary ?? lastRun)
          }
          selectedId={selectedStep}
          states={replay.states}
          replaying={replay.running}
          busy={locked}
          nodes={nodes}
          onSelect={selectStep}
          onNewRecording={() =>
            change(async () => {
              const response = await client.newRecording({});
              setRecording({ steps: [], missingSecrets: [], parameters: [] });
              setSelectedStep(null);
              setLastRun(null);
              replay.clearSummary();
              return response;
            })
          }
          onReplay={(request) => startReplay(request)}
          onStop={replay.stop}
          onOpen={(document) =>
            edit(async () => {
              const response = await client.openRecording({ document });
              if (response.session) onSession(response.session);
              setSelectedStep(null);
              return response;
            })
          }
          onUpdate={(step, secretValue) => edit(() => client.updateStep({ step, secretValue }))}
          onDelete={(stepId) => edit(() => client.deleteStep({ stepId }))}
          onMove={(stepId, beforeStepId) => edit(() => client.moveStep({ stepId, beforeStepId }))}
          onDuplicate={(stepId) => edit(() => client.duplicateStep({ stepId }))}
          onDirty={setDirty}
          session={session}
          onSession={onSession}
          parameters={recording.parameters}
          onParameters={(parameters) => edit(() => client.setParameters({ parameters }))}
          canUndo={session.canUndo}
          canRedo={session.canRedo}
          onUndo={undo}
          onRedo={redo}
        />
      </main>
      {dialog?.kind === "discard" && (
        <ChoiceDialog
          title="Discard your changes to this step?"
          body={<p>The edits in the open step have not been saved.</p>}
          choices={[
            {
              label: "Discard",
              primary: true,
              onChoose: () => {
                setDialog(null);
                dialog.then();
              },
            },
          ]}
          onCancel={() => setDialog(null)}
        />
      )}
      {dialog?.kind === "launch" && (
        <ChoiceDialog
          title="Start from a cold launch?"
          body={
            <p>
              The recording does not start with an app step, so a replay starts from whatever screen the device shows now. A cold launch of{" "}
              <code>{app}</code> (the app panel's) first makes it reproducible
              {session.recording ? "; it is added as step 1" : " (not recorded: recording is paused)"}.
            </p>
          }
          choices={[
            { label: "Replay as it is", onChoose: () => startReplay(dialog.request, { launch: true }) },
            { label: "Cold launch first", primary: true, onChoose: () => void launchFirst(dialog.request) },
          ]}
          onCancel={() => setDialog(null)}
        />
      )}
      {dialog?.kind === "secrets" && (
        <SecretsDialog
          names={dialog.names}
          onSubmit={(values) =>
            startReplay({ ...dialog.request, secretValues: { ...dialog.request.secretValues, ...values } }, { ...dialog.checked, launch: true })
          }
          onCancel={() => setDialog(null)}
        />
      )}
    </>
  );
}

function withOutcome(step: Step, outcome: Outcome): Step {
  const copy = clone(StepSchema, step);
  copy.outcome = outcome;
  return copy;
}
