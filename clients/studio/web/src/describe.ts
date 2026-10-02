// How the page shows selectors and steps: in the Kotlin SDK's DSL (`res("search").andText("Go")`,
// `app("com.example").element(...).tap()`), the form a test would write. What is sent and recorded
// is the message itself; `parse.ts` reads a typed selector back. A selector's package predicate is
// the one thing the DSL has no factory for (`App.element` adds it): a step shows it as
// `app("…").element(…)`, a lone selector as `.andPackageName("…")`, which `parse.ts` reads.

import { create } from "@bufbuild/protobuf";
import { Check, Condition, type AssertionStep, type Step } from "./gen/studio_pb";
import { Direction, StabilitySignal, SystemPanel, type Command } from "./gen/command_pb";
import { MatchMode, NodeFlag, NodeSchema, Relation, SelectorSchema, TextProperty, type Match, type Node, type Selector } from "./gen/selector_pb";
import { KEY_BACK, KEY_HOME, keyName } from "./keys";

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

const KEYS: Record<number, string> = { [KEY_HOME]: "pressHome()", [KEY_BACK]: "pressBack()" };

const PANELS: Partial<Record<SystemPanel, string>> = {
  [SystemPanel.NOTIFICATIONS]: "openNotifications()",
  [SystemPanel.QUICK_SETTINGS]: "openQuickSettings()",
};

const APP: Record<string, string> = {
  cold_launch: "coldLaunch",
  launch: "launch",
  force_stop: "forceStop",
  clear_data: "clearData",
  grant_permission: "grantPermission",
};

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

export type StepKind = "app" | "action" | "key" | "system" | "type" | "assertion" | "wait";

export function stepKind(step: Step): StepKind | null {
  switch (step.kind.case) {
    case "action": {
      const op = step.kind.value.command?.op.case;
      return op === "pressKey" ? "key" : op === "openSystemPanel" ? "system" : "action";
    }
    case "scrollUntil":
      return "action";
    case "appWait":
      return "wait";
    default:
      return step.kind.case ?? null;
  }
}

/** The step as the SDK call it replays as. */
export function describeStep(step: Step): string {
  switch (step.kind.case) {
    case "app": {
      const call = step.kind.value;
      const argument = call.operation === "grant_permission" ? quote(call.permission ?? "") : call.activity ? quote(call.activity) : "";
      return `app(${quote(call.packageName)}).${APP[call.operation] ?? call.operation}(${argument})`;
    }
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
