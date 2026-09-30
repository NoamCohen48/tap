// How the page shows selectors and steps: in the Kotlin SDK's DSL (`res("search").andText("Go")`,
// `element(...).tap()`), the form a test would write. What is sent and recorded is the message
// itself; `parse.ts` reads a typed selector back.

import { Condition, type Step } from "./gen/studio_pb";
import { Direction, type Command } from "./gen/command_pb";
import { MatchMode, NodeFlag, Relation, TextProperty, type Match, type Node, type Selector } from "./gen/selector_pb";

/** A Kotlin string literal: JSON's escapes, and `$` escaped so it is not a template. */
export const quote = (value: string) => JSON.stringify(value).replace(/\$/g, "\\$");

export const FACTORY: Record<TextProperty, string> = {
  [TextProperty.PROPERTY_UNSPECIFIED]: "text",
  [TextProperty.PROPERTY_TEXT]: "text",
  [TextProperty.PROPERTY_CONTENT_DESCRIPTION]: "desc",
  [TextProperty.PROPERTY_HINT]: "hint",
  [TextProperty.PROPERTY_CLASS_NAME]: "className",
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
      if (r.autPackage) return `res(${quote(r.name)})`;
      if (r.packageName !== undefined) return `resId(${quote(r.packageName)}, ${quote(r.name)})`;
      return `rawRes(${quote(r.name)})`;
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
    case "resource":
      return node.kind.value.autPackage ? `.andRes(${quote(node.kind.value.name)})` : `.and(${base(node)})`;
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
  if (selector.scope.case === "system") text += `.inPackage(${quote(selector.scope.value.packageName)})`;
  if (selector.scope.case === "anyWindow") text += ".inAnyWindow()";
  if (selector.pick.case === "at") text += `.at(${selector.pick.value.index})`;
  if (selector.pick.case === "first") text += ".first()";
  return text;
}

const DIRECTIONS: Record<Direction, string> = {
  [Direction.DIR_UNSPECIFIED]: "?",
  [Direction.DIR_UP]: "UP",
  [Direction.DIR_DOWN]: "DOWN",
  [Direction.DIR_LEFT]: "LEFT",
  [Direction.DIR_RIGHT]: "RIGHT",
};

const KEYS: Record<number, string> = { 3: "pressHome()", 4: "pressBack()" };

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

function value(text: string, secret: string | undefined): string {
  return secret !== undefined ? `\${${secret}}` : quote(text);
}

function command(c: Command | undefined, secret: string | undefined): string {
  if (!c) return "(no command)";
  switch (c.op.case) {
    case "pressKey":
      return KEYS[c.op.value.keyCode] ?? `pressKey(${c.op.value.keyCode})`;
    case "tap":
      return `element(${describeSelector(c.op.value.selector)}).tap()`;
    case "longTap":
      return `element(${describeSelector(c.op.value.selector)}).longTap()`;
    case "setText":
      return `element(${describeSelector(c.op.value.selector)}).setText(${value(c.op.value.text, secret)})`;
    case "clearText":
      return `element(${describeSelector(c.op.value.selector)}).clearText()`;
    case "scroll":
      return `element(${describeSelector(c.op.value.selector)}).scroll(${DIRECTIONS[c.op.value.direction]})`;
    case "swipe":
      return `element(${describeSelector(c.op.value.selector)}).swipe(${DIRECTIONS[c.op.value.direction]})`;
    default:
      return `(${c.op.case ?? "empty"} command)`;
  }
}

export type StepKind = "app" | "action" | "key" | "type" | "assertion";

export function stepKind(step: Step): StepKind | null {
  if (step.kind.case === "action") return step.kind.value.command?.op.case === "pressKey" ? "key" : "action";
  return step.kind.case ?? null;
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
      return `element(${describeSelector(t.selector)}).typeText(${input}${t.skipFocusWait ? ", awaitFocus = false" : ""})`;
    }
    case "assertion": {
      const a = step.kind.value;
      const argument = a.value.case === "text" ? quote(a.value.value) : a.value.case === "count" ? String(a.value.value) : "";
      return `await(${describeSelector(a.selector)}).${CONDITIONS[a.condition]}(${argument})`;
    }
    default:
      return "(empty step)";
  }
}

/** The inferred wait an action runs after, as the SDK call; `null` when it has none. */
export function describeWait(step: Step): string | null {
  if (step.kind.case !== "action") return null;
  const wait = step.kind.value.wait;
  if (wait?.op.case !== "waitVisible") return null;
  return `await(${describeSelector(wait.op.value.selector)}).${wait.op.value.exactlyOne ? "one" : "visible"}()`;
}
