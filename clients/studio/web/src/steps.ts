// The steps the page asks the studio to perform. The back end completes them (the inferred wait,
// the default gesture distance), runs them and records the ones that pass: the page only says
// what the user did to which selector.

import { create, type MessageInitShape } from "@bufbuild/protobuf";
import type { IntentExtra } from "./gen/app_pb";
import { AppCallSchema, DeviceCallSchema } from "./gen/event_log_pb";
import {
  CommandSchema,
  PinchDirection,
  StabilitySignal,
  type Direction,
  type DisplayRotation,
  type LocationAccuracy,
  type Orientation,
  type PermissionChoice,
  type StandardAction,
  type SystemPanel,
} from "./gen/command_pb";
import { MatchMode, type Selector } from "./gen/selector_pb";
import { Check, Condition, DeviceCheck, PerformRequestSchema, SelectorOrigin, StepSchema, type PerformRequest, type Step } from "./gen/studio_pb";

/** The actions on an element that take nothing but its selector. */
export type Gesture = "tap" | "longTap" | "doubleTap" | "clearText" | "performImeAction";

/** Text for a node: typed as-is, or the value of a named secret, which is sent to the device and
 *  never recorded. */
export type TextInput = { text: string } | { secret: string; value: string };

/** What a step acts on: a selector, and where it came from (the daemon's first candidate unless
 *  the user picked another or typed one). */
export type Target = { selector: Selector; origin: SelectorOrigin };

export const synthesized = (selector: Selector): Target => ({ selector, origin: SelectorOrigin.SYNTHESIZED });

function request(step: Step, secretValue?: string): PerformRequest {
  return create(PerformRequestSchema, { step, secretValue });
}

type Op = NonNullable<MessageInitShape<typeof CommandSchema>["op"]>;

function action(op: Op, origin: SelectorOrigin, secret?: string): Step {
  return create(StepSchema, {
    kind: { case: "action", value: { command: create(CommandSchema, { op }), secret, selectorOrigin: origin } },
  });
}

export function gesture({ selector, origin }: Target, kind: Gesture): PerformRequest {
  return request(action({ case: kind, value: { selector } }, origin));
}

/** The SDKs' gesture distance, in percent of the element: left out of a step that uses it. */
export const DEFAULT_DISTANCE = 80;

const distanceOf = (percent: number) => (percent === DEFAULT_DISTANCE ? undefined : percent);

/** `scroll` towards `direction`'s content edge, the finger moving `distance` percent of the element. */
export function scroll({ selector, origin }: Target, direction: Direction, distance = DEFAULT_DISTANCE): PerformRequest {
  return request(action({ case: "scroll", value: { selector, direction, distancePercent: distanceOf(distance) } }, origin));
}

/** `swipe` in the direction the finger moves, over `distance` percent of the element. */
export function swipe({ selector, origin }: Target, direction: Direction, distance = DEFAULT_DISTANCE): PerformRequest {
  return request(action({ case: "swipe", value: { selector, direction, distancePercent: distanceOf(distance) } }, origin));
}

/** The same request, run on the device but never recorded: a probe such as a scroll until's search. */
export function probe(sent: PerformRequest): PerformRequest {
  return create(PerformRequestSchema, { ...sent, skipRecording: true });
}

/** An action on the device: one command with no selector, so no wait. */
function onDevice(op: Op): PerformRequest {
  return request(create(StepSchema, { kind: { case: "action", value: { command: create(CommandSchema, { op }) } } }));
}

export const pressKey = (keyCode: number) => onDevice({ case: "pressKey", value: { keyCode } });

/** `open_system_panel`: the notification shade or quick settings, no target. */
export const openSystemPanel = (panel: SystemPanel) => onDevice({ case: "openSystemPanel", value: { panel } });

/** `fling` towards `direction`'s content edge, as for scroll. */
export function fling({ selector, origin }: Target, direction: Direction): PerformRequest {
  return request(action({ case: "fling", value: { selector, direction } }, origin));
}

/** `pinchOpen` / `pinchClose` across `percent` of the element. */
export function pinch({ selector, origin }: Target, open: boolean, percent = DEFAULT_DISTANCE): PerformRequest {
  const direction = open ? PinchDirection.PINCH_OPEN : PinchDirection.PINCH_CLOSE;
  return request(action({ case: "pinch", value: { selector, direction, percent: distanceOf(percent) } }, origin));
}

/** `dragTo`: long-press the element and drop it on the centre of `destination`. */
export function drag({ selector, origin }: Target, destination: Selector): PerformRequest {
  return request(action({ case: "drag", value: { selector, target: destination } }, origin));
}

/** `performAction` (a standard accessibility action) or `performCustomAction` (by its label). */
export function accessibilityAction({ selector, origin }: Target, choice: { standard: StandardAction } | { custom: string }): PerformRequest {
  const chosen = "standard" in choice ? { case: "standard" as const, value: choice.standard } : { case: "custom" as const, value: choice.custom };
  return request(action({ case: "performAccessibilityAction", value: { selector, action: chosen } }, origin));
}

/** `setProgress`: a range node's value, in its own units. */
export function setProgress({ selector, origin }: Target, value: number): PerformRequest {
  return request(action({ case: "setProgress", value: { selector, value } }, origin));
}

export const setOrientation = (orientation: Orientation) => onDevice({ case: "setOrientation", value: { orientation } });
export const setDisplayRotation = (rotation: DisplayRotation) => onDevice({ case: "setDisplayRotation", value: { rotation } });
export const unfreezeRotation = () => onDevice({ case: "unfreezeRotation", value: {} });
export const dismissKeyguard = () => onDevice({ case: "dismissKeyguard", value: {} });
export const hideKeyboard = () => onDevice({ case: "hideKeyboard", value: {} });
export const setClipboard = (text: string) => onDevice({ case: "setClipboard", value: { text } });

/** `choosePermission`: the dialog's button, after its accuracy radio when given. */
export const choosePermission = (choice: PermissionChoice, accuracy?: LocationAccuracy) =>
  onDevice({ case: "choosePermission", value: { choice, accuracy } });

/** Which notification: any of title, text (exact, or contained) and package. */
export type NotificationQuery = { title?: string; text?: string; contains?: boolean; packageName?: string };

function notificationMatch({ title, text, contains, packageName }: NotificationQuery) {
  const worded = title !== undefined || text !== undefined;
  return { title, text, packageName, mode: worded ? (contains ? MatchMode.MATCH_CONTAINS : MatchMode.MATCH_EXACT) : MatchMode.MATCH_UNSPECIFIED };
}

/** `openNotification`: as a tap on it in the shade, or with `button` the action button of that title. */
export const openNotification = (query: NotificationQuery, button?: string) =>
  onDevice({ case: "openNotification", value: { match: notificationMatch(query), action: button } });

export const dismissNotification = (query: NotificationQuery) => onDevice({ case: "dismissNotification", value: { match: notificationMatch(query) } });

function deviceWait(op: Op): PerformRequest {
  return request(create(StepSchema, { kind: { case: "deviceWait", value: { command: create(CommandSchema, { op }) } } }));
}

export const awaitNotification = (query: NotificationQuery) => deviceWait({ case: "awaitNotification", value: { match: notificationMatch(query) } });

/** `awaitToast`: any toast, or one whose text equals (or contains) `text`; from any app, or `packageName`'s. */
export function awaitToast({ text, contains, packageName }: { text?: string; contains?: boolean; packageName?: string }): PerformRequest {
  const mode = text === undefined ? MatchMode.MATCH_UNSPECIFIED : contains ? MatchMode.MATCH_CONTAINS : MatchMode.MATCH_EXACT;
  return deviceWait({ case: "awaitToast", value: { text, mode, packageName } });
}

export const awaitPermissionPrompt = () => deviceWait({ case: "waitPermissionPrompt", value: {} });

/** A device condition, held until the device is released: the `DeviceCall` of the `Device` call. */
export type DeviceCondition =
  | { operation: "set_animations" | "set_dark_mode" | "set_stay_awake"; enabled: boolean }
  | { operation: "set_font_scale"; fontScale: number }
  | { operation: "set_density"; densityDpi?: number }
  | { operation: "set_network"; airplaneMode?: boolean; wifi?: boolean; mobileData?: boolean }
  | { operation: "set_system_locales"; locales: string[] }
  | { operation: "set_location"; latitude: number; longitude: number; accuracyM?: number; altitudeM?: number }
  | { operation: "set_accessibility_display"; highContrastText?: boolean; colorInversion?: boolean; boldText?: boolean };

export function deviceCondition(condition: DeviceCondition): PerformRequest {
  return request(create(StepSchema, { kind: { case: "device", value: create(DeviceCallSchema, condition) } }));
}

/** A device assertion: the foreground activity (`package/class`), the keyboard, or the clipboard. */
export function deviceAssertion(check: DeviceCheck, text?: string): PerformRequest {
  return request(create(StepSchema, { kind: { case: "deviceAssertion", value: { check, text } } }));
}
export { DeviceCheck };

/** `set_text`: one command, no keyboard, no focus needed. */
export function setText({ selector, origin }: Target, input: TextInput): PerformRequest {
  if ("secret" in input) {
    return request(action({ case: "setText", value: { selector, text: "" } }, origin, input.secret), input.value);
  }
  return request(action({ case: "setText", value: { selector, text: input.text } }, origin));
}

/** The SDKs' element `typeText`: tap, await focus (unless `awaitFocus` is false), then
 *  `type_text`, for fields that react to keys. */
export function typeText({ selector, origin }: Target, input: TextInput, { awaitFocus = true }: { awaitFocus?: boolean } = {}): PerformRequest {
  const step = create(StepSchema, {
    kind: {
      case: "type",
      value: {
        selector,
        input: "secret" in input ? { case: "secret", value: input.secret } : { case: "text", value: input.text },
        selectorOrigin: origin,
        skipFocusWait: !awaitFocus,
      },
    },
  });
  return request(step, "secret" in input ? input.value : undefined);
}

export type AppOperation =
  | "cold_launch"
  | "launch"
  | "foreground"
  | "force_stop"
  | "clear_data"
  | "grant_permission"
  | "revoke_permission"
  | "open_link"
  | "set_locales";

type AppArguments = { permission?: string; activity?: string; extras?: IntentExtra[]; uri?: string; anyApp?: boolean; locales?: string[] };

/** An app step. `permission` goes with the permission calls; `activity` and `extras` with the
 *  launches (no activity: the launcher activity; `.ui.Settings` is in the package); `uri` and
 *  `anyApp` with open_link; `locales` with set_locales (none: follow the system). */
export function app(operation: AppOperation, packageName: string, { anyApp, ...rest }: AppArguments = {}): PerformRequest {
  const call = create(AppCallSchema, { operation, packageName, ...rest, anyApp: anyApp || undefined });
  return request(create(StepSchema, { kind: { case: "app", value: call } }));
}

/** An element wait: the condition, and the value the text and count conditions take. */
export type Until =
  | { condition: Exclude<Condition, Condition.TEXT_EQUALS | Condition.TEXT_CONTAINS | Condition.COUNT> }
  | { condition: Condition.TEXT_EQUALS | Condition.TEXT_CONTAINS; text: string }
  | { condition: Condition.COUNT; count: number };

/** A `WaitStep`: the SDK's element wait, replayed within the device's wait timeout. */
export function wait({ selector, origin }: Target, until: Until): PerformRequest {
  return request(create(StepSchema, { kind: { case: "wait", value: { selector, condition: until.condition, value: valueOf(until), selectorOrigin: origin } } }));
}

/** An assertion: the check, and the value the text and count checks take. */
export type Expect =
  | { check: Exclude<Check, Check.TEXT_EQUALS | Check.TEXT_CONTAINS | Check.COUNT> }
  | { check: Check.TEXT_EQUALS | Check.TEXT_CONTAINS; text: string }
  | { check: Check.COUNT; count: number };

/** An `AssertionStep`: one query of the screen as it is now, with no waiting. */
export function assertion({ selector, origin }: Target, expect: Expect): PerformRequest {
  return request(create(StepSchema, { kind: { case: "assertion", value: { selector, check: expect.check, value: valueOf(expect), selectorOrigin: origin } } }));
}

function valueOf(value: { text: string } | { count: number } | object) {
  if ("text" in value) return { case: "text" as const, value: value.text };
  if ("count" in value) return { case: "count" as const, value: value.count };
  return undefined;
}

/** The SDKs' default scroll budget for `scrollUntil`. */
export const DEFAULT_MAX_SCROLLS = 20;

/** The SDKs' `Element.scrollUntil`: scroll the container until `target` is inside it. The studio
 *  fills in the default scroll budget and distance for the ones left out. */
export function scrollUntil(
  { selector, origin }: Target,
  target: Selector,
  direction: Direction,
  { distance = DEFAULT_DISTANCE, maxScrolls = DEFAULT_MAX_SCROLLS }: { distance?: number; maxScrolls?: number } = {},
): PerformRequest {
  return request(
    create(StepSchema, {
      kind: {
        case: "scrollUntil",
        value: {
          container: selector,
          target,
          direction,
          maxScrolls: maxScrolls === DEFAULT_MAX_SCROLLS ? undefined : maxScrolls,
          distancePercent: distanceOf(distance),
          selectorOrigin: origin,
        },
      },
    }),
  );
}

export type AppWait = "visible" | "stable" | "settled" | "animation_end";

const SIGNALS: Record<Exclude<AppWait, "visible">, StabilitySignal> = {
  stable: StabilitySignal.STABILITY_ALL,
  settled: StabilitySignal.STABILITY_TREE,
  animation_end: StabilitySignal.STABILITY_PIXELS,
};

/** `App.awaitVisible`, or `App.awaitScreenStable` on one signal or both. */
export function appWait(kind: AppWait, packageName: string): PerformRequest {
  const op =
    kind === "visible"
      ? { case: "waitAppVisible" as const, value: { packageName } }
      : { case: "waitScreenStable" as const, value: { packageName, signal: SIGNALS[kind] } };
  return request(create(StepSchema, { kind: { case: "appWait", value: { command: create(CommandSchema, { op }) } } }));
}
