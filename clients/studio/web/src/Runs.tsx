import { timestampDate } from "@bufbuild/protobuf/wkt";
import { useCallback, useEffect, useState } from "react";
import type { StudioClient } from "./api";
import { errorMessage } from "./frames";
import { DiagnosticKind, RunStatus, type RunResult } from "./gen/studio_pb";
import { download } from "./Tools";

const STATUS: Partial<Record<RunStatus, string>> = {
  [RunStatus.RUNNING]: "Running",
  [RunStatus.PASSED]: "Passed",
  [RunStatus.FAILED]: "Failed",
  [RunStatus.STOPPED]: "Stopped",
};

const EVIDENCE = [
  [DiagnosticKind.DIAGNOSTIC_SCREENSHOT, "Screenshot"],
  [DiagnosticKind.DIAGNOSTIC_HIERARCHY, "Hierarchy"],
  [DiagnosticKind.DIAGNOSTIC_DEVICE_LOGS, "Device logs"],
] as const;

/** The last replays of this Studio, and what to capture from the device now, when asked. */
export function Runs({ client, revision, busy }: { client: StudioClient; revision: bigint; busy: boolean }) {
  const [runs, setRuns] = useState<RunResult[]>([]);
  const [error, setError] = useState<string | null>(null);

  const list = useCallback(() => {
    client.listRuns({}).then(
      (r) => setRuns(r.runs),
      () => undefined, // the next change reads them again
    );
  }, [client]);
  useEffect(list, [list, revision, busy]);

  const results = async (run: RunResult) => {
    setError(null);
    try {
      const { document } = await client.getRun({ id: run.id });
      download(document, `tap-run-${run.id}.json`, "application/json");
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  const capture = async (kind: DiagnosticKind) => {
    setError(null);
    try {
      const { data, filename, contentType } = await client.exportDiagnostic({ kind });
      download(data, filename, contentType);
    } catch (e) {
      setError(errorMessage(e));
    }
  };

  return (
    <details className="library">
      <summary>Runs ({runs.length})</summary>
      {error && (
        <div className="error" role="alert">
          {error}
        </div>
      )}
      {runs.length > 0 && (
        <ul className="library-list" aria-label="Recent runs">
          {runs.map((run) => (
            <li key={run.id}>
              <span className="library-name">{STATUS[run.status] ?? "Unknown"}</span>
              <span className="count">
                {run.ran} of {run.planned} step{run.planned === 1 ? "" : "s"} on {run.serial}
                {run.startedAt ? ` · ${timestampDate(run.startedAt).toLocaleTimeString()}` : ""}
              </span>
              <button type="button" className="btn small" aria-label={`Download the results of the run at ${run.id}`} onClick={() => void results(run)}>
                Results
              </button>
            </li>
          ))}
        </ul>
      )}
      <div className="actions" role="group" aria-label="Capture from the device">
        <span className="unit">Capture now:</span>
        {EVIDENCE.map(([kind, label]) => (
          <button key={kind} type="button" className="btn small" disabled={busy} onClick={() => void capture(kind)}>
            {label}
          </button>
        ))}
      </div>
      <p className="hint">Results stay in memory and are never automatic evidence: Studio captures the screen or logs only when you ask, as they are now, and either can show private data.</p>
    </details>
  );
}
