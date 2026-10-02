import { useCallback, useEffect, useId, useState, type ReactNode } from "react";
import type { StudioClient } from "./api";
import { Group, Row } from "./controls";
import { errorMessage } from "./frames";
import { DisplayRotation, LocationAccuracy, Orientation, PermissionChoice, type DeviceNotification } from "./gen/command_pb";
import type { GetDeviceStatusResponse, PerformRequest } from "./gen/studio_pb";
import { KEY_SLEEP, KEY_WAKEUP } from "./keys";
import * as steps from "./steps";

type Props = {
  client: StudioClient;
  busy: boolean;
  onPerform: (request: PerformRequest) => void;
  /** Bumped after every step the page runs: the read-back is asked again. */
  revision: number;
};

type Status = { state: "loading" } | { state: "done"; status: GetDeviceStatusResponse } | { state: "failed"; message: string };

/**
 * The composer's Device tab: the device itself, apart from any element or app. It reads the
 * device back first (screen, rotation, keyboard, the activity in front, and every condition), so
 * each control shows where the device is now; choosing a value runs it and records the step, the
 * value it already has included, since a test sets what it relies on.
 */
export function DeviceControls({ client, busy, onPerform, revision }: Props) {
  const [status, setStatus] = useState<Status>({ state: "loading" });
  const [asked, setAsked] = useState(0);
  useEffect(() => {
    let cancelled = false;
    client.getDeviceStatus({}).then(
      (r) => !cancelled && setStatus({ state: "done", status: r }),
      (e: unknown) => !cancelled && setStatus({ state: "failed", message: errorMessage(e) }),
    );
    return () => {
      cancelled = true;
    };
  }, [client, revision, asked]);
  const now = status.state === "done" ? status.status : null;
  const usable = !busy;
  return (
    <div className="devctl" role="group" aria-label="Device">
      <NowStrip status={status} onRefresh={() => setAsked((n) => n + 1)} />
      <ScreenGroup now={now} usable={usable} onPerform={onPerform} />
      <KeyboardGroup usable={usable} onPerform={onPerform} />
      <PermissionGroup usable={usable} onPerform={onPerform} />
      <NotificationsGroup client={client} usable={usable} revision={revision} onPerform={onPerform} />
      <ConditionsGroup now={now} usable={usable} onPerform={onPerform} />
      <AssertGroup now={now} usable={usable} onPerform={onPerform} />
    </div>
  );
}

const ROTATIONS: { rotation: DisplayRotation; label: string }[] = [
  { rotation: DisplayRotation.NATURAL, label: "Natural" },
  { rotation: DisplayRotation.LEFT, label: "Left" },
  { rotation: DisplayRotation.UPSIDE_DOWN, label: "Upside down" },
  { rotation: DisplayRotation.RIGHT, label: "Right" },
];

/** The display's rotation as `DisplayRotation` (the device reports Surface.ROTATION_0..3). */
const rotationOf = (degrees: number) => ROTATIONS[degrees]?.rotation;

/** The foreground activity as the assertion takes it: `package/class`. */
const activityOf = (now: GetDeviceStatusResponse | null) =>
  now?.foregroundPackage ? `${now.foregroundPackage}/${now.foregroundActivity ?? ""}` : "";

/** The read-back: one line of what the device is now. */
function NowStrip({ status, onRefresh }: { status: Status; onRefresh: () => void }) {
  if (status.state !== "done") {
    return (
      <div className="now" aria-live="polite">
        <span className="now-l">Now</span>
        <span className="now-msg">{status.state === "loading" ? "Reading the device…" : status.message}</span>
        <button type="button" className="btn ghost" onClick={onRefresh}>
          Read again
        </button>
      </div>
    );
  }
  const now = status.status;
  const info = now.info;
  const facts: [string, ReactNode][] = info
    ? [
        ["Screen", info.screenOn ? (info.keyguardLocked ? "on, locked" : "on") : "off"],
        ["Rotation", `${ROTATIONS[info.displayRotation]?.label.toLowerCase() ?? info.displayRotation}${info.autoRotate ? ", auto" : ""}`],
        ["Keyboard", info.keyboardShown ? "shown" : "hidden"],
      ]
    : [];
  return (
    <div className="now" aria-label="The device now">
      <span className="now-l">Now</span>
      <dl>
        {facts.map(([k, v]) => (
          <div key={k}>
            <dt>{k}</dt>
            <dd>{v}</dd>
          </div>
        ))}
        <div className="wide">
          <dt>In front</dt>
          <dd className="mono">{activityOf(now) || "nothing reported"}</dd>
        </div>
      </dl>
      <button type="button" className="btn ghost" onClick={onRefresh} title="Read the device again">
        Read again
      </button>
    </div>
  );
}

/**
 * A choice of values the device holds now: each runs (and records) the step that sets it; the
 * current one is marked. Choosing it again still records it.
 */
function Choice<T>({
  label,
  options,
  current,
  usable,
  onChoose,
}: {
  label: string;
  options: { value: T; label: string; title?: string }[];
  current: T | undefined;
  usable: boolean;
  onChoose: (value: T) => void;
}) {
  return (
    <span className="seg choice" role="group" aria-label={label}>
      {options.map((o) => (
        <button
          key={o.label}
          type="button"
          className={o.value === current ? "now-v" : undefined}
          aria-pressed={o.value === current}
          title={o.title ?? (o.value === current ? `${o.label} (now); records it` : o.label)}
          disabled={!usable}
          onClick={() => onChoose(o.value)}
        >
          {o.label}
        </button>
      ))}
    </span>
  );
}

const OFF_ON = [
  { value: false, label: "Off" },
  { value: true, label: "On" },
];

function ScreenGroup({ now, usable, onPerform }: { now: GetDeviceStatusResponse | null; usable: boolean; onPerform: (r: PerformRequest) => void }) {
  const info = now?.info;
  const rotation = info && !info.autoRotate ? rotationOf(info.displayRotation) : undefined;
  return (
    <Group title="Screen">
      <Row label="Orientation">
        <button type="button" className="btn" disabled={!usable} title="setOrientation(PORTRAIT): rotates as needed, then holds it" onClick={() => onPerform(steps.setOrientation(Orientation.PORTRAIT))}>
          Portrait
        </button>
        <button type="button" className="btn" disabled={!usable} title="setOrientation(LANDSCAPE)" onClick={() => onPerform(steps.setOrientation(Orientation.LANDSCAPE))}>
          Landscape
        </button>
      </Row>
      <Row label="Rotation" why="Held until auto-rotate is given back.">
        <Choice<DisplayRotation | "auto">
          label="Display rotation"
          usable={usable}
          current={info?.autoRotate ? "auto" : rotation}
          options={[...ROTATIONS.map((r) => ({ value: r.rotation, label: r.label })), { value: "auto", label: "Auto", title: "unfreezeRotation(): the sensor rotates the display again" }]}
          onChoose={(v) => onPerform(v === "auto" ? steps.unfreezeRotation() : steps.setDisplayRotation(v))}
        />
      </Row>
      <Row label="Power" why={info?.keyguardSecure ? "The keyguard has a PIN, pattern or password: Unlock cannot pass it." : undefined}>
        <button type="button" className="btn" disabled={!usable} title="wake(): the wake-up key" onClick={() => onPerform(steps.pressKey(KEY_WAKEUP))}>
          Wake
        </button>
        <button type="button" className="btn" disabled={!usable} title="sleep(): the sleep key" onClick={() => onPerform(steps.pressKey(KEY_SLEEP))}>
          Sleep
        </button>
        <button type="button" className="btn" disabled={!usable} title="dismissKeyguard(): an insecure keyguard only" onClick={() => onPerform(steps.dismissKeyguard())}>
          Unlock
        </button>
      </Row>
    </Group>
  );
}

function KeyboardGroup({ usable, onPerform }: { usable: boolean; onPerform: (r: PerformRequest) => void }) {
  const id = useId();
  const [clip, setClip] = useState("");
  return (
    <Group title="Keyboard and clipboard">
      <Row label="Keyboard">
        <button type="button" className="btn" disabled={!usable} title="hideKeyboard(): closes the on-screen keyboard if it is shown" onClick={() => onPerform(steps.hideKeyboard())}>
          Hide keyboard
        </button>
      </Row>
      <form
        className="crow"
        aria-label="Set the clipboard"
        onSubmit={(e) => {
          e.preventDefault();
          onPerform(steps.setClipboard(clip));
        }}
      >
        <label className="rl" htmlFor={`${id}-clip`}>
          Clipboard
        </label>
        <div className="actions">
          <input id={`${id}-clip`} className="grow" placeholder="Text to paste" value={clip} onChange={(e) => setClip(e.target.value)} />
          <button type="submit" className="btn" disabled={!usable}>
            Set
          </button>
        </div>
      </form>
    </Group>
  );
}

const CHOICES: { choice: PermissionChoice; label: string }[] = [
  { choice: PermissionChoice.PERMISSION_ALLOW, label: "Allow" },
  { choice: PermissionChoice.PERMISSION_ALLOW_FOREGROUND_ONLY, label: "While using the app" },
  { choice: PermissionChoice.PERMISSION_ALLOW_ONE_TIME, label: "Only this time" },
  { choice: PermissionChoice.PERMISSION_ALLOW_ALWAYS, label: "Allow all the time" },
  { choice: PermissionChoice.PERMISSION_ALLOW_SELECTED, label: "Select photos" },
  { choice: PermissionChoice.PERMISSION_ALLOW_ALL, label: "Allow all" },
  { choice: PermissionChoice.PERMISSION_DENY, label: "Don't allow" },
  { choice: PermissionChoice.PERMISSION_DENY_AND_DONT_ASK_AGAIN, label: "Deny, don't ask again" },
  { choice: PermissionChoice.PERMISSION_KEEP_FOREGROUND_ONLY, label: "Keep while using" },
  { choice: PermissionChoice.PERMISSION_KEEP_ONE_TIME, label: "Keep only this time" },
];

const ACCURACIES = [
  { value: LocationAccuracy.LOCATION_ACCURACY_UNSPECIFIED, label: "As shown" },
  { value: LocationAccuracy.LOCATION_PRECISE, label: "Precise" },
  { value: LocationAccuracy.LOCATION_APPROXIMATE, label: "Approximate" },
];

function PermissionGroup({ usable, onPerform }: { usable: boolean; onPerform: (r: PerformRequest) => void }) {
  const id = useId();
  const [choice, setChoice] = useState(PermissionChoice.PERMISSION_ALLOW_FOREGROUND_ONLY);
  const [accuracy, setAccuracy] = useState(LocationAccuracy.LOCATION_ACCURACY_UNSPECIFIED);
  return (
    <Group title="Permission dialog" note="the system's, over the app">
      <Row label="Wait until">
        <button type="button" className="btn wait" disabled={!usable} title="awaitPermissionPrompt(): the dialog is on screen" onClick={() => onPerform(steps.awaitPermissionPrompt())}>
          Dialog shown
        </button>
      </Row>
      <div className="crow">
        <label className="rl" htmlFor={`${id}-choice`}>
          Answer
        </label>
        <div className="actions">
          <select id={`${id}-choice`} value={choice} onChange={(e) => setChoice(Number(e.target.value))}>
            {CHOICES.map((c) => (
              <option key={c.choice} value={c.choice}>
                {c.label}
              </option>
            ))}
          </select>
          <span className="seg" role="group" aria-label="Location accuracy">
            {ACCURACIES.map((a) => (
              <button key={a.value} type="button" aria-pressed={accuracy === a.value} onClick={() => setAccuracy(a.value)}>
                {a.label}
              </button>
            ))}
          </span>
          <button type="button" className="btn" disabled={!usable} onClick={() => onPerform(steps.choosePermission(choice, accuracy || undefined))}>
            Choose
          </button>
        </div>
        <div className="why">The button the dialog shows for it; the accuracy is for a location request.</div>
      </div>
    </Group>
  );
}

/** How a listed notification is matched: by its title (or its text without one), in its package. */
function queryOf(n: DeviceNotification): steps.NotificationQuery {
  return n.title !== undefined ? { title: n.title, packageName: n.packageName } : { text: n.text, packageName: n.packageName };
}

function NotificationsGroup({
  client,
  usable,
  revision,
  onPerform,
}: {
  client: StudioClient;
  usable: boolean;
  revision: number;
  onPerform: (r: PerformRequest) => void;
}) {
  const id = useId();
  const [shown, setShown] = useState<{ list: DeviceNotification[] } | { message: string } | null>(null);
  const [toast, setToast] = useState("");
  const [contains, setContains] = useState(false);
  const load = useCallback(() => {
    client.listNotifications({}).then(
      (r) => setShown({ list: r.notifications }),
      (e: unknown) => setShown({ message: errorMessage(e) }),
    );
  }, [client]);
  useEffect(load, [load, revision]);
  return (
    <Group title="Notifications and toasts">
      <div className="notes-list" aria-label="Notifications shown now">
        {shown === null ? (
          <p className="why">Reading the notifications…</p>
        ) : "message" in shown ? (
          <p className="why bad">{shown.message}</p>
        ) : shown.list.length === 0 ? (
          <p className="why">No notifications now. When the app posts one, it appears here to wait for, open or dismiss.</p>
        ) : (
          <ul>
            {shown.list.map((n) => (
              <li key={`${n.packageName}-${n.postedAtMs}-${n.title}`}>
                <div className="n-text">
                  <b>{n.title ?? n.text ?? "(no text)"}</b>
                  {n.title !== undefined && n.text && <span>{n.text}</span>}
                  <span className="n-pkg mono">{n.packageName}</span>
                </div>
                <div className="actions">
                  <button type="button" className="btn wait" disabled={!usable} title="awaitNotification(…)" onClick={() => onPerform(steps.awaitNotification(queryOf(n)))}>
                    Wait for
                  </button>
                  <button type="button" className="btn" disabled={!usable} title="openNotification(…): as a tap on it" onClick={() => onPerform(steps.openNotification(queryOf(n)))}>
                    Open
                  </button>
                  {n.actions.map((a) => (
                    <button key={a} type="button" className="btn" disabled={!usable} title={`openNotification(…, action = "${a}")`} onClick={() => onPerform(steps.openNotification(queryOf(n), a))}>
                      {a}
                    </button>
                  ))}
                  <button
                    type="button"
                    className="btn"
                    disabled={!usable || !n.clearable}
                    title={n.clearable ? "dismissNotification(…): as a swipe" : "Ongoing: it cannot be dismissed"}
                    onClick={() => onPerform(steps.dismissNotification(queryOf(n)))}
                  >
                    Dismiss
                  </button>
                </div>
              </li>
            ))}
          </ul>
        )}
        <button type="button" className="btn ghost" onClick={load}>
          Read again
        </button>
      </div>
      <form
        className="crow"
        aria-label="Wait for a toast"
        onSubmit={(e) => {
          e.preventDefault();
          onPerform(steps.awaitToast({ text: toast || undefined, contains }));
        }}
      >
        <label className="rl" htmlFor={`${id}-toast`}>
          Toast
        </label>
        <div className="actions">
          <span className="seg" role="group" aria-label="How the text matches">
            <button type="button" aria-pressed={!contains} onClick={() => setContains(false)}>
              equals
            </button>
            <button type="button" aria-pressed={contains} onClick={() => setContains(true)}>
              contains
            </button>
          </span>
          <input id={`${id}-toast`} className="grow" placeholder="Any toast" value={toast} onChange={(e) => setToast(e.target.value)} />
          <button type="submit" className="btn wait" disabled={!usable}>
            Wait
          </button>
        </div>
        <div className="why">Toasts come and go: wait for one right after the step that shows it.</div>
      </form>
    </Group>
  );
}

const FONT_SCALES = [0.85, 1, 1.15, 1.3, 1.5, 2];

function ConditionsGroup({ now, usable, onPerform }: { now: GetDeviceStatusResponse | null; usable: boolean; onPerform: (r: PerformRequest) => void }) {
  const id = useId();
  const info = now?.info;
  const [density, setDensity] = useState("");
  const [locales, setLocales] = useState<string | null>(null);
  const [place, setPlace] = useState({ latitude: "", longitude: "", accuracy: "", altitude: "" });
  const shownLocales = locales ?? info?.systemLocales.join(", ") ?? "";
  const toggle = (label: string, current: boolean | undefined, run: (on: boolean) => steps.DeviceCondition) => (
    <Row label={label}>
      <Choice label={label} usable={usable} current={current} options={OFF_ON} onChoose={(on) => onPerform(steps.deviceCondition(run(on)))} />
    </Row>
  );
  const number = (text: string) => (text.trim() === "" ? undefined : Number(text));
  const lat = number(place.latitude);
  const lon = number(place.longitude);
  const placeOk = lat !== undefined && lon !== undefined && Math.abs(lat) <= 90 && Math.abs(lon) <= 180;
  return (
    <Group title="Conditions" note="held until the device is released, then put back">
      {toggle("Animations", info?.animationsEnabled, (enabled) => ({ operation: "set_animations", enabled }))}
      {toggle("Dark mode", info?.darkMode, (enabled) => ({ operation: "set_dark_mode", enabled }))}
      {toggle("Stay awake", info?.stayAwake, (enabled) => ({ operation: "set_stay_awake", enabled }))}
      <Row label="Font size">
        <Choice
          label="Font scale"
          usable={usable}
          current={info ? FONT_SCALES.find((s) => Math.abs(s - info.fontScale) < 0.01) : undefined}
          options={FONT_SCALES.map((s) => ({ value: s, label: `${s}×` }))}
          onChoose={(fontScale) => onPerform(steps.deviceCondition({ operation: "set_font_scale", fontScale }))}
        />
      </Row>
      <form
        className="crow"
        aria-label="Display density"
        onSubmit={(e) => {
          e.preventDefault();
          const dpi = Number(density);
          if (Number.isInteger(dpi) && dpi > 0) onPerform(steps.deviceCondition({ operation: "set_density", densityDpi: dpi }));
        }}
      >
        <label className="rl" htmlFor={`${id}-dpi`}>
          Density
        </label>
        <div className="actions">
          <input id={`${id}-dpi`} className="num" inputMode="numeric" placeholder={info ? String(info.densityDpi) : ""} value={density} onChange={(e) => setDensity(e.target.value)} />
          <span className="unit">dpi{info ? `, now ${info.densityDpi}` : ""}</span>
          <button type="submit" className="btn" disabled={!usable || !/^\d+$/.test(density)}>
            Set
          </button>
          <button type="button" className="btn ghost" disabled={!usable} title="setDensity(null): the display's own density" onClick={() => onPerform(steps.deviceCondition({ operation: "set_density" }))}>
            Default
          </button>
        </div>
      </form>
      {toggle("Airplane", info?.airplaneMode, (airplaneMode) => ({ operation: "set_network", airplaneMode }))}
      {toggle("Wi-Fi", info?.wifiEnabled, (wifi) => ({ operation: "set_network", wifi }))}
      {toggle("Mobile data", info?.mobileDataEnabled, (mobileData) => ({ operation: "set_network", mobileData }))}
      {toggle("High contrast", info?.highContrastText, (highContrastText) => ({ operation: "set_accessibility_display", highContrastText }))}
      {toggle("Invert colors", info?.colorInversion, (colorInversion) => ({ operation: "set_accessibility_display", colorInversion }))}
      {toggle("Bold text", info?.boldText, (boldText) => ({ operation: "set_accessibility_display", boldText }))}
      <form
        className="crow"
        aria-label="System languages"
        onSubmit={(e) => {
          e.preventDefault();
          const tags = shownLocales.split(/[\s,]+/).filter(Boolean);
          if (tags.length) onPerform(steps.deviceCondition({ operation: "set_system_locales", locales: tags }));
        }}
      >
        <label className="rl" htmlFor={`${id}-locales`}>
          Languages
        </label>
        <div className="actions">
          <input id={`${id}-locales`} className="mono grow" placeholder="en-US, fr-FR" spellCheck={false} value={shownLocales} onChange={(e) => setLocales(e.target.value)} />
          <button type="submit" className="btn" disabled={!usable || !shownLocales.trim()}>
            Set
          </button>
        </div>
        <div className="why">The system's, in order of preference, as BCP 47 tags.</div>
      </form>
      <form
        className="crow"
        aria-label="Location"
        onSubmit={(e) => {
          e.preventDefault();
          if (!placeOk) return;
          onPerform(
            steps.deviceCondition({
              operation: "set_location",
              latitude: lat,
              longitude: lon,
              accuracyM: number(place.accuracy),
              altitudeM: number(place.altitude),
            }),
          );
        }}
      >
        <span className="rl">Location</span>
        <div className="actions place">
          {(
            [
              ["latitude", "Latitude", "51.5072"],
              ["longitude", "Longitude", "-0.1276"],
              ["accuracy", "Accuracy, m", "optional"],
              ["altitude", "Altitude, m", "optional"],
            ] as const
          ).map(([key, label, hint]) => (
            <label key={key} className="field">
              <span>{label}</span>
              <input inputMode="decimal" placeholder={hint} value={place[key]} onChange={(e) => setPlace({ ...place, [key]: e.target.value })} />
            </label>
          ))}
          <button type="submit" className="btn" disabled={!usable || !placeOk}>
            Set
          </button>
        </div>
        <div className="why">A mock location the apps read; real positioning comes back on release.</div>
      </form>
    </Group>
  );
}

function AssertGroup({ now, usable, onPerform }: { now: GetDeviceStatusResponse | null; usable: boolean; onPerform: (r: PerformRequest) => void }) {
  const id = useId();
  const [activity, setActivity] = useState<string | null>(null);
  const [clip, setClip] = useState("");
  const shown = activity ?? activityOf(now);
  return (
    <Group title="Assert" note="the device as it is now">
      <form
        className="crow"
        aria-label="Assert the foreground activity"
        onSubmit={(e) => {
          e.preventDefault();
          onPerform(steps.deviceAssertion(steps.DeviceCheck.FOREGROUND_ACTIVITY, shown.trim()));
        }}
      >
        <label className="rl" htmlFor={`${id}-act`}>
          In front
        </label>
        <div className="actions">
          <input id={`${id}-act`} className="mono grow" placeholder="com.example/.MainActivity" spellCheck={false} value={shown} onChange={(e) => setActivity(e.target.value)} />
          <button type="submit" className="btn assert" disabled={!usable || !/^[^/\s]+\/\S+$/.test(shown.trim())}>
            Assert
          </button>
        </div>
      </form>
      <Row label="Keyboard">
        <button type="button" className="btn assert" disabled={!usable} onClick={() => onPerform(steps.deviceAssertion(steps.DeviceCheck.KEYBOARD_SHOWN))}>
          Shown
        </button>
        <button type="button" className="btn assert" disabled={!usable} onClick={() => onPerform(steps.deviceAssertion(steps.DeviceCheck.KEYBOARD_HIDDEN))}>
          Hidden
        </button>
      </Row>
      <form
        className="crow"
        aria-label="Assert the clipboard"
        onSubmit={(e) => {
          e.preventDefault();
          onPerform(steps.deviceAssertion(steps.DeviceCheck.CLIPBOARD_EQUALS, clip));
        }}
      >
        <label className="rl" htmlFor={`${id}-clipeq`}>
          Clipboard
        </label>
        <div className="actions">
          <input id={`${id}-clipeq`} className="grow" placeholder="equals" value={clip} onChange={(e) => setClip(e.target.value)} />
          <button type="submit" className="btn assert" disabled={!usable}>
            Assert
          </button>
        </div>
      </form>
    </Group>
  );
}
