import { clone } from "@bufbuild/protobuf";
import { Code, ConnectError } from "@connectrpc/connect";
import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import type { StudioClient } from "./api";
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
  StepSchema,
  type InfoResponse,
  type Outcome,
  type PerformRequest,
  type Recording,
  type Session,
  type Step,
} from "./gen/studio_pb";
import { AppPanel } from "./AppPanel";
import { Composer, INTENTS, type Seek } from "./Composer";
import { DeviceBar } from "./DeviceBar";
import type { Direction } from "./gen/command_pb";
import { Eject, Logo } from "./icons";
import { Inspector } from "./Inspector";
import { ScreenView, type Intent, type OverlayFilter } from "./ScreenView";
import { useReplay, type ReplayRequest } from "./replay";
import * as stepsApi from "./steps";
import { synthesized, type Target } from "./steps";
import { StepsPanel, type LastRun } from "./StepsPanel";

type Status = { kind: "loading" } | { kind: "ready"; info: InfoResponse } | { kind: "failed"; message: string };

export function App({ client }: { client: StudioClient }) {
  const [status, setStatus] = useState<Status>({ kind: "loading" });
  const [session, setSession] = useState<Session | null>(null);

  useEffect(() => {
    let cancelled = false;
    client.info({}).then(
      (info) => !cancelled && setStatus({ kind: "ready", info }),
      (error: unknown) => !cancelled && setStatus({ kind: "failed", message: errorMessage(error) }),
    );
    client.getSession({}).then(
      (r) => !cancelled && setSession(r.session ?? null),
      () => undefined, // reported by Info
    );
    return () => {
      cancelled = true;
    };
  }, [client]);

  return (
    <div className="app">
      {session?.device ? (
        <Workspace client={client} session={session} onSession={setSession} status={status} />
      ) : (
        <>
          <TopBar status={status} />
          {session && <DevicePicker client={client} onSession={setSession} />}
        </>
      )}
    </div>
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
          {status.kind === "ready" && `${status.info.recorder} · writes ${status.info.format}`}
          {status.kind === "failed" && `Cannot reach tap-studio: ${status.message}`}
        </span>
      )}
    </header>
  );
}

type RecordingState = { steps: Step[]; missingSecrets: string[] };

/** `launched`: the cold launch was just performed as step 1, so the replay starts after it and counts it. */
type Checked = { launch?: boolean; launched?: boolean };
type Dialog = { kind: "launch"; request: ReplayRequest } | { kind: "secrets"; names: string[]; request: ReplayRequest; checked: Checked };

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
  const [intent, setIntent] = useState<Intent>("act");
  const [overlay, setOverlay] = useState<OverlayFilter>("interactive");
  const [selected, setSelected] = useState<ScreenNode | null>(null);
  // The candidate picked in the composer for one node; steps on other nodes use their first.
  const [chosen, setChosen] = useState<{ ref: string; index: number } | null>(null);
  const [recording, setRecording] = useState<RecordingState>({ steps: [], missingSecrets: [] });
  const [selectedStep, setSelectedStep] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const running = useRef(false); // set at once, so a second click cannot start another step
  const [lastRun, setLastRun] = useState<LastRun>(null);
  const [dialog, setDialog] = useState<Dialog | null>(null);
  const [distance, setDistance] = useState(stepsApi.DEFAULT_DISTANCE);
  const [seek, setSeek] = useState<Seek | null>(null);
  const searching = useRef(false); // for the key handler: the tab cannot change during a search
  searching.current = seek !== null;
  const { steps } = recording;

  const onOutcome = useCallback((stepId: string, outcome: Outcome) => {
    setRecording((r) => ({ ...r, steps: r.steps.map((s) => (s.id === stepId ? withOutcome(s, outcome) : s)) }));
  }, []);
  const replay = useReplay(client, onOutcome);

  const apply = useCallback((r: { recording?: Recording; missingSecrets: string[] }) => {
    const next = r.recording?.steps ?? [];
    setRecording({ steps: next, missingSecrets: r.missingSecrets });
    setSelectedStep((id) => (id && next.some((s) => s.id === id) ? id : null));
  }, []);

  useEffect(() => {
    let cancelled = false;
    client.getRecording({}).then(
      (r) => !cancelled && apply(r),
      (e: unknown) =>
        !cancelled && !(e instanceof ConnectError && e.code === Code.NotFound) && setLastRun({ tone: "fail", text: errorMessage(e) }),
    );
    return () => {
      cancelled = true;
    };
  }, [client, apply]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLElement && e.target.closest("input, textarea, select, [contenteditable]")) return;
      if (e.altKey || e.ctrlKey || e.metaKey) return;
      if (e.key === "Escape") {
        setSeek(null);
        return;
      }
      const chosen = INTENTS.find((i) => i.key === e.key);
      if (chosen && !searching.current) setIntent(chosen.intent);
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
        const response = await client.perform(sent);
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
      }
    },
    [client, replay, selectedStep, steps],
  );

  const change = async (call: () => Promise<{ session?: Session }>) => {
    try {
      const response = await call();
      if (response.session) onSession(response.session);
    } catch (e) {
      setLastRun({ tone: "fail", text: errorMessage(e) });
    }
  };

  const edit = async (call: () => Promise<{ recording?: Recording; missingSecrets: string[] }>) => {
    replay.clearSummary();
    try {
      apply(await call());
      setLastRun(null);
    } catch (e) {
      setLastRun({ tone: "fail", text: errorMessage(e) });
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

  const nodes = frame?.nodes ?? [];
  const packages = [...new Set(nodes.map((n) => n.windowPackage).filter(Boolean))];
  const current = selected ? (nodes.find((n) => n.ref === selected.ref) ?? null) : null;
  const inspected = current ?? selected;
  const locked = busy || replay.running;
  const seeking = seek ? (nodeFor(nodes, seek.container.selector) ?? seek.containerNode) : null;

  const selectStep = (stepId: string | null) => {
    setSelectedStep(stepId);
    const step = steps.find((s) => s.id === stepId);
    const node = step && nodeFor(nodes, stepSelector(step));
    if (node) setSelected(node);
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
            disabled={replay.running}
            onClick={() => change(() => client.release({}))}
          >
            <Eject />
          </button>
        </div>
        <span className="spacer" />
        <button
          type="button"
          className="rec"
          aria-pressed={session.recording}
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
          intent={intent}
          overlay={overlay}
          onOverlay={setOverlay}
          selectedRef={inspected?.ref ?? null}
          busy={locked}
          onSelect={(node) => (seek ? void pickTarget(seek, node) : setSelected(node))}
          targetOf={targetOf}
          seeking={seeking}
        >
          <DeviceBar disabled={locked || !!seek} onPerform={(request) => void perform(request)} />
        </ScreenView>
        <div className="mid">
          <Composer
            client={client}
            intent={intent}
            onIntent={setIntent}
            node={inspected}
            onScreen={current !== null}
            nodes={nodes}
            onSelect={setSelected}
            busy={locked}
            target={inspected ? targetOf(inspected) : null}
            onChoose={(node, index) => setChosen({ ref: node.ref, index })}
            onPerform={(request) => void perform(request)}
            distance={distance}
            onDistance={setDistance}
            seek={seek}
            onSeek={startSeek}
            onSeekAgain={() => seek && void probeScroll(seek)}
            onSeekCancel={() => setSeek(null)}
          />
          <AppPanel appPackage={appPackage} onAppPackage={setAppPackage} packages={packages} busy={locked || !!seek} onPerform={(request) => void perform(request)} />
          <Inspector node={inspected} nodes={nodes} appPackage={app} onSelect={setSelected} />
        </div>
        <StepsPanel
          client={client}
          steps={steps}
          missingSecrets={recording.missingSecrets}
          recording={session.recording}
          lastRun={replay.running ? { tone: "info", text: "Replaying…" } : (summary ?? lastRun)}
          selectedId={selectedStep}
          states={replay.states}
          replaying={replay.running}
          busy={busy}
          nodes={nodes}
          onSelect={selectStep}
          onNewRecording={() =>
            change(async () => {
              const response = await client.newRecording({});
              setRecording({ steps: [], missingSecrets: [] });
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
        />
      </main>
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
