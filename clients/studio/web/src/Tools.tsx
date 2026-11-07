import { fromJsonString, toJsonString } from "@bufbuild/protobuf";
import { useEffect, useRef, useState } from "react";
import type { StudioClient } from "./api";
import { errorMessage } from "./frames";
import { AppQuery, DatabaseBundleSchema, DiagnosticKind, PrivateDataOperation, type PrivateDataResponse } from "./gen/studio_pb";

const LIMIT = 1 << 20;
const MUTATIONS = new Set([PrivateDataOperation.PRIVATE_DATA_WRITE, PrivateDataOperation.PRIVATE_DATA_DELETE, PrivateDataOperation.PRIVATE_DATA_IMPORT_DATABASE]);
const PRIVATE = [
  [PrivateDataOperation.PRIVATE_DATA_CAPABILITIES, "Capabilities"], [PrivateDataOperation.PRIVATE_DATA_LIST, "List files"],
  [PrivateDataOperation.PRIVATE_DATA_READ, "Download file"], [PrivateDataOperation.PRIVATE_DATA_WRITE, "Write uploaded file"],
  [PrivateDataOperation.PRIVATE_DATA_DELETE, "Delete file"], [PrivateDataOperation.PRIVATE_DATA_EXPORT_DATABASE, "Export database bundle"],
  [PrivateDataOperation.PRIVATE_DATA_IMPORT_DATABASE, "Import database bundle"],
] as const;
const QUERIES = [[AppQuery.INSTALLED, "Installed"], [AppQuery.RUNNING, "Running"], [AppQuery.PROCESS, "Process identity"], [AppQuery.LOCALES, "App languages"], [AppQuery.PERMISSION, "Permission granted"]] as const;
const DIAGNOSTICS = [
  [DiagnosticKind.DIAGNOSTIC_SCREENSHOT, "Screenshot"], [DiagnosticKind.DIAGNOSTIC_HIERARCHY, "Diagnostic hierarchy"],
  [DiagnosticKind.DIAGNOSTIC_DEVICE_LOGS, "Device logs"], [DiagnosticKind.DIAGNOSTIC_DRIVER_LOG, "Driver log"],
  [DiagnosticKind.DIAGNOSTIC_EVENTS, "Raw event history"], [DiagnosticKind.DIAGNOSTIC_SERVER_INFO, "Daemon metadata"],
  [DiagnosticKind.DIAGNOSTIC_DEVICE_INFO, "Device metadata"], [DiagnosticKind.DIAGNOSTIC_DEVICES, "Device inventory"],
] as const;

/** Request a browser download, never claim it was saved or write arbitrary server host paths. */
export function download(data: Uint8Array | string, name: string, contentType: string) {
  const body = typeof data === "string" ? data : Uint8Array.from(data).buffer;
  const url = URL.createObjectURL(new Blob([body], { type: contentType }));
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = name.replace(/[^a-zA-Z0-9._-]/g, "_").slice(0, 100) || "download.bin";
  document.body.append(anchor);
  try { anchor.click(); } finally {
    anchor.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }
}

/** All tools are explicit and unrecorded. Parent locking prevents UI actions during private IO. */
export function Tools({ client, appPackage, onAppPackage, disabled, onBusy }: { client: StudioClient; appPackage: string; onAppPackage: (value: string) => void; disabled: boolean; onBusy: (busy: boolean) => void }) {
  const active = useRef(false);
  const [working, setWorking] = useState(false);
  const [message, setMessage] = useState("");
  const [failed, setFailed] = useState(false);
  const [query, setQuery] = useState(AppQuery.INSTALLED);
  const [permission, setPermission] = useState("");
  const [timeout, setTimeout] = useState("");
  const [operation, setOperation] = useState(PrivateDataOperation.PRIVATE_DATA_CAPABILITIES);
  const [path, setPath] = useState("");
  const [name, setName] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [consentFor, setConsentFor] = useState<string | null>(null);
  const setConfirmed = (value: boolean) => setConsentFor(value ? appPackage.trim() : null);
  useEffect(() => { setConsentFor(null); }, [appPackage]); // also invalidate an App-tab change away and back
  const [privateBudget, setPrivateBudget] = useState("");
  const [overwrite, setOverwrite] = useState(false);
  const [kind, setKind] = useState(DiagnosticKind.DIAGNOSTIC_SCREENSHOT);
  const [after, setAfter] = useState("0");
  const app = appPackage.trim();
  const confirmed = !!app && consentFor === app;
  const transferBudget = [PrivateDataOperation.PRIVATE_DATA_READ, PrivateDataOperation.PRIVATE_DATA_WRITE, PrivateDataOperation.PRIVATE_DATA_EXPORT_DATABASE, PrivateDataOperation.PRIVATE_DATA_IMPORT_DATABASE].includes(operation);
  const privateBudgetOk = privateBudget === "" || (Number.isInteger(Number(privateBudget)) && Number(privateBudget) >= 1 && Number(privateBudget) <= 120000);
  const mutating = MUTATIONS.has(operation);
  const needsFile = operation === PrivateDataOperation.PRIVATE_DATA_WRITE || operation === PrivateDataOperation.PRIVATE_DATA_IMPORT_DATABASE;
  const needsPath = [PrivateDataOperation.PRIVATE_DATA_LIST, PrivateDataOperation.PRIVATE_DATA_READ, PrivateDataOperation.PRIVATE_DATA_WRITE, PrivateDataOperation.PRIVATE_DATA_DELETE].includes(operation);
  const needsName = operation === PrivateDataOperation.PRIVATE_DATA_EXPORT_DATABASE || operation === PrivateDataOperation.PRIVATE_DATA_IMPORT_DATABASE;
  const timeoutOk = timeout === "" || (Number.isInteger(Number(timeout)) && Number(timeout) >= 1 && Number(timeout) <= 120000);
  const afterOk = /^\d{1,19}$/.test(after) && BigInt(after) <= 9223372036854775807n;

  const run = async (call: () => Promise<string>) => {
    if (disabled || active.current) return;
    active.current = true;
    setWorking(true);
    onBusy(true);
    setMessage("");
    setFailed(false);
    try { setMessage(await call()); } catch (error) {
      setFailed(true);
      setMessage(`${errorMessage(error)} Do not repeat a mutation automatically or relaunch after an interrupted database import.`);
    } finally {
      active.current = false;
      setWorking(false);
      onBusy(false);
    }
  };

  const privateTool = async (): Promise<string> => {
    if (mutating) setConfirmed(false); // consent is for this attempt, never an automatic retry
    if (transferBudget && !privateBudgetOk) throw new Error("Private transfer budget must be 1..120000 whole milliseconds.");
    let data: Uint8Array | undefined;
    let bundle;
    if (needsFile) {
      if (!file) throw new Error("Choose an upload first.");
      if (file.size > (operation === PrivateDataOperation.PRIVATE_DATA_IMPORT_DATABASE ? LIMIT * 2 : LIMIT)) throw new Error("Upload exceeds the browser transfer limit.");
      if (operation === PrivateDataOperation.PRIVATE_DATA_WRITE) data = new Uint8Array(await file.arrayBuffer());
      else {
        bundle = fromJsonString(DatabaseBundleSchema, await file.text());
        if (bundle.format !== "tap-studio-database/1" || !bundle.database.length || bundle.database.length + (bundle.wal?.length ?? 0) + (bundle.journal?.length ?? 0) > LIMIT) throw new Error("Expected a tap-studio-database/1 bundle, at most 1 MiB total.");
      }
    }
    const response: PrivateDataResponse = await client.privateData({
      packageName: app, operation, path: needsPath ? path : undefined, name: needsName ? name : undefined,
      data, bundle, confirmed: mutating && confirmed,
      timeoutMs: transferBudget && privateBudget !== "" ? BigInt(Number(privateBudget)) : undefined,
      overwrite: (operation === PrivateDataOperation.PRIVATE_DATA_WRITE || operation === PrivateDataOperation.PRIVATE_DATA_IMPORT_DATABASE) && overwrite,
    });
    if (operation === PrivateDataOperation.PRIVATE_DATA_READ) {
      download(response.data, path.split(/[\\/]/).pop() || "private.bin", "application/octet-stream");
      return "Private file download requested. Store it securely; browser permissions apply.";
    }
    if (operation === PrivateDataOperation.PRIVATE_DATA_EXPORT_DATABASE && response.bundle) {
      download(toJsonString(DatabaseBundleSchema, response.bundle), `${response.bundle.name}.tap-database.json`, "application/json");
      return "Database bundle download requested (base + present WAL/journal, no SHM). Store it securely.";
    }
    return response.json || "Completed; no implicit relaunch or restore.";
  };

  return <details className="execution-defaults tools">
    <summary>Tools (not recorded)</summary>
    <p className="hint">Explicit SDK tools, not replay steps. Transfers/downloads are limited to 1 MiB; use the SDK for larger files. Downloads may contain sensitive data and use browser permissions.</p>
    <fieldset className="execution-controls" disabled={disabled || working}>
      <legend>App and private data</legend>
      <label className="field"><span>Tool package</span><input value={appPackage} onChange={(e) => { onAppPackage(e.target.value); setConfirmed(false); }} placeholder="com.example.app" /></label>
      <label className="field"><span>App query</span><select value={query} onChange={(e) => setQuery(Number(e.target.value))}>{QUERIES.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
      {query === AppQuery.PERMISSION && <label className="field"><span>Permission to query</span><input value={permission} onChange={(e) => setPermission(e.target.value)} /></label>}
      {query === AppQuery.PROCESS && <label className="field"><span>Process query budget (ms)</span><input type="number" min={1} max={120000} value={timeout} onChange={(e) => setTimeout(e.target.value)} aria-invalid={!timeoutOk} /></label>}
      <button type="button" className="btn" disabled={!app || (query === AppQuery.PROCESS && !timeoutOk) || (query === AppQuery.PERMISSION && !permission.trim())} onClick={() => void run(async () => (await client.queryApp({ packageName: app, query, permission: query === AppQuery.PERMISSION ? permission.trim() : undefined, timeoutMs: query === AppQuery.PROCESS && timeout !== "" ? BigInt(Number(timeout)) : undefined })).json)}>Read app state</button>
      <label className="field"><span>Private data operation</span><select value={operation} onChange={(e) => { setOperation(Number(e.target.value)); setFile(null); setOverwrite(false); setConfirmed(false); }}>{PRIVATE.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
      <p className="hint">Except capabilities, explicitly force-stop the entire app UID first using App → Force stop. No automatic stop, restart, SQL or restoration. Do not drive the app concurrently. Interrupted imports may need manual recovery before any relaunch.</p>
      {needsPath && <label className="field"><span>Relative private path</span><input value={path} onChange={(e) => { setPath(e.target.value); setConfirmed(false); }} placeholder={operation === PrivateDataOperation.PRIVATE_DATA_LIST ? "empty = private root" : "files/state.bin"} /></label>}
      {needsName && <label className="field"><span>Database basename</span><input value={name} onChange={(e) => { setName(e.target.value); setConfirmed(false); }} placeholder="shop.db (empty import name keeps the bundle name)" /></label>}
      {needsFile && <label className="field"><span>{operation === PrivateDataOperation.PRIVATE_DATA_WRITE ? "Private file upload" : "Database bundle upload"}</span><input key={operation} type="file" onChange={(e) => { setFile(e.target.files?.[0] ?? null); setConfirmed(false); }} /></label>}
      {(operation === PrivateDataOperation.PRIVATE_DATA_WRITE || operation === PrivateDataOperation.PRIVATE_DATA_IMPORT_DATABASE) && <label className="check"><input type="checkbox" checked={overwrite} onChange={(e) => { setOverwrite(e.target.checked); setConfirmed(false); }} />Overwrite existing data (not restored on release)</label>}
      {transferBudget && <label className="field"><span>Private transfer budget (ms)</span><input type="number" min={1} max={120000} value={privateBudget} onChange={(e) => { setPrivateBudget(e.target.value); setConfirmed(false); }} placeholder="SDK default" aria-invalid={!privateBudgetOk} /></label>}
      {mutating && <label className="check"><input type="checkbox" checked={confirmed} onChange={(e) => setConfirmed(e.target.checked)} />I explicitly stopped the app and confirm this private-data mutation</label>}
      <button type="button" className="btn" disabled={!app || (transferBudget && !privateBudgetOk) || (mutating && !confirmed) || (needsFile && !file) || (operation === PrivateDataOperation.PRIVATE_DATA_EXPORT_DATABASE && !name) || (needsPath && operation !== PrivateDataOperation.PRIVATE_DATA_LIST && !path)} onClick={() => void run(privateTool)}>Run private data tool</button>
    </fieldset>
    <fieldset className="execution-controls" disabled={disabled || working}>
      <legend>Diagnostics and metadata</legend>
      <label className="field"><span>Diagnostic download</span><select value={kind} onChange={(e) => setKind(Number(e.target.value))}>{DIAGNOSTICS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
      {kind === DiagnosticKind.DIAGNOSTIC_EVENTS && <label className="field"><span>After event sequence</span><input inputMode="numeric" value={after} onChange={(e) => setAfter(e.target.value)} aria-invalid={!afterOk} /></label>}
      <button type="button" className="btn" disabled={kind === DiagnosticKind.DIAGNOSTIC_EVENTS && !afterOk} onClick={() => void run(async () => {
        const response = await client.exportDiagnostic({ kind, afterSeq: kind === DiagnosticKind.DIAGNOSTIC_EVENTS ? BigInt(after) : undefined });
        download(response.data, response.filename, response.contentType);
        return "Diagnostic download requested. Raw history is not a Studio recording; older ring events may have been dropped.";
      })}>Download diagnostic</button>
    </fieldset>
    {working && <p role="status">Running tool…</p>}
    {message && <pre role={failed ? "alert" : "status"} className="tool-result">{message}</pre>}
  </details>;
}
