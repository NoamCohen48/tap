// How the page shows selectors and steps: in the Kotlin SDK's DSL (`res("search").andText("Go")`,
// `app("com.example").element(...).tap()`), the form a test would write. What is sent and recorded
// is the message itself; `parse.ts` reads a typed selector back. A selector's package predicate is
// the one thing the DSL has no factory for (`App.element` adds it): a step shows it as
// `app("…").element(…)`, a lone selector as `.andPackageName("…")`, which `parse.ts` reads.

import { create } from "@bufbuild/protobuf";
import type { IntentExtra } from "./gen/app_pb";
import type { AppCall, DeviceCall } from "./gen/event_log_pb";
import { Check, Condition, DeviceCheck, type AssertionStep, type DeviceAssertionStep, type Step } from "./gen/studio_pb";
import {
  Direction,
  DisplayRotation,
  LocationAccuracy,
  Orientation,
  PermissionChoice,
  PinchDirection,
  StabilitySignal,
  StandardAction,
  SystemPanel,
  type Command,
  type NotificationMatch,
} from "./gen/command_pb";
import { MatchMode, NodeFlag, NodeSchema, Relation, SelectorSchema, TextProperty, type Match, type Node, type Selector } from "./gen/selector_pb";
import { KEY_BACK, KEY_HOME, KEY_SLEEP, KEY_WAKEUP, keyName } from "./keys";

/** A Kotlin string literal: JSON's escapes, and `$` escaped so it is not a template. */
export const quote = (value: string) => JSON.stringify(value).replace(/\$/g, "\\$");

export const FACTORY: Record<TextProperty, string> = {
  [TextProperty.PROPERTY_UNSPECIFIED]: "text",
  [TextProperty.PROPERTY_TEXT]: "text",
  [TextProperty.PROPERTY_CONTENT_DESCRIPTION]: "desc",
  [TextProperty.PROPERTY_HINT]: "hint",
  [TextProperty.PROPERTY_CLASS_NAME]: "className",
  [TextProperty.PROPERTY_PACKAGE_NAME]: "packageName",
};

export const TEXT_MODES: Partial<Record<MatchMode, string>> = {
  [MatchMode.MATCH_CONTAINS]: "textContains",
  [MatchMode.MATCH_STARTS_WITH]: "textStartsWith",
  [MatchMode.MATCH_REGEX]: "textMatches",
};

export const MODE_NAMES: Record<MatchMode, string> = {
  [MatchMode.MATCH_UNSPECIFIED]: "EXACT",
  [MatchMode.MATCH_EXACT]: "EXACT",
  [MatchMode.MATCH_CONTAINS]: "CONTAINS",
  [MatchMode.MATCH_STARTS_WITH]: "STARTS_WITH",
  [MatchMode.MATCH_ENDS_WITH]: "ENDS_WITH",
  [MatchMode.MATCH_REGEX]: "REGEX",
};

export const FLAGS: Record<NodeFlag, string> = {
  [NodeFlag.FLAG_UNSPECIFIED]: "flag",
  [NodeFlag.FLAG_ENABLED]: "enabled",
  [NodeFlag.FLAG_CHECKED]: "checked",
  [NodeFlag.FLAG_CHECKABLE]: "checkable",
  [NodeFlag.FLAG_CLICKABLE]: "clickable",
  [NodeFlag.FLAG_FOCUSED]: "focused",
  [NodeFlag.FLAG_FOCUSABLE]: "focusable",
  [NodeFlag.FLAG_LONG_CLICKABLE]: "longClickable",
  [NodeFlag.FLAG_SCROLLABLE]: "scrollable",
  [NodeFlag.FLAG_SELECTED]: "selected",
};

export const RELATIONS: Record<Relation, string> = {
  [Relation.UNSPECIFIED]: "related",
  [Relation.PARENT]: "hasParent",
  [Relation.ANCESTOR]: "hasAncestor",
  [Relation.CHILD]: "hasChild",
  [Relation.DESCENDANT]: "hasDescendant",
};

function match(m: Match): string {
  const exact = m.mode === MatchMode.MATCH_EXACT || m.mode === MatchMode.MATCH_UNSPECIFIED;
  if (m.property === TextProperty.PROPERTY_TEXT && !exact && TEXT_MODES[m.mode]) {
    return `${TEXT_MODES[m.mode]}(${quote(m.value)})`;
  }
  const mode = exact ? "" : `, MatchMode.${MODE_NAMES[m.mode]}`;
  return `${FACTORY[m.property]}(${quote(m.value)}${mode})`;
}

function operands(node: Node): Node[] {
  return node.kind.case === "allOf" ? node.kind.value.nodes.flatMap(operands) : [node];
}

/** A stand-alone selector expression for one operand; `null` for one that only chains. */
function base(node: Node): string | null {
  switch (node.kind.case) {
    case "match":
      return match(node.kind.value);
    case "resource": {
      const r = node.kind.value;
      return r.packageName !== undefined ? `resId(${quote(r.packageName)}, ${quote(r.name)})` : `res(${quote(r.name)})`;
    }
    case "anyOf":
      return `anyOf(${node.kind.value.nodes.map(expression).join(", ")})`;
    default:
      return null;
  }
}

/** The operand as a chained call on a selector that already has a base. */
function chained(node: Node): string {
  switch (node.kind.case) {
    case "match": {
      const m = node.kind.value;
      const name = FACTORY[m.property];
      const exact = m.mode === MatchMode.MATCH_EXACT || m.mode === MatchMode.MATCH_UNSPECIFIED;
      return `.and${name[0]!.toUpperCase()}${name.slice(1)}(${quote(m.value)}${exact ? "" : `, MatchMode.${MODE_NAMES[m.mode]}`})`;
    }
    case "resource": {
      const r = node.kind.value;
      return r.packageName !== undefined ? `.andRes(${quote(r.packageName)}, ${quote(r.name)})` : `.andRes(${quote(r.name)})`;
    }
    case "flag":
      return `.${FLAGS[node.kind.value.property]}(${node.kind.value.value ? "" : "false"})`;
    case "related":
      return `.${RELATIONS[node.kind.value.relation]}(${node.kind.value.node ? expression(node.kind.value.node) : ""})`;
    case "anyOf":
      return `.and(${base(node)})`;
    default:
      return "";
  }
}

/** A node as a selector expression: its first stand-alone operand, then the rest chained. */
function expression(node: Node): string {
  const all = operands(node);
  const first = all.findIndex((operand) => base(operand) !== null);
  if (first < 0) {
    // Only flags and relations, which the DSL has no factory for (except two flags).
    return `allOf(${all.map((operand) => chained(operand).slice(1)).join(", ")})`;
  }
  return (
    base(all[first]!) +
    all
      .filter((_, i) => i !== first)
      .map(chained)
      .join("")
  );
}

export function describeSelector(selector: Selector | undefined): string {
  if (!selector?.node) return "(no selector)";
  let text = expression(selector.node);
  if (selector.pick.case === "at") text += `.at(${selector.pick.value.index})`;
  if (selector.pick.case === "first") text += ".first()";
  return text;
}

const isPackage = (node: Node) =>
  node.kind.case === "match" &&
  node.kind.value.property === TextProperty.PROPERTY_PACKAGE_NAME &&
  (node.kind.value.mode === MatchMode.MATCH_EXACT || node.kind.value.mode === MatchMode.MATCH_UNSPECIFIED);

/** The app a selector is confined to — the package predicate `App.element` appends, as one
 *  operand of its top conjunction — and the selector without it; `packageName` is null when it
 *  has none (a `screen` selector). */
export function splitPackage(selector: Selector): { packageName: string | null; selector: Selector } {
  const nodes = selector.node?.kind.case === "allOf" ? selector.node.kind.value.nodes : [];
  const index = nodes.findIndex(isPackage);
  if (index < 0) return { packageName: null, selector };
  const rest = nodes.filter((_, i) => i !== index);
  const node = rest.length === 1 ? rest[0]! : create(NodeSchema, { kind: { case: "allOf", value: { nodes: rest } } });
  const packageName = (nodes[index]!.kind.value as Match).value;
  return { packageName, selector: create(SelectorSchema, { node, pick: selector.pick }) };
}

/** `app("…")` or `screen`, the receiver a step's selector runs on, and the selector it gets. */
function on(selector: Selector | undefined): [string, string] {
  if (!selector) return ["screen", describeSelector(selector)];
  const split = splitPackage(selector);
  return [split.packageName !== null ? `app(${quote(split.packageName)})` : "screen", describeSelector(split.selector)];
}

/** `app("…").element(…)` / `screen.element(…)`. */
function element(selector: Selector | undefined): string {
  const [receiver, text] = on(selector);
  return `${receiver}.element(${text})`;
}

/** `app("…").await(…)` / `screen.await(…)`. */
function awaiting(selector: Selector | undefined): string {
  const [receiver, text] = on(selector);
  return `${receiver}.await(${text})`;
}

const DIRECTIONS: Record<Direction, string> = {
  [Direction.DIR_UNSPECIFIED]: "?",
  [Direction.DIR_UP]: "UP",
  [Direction.DIR_DOWN]: "DOWN",
  [Direction.DIR_LEFT]: "LEFT",
  [Direction.DIR_RIGHT]: "RIGHT",
};

const KEYS: Record<number, string> = { [KEY_HOME]: "pressHome()", [KEY_BACK]: "pressBack()", [KEY_WAKEUP]: "wake()", [KEY_SLEEP]: "sleep()" };

const PANELS: Partial<Record<SystemPanel, string>> = {
  [SystemPanel.NOTIFICATIONS]: "openNotifications()",
  [SystemPanel.QUICK_SETTINGS]: "openQuickSettings()",
};

const APP: Record<string, string> = {
  cold_launch: "coldLaunch",
  launch: "launch",
  foreground: "foreground",
  force_stop: "forceStop",
  clear_data: "clearData",
  grant_permission: "grantPermission",
  revoke_permission: "revokePermission",
  open_link: "openLink",
  set_locales: "setLocales",
};

/** A Kotlin enum constant from a proto one: the proto's prefix dropped (`A11Y_EXPAND` → `StandardAction.EXPAND`). */
function constant(kotlinEnum: string, names: Record<number, string>, value: number, prefix = ""): string {
  return `${kotlinEnum}.${(names[value] ?? String(value)).replace(prefix, "")}`;
}

/** Kotlin literals for the numbers a step carries. */
const float = (n: number) => `${n}f`;
const double = (n: number) => (Number.isInteger(n) ? `${n}.0` : String(n));
const list = (values: string[]) => `listOf(${values.map(quote).join(", ")})`;

/** Named arguments, in order, for the ones given. */
function named(args: [string, string | undefined][]): string {
  return args
    .filter(([, v]) => v !== undefined)
    .map(([k, v]) => `${k} = ${v}`)
    .join(", ");
}

function extra(e: IntentExtra): string {
  const v = e.value;
  const literal =
    v.case === "stringValue"
      ? quote(v.value)
      : v.case === "longValue"
        ? `${v.value}L`
        : v.case === "floatValue"
          ? float(v.value)
          : String(v.value ?? "null");
  return `${quote(e.key)} to ${literal}`;
}

function appCall(call: AppCall): string {
  let args: string;
  switch (call.operation) {
    case "grant_permission":
    case "revoke_permission":
      args = quote(call.permission ?? "");
      break;
    case "open_link":
      args = [quote(call.uri ?? ""), call.anyApp ? "anyApp = true" : ""].filter(Boolean).join(", ");
      break;
    case "set_locales":
      args = list(call.locales);
      break;
    default: {
      const extras = call.extras.length ? `extras = mapOf(${call.extras.map(extra).join(", ")})` : "";
      args = [call.activity ? quote(call.activity) : "", extras].filter(Boolean).join(", ");
    }
  }
  return `app(${quote(call.packageName)}).${APP[call.operation] ?? call.operation}(${args})`;
}

const bool = (b: boolean | undefined) => (b === undefined ? undefined : String(b));

/** A held device condition as its `Device` call. */
function deviceCall(call: DeviceCall): string {
  switch (call.operation) {
    case "set_animations":
      return `setAnimations(${call.enabled})`;
    case "set_dark_mode":
      return `setDarkMode(${call.enabled})`;
    case "set_stay_awake":
      return `setStayAwake(${call.enabled})`;
    case "set_font_scale":
      return `setFontScale(${float(call.fontScale ?? 1)})`;
    case "set_density":
      return `setDensity(${call.densityDpi ?? "null"})`;
    case "set_network":
      return `setNetwork(${named([["airplaneMode", bool(call.airplaneMode)], ["wifi", bool(call.wifi)], ["mobileData", bool(call.mobileData)]])})`;
    case "set_system_locales":
      return `setSystemLocales(${list(call.locales)})`;
    case "set_location": {
      const rest = named([
        ["accuracyM", call.accuracyM !== undefined ? float(call.accuracyM) : undefined],
        ["altitudeM", call.altitudeM !== undefined ? double(call.altitudeM) : undefined],
      ]);
      return `setLocation(${[double(call.latitude ?? 0), double(call.longitude ?? 0), rest].filter(Boolean).join(", ")})`;
    }
    case "set_accessibility_display":
      return `setAccessibilityDisplay(${named([
        ["highContrastText", bool(call.highContrastText)],
        ["colorInversion", bool(call.colorInversion)],
        ["boldText", bool(call.boldText)],
      ])})`;
    default:
      return `(${call.operation} device call)`;
  }
}

/** A notification match as the SDK's `title`/`text`/`mode`/`packageName` arguments, plus any more. */
function matching(m: NotificationMatch | undefined, more: [string, string | undefined][] = []): string {
  const exact = !m || m.mode === MatchMode.MATCH_EXACT || m.mode === MatchMode.MATCH_UNSPECIFIED;
  return named([
    ["title", m?.title !== undefined ? quote(m.title) : undefined],
    ["text", m?.text !== undefined ? quote(m.text) : undefined],
    ["mode", exact ? undefined : `MatchMode.${MODE_NAMES[m.mode]}`],
    ["packageName", m?.packageName !== undefined ? quote(m.packageName) : undefined],
    ...more,
  ]);
}

function deviceWait(c: Command | undefined): string {
  switch (c?.op.case) {
    case "awaitToast": {
      const t = c.op.value;
      const exact = t.mode === MatchMode.MATCH_EXACT || t.mode === MatchMode.MATCH_UNSPECIFIED;
      const args = named([
        ["text", t.text !== undefined ? quote(t.text) : undefined],
        ["mode", exact ? undefined : `MatchMode.${MODE_NAMES[t.mode]}`],
        ["packageName", t.packageName !== undefined ? quote(t.packageName) : undefined],
      ]);
      return `awaitToast(${args})`;
    }
    case "awaitNotification":
      return `awaitNotification(${matching(c.op.value.match)})`;
    case "waitPermissionPrompt":
      return "awaitPermissionPrompt()";
    default:
      return `(${c?.op.case ?? "empty"} device wait)`;
  }
}

function deviceAssertion(a: DeviceAssertionStep): string {
  switch (a.check) {
    case DeviceCheck.FOREGROUND_ACTIVITY: {
      const [pkg = "", cls = ""] = (a.text ?? "").split("/");
      return `assertEquals(ForegroundActivity(${quote(pkg)}, ${quote(cls)}), foregroundActivity())`;
    }
    case DeviceCheck.KEYBOARD_SHOWN:
      return "assertTrue(keyboardShown())";
    case DeviceCheck.KEYBOARD_HIDDEN:
      return "assertFalse(keyboardShown())";
    case DeviceCheck.CLIPBOARD_EQUALS:
      return `assertEquals(${quote(a.text ?? "")}, clipboard())`;
    default:
      return "(no device check)";
  }
}

const CONDITIONS: Record<Condition, string> = {
  [Condition.UNSPECIFIED]: "?",
  [Condition.VISIBLE]: "visible",
  [Condition.ONE]: "one",
  [Condition.GONE]: "gone",
  [Condition.ENABLED]: "enabled",
  [Condition.DISABLED]: "disabled",
  [Condition.CHECKED]: "checked",
  [Condition.UNCHECKED]: "unchecked",
  [Condition.FOCUSED]: "focused",
  [Condition.TEXT_EQUALS]: "textEquals",
  [Condition.TEXT_CONTAINS]: "textContains",
  [Condition.COUNT]: "count",
};

/** An assertion as a test writes it: `kotlin.test` around the SDK's element query. */
function assertion(a: AssertionStep): string {
  const query = element(a.selector);
  const text = a.value.case === "text" ? quote(a.value.value) : "";
  switch (a.check) {
    case Check.EXISTS:
      return `assertTrue(${query}.exists())`;
    case Check.COUNT:
      return `assertEquals(${a.value.case === "count" ? a.value.value : "?"}, ${query}.count())`;
    case Check.TEXT_EQUALS:
      return `assertEquals(${text}, ${query}.text())`;
    case Check.TEXT_CONTAINS:
      return `assertContains(${query}.text().orEmpty(), ${text})`;
    case Check.ENABLED:
      return `assertTrue(${query}.isEnabled())`;
    case Check.DISABLED:
      return `assertFalse(${query}.isEnabled())`;
    case Check.CHECKED:
      return `assertTrue(${query}.isChecked())`;
    case Check.UNCHECKED:
      return `assertFalse(${query}.isChecked())`;
    case Check.FOCUSED:
      return `assertTrue(${query}.snapshot().focused)`;
    default:
      return `(no check on ${query})`;
  }
}

const STABLE: Record<StabilitySignal, string> = {
  [StabilitySignal.STABILITY_UNSPECIFIED]: "awaitScreenStable",
  [StabilitySignal.STABILITY_ALL]: "awaitScreenStable",
  [StabilitySignal.STABILITY_TREE]: "awaitSettled",
  [StabilitySignal.STABILITY_PIXELS]: "awaitAnimationEnd",
};

function appWait(c: Command | undefined): string {
  switch (c?.op.case) {
    case "waitAppVisible":
      return `app(${quote(c.op.value.packageName)}).awaitVisible()`;
    case "waitScreenStable": {
      const w = c.op.value;
      const stableFor = w.stableForMs !== undefined && w.stableForMs !== 500n ? `stableFor = ${w.stableForMs}.milliseconds` : "";
      return `app(${quote(w.packageName)}).${STABLE[w.signal]}(${stableFor})`;
    }
    default:
      return `(${c?.op.case ?? "empty"} app wait)`;
  }
}

function value(text: string, secret: string | undefined): string {
  return secret !== undefined ? `\${${secret}}` : quote(text);
}

function command(c: Command | undefined, secret: string | undefined): string {
  if (!c) return "(no command)";
  switch (c.op.case) {
    case "pressKey": {
      const code = c.op.value.keyCode;
      const name = keyName(code);
      return KEYS[code] ?? `pressKey(${code}${name ? ` /* ${name} */` : ""})`;
    }
    case "openSystemPanel":
      return PANELS[c.op.value.panel] ?? `openSystemPanel(${c.op.value.panel})`;
    case "tap":
      return `${element(c.op.value.selector)}.tap()`;
    case "longTap":
      return `${element(c.op.value.selector)}.longTap()`;
    case "setText":
      return `${element(c.op.value.selector)}.setText(${value(c.op.value.text, secret)})`;
    case "clearText":
      return `${element(c.op.value.selector)}.clearText()`;
    case "doubleTap":
      return `${element(c.op.value.selector)}.doubleTap()`;
    case "performImeAction":
      return `${element(c.op.value.selector)}.imeAction()`;
    case "fling":
      return `${element(c.op.value.selector)}.fling(${DIRECTIONS[c.op.value.direction]})`;
    case "pinch": {
      const p = c.op.value;
      const percent = p.percent !== undefined && p.percent !== 80 ? String(p.percent) : "";
      return `${element(p.selector)}.${p.direction === PinchDirection.PINCH_CLOSE ? "pinchClose" : "pinchOpen"}(${percent})`;
    }
    case "drag":
      return `${element(c.op.value.selector)}.dragTo(${describeSelector(c.op.value.target)})`;
    case "performAccessibilityAction": {
      const a = c.op.value;
      return a.action.case === "custom"
        ? `${element(a.selector)}.performCustomAction(${quote(a.action.value)})`
        : `${element(a.selector)}.performAction(${constant("StandardAction", StandardAction, a.action.value ?? 0, "A11Y_")})`;
    }
    case "setProgress":
      return `${element(c.op.value.selector)}.setProgress(${float(c.op.value.value)})`;
    case "setOrientation":
      return `setOrientation(${constant("Orientation", Orientation, c.op.value.orientation)})`;
    case "setDisplayRotation":
      return `setDisplayRotation(${constant("DisplayRotation", DisplayRotation, c.op.value.rotation)})`;
    case "unfreezeRotation":
      return "unfreezeRotation()";
    case "dismissKeyguard":
      return "dismissKeyguard()";
    case "hideKeyboard":
      return "hideKeyboard()";
    case "setClipboard":
      return `setClipboard(${quote(c.op.value.text)})`;
    case "choosePermission": {
      const p = c.op.value;
      const choice = constant("PermissionChoice", PermissionChoice, p.choice, "PERMISSION_");
      const accuracy =
        p.accuracy !== LocationAccuracy.LOCATION_ACCURACY_UNSPECIFIED ? `, ${constant("LocationAccuracy", LocationAccuracy, p.accuracy, "LOCATION_")}` : "";
      return `choosePermission(${choice}${accuracy})`;
    }
    case "openNotification":
      return `openNotification(${matching(c.op.value.match, [["action", c.op.value.action !== undefined ? quote(c.op.value.action) : undefined]])})`;
    case "dismissNotification":
      return `dismissNotification(${matching(c.op.value.match)})`;
    case "scroll":
    case "swipe": {
      const g = c.op.value;
      const distance = g.distancePercent !== undefined && g.distancePercent !== 80 ? `, distancePercent = ${g.distancePercent}` : "";
      return `${element(g.selector)}.${c.op.case}(${DIRECTIONS[g.direction]}${distance})`;
    }
    default:
      return `(${c.op.case ?? "empty"} command)`;
  }
}

export type StepKind = "app" | "action" | "key" | "system" | "device" | "type" | "assertion" | "wait";

/** The command ops that act on the device rather than an element. */
const DEVICE_OPS = new Set<string>([
  "setOrientation",
  "setDisplayRotation",
  "unfreezeRotation",
  "dismissKeyguard",
  "hideKeyboard",
  "setClipboard",
  "choosePermission",
  "openNotification",
  "dismissNotification",
]);

export function stepKind(step: Step): StepKind | null {
  switch (step.kind.case) {
    case "action": {
      const op = step.kind.value.command?.op.case;
      return op === "pressKey" ? "key" : op === "openSystemPanel" ? "system" : op && DEVICE_OPS.has(op) ? "device" : "action";
    }
    case "scrollUntil":
      return "action";
    case "appWait":
    case "deviceWait":
      return "wait";
    case "device":
      return "device";
    case "deviceAssertion":
      return "assertion";
    default:
      return step.kind.case ?? null;
  }
}

/** The step as the SDK call it replays as. */
export function describeStep(step: Step): string {
  switch (step.kind.case) {
    case "app":
      return appCall(step.kind.value);
    case "device":
      return deviceCall(step.kind.value);
    case "deviceWait":
      return deviceWait(step.kind.value.command);
    case "deviceAssertion":
      return deviceAssertion(step.kind.value);
    case "action":
      return command(step.kind.value.command, step.kind.value.secret);
    case "type": {
      const t = step.kind.value;
      const input = t.input.case === "secret" ? `\${${t.input.value}}` : quote(t.input.value ?? "");
      return `${element(t.selector)}.typeText(${input}${t.skipFocusWait ? ", awaitFocus = false" : ""})`;
    }
    case "wait": {
      const w = step.kind.value;
      const argument = w.value.case === "text" ? quote(w.value.value) : w.value.case === "count" ? String(w.value.value) : "";
      return `${awaiting(w.selector)}.${CONDITIONS[w.condition]}(${argument})`;
    }
    case "assertion":
      return assertion(step.kind.value);
    case "scrollUntil": {
      const u = step.kind.value;
      const extra = [
        u.direction !== Direction.DIR_DOWN ? DIRECTIONS[u.direction] : "",
        u.maxScrolls !== undefined && u.maxScrolls !== 20 ? `maxScrolls = ${u.maxScrolls}` : "",
        u.distancePercent !== undefined && u.distancePercent !== 80 ? `distancePercent = ${u.distancePercent}` : "",
      ].filter(Boolean);
      return `${element(u.container)}.scrollUntil(${[describeSelector(u.target), ...extra].join(", ")})`;
    }
    case "appWait":
      return appWait(step.kind.value.command);
    default:
      return "(empty step)";
  }
}

/** The inferred wait an action runs after, as the SDK call; `null` when it has none. */
export function describeWait(step: Step): string | null {
  if (step.kind.case !== "action") return null;
  const wait = step.kind.value.wait;
  if (wait?.op.case !== "waitVisible") return null;
  return `${awaiting(wait.op.value.selector)}.${wait.op.value.exactlyOne ? "one" : "visible"}()`;
}
