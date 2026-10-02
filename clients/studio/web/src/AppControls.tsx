import { create } from "@bufbuild/protobuf";
import { useId, useState } from "react";
import { Row } from "./controls";
import { IntentExtraSchema, type IntentExtra } from "./gen/app_pb";
import type { PerformRequest } from "./gen/studio_pb";
import { KEY_HOME } from "./keys";
import * as steps from "./steps";

const LAUNCH: { operation: steps.AppOperation; label: string; title: string; primary?: boolean }[] = [
  { operation: "cold_launch", label: "Cold launch", title: "Force-stop, then launch: a new process", primary: true },
  { operation: "launch", label: "Launch", title: "Start the activity (brings a running app to the front)" },
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

type ExtraType = "string" | "int" | "long" | "float" | "bool";
type ExtraRow = { key: string; type: ExtraType; value: string };

const INT_MAX = 2 ** 31 - 1;

/** A launch extra from its row; `null` when the value does not read as its type. */
function extraOf({ key, type, value }: ExtraRow): IntentExtra | null {
  const v = value.trim();
  if (!key.trim()) return null;
  switch (type) {
    case "string":
      return create(IntentExtraSchema, { key: key.trim(), value: { case: "stringValue", value } });
    case "bool":
      return create(IntentExtraSchema, { key: key.trim(), value: { case: "boolValue", value: v === "true" } });
    case "int":
      return /^-?\d+$/.test(v) && Math.abs(Number(v)) <= INT_MAX
        ? create(IntentExtraSchema, { key: key.trim(), value: { case: "intValue", value: Number(v) } })
        : null;
    case "long":
      return /^-?\d+$/.test(v) ? create(IntentExtraSchema, { key: key.trim(), value: { case: "longValue", value: BigInt(v) } }) : null;
    case "float":
      return v !== "" && Number.isFinite(Number(v)) ? create(IntentExtraSchema, { key: key.trim(), value: { case: "floatValue", value: Number(v) } }) : null;
  }
}

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
  const [activity, setActivity] = useState("");
  const [extras, setExtras] = useState<ExtraRow[]>([]);
  const [link, setLink] = useState("");
  const [anyApp, setAnyApp] = useState(false);
  const [locales, setLocales] = useState("");
  const app = appPackage.trim();
  const built = extras.map(extraOf);
  const extrasOk = built.every((e) => e !== null);
  const launchWith = { activity: activity.trim() || undefined, extras: built.filter((e): e is IntentExtra => e !== null) };
  const editExtra = (i: number, change: Partial<ExtraRow>) => setExtras(extras.map((row, j) => (j === i ? { ...row, ...change } : row)));
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
            disabled={!usable || !extrasOk}
            onClick={() => onPerform(steps.app(operation, app, launchWith))}
          >
            {label}
          </button>
        ))}
      </Row>
      <div className="crow">
        <label className="rl" htmlFor={`${id}-activity`}>
          Activity
        </label>
        <div className="actions">
          <input
            id={`${id}-activity`}
            className="mono grow"
            placeholder="The launcher activity"
            spellCheck={false}
            autoCapitalize="off"
            value={activity}
            onChange={(e) => setActivity(e.target.value)}
          />
        </div>
        <div className="why">Which activity the launches start, such as .ui.SettingsActivity. Left empty, the one the home screen starts.</div>
      </div>
      <div className="crow">
        <span className="rl">Extras</span>
        <div className="extras">
          {extras.map((row, i) => (
            <div key={i} className="extra" role="group" aria-label={`Extra ${i + 1}`}>
              <input className="mono" placeholder="key" aria-label="Key" spellCheck={false} value={row.key} onChange={(e) => editExtra(i, { key: e.target.value })} />
              <select aria-label="Type" value={row.type} onChange={(e) => editExtra(i, { type: e.target.value as ExtraType, value: e.target.value === "bool" ? "true" : row.value })}>
                <option value="string">String</option>
                <option value="int">Int</option>
                <option value="long">Long</option>
                <option value="float">Float</option>
                <option value="bool">Boolean</option>
              </select>
              {row.type === "bool" ? (
                <select aria-label="Value" value={row.value} onChange={(e) => editExtra(i, { value: e.target.value })}>
                  <option value="true">true</option>
                  <option value="false">false</option>
                </select>
              ) : (
                <input
                  aria-label="Value"
                  placeholder="value"
                  inputMode={row.type === "string" ? undefined : "decimal"}
                  aria-invalid={built[i] === null && (row.key !== "" || row.value !== "")}
                  value={row.value}
                  onChange={(e) => editExtra(i, { value: e.target.value })}
                />
              )}
              <button type="button" className="btn ghost" aria-label={`Remove extra ${i + 1}`} onClick={() => setExtras(extras.filter((_, j) => j !== i))}>
                Remove
              </button>
            </div>
          ))}
          <button type="button" className="btn ghost add" onClick={() => setExtras([...extras, { key: "", type: "string", value: "" }])}>
            Add extra
          </button>
        </div>
        {!extrasOk && <div className="why bad">Every extra needs a key and a value of its type before the app can launch with it.</div>}
      </div>
      <Row label="Running app">
        <button type="button" className="btn" title="foreground(): bring it back to the front as Recents does, without a new launch" disabled={!usable} onClick={() => onPerform(steps.app("foreground", app))}>
          Foreground
        </button>
        <button type="button" className="btn" title="pressHome(): send it to the background" disabled={busy} onClick={() => onPerform(steps.pressKey(KEY_HOME))}>
          Background
        </button>
      </Row>
      <form
        className="crow"
        aria-label="Open a link"
        onSubmit={(e) => {
          e.preventDefault();
          if (link.trim()) onPerform(steps.app("open_link", app, { uri: link.trim(), anyApp }));
        }}
      >
        <label className="rl" htmlFor={`${id}-link`}>
          Link
        </label>
        <div className="actions">
          <input
            id={`${id}-link`}
            className="mono grow"
            placeholder="https://example.com/item/42"
            spellCheck={false}
            autoCapitalize="off"
            value={link}
            onChange={(e) => setLink(e.target.value)}
          />
          <label className="chk" title="Let any app handle it (a chooser or the browser) instead of this one">
            <input type="checkbox" checked={anyApp} onChange={(e) => setAnyApp(e.target.checked)} />
            Any app
          </label>
          <button type="submit" className="btn" disabled={!usable || !link.trim()}>
            Open
          </button>
        </div>
        <div className="why">A deep link, opened in this app unless Any app is checked.</div>
      </form>
      <Row label="Stop">
        {STOP.map(({ operation, label, title }) => (
          <button key={operation} type="button" className="btn" title={title} disabled={!usable} onClick={() => onPerform(steps.app(operation, app))}>
            {label}
          </button>
        ))}
      </Row>
      <form
        className="crow"
        aria-label="Grant or revoke a permission"
        onSubmit={(e) => {
          e.preventDefault();
          const operation = (e.nativeEvent as SubmitEvent).submitter?.getAttribute("value") === "revoke" ? "revoke_permission" : "grant_permission";
          if (permission.trim()) onPerform(steps.app(operation, app, { permission: permission.trim() }));
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
          <button type="submit" value="grant" className="btn" disabled={!usable || !permission.trim()}>
            Grant
          </button>
          <button type="submit" value="revoke" className="btn" title="Revoking kills the app, as Android does" disabled={!usable || !permission.trim()}>
            Revoke
          </button>
        </div>
      </form>
      <form
        className="crow"
        aria-label="App languages"
        onSubmit={(e) => {
          e.preventDefault();
          const tags = locales.split(/[\s,]+/).filter(Boolean);
          if (tags.length) onPerform(steps.app("set_locales", app, { locales: tags }));
        }}
      >
        <label className="rl" htmlFor={`${id}-locales`}>
          Languages
        </label>
        <div className="actions">
          <input
            id={`${id}-locales`}
            className="mono grow"
            placeholder="fr-FR, en-US"
            spellCheck={false}
            value={locales}
            onChange={(e) => setLocales(e.target.value)}
          />
          <button type="submit" className="btn" disabled={!usable || !locales.trim()}>
            Set
          </button>
          <button type="button" className="btn ghost" title="setLocales(emptyList()): the system's languages again" disabled={!usable} onClick={() => onPerform(steps.app("set_locales", app))}>
            Follow system
          </button>
        </div>
        <div className="why">This app's own languages (Android 13 and up), leaving the system's alone.</div>
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
