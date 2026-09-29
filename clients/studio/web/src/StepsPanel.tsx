import { useEffect, useRef, useState } from "react";
import type { StudioClient } from "./api";
import { describeStep, describeWait, stepKind } from "./describe";
import { errorMessage } from "./frames";
import type { Step } from "./gen/studio_pb";
import { Close, Download } from "./icons";

export type LastRun = { tone: "fail" | "info"; text: string } | null;

const KIND_LABELS = { app: "app", action: "action", key: "key", type: "type", assertion: "assert" } as const;

export function StepsPanel({
  client,
  steps,
  recording,
  lastRun,
  onNewRecording,
}: {
  client: StudioClient;
  steps: readonly Step[];
  recording: boolean;
  lastRun: LastRun;
  onNewRecording: () => void;
}) {
  const [exporting, setExporting] = useState(false);
  const list = useRef<HTMLOListElement>(null);

  useEffect(() => {
    list.current?.lastElementChild?.scrollIntoView?.({ block: "nearest" });
  }, [steps.length]);

  return (
    <section className="panel steps-col" aria-label="Steps">
      <div className="panel-h">
        <h2>Steps</h2>
        <span className="count">{steps.length === 1 ? "1 step" : `${steps.length} steps`}</span>
        <span className="spacer" />
        <button
          type="button"
          className="btn small"
          disabled={steps.length === 0}
          onClick={() => {
            if (window.confirm(`Discard the ${steps.length === 1 ? "recorded step" : `${steps.length} recorded steps`}?`)) onNewRecording();
          }}
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
          <li className="empty">Act on the screen to record steps.</li>
        ) : (
          steps.map((step, i) => {
            const kind = stepKind(step) ?? "action";
            const wait = describeWait(step);
            return (
              <li key={step.id} className={`step${i === steps.length - 1 ? " new" : ""}`}>
                <span className="num">{i + 1}</span>
                <div>
                  <div className={`kind k-${kind}`}>{KIND_LABELS[kind]}</div>
                  <code>{describeStep(step)}</code>
                  {wait && (
                    <div className="wait">
                      after <code>{wait}</code>
                    </div>
                  )}
                </div>
                <span className="res">✓ {step.outcome?.durationMs ?? 0} ms</span>
              </li>
            );
          })
        )}
      </ol>
      <div className="hint-line">Each action is recorded after the wait that proves it can run: its element was on screen and matched once.</div>
      {exporting && <ExportDrawer client={client} onClose={() => setExporting(false)} />}
    </section>
  );
}

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
    a.click();
    URL.revokeObjectURL(url);
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
            Commands are <code>tap.v1</code> messages in proto3 JSON: replay one by sending it unchanged. Secret values are not in the
            file, only their names.
          </small>
          {state && "document" in state && (
            <>
              <button
                type="button"
                className="btn"
                onClick={() => navigator.clipboard?.writeText(state.document).then(() => setCopied(true), () => setCopied(false))}
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
