import { useId, useState } from "react";
import { Row } from "./controls";
import type { PerformRequest } from "./gen/studio_pb";
import * as steps from "./steps";

const LAUNCH: { operation: steps.AppOperation; label: string; title: string; primary?: boolean }[] = [
  { operation: "cold_launch", label: "Cold launch", title: "Force-stop, then launch: a new process", primary: true },
  { operation: "launch", label: "Launch", title: "Start its launcher activity (brings a running app to the front)" },
];

const STOP: { operation: steps.AppOperation; label: string; title: string }[] = [
  { operation: "force_stop", label: "Force stop", title: "Close the app: kill its processes" },
  { operation: "clear_data", label: "Clear data", title: "Wipe its data; also revokes permissions" },
];

const WAITS: { kind: steps.AppWait; label: string; title: string }[] = [
  { kind: "visible", label: "In the foreground", title: "awaitVisible(): the app owns the focused window" },
  { kind: "stable", label: "Screen stable", title: "awaitScreenStable(): neither the elements nor the pixels change for 0.5 s" },
  { kind: "settled", label: "Settled", title: "awaitSettled(): the elements do not change for 0.5 s" },
  { kind: "animation_end", label: "Animation ended", title: "awaitAnimationEnd(): the pixels do not change for 0.5 s" },
];

type Props = {
  /** The package, as typed. */
  appPackage: string;
  onAppPackage: (pkg: string) => void;
  /** The packages of the windows on screen, offered as suggestions. */
  packages: string[];
  busy: boolean;
  onPerform: (request: PerformRequest) => void;
};

/**
 * The composer's App tab: the app under test, apart from any element. Which package it is, how
 * to launch, stop or reset it, and the waits on the app as a whole. Its package is also what a
 * replay offers to cold launch first.
 */
export function AppControls({ appPackage, onAppPackage, packages, busy, onPerform }: Props) {
  const id = useId();
  const [permission, setPermission] = useState("");
  const app = appPackage.trim();
  const usable = !!app && !busy;
  const suggested = packages.filter((p) => p !== app);
  return (
    <div className="appctl" role="group" aria-label="App">
      <div className="crow">
        <label className="rl" htmlFor={`${id}-pkg`}>
          Package
        </label>
        <div className="actions">
          <input
            id={`${id}-pkg`}
            className="mono grow"
            placeholder="com.example.app"
            aria-label="App package"
            list={`${id}-packages`}
            spellCheck={false}
            autoCapitalize="off"
            value={appPackage}
            onChange={(e) => onAppPackage(e.target.value)}
          />
          <datalist id={`${id}-packages`}>
            {suggested.map((p) => (
              <option key={p} value={p} />
            ))}
          </datalist>
        </div>
        {!app && suggested[0] ? (
          <div className="why">
            <button type="button" className="btn ghost use" title="Use the app on screen" onClick={() => onAppPackage(suggested[0]!)}>
              Use {suggested[0]}
            </button>
          </div>
        ) : (
          !app && <div className="why">Enter the app's package to launch, stop or wait for it.</div>
        )}
      </div>
      <Row label="Launch">
        {LAUNCH.map(({ operation, label, title, primary }) => (
          <button
            key={operation}
            type="button"
            className={primary ? "btn primary" : "btn"}
            title={title}
            disabled={!usable}
            onClick={() => onPerform(steps.app(operation, app))}
          >
            {label}
          </button>
        ))}
      </Row>
      <Row label="Stop">
        {STOP.map(({ operation, label, title }) => (
          <button key={operation} type="button" className="btn" title={title} disabled={!usable} onClick={() => onPerform(steps.app(operation, app))}>
            {label}
          </button>
        ))}
      </Row>
      <form
        className="crow"
        aria-label="Grant a permission"
        onSubmit={(e) => {
          e.preventDefault();
          if (permission.trim()) onPerform(steps.app("grant_permission", app, permission.trim()));
        }}
      >
        <label className="rl" htmlFor={`${id}-perm`}>
          Permission
        </label>
        <div className="actions">
          <input
            id={`${id}-perm`}
            className="mono grow"
            placeholder="android.permission.CAMERA"
            spellCheck={false}
            value={permission}
            onChange={(e) => setPermission(e.target.value)}
          />
          <button type="submit" className="btn" disabled={!usable || !permission.trim()}>
            Grant
          </button>
        </div>
      </form>
      <Row label="Wait until">
        {WAITS.map(({ kind, label, title }) => (
          <button key={kind} type="button" className="btn wait" title={title} disabled={!usable} onClick={() => onPerform(steps.appWait(kind, app))}>
            {label}
          </button>
        ))}
      </Row>
    </div>
  );
}
