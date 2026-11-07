// The page: a top bar (views, daemon status), then Live or Library. Live is the wall of every
// device until one is opened; then the device pane, its screen and its actions.

import { useCallback, useEffect, useMemo, useState, type CSSProperties } from "react";
import { api } from "./api";
import { DeviceHeader } from "./DeviceHeader";
import { FleetPane } from "./FleetPane";
import { fleet, readout } from "./fleet";
import { RecordingKind, type Recording } from "./gen/watcher_pb";
import { useActivity, useDevices, useNow, usePageVisible, usePoll, useScreens, useStatus, useStore, store } from "./hooks";
import { ScreensContext } from "./screens";
import * as Icon from "./icons";
import { Library } from "./Library";
import { Stage } from "./Stage";
import { openingFailure, stepFailure } from "./steps";
import { Timeline } from "./Timeline";
import { Wall } from "./Wall";

type View = "live" | "library";
type Toast = { id: number; message: string; failed: boolean; recording?: Recording };

function fromHash(): { view: View; serial: string } {
  const [view, serial] = window.location.hash.replace(/^#\/?/, "").split("/");
  return { view: view === "library" ? "library" : "live", serial: decodeURIComponent(serial ?? "") };
}

export function App() {
  const [view, setView] = useState<View>(() => fromHash().view);
  const [serial, setSerial] = useState(() => fromHash().serial);
  const [selectedKey, setSelectedKey] = useState<string>();
  const [comparing, setComparing] = useState(false);
  const [libraryId, setLibraryId] = useState("");
  const [toast, setToast] = useState<Toast>();
  const [liveScreens, setLiveScreens] = useState(() => {
    try {
      return globalThis.localStorage?.getItem("tap-watcher.live-screens") !== "off";
    } catch {
      return true;
    }
  });
  const [paneWidth, setPaneWidth] = useState(152);
  const now = useNow(1000);

  useStore();
  const activity = useActivity();
  const devices = useDevices();
  const library = usePoll((signal) => api.listRecordings({}, { signal }), view === "library" ? 5000 : 30000);

  // Deep link: #/live/<serial>, #/library; the URL follows the view and back/forward moves it.
  useEffect(() => {
    const onHash = () => {
      const target = fromHash();
      setView(target.view);
      setSerial(target.serial);
      setSelectedKey(undefined);
      setComparing(false);
    };
    window.addEventListener("hashchange", onHash);
    return () => window.removeEventListener("hashchange", onHash);
  }, []);
  useEffect(() => {
    const next = `#/${view}${view === "live" && serial ? `/${encodeURIComponent(serial)}` : ""}`;
    if (window.location.hash !== next) window.history.replaceState(null, "", next);
  }, [view, serial]);

  const inventory = devices.value?.devices ?? [];
  // store.version: the activity store is mutable; this re-derives on every change.
  const everyDevice = useMemo(
    () => fleet(inventory, store.connections.values(), (s) => store.device(s), (s) => store.owner(s)),
    [inventory, store.version], // eslint-disable-line react-hooks/exhaustive-deps
  );

  // One stream for every tile's screen, while Live shows tiles, screens are on and the tab is
  // visible: a hidden tab closes it, so the devices stop encoding for it.
  const pageVisible = usePageVisible();
  const screenSerials = useMemo(
    () => (view === "live" && liveScreens && pageVisible ? everyDevice.filter((entry) => entry.streamable && entry.serial !== serial).map((entry) => entry.serial) : []),
    [view, liveScreens, pageVisible, everyDevice, serial],
  );
  const screens = useScreens(screenSerials);

  const status = useStatus();
  const keptMs = (status.value?.history?.maxSeconds ?? 120) * 1000;
  // Opening a device shows its newest failure the viewer has not seen (preferring one whose
  // video is still kept), and marks them seen.
  const open = useCallback(
    (next: string) => {
      setSelectedKey(openingFailure(store.unseen(next), Date.now(), keptMs)?.key);
      setComparing(false);
      setSerial(next);
    },
    [keptMs],
  );
  const toWall = useCallback(() => {
    setSerial("");
    setSelectedKey(undefined);
    setComparing(false);
  }, []);
  const stepDevice = useCallback(
    (dir: 1 | -1) => {
      if (!everyDevice.length) return;
      const index = everyDevice.findIndex((entry) => entry.serial === serial);
      open(everyDevice[(index < 0 ? 0 : index + dir + everyDevice.length) % everyDevice.length].serial);
    },
    [everyDevice, serial, open],
  );
  // n / N and the failure buttons: the next or previous failure, shown before / after.
  const moveFailure = useCallback(
    (dir: 1 | -1) => {
      const actions = store.device(serial).actions;
      const next = stepFailure(actions, actions.find((action) => action.key === selectedKey), dir);
      if (!next) return;
      setSelectedKey(next.key);
      setComparing(true);
    },
    [serial, selectedKey],
  );
  useEffect(() => {
    if (serial) store.markSeen(serial);
  }, [serial, store.version]); // eslint-disable-line react-hooks/exhaustive-deps

  // G: back to the wall; [ and ]: the previous or next device, in the wall's order.
  useEffect(() => {
    if (view !== "live") return;
    const onKey = (event: KeyboardEvent) => {
      const target = event.target as HTMLElement | null;
      if (event.ctrlKey || event.metaKey || event.altKey || target?.closest("input, textarea, select")) return;
      if ((event.key === "g" || event.key === "G") && serial) toWall();
      else if (event.key === "[" || event.key === "]") stepDevice(event.key === "]" ? 1 : -1);
      else if ((event.key === "n" || event.key === "N") && serial) moveFailure(event.key === "n" ? 1 : -1);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [view, serial, stepDevice, moveFailure, toWall]);

  const switchScreens = useCallback((on: boolean) => {
    setLiveScreens(on);
    try {
      globalThis.localStorage?.setItem("tap-watcher.live-screens", on ? "on" : "off");
    } catch {
      // Not remembered in a private window.
    }
  }, []);

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
  const onSelect = useCallback((key: string | undefined) => {
    setSelectedKey(key);
    if (!key) setComparing(false);
  }, []);
  const entryIndex = everyDevice.findIndex((entry) => entry.serial === serial);
  const entry = everyDevice[entryIndex];
  // Once the inventory is in, a device without a screen is not asked for one.
  const unavailable = devices.value && (!entry || !entry.streamable) ? (entry ? readout(entry, now).step : "the daemon does not list this device") : undefined;
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
      <ScreensContext value={screens}>
        {view === "live" && !serial ? (
          <main className="live-wall">
            <Wall devices={everyDevice} now={now} live={liveScreens} recording={recordingSerials} onLive={switchScreens} onOpen={open} />
          </main>
        ) : view === "live" ? (
          <main className="live" style={{ "--pane": `${paneWidth}px` } as CSSProperties}>
            <FleetPane devices={everyDevice} current={serial} now={now} live={liveScreens} recording={recordingSerials} onOpen={open} onWidth={setPaneWidth} />
            <div className="device-view">
            <DeviceHeader entry={entry} serial={serial} count={everyDevice.length} index={entryIndex} onWall={toWall} onStep={stepDevice} />
            <Stage
              key={serial}
              serial={serial}
              limits={limits}
              actions={deviceActivity.actions}
              selected={selected}
              onSelect={onSelect}
              running={deviceActivity.running}
              marks={deviceActivity.marks}
              unavailable={unavailable}
              comparing={comparing}
              onComparing={setComparing}
              failures={deviceActivity.failures}
              onFailure={moveFailure}
              recording={status.value?.recordings.find((entry) => entry.serial === serial)}
              onRecordingChanged={status.refresh}
              onSaved={onSaved}
              notify={notify}
            />
            </div>
            <Timeline
              key={serial}
              actions={deviceActivity.actions}
              marks={deviceActivity.marks}
              failures={deviceActivity.failures}
              held={Boolean(entry?.owner)}
              dropped={store.dropped}
              selected={selected}
              onSelect={onSelect}
            />
          </main>
        ) : (
          <main className="library-view">
            <Library library={library.value} error={library.error} selected={libraryId} onSelect={setLibraryId} onChanged={library.refresh} notify={notify} />
          </main>
        )}
      </ScreensContext>
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

