import type { ComponentType } from "react";
import { SystemPanel } from "./gen/command_pb";
import type { PerformRequest } from "./gen/studio_pb";
import { Back, Bell, Home, Recents, Toggles } from "./icons";
import * as steps from "./steps";

const KEY_HOME = 3;
const KEY_BACK = 4;
const KEY_APP_SWITCH = 187;

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
 * then the notification shade and quick settings. They need no element; each records its step.
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
    </div>
  );
}
