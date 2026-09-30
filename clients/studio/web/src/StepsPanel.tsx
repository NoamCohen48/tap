import { useEffect, useRef, useState } from "react";
import type { StudioClient } from "./api";
import { describeStep, describeWait, stepKind } from "./describe";
import { stepChips } from "./edit";
import { errorMessage } from "./frames";
import type { ScreenNode } from "./gen/device_pb";
import type { Outcome, Step } from "./gen/studio_pb";
import { ChoiceDialog } from "./Dialogs";
import { Close, Down, Download, Play, Stop, Trash, Up, Upload } from "./icons";
import { passed, type ReplayRequest, type StepState } from "./replay";
import { StepEditor } from "./StepEditor";

export type LastRun = { tone: "fail" | "info" | "ok"; text: string } | null;

const KIND_LABELS = { app: "app", action: "action", key: "key", system: "system", type: "type", assertion: "assert" } as const;

export function StepsPanel({
  client,
  steps,
  missingSecrets,
  recording,
  lastRun,
  selectedId,
  states,
  replaying,
  busy,
  nodes,
  onSelect,
  onNewRecording,
  onReplay,
  onStop,
  onOpen,
  onUpdate,
  onDelete,
  onMove,
}: {
  client: StudioClient;
  steps: readonly Step[];
  missingSecrets: readonly string[];
  recording: boolean;
  lastRun: LastRun;
  /** The selected step: new steps are added after it. */
  selectedId: string | null;
  /** How each step went in the replay that runs or ran last. */
  states: Record<string, StepState>;
  replaying: boolean;
  busy: boolean;
  /** The newest frame's nodes, to offer the other candidates of a step's element. */
  nodes: readonly ScreenNode[];
  onSelect: (stepId: string | null) => void;
  onNewRecording: () => void;
  onReplay: (request: ReplayRequest) => void;
  onStop: () => void;
  onOpen: (document: string) => void;
  onUpdate: (step: Step, secretValue?: string) => void;
  onDelete: (stepId: string) => void;
  onMove: (stepId: string, beforeStepId: string) => void;
}) {
  const [exporting, setExporting] = useState(false);
  // Replacing recorded steps asks first, in the page's own dialog (not a blocking `confirm`).
  const [replacing, setReplacing] = useState<{ kind: "new" } | { kind: "open"; name: string; document: string } | null>(null);
  const list = useRef<HTMLOListElement>(null);
  const file = useRef<HTMLInputElement>(null);
  const locked = replaying || busy;

  useEffect(() => {
    const target = selectedId ? list.current?.querySelector(`[data-step="${CSS.escape(selectedId)}"]`) : list.current?.lastElementChild;
    target?.scrollIntoView?.({ block: "nearest" });
  }, [steps.length, selectedId]);

  const open = async (chosen: File | undefined) => {
    if (!chosen) return;
    const document = await chosen.text();
    if (steps.length) setReplacing({ kind: "open", name: chosen.name, document });
    else onOpen(document);
  };

  const selectedIndex = steps.findIndex((s) => s.id === selectedId);

  return (
    <section className="panel steps-col" aria-label="Steps">
      <div className="panel-h">
        <h2>Steps</h2>
        <span className="count">{plural(steps.length, "step")}</span>
        <span className="spacer" />
        {replaying ? (
          <button type="button" className="btn small" onClick={onStop}>
            <Stop />
            Stop
          </button>
        ) : (
          <button
            type="button"
            className="btn small"
            disabled={steps.length === 0 || busy}
            title="Run every step from the first"
            onClick={() => onReplay({})}
          >
            <Play />
            Replay
          </button>
        )}
        <button
          type="button"
          className="btn small"
          disabled={locked}
          title="Open a tap-recording/1 file"
          onClick={() => file.current?.click()}
        >
          <Upload />
          Open
        </button>
        <input
          ref={file}
          type="file"
          accept=".json,application/json"
          hidden
          aria-label="Recording file"
          onChange={(e) => {
            void open(e.target.files?.[0]);
            e.target.value = "";
          }}
        />
        <button
          type="button"
          className="btn small"
          disabled={steps.length === 0 || locked}
          onClick={() => setReplacing({ kind: "new" })}
        >
          New
        </button>
        <button type="button" className="btn small primary" disabled={steps.length === 0} onClick={() => setExporting(true)}>
          <Download />
          Export
        </button>
      </div>
      {!recording && <div className="paused-note">Recording paused: steps run on the device but are not added.</div>}
      {lastRun && (
        <div className={`last-run ${lastRun.tone}`} role={lastRun.tone === "fail" ? "alert" : "status"}>
          {lastRun.text}
        </div>
      )}
      <ol className="steps" ref={list}>
        {steps.length === 0 ? (
          <li className="empty">Act on the screen to record steps, or open a recording.</li>
        ) : (
          steps.map((step, i) => {
            const kind = stepKind(step) ?? "action";
            const wait = describeWait(step);
            const selected = step.id === selectedId;
            const chips = stepChips(step, missingSecrets);
            return (
              <li
                key={step.id}
                data-step={step.id}
                className={`step${selected ? " sel" : ""}${!selectedId && i === steps.length - 1 ? " new" : ""}`}
              >
                <span className="num">{i + 1}</span>
                <button type="button" className="step-main" aria-expanded={selected} onClick={() => onSelect(selected ? null : step.id)}>
                  <span className={`kind k-${kind}`}>{KIND_LABELS[kind]}</span>
                  <code>{describeStep(step)}</code>
                  {wait && (
                    <span className="wait">
                      after <code>{wait}</code>
                    </span>
                  )}
                  {step.note && <span className="note">{step.note}</span>}
                  {chips.length > 0 && (
                    <span className="chips">
                      {chips.map((chip) => (
                        <span key={chip.text} className={`chip ${chip.tone}`} title={chip.title}>
                          {chip.text}
                        </span>
                      ))}
                    </span>
                  )}
                </button>
                <Result state={states[step.id]} outcome={step.outcome} />
                {selected && (
                  <div className="step-open">
                    <div className="step-tools" role="group" aria-label={`Step ${i + 1}`}>
                      <button
                        type="button"
                        className="btn small"
                        disabled={locked}
                        onClick={() => onReplay({ fromStepId: step.id, only: true })}
                      >
                        <Play />
                        Run
                      </button>
                      <button type="button" className="btn small" disabled={locked} onClick={() => onReplay({ fromStepId: step.id })}>
                        Run from here
                      </button>
                      <span className="spacer" />
                      <button
                        type="button"
                        className="iconbtn"
                        aria-label="Move up"
                        title="Move up"
                        disabled={locked || i === 0}
                        onClick={() => onMove(step.id, steps[i - 1]!.id)}
                      >
                        <Up />
                      </button>
                      <button
                        type="button"
                        className="iconbtn"
                        aria-label="Move down"
                        title="Move down"
                        disabled={locked || i === steps.length - 1}
                        onClick={() => onMove(step.id, steps[i + 2]?.id ?? "")}
                      >
                        <Down />
                      </button>
                      <button
                        type="button"
                        className="iconbtn"
                        aria-label="Delete the step"
                        title="Delete"
                        disabled={locked}
                        onClick={() => onDelete(step.id)}
                      >
                        <Trash />
                      </button>
                    </div>
                    <StepEditor key={step.id} step={step} nodes={nodes} client={client} busy={locked} onSave={onUpdate} />
                  </div>
                )}
              </li>
            );
          })
        )}
      </ol>
      <div className="hint-line">
        {selectedIndex >= 0 ? (
          <>
            New steps go after step {selectedIndex + 1}.{" "}
            <button type="button" className="linkbtn" onClick={() => onSelect(null)}>
              Add at the end
            </button>
          </>
        ) : (
          "Each action is recorded after the wait that proves it can run: its element was on screen and matched once."
        )}
      </div>
      {replacing && (
        <ChoiceDialog
          title={replacing.kind === "new" ? "Start a new recording?" : `Open ${replacing.name}?`}
          body={
            <p>
              {replacing.kind === "new"
                ? `The ${plural(steps.length, "recorded step")} will be discarded. Export them first to keep them.`
                : `It replaces the ${plural(steps.length, "recorded step")}. Export them first to keep them.`}
            </p>
          }
          choices={[
            {
              label: replacing.kind === "new" ? "Discard and start new" : "Replace",
              primary: true,
              onChoose: () => {
                setReplacing(null);
                if (replacing.kind === "new") onNewRecording();
                else onOpen(replacing.document);
              },
            },
          ]}
          onCancel={() => setReplacing(null)}
        />
      )}
      {exporting && <ExportDrawer client={client} onClose={() => setExporting(false)} />}
    </section>
  );
}

function Result({ state, outcome }: { state: StepState | undefined; outcome: Outcome | undefined }) {
  if (state === "run") return <span className="res run">running…</span>;
  if (!outcome)
    return (
      <span className="res none" title="Not run since it was edited or opened">
        —
      </span>
    );
  if (passed(outcome)) return <span className="res">✓ {outcome.durationMs} ms</span>;
  const why = outcome.error?.message || (outcome.failure ? "the call failed" : "failed");
  return (
    <span className="res fail" title={why}>
      ✗ failed
    </span>
  );
}

const plural = (count: number, noun: string) => `${count} ${noun}${count === 1 ? "" : "s"}`;

function ExportDrawer({ client, onClose }: { client: StudioClient; onClose: () => void }) {
  const [state, setState] = useState<{ document: string; name: string } | { error: string } | null>(null);
  const [copied, setCopied] = useState(false);
  const close = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    let cancelled = false;
    client.getRecording({}).then(
      (r) => !cancelled && setState({ document: r.document, name: fileName(r.recording?.autPackage ?? "recording") }),
      (e: unknown) => !cancelled && setState({ error: errorMessage(e) }),
    );
    close.current?.focus();
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    document.addEventListener("keydown", onKey);
    return () => {
      cancelled = true;
      document.removeEventListener("keydown", onKey);
    };
  }, [client, onClose]);

  const download = (document: string, name: string) => {
    const url = URL.createObjectURL(new Blob([document], { type: "application/json" }));
    const a = window.document.createElement("a");
    a.href = url;
    a.download = name;
    // In the document (Firefox ignores a detached link), and the URL outlives the click: the
    // browser fetches it after `click()` returns, so revoking it at once cancels the download.
    window.document.body.append(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 60_000);
  };

  return (
    <div className="drawer-back" onClick={(e) => e.target === e.currentTarget && onClose()}>
      <div className="drawer" role="dialog" aria-modal="true" aria-labelledby="export-title">
        <div className="panel-h">
          <h2 id="export-title">Export recording</h2>
          <span className="count">tap-recording/1</span>
          <span className="spacer" />
          <button type="button" className="iconbtn" aria-label="Close" ref={close} onClick={onClose}>
            <Close />
          </button>
        </div>
        {state === null ? (
          <div className="empty">Loading…</div>
        ) : "error" in state ? (
          <div className="panel-b">
            <div className="error">{state.error}</div>
          </div>
        ) : (
          <pre data-testid="recording-json">{state.document}</pre>
        )}
        <div className="foot">
          <small>
            Commands are <code>tap.v1</code> messages in proto3 JSON: replay one by sending it unchanged. Secret values are not in the file,
            only their names.
          </small>
          {state && "document" in state && (
            <>
              <button
                type="button"
                className="btn"
                onClick={() =>
                  navigator.clipboard?.writeText(state.document).then(
                    () => setCopied(true),
                    () => setCopied(false),
                  )
                }
              >
                {copied ? "Copied" : "Copy JSON"}
              </button>
              <button type="button" className="btn primary" onClick={() => download(state.document, state.name)}>
                <Download />
                Download
              </button>
            </>
          )}
        </div>
      </div>
    </div>
  );
}

function fileName(autPackage: string): string {
  const stamp = new Date().toISOString().slice(0, 16).replace(/[-:]/g, "").replace("T", "-");
  return `${autPackage}-${stamp}.tap-recording.json`;
}
