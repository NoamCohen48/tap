import { useEffect, useId, useRef, useState, type ComponentType } from "react";
import { SystemPanel } from "./gen/command_pb";
import type { PerformRequest } from "./gen/studio_pb";
import { Back, Bell, Home, Keyboard, Recents, Toggles } from "./icons";
import { KEY_APP_SWITCH, KEY_BACK, KEY_HOME, KEYS } from "./keys";
import * as steps from "./steps";

type Button = { label: string; call: string; icon: ComponentType; request: () => PerformRequest };

const NAVIGATION: Button[] = [
  { label: "Back", call: "pressBack()", icon: Back, request: () => steps.pressKey(KEY_BACK) },
  { label: "Home", call: "pressHome()", icon: Home, request: () => steps.pressKey(KEY_HOME) },
  { label: "Recent apps", call: `pressKey(${KEY_APP_SWITCH})`, icon: Recents, request: () => steps.pressKey(KEY_APP_SWITCH) },
];

const PANELS: Button[] = [
  { label: "Notifications", call: "openNotifications()", icon: Bell, request: () => steps.openSystemPanel(SystemPanel.NOTIFICATIONS) },
  { label: "Quick settings", call: "openQuickSettings()", icon: Toggles, request: () => steps.openSystemPanel(SystemPanel.QUICK_SETTINGS) },
];

/**
 * The device's own buttons, under the phone as its navigation bar is: Back, Home and Recent apps,
 * then the notification shade and quick settings, then a menu of other keys. They need no
 * element; each records its step.
 */
export function DeviceBar({ disabled, onPerform }: { disabled: boolean; onPerform: (request: PerformRequest) => void }) {
  const button = ({ label, call, icon: Icon, request }: Button) => (
    <button key={label} type="button" className="navbtn" disabled={disabled} aria-label={label} title={`${label}: ${call}`} onClick={() => onPerform(request())}>
      <Icon />
    </button>
  );
  return (
    <div className="navbar" role="toolbar" aria-label="Device">
      {NAVIGATION.map(button)}
      <span className="navsep" aria-hidden="true" />
      {PANELS.map(button)}
      <span className="navsep" aria-hidden="true" />
      <KeysMenu disabled={disabled} onPress={(code) => onPerform(steps.pressKey(code))} />
    </div>
  );
}

/** Other keys, to whatever has focus: Enter to submit a field, Delete, Tab, the volume keys, or
 *  any key code. Pressing one closes the menu. */
function KeysMenu({ disabled, onPress }: { disabled: boolean; onPress: (code: number) => void }) {
  const id = useId();
  const [open, setOpen] = useState(false);
  const [code, setCode] = useState("");
  const root = useRef<HTMLDivElement>(null);
  const opener = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    const outside = (e: PointerEvent) => {
      if (!root.current?.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener("pointerdown", outside);
    return () => document.removeEventListener("pointerdown", outside);
  }, [open]);

  useEffect(() => {
    if (disabled) setOpen(false);
  }, [disabled]);

  const close = () => {
    setOpen(false);
    opener.current?.focus();
  };
  const press = (key: number) => {
    close();
    onPress(key);
  };
  const typed = Number(code);
  const valid = code.trim() !== "" && Number.isInteger(typed) && typed >= 0;

  return (
    <div
      className="keys"
      ref={root}
      onKeyDown={(e) => {
        if (e.key === "Escape" && open) {
          e.stopPropagation();
          close();
        }
      }}
    >
      <button
        ref={opener}
        type="button"
        className="navbtn"
        disabled={disabled}
        aria-label="More keys"
        aria-expanded={open}
        aria-controls={`${id}-menu`}
        title="Press a key: Enter, Delete, Tab, the volume keys or any key code"
        onClick={() => setOpen((o) => !o)}
      >
        <Keyboard />
      </button>
      {open && (
        <div className="keys-pop" id={`${id}-menu`} role="group" aria-label="Press a key">
          <div className="keys-grid">
            {KEYS.map((k) => (
              <button key={k.code} type="button" className="btn" title={k.title} onClick={() => press(k.code)}>
                {k.name}
                <span className="code">{k.code}</span>
              </button>
            ))}
          </div>
          <form
            className="keys-code"
            onSubmit={(e) => {
              e.preventDefault();
              if (valid) press(typed);
            }}
          >
            <label htmlFor={`${id}-code`}>Key code</label>
            <input
              id={`${id}-code`}
              className="mono"
              type="number"
              min={0}
              placeholder="66"
              value={code}
              onChange={(e) => setCode(e.target.value)}
            />
            <button type="submit" className="btn" disabled={!valid}>
              Press
            </button>
          </form>
          <p className="keys-note">A key goes to whatever has focus. It is recorded as pressKey(code).</p>
        </div>
      )}
    </div>
  );
}
