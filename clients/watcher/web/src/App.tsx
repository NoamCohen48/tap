// The page: a top bar (views, daemon status), then Live (devices, screen, actions and log) or
// Library.

import { useCallback, useEffect, useMemo, useState } from "react";
import { api } from "./api";
import { clockMs } from "./format";
import { DeviceRail } from "./DeviceRail";
import { RecordingKind, type Recording } from "./gen/watcher_pb";
import { useActivity, useDevices, useLog, useNow, usePoll, useStatus, useStore, store } from "./hooks";
import * as Icon from "./icons";
import { Library } from "./Library";
import { rowAt, type LogRow } from "./log";
import { LogPanel } from "./LogPanel";
import { Stage, type Moment, type SeekRequest } from "./Stage";
import { Timeline } from "./Timeline";

type View = "live" | "library";
type Side = "actions" | "log";
type Toast = { id: number; message: string; failed: boolean; recording?: Recording };

function fromHash(): { view: View; serial: string } {
  const [view, serial] = window.location.hash.replace(/^#\/?/, "").split("/");
  return { view: view === "library" ? "library" : "live", serial: decodeURIComponent(serial ?? "") };
}

export function App() {
  const [view, setView] = useState<View>(() => fromHash().view);
  const [serial, setSerial] = useState(() => fromHash().serial);
  const [selectedKey, setSelectedKey] = useState<string>();
  const [libraryId, setLibraryId] = useState("");
  const [toast, setToast] = useState<Toast>();
  const [side, setSide] = useState<Side>("actions");
  // The picked log entry, the place in the video it asked for, and where the video is.
  const [logSeq, setLogSeq] = useState<bigint>();
  const [seekRequest, setSeekRequest] = useState<SeekRequest>();
  const [playhead, setPlayhead] = useState<Moment>();
  const now = useNow(5000);

  useStore();
  const activity = useActivity();
  const status = useStatus();
  const devices = useDevices();
  const log = useLog(serial);
  const library = usePoll((signal) => api.listRecordings({}, { signal }), view === "library" ? 5000 : 30000);

  // Deep link: #/live/<serial>, #/library; the URL follows the view and back/forward moves it.
  useEffect(() => {
    const onHash = () => {
      const target = fromHash();
      setView(target.view);
      if (target.serial) setSerial(target.serial);
    };
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);
  useEffect(() => {
    const next = `#/${view}${view === "live" && serial ? `/${encodeURIComponent(serial)}` : ""}`;
    if (window.location.hash !== next) window.history.replaceState(null, "", next);
  }, [view, serial]);

  // Until the user picks one, watch the first device in use (else the first device).
  const inventory = devices.value?.devices ?? [];
  useEffect(() => {
    if (serial || !inventory.length) return;
    setSerial(inventory.find((device) => store.owner(device.serial))?.serial ?? inventory[0].serial);
  }, [serial, inventory]);

  const notify = useCallback((message: string, failed = false, recording?: Recording) => {
    setToast({ id: Date.now(), message, failed, recording });
  }, []);
  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(() => setToast(undefined), toast.failed ? 8000 : 5000);
    return () => clearTimeout(timer);
  }, [toast]);

  const refreshLibrary = library.refresh;
  const onSaved = useCallback(
    (recording: Recording) => {
      refreshLibrary();
      notify(`${recording.kind === RecordingKind.CLIP ? "Clip" : "Recording"} saved`, false, recording);
    },
    [refreshLibrary, notify],
  );

  const deviceActivity = store.device(serial);
  const selected = useMemo(() => deviceActivity.actions.find((action) => action.key === selectedKey), [deviceActivity, selectedKey]);
  const onSelect = useCallback((key: string | undefined) => setSelectedKey(key), []);
  // Picking a log entry shows the screen when the daemon got it.
  const onPickLog = useCallback((row: LogRow | undefined) => {
    setLogSeq(row?.seq);
    if (row) setSeekRequest((last) => ({ id: (last?.id ?? 0) + 1, clockId: row.clockId, ns: row.ns }));
  }, []);
  // A log tick on the scrubber: the screen is already there, so only open the entry.
  const onLogTick = useCallback((row: LogRow) => {
    setLogSeq(row.seq);
    setSide("log");
  }, []);
  const logRows = log.store.rows;
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const logAt = useMemo(() => (playhead ? (rowAt(logRows, playhead.clockId, playhead.ns)?.seq ?? 0n) : undefined), [playhead, logRows, log.version]);
  const logTime = useCallback((row: LogRow) => clockMs(row.epochMs), []);
  const failedLog = useMemo(() => logRows.reduce((count, row) => count + (row.bad ? 1 : 0), 0), [logRows, log.version]);
  const recordingSerials = useMemo(() => new Set(status.value?.recordings.map((entry) => entry.serial)), [status.value]);
  const history = status.value?.history;
  const limits = useMemo(() => (history ? { maxSeconds: history.maxSeconds, maxBytes: Number(history.maxBytes) } : undefined), [history]);

  if (status.denied || activity.denied) {
    return (
      <main className="gate">
        <Icon.Logo />
        <h1>Open the login link</h1>
        <p>This page needs the one-time login URL that <code>tap-watcher</code> printed when it started.</p>
      </main>
    );
  }

  const backEndDown = Boolean(status.error);
  const daemon = status.value;
  const pill = backEndDown
    ? { tone: "bad", text: "Watcher back end unreachable", title: status.error }
    : !daemon
      ? { tone: "idle", text: "Connecting…", title: "" }
      : daemon.daemonConnected
        ? { tone: "ok", text: "Daemon connected", title: "" }
        : { tone: "bad", text: "Daemon not reachable", title: daemon.daemonError };

  return (
    <div className="app">
      <header className="topbar">
        <span className="brand">
          <Icon.Logo /> Tap Watch
        </span>
        <nav className="tabs" aria-label="View">
          <button aria-current={view === "live" ? "page" : undefined} onClick={() => setView("live")}>
            Live
          </button>
          <button aria-current={view === "library" ? "page" : undefined} onClick={() => setView("library")}>
            Library {library.value && library.value.recordings.length > 0 && <span className="count">{library.value.recordings.length}</span>}
          </button>
        </nav>
        <span className="spacer" />
        {(status.value?.recordings.length ?? 0) > 0 && (
          <span className="pill rec" title="Recordings run on the watcher back end; closing this tab does not stop them.">
            <span className="dot" /> Recording {status.value!.recordings.map((entry) => entry.serial).join(", ")}
          </span>
        )}
        <span className={`pill ${pill.tone}`} title={pill.title} role="status">
          <span className="dot" /> {pill.text}
        </span>
      </header>
      {daemon && !daemon.daemonConnected && daemon.daemonError && !backEndDown && (
        <p className="banner" role="alert">
          <Icon.Alert /> {daemon.daemonError}. Start it with <code>tap start</code>; the watcher reconnects on its own.
        </p>
      )}
      {view === "live" ? (
        <main className="live">
          <DeviceRail
            devices={inventory}
            recording={recordingSerials}
            selected={serial}
            now={now}
            onSelect={(next) => {
              setSerial(next);
              setSelectedKey(undefined);
              setLogSeq(undefined);
              setSeekRequest(undefined);
            }}
          />
          <Stage
            serial={serial}
            limits={limits}
            actions={deviceActivity.actions}
            selected={selected}
            onSelect={onSelect}
            recording={status.value?.recordings.find((entry) => entry.serial === serial)}
            onRecordingChanged={status.refresh}
            onSaved={onSaved}
            notify={notify}
            log={logRows}
            logVersion={log.version}
            seekRequest={seekRequest}
            onPlayhead={setPlayhead}
            onLogTick={onLogTick}
          />
          <div className="side">
            <div className="side-tabs" role="tablist" aria-label="Beside the screen">
              <button role="tab" aria-selected={side === "actions"} onClick={() => setSide("actions")}>
                Actions <span className="count">{deviceActivity.actions.length}</span>
              </button>
              <button role="tab" aria-selected={side === "log"} onClick={() => setSide("log")}>
                Log <span className={`count${failedLog ? " bad" : ""}`}>{logRows.length}</span>
              </button>
            </div>
            <Timeline
              actions={deviceActivity.actions}
              failures={deviceActivity.failures}
              dropped={store.dropped}
              selected={selected}
              onSelect={onSelect}
              hidden={side !== "actions"}
            />
            <LogPanel
              rows={logRows}
              version={log.version}
              time={logTime}
              timeLabel="Host time"
              at={logAt}
              selected={logSeq}
              onPick={onPickLog}
              hidden={side !== "log"}
              note={log.error || log.store.unavailable || (log.store.dropped > 0n ? `${log.store.dropped} older entries are no longer kept.` : undefined)}
              empty={serial ? "No log for this device yet. It is read while a test has the device attached." : "Pick a device to read its log."}
            />
          </div>
        </main>
      ) : (
        <main className="library-view">
          <Library library={library.value} error={library.error} selected={libraryId} onSelect={setLibraryId} onChanged={library.refresh} notify={notify} />
        </main>
      )}
      {toast && (
        <div key={toast.id} className={`toast${toast.failed ? " bad" : ""}`} role={toast.failed ? "alert" : "status"}>
          {toast.failed ? <Icon.Alert /> : <Icon.Check />}
          <span>{toast.message}</span>
          {toast.recording && (
            <button
              className="link"
              onClick={() => {
                setLibraryId(toast.recording!.id);
                setView("library");
                setToast(undefined);
              }}
            >
              Open in library
            </button>
          )}
          <button className="icon-button" aria-label="Dismiss" onClick={() => setToast(undefined)}>
            <Icon.Close />
          </button>
        </div>
      )}
    </div>
  );
}

