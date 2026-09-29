import { Code, ConnectError } from "@connectrpc/connect";
import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import type { StudioClient } from "./api";
import { describeStep } from "./describe";
import { DevicePicker } from "./DevicePicker";
import { errorMessage, useFrames } from "./frames";
import type { ScreenNode } from "./gen/device_pb";
import type { InfoResponse, PerformRequest, Session, Step } from "./gen/studio_pb";
import { Eject, Logo } from "./icons";
import { Inspector } from "./Inspector";
import { ScreenView, type Mode, type OverlayFilter } from "./ScreenView";
import { StepsPanel, type LastRun } from "./StepsPanel";

type Status = { kind: "loading" } | { kind: "ready"; info: InfoResponse } | { kind: "failed"; message: string };

const MODES: { mode: Mode; label: string; key: string; title: string }[] = [
  { mode: "act", label: "Act", key: "1", title: "A click performs the action and records it" },
  { mode: "assert", label: "Assert", key: "2", title: "A click records a check on the element" },
  { mode: "inspect", label: "Inspect", key: "3", title: "A click only selects; nothing runs" },
];

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
          {session && <DevicePicker client={client} session={session} onSession={setSession} />}
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
  const { frame, error } = useFrames(client, `${device.serial}/${device.autPackage}`);
  const [mode, setMode] = useState<Mode>("act");
  const [overlay, setOverlay] = useState<OverlayFilter>("interactive");
  const [selected, setSelected] = useState<ScreenNode | null>(null);
  const [steps, setSteps] = useState<Step[]>([]);
  const [busy, setBusy] = useState(false);
  const running = useRef(false); // set at once, so a second click cannot start another step
  const [lastRun, setLastRun] = useState<LastRun>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    client.getRecording({}).then(
      (r) => !cancelled && setSteps(r.recording?.steps ?? []),
      (e: unknown) => !cancelled && !(e instanceof ConnectError && e.code === Code.NotFound) && setLastRun({ tone: "fail", text: errorMessage(e) }),
    );
    return () => {
      cancelled = true;
    };
  }, [client]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLElement && e.target.closest("input, textarea, select, [contenteditable]")) return;
      if (e.altKey || e.ctrlKey || e.metaKey) return;
      const chosen = MODES.find((m) => m.key === e.key);
      if (chosen) setMode(chosen.mode);
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, []);

  useEffect(() => {
    if (!notice) return;
    const timer = setTimeout(() => setNotice(null), 3000);
    return () => clearTimeout(timer);
  }, [notice]);

  const perform = useCallback(
    async (request: PerformRequest) => {
      if (running.current) return;
      running.current = true;
      setBusy(true);
      try {
        const response = await client.perform(request);
        const step = response.step!;
        if (response.recorded) {
          setSteps((s) => [...s, step]);
          setLastRun(null);
        } else if (response.message) {
          setLastRun({ tone: "fail", text: `Not recorded: ${describeStep(step)} failed. ${response.message}` });
        } else {
          setLastRun({ tone: "info", text: `Ran ${describeStep(step)} (${step.outcome?.durationMs ?? 0} ms), not recorded.` });
        }
      } catch (e) {
        setLastRun({ tone: "fail", text: errorMessage(e) });
      } finally {
        running.current = false;
        setBusy(false);
      }
    },
    [client],
  );

  const change = async (call: () => Promise<{ session?: Session }>) => {
    try {
      const response = await call();
      if (response.session) onSession(response.session);
    } catch (e) {
      setLastRun({ tone: "fail", text: errorMessage(e) });
    }
  };

  const nodes = frame?.nodes ?? [];
  const current = selected ? (nodes.find((n) => n.ref === selected.ref) ?? null) : null;
  const inspected = current ?? selected;

  return (
    <>
      <TopBar status={status}>
        <div className="devchip" title={`${device.manufacturer} ${device.model}, ${device.displayWidth}×${device.displayHeight}`}>
          <span className="dot" aria-hidden="true" />
          <span className="mono">{device.serial}</span>
          <span className="sep">·</span>
          <span>API {device.apiLevel}</span>
          <span className="sep">·</span>
          <span className="mono">{device.autPackage}</span>
          <button type="button" className="iconbtn" aria-label="Release the device" title="Release the device" onClick={() => change(() => client.release({}))}>
            <Eject />
          </button>
        </div>
        <span className="spacer" />
        <div className="modes" role="group" aria-label="Click mode">
          {MODES.map((m) => (
            <button key={m.mode} type="button" data-mode={m.mode} aria-pressed={mode === m.mode} title={`${m.title} (${m.key})`} onClick={() => setMode(m.mode)}>
              {m.label} <kbd>{m.key}</kbd>
            </button>
          ))}
        </div>
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
          autPackage={device.autPackage}
          mode={mode}
          overlay={overlay}
          onOverlay={setOverlay}
          selectedRef={inspected?.ref ?? null}
          busy={busy}
          onSelect={setSelected}
          onPerform={perform}
          onNotice={setNotice}
        />
        <Inspector
          node={inspected}
          onScreen={current !== null}
          nodes={nodes}
          autPackage={device.autPackage}
          busy={busy}
          onSelect={setSelected}
          onPerform={perform}
        />
        <StepsPanel
          client={client}
          steps={steps}
          recording={session.recording}
          lastRun={lastRun}
          onNewRecording={() =>
            change(async () => {
              const response = await client.newRecording({});
              setSteps([]);
              setLastRun(null);
              return response;
            })
          }
        />
      </main>
      {notice && (
        <div className="toast" role="status">
          {notice}
        </div>
      )}
    </>
  );
}
