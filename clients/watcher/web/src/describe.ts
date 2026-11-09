// Human words for what a connection did: "Tap res("email")", "Set text res("email") ← "ada@…"".
// A lean describer of tap.v1 messages; it never invents what the event does not carry.

import type { LoggedEvent } from "./gen/event_log_pb";
import { ErrorCode, type Command, type ElementSnapshot, type Error as CommandError } from "./gen/command_pb";
import { FailureReason } from "./gen/failure_pb";
import { MatchMode, NodeFlag, Relation, TextProperty, type Node, type Selector } from "./gen/selector_pb";

// A sensitive set/type text: the daemon logged it without the text.
const HIDDEN = "(hidden)";

export type Described = {
  /** The verb: "Tap", "Set text", "Launch". */
  verb: string;
  /** What it acted on: a selector, a package, a key. */
  target: string;
  /** What it typed or set, when it did. */
  input: string;
  /** The error code (or failure reason) when it failed. */
  outcome: string;
  /** The error's human detail, when the event carries one. */
  detail: string;
  /**
   * Why a failed command found nothing, or why a state wait's condition did not hold, one line
   * each: the selector parts that ruled the nodes out, or the condition's parts with what the node
   * had. Empty when the error carries neither.
   */
  why: string[];
};

const QUOTE_LIMIT = 40;

export function quote(value: string, limit = QUOTE_LIMIT): string {
  const shown = value.length > limit ? value.slice(0, limit - 1) + "…" : value;
  return JSON.stringify(shown);
}

function words(identifier: string): string {
  const spaced = identifier.replace(/([a-z])([A-Z])/g, "$1 $2").replaceAll("_", " ").toLowerCase();
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

const PROPERTY: Record<number, string> = {
  [TextProperty.PROPERTY_TEXT]: "text",
  [TextProperty.PROPERTY_CONTENT_DESCRIPTION]: "desc",
  [TextProperty.PROPERTY_HINT]: "hint",
  [TextProperty.PROPERTY_CLASS_NAME]: "className",
  [TextProperty.PROPERTY_PACKAGE_NAME]: "pkg",
};

const MODE: Record<number, string> = {
  [MatchMode.MATCH_CONTAINS]: "Contains",
  [MatchMode.MATCH_STARTS_WITH]: "StartsWith",
  [MatchMode.MATCH_ENDS_WITH]: "EndsWith",
  [MatchMode.MATCH_REGEX]: "Matches",
};

const RELATION: Record<number, string> = {
  [Relation.PARENT]: "parent",
  [Relation.ANCESTOR]: "inside",
  [Relation.CHILD]: "child",
  [Relation.DESCENDANT]: "has",
};

export function node(value: Node | undefined): string {
  const kind = value?.kind;
  switch (kind?.case) {
    case "match": {
      const name = PROPERTY[kind.value.property] ?? "text";
      return `${name}${MODE[kind.value.mode] ?? ""}(${quote(kind.value.value)})`;
    }
    case "resource":
      return `res(${quote(kind.value.name)})`;
    case "flag": {
      const flag = NodeFlag[kind.value.property]?.replace("FLAG_", "").toLowerCase() ?? "flag";
      return kind.value.value ? flag : `not ${flag}`;
    }
    case "related":
      return `${RELATION[kind.value.relation] ?? "related"}(${node(kind.value.node)})`;
    case "allOf":
      return kind.value.nodes.map(node).join(" & ");
    case "anyOf":
      return kind.value.nodes.map(node).join(" | ");
    default:
      return "?";
  }
}

export function selector(value: Selector | undefined): string {
  if (!value) return "";
  const base = node(value.node);
  if (value.pick.case === "first") return `${base}.first`;
  if (value.pick.case === "at") return `${base}[${value.pick.value.index}]`;
  return base;
}

function direction(value: number): string {
  return ["", "up", "down", "left", "right"][value] ?? "";
}

function command(value: Command): Pick<Described, "verb" | "target" | "input"> {
  const op = value.op;
  const verb = op.case ? words(op.case) : "Command";
  switch (op.case) {
    case "tap":
    case "longTap":
    case "doubleTap":
    case "tapOutsidePanel":
    case "clearText":
    case "exists":
    case "count":
    case "snapshot":
    case "waitGone":
      return { verb, target: selector(op.value.selector), input: "" };
    case "waitVisible":
      return { verb: "Wait for", target: selector(op.value.selector), input: "" };
    case "waitState":
      return { verb: "Wait for", target: selector(op.value.selector), input: node(op.value.condition) };
    case "setText":
      return { verb, target: selector(op.value.selector), input: op.value.sensitive ? HIDDEN : quote(op.value.text) };
    case "typeText":
      return { verb, target: "", input: op.value.sensitive ? HIDDEN : quote(op.value.text) };
    case "swipe":
    case "scroll":
    case "fling":
      return { verb: `${verb} ${direction(op.value.direction)}`.trim(), target: selector(op.value.selector), input: "" };
    case "pinch":
      return { verb: op.value.direction === 2 ? "Pinch close" : "Pinch open", target: selector(op.value.selector), input: "" };
    case "drag":
      return { verb, target: `${selector(op.value.selector)} → ${selector(op.value.target)}`, input: "" };
    case "pressKey":
      return { verb: "Press key", target: String(op.value.keyCode), input: "" };
    case "waitAppVisible":
    case "waitScreenStable":
      return { verb: op.case === "waitAppVisible" ? "Wait for app" : "Wait for stable screen", target: op.value.packageName, input: "" };
    case "setClipboard":
      return { verb, target: "", input: quote(op.value.text) };
    default:
      return { verb, target: "", input: "" };
  }
}

/** The parts of a row for [event]. */
export function describe(event: LoggedEvent): Described {
  let parts: Pick<Described, "verb" | "target" | "input">;
  const call = event.call;
  if (call.case === "command") parts = command(call.value);
  else if (call.case === "app") parts = { verb: words(call.value.operation), target: call.value.packageName, input: call.value.uri ?? call.value.permission ?? "" };
  else if (call.case === "device") parts = { verb: words(call.value.operation), target: call.value.devicePath ?? "", input: call.value.locales.join(", ") };
  else parts = { verb: "Call", target: "", input: "" };
  let outcome = "";
  let detail = "";
  if (event.error) {
    outcome = ErrorCode[event.error.code]?.replace(/^ERR_/, "") ?? "ERROR";
    detail = event.error.message ?? event.error.detail ?? "";
  } else if (event.failure) {
    outcome = FailureReason[event.failure.reason]?.replace(/^FAILURE_REASON_/, "") ?? "FAILED";
    detail = event.failure.detail;
  }
  const why = call.case === "command" && event.error ? [...narrowing(call.value, event.error), ...conditionParts(call.value, event.error)] : [];
  return { ...parts, outcome, detail, why };
}

/** The command's selectors in `Error.narrowing.selector_index` order: `selector`, then drag's `target`. */
function selectors(value: Command): Selector[] {
  const op = value.op.value as { selector?: Selector; target?: Selector } | undefined;
  return [op?.selector, op?.target].filter((s): s is Selector => s !== undefined);
}

/** `Error.narrowing` against the selector it names; nothing when it does not fit the command. */
function narrowing(value: Command, error: CommandError): string[] {
  const narrowed = error.narrowing;
  const target = narrowed ? selectors(value)[narrowed.selectorIndex] : undefined;
  if (!narrowed || !target) return [];
  const operands = target.node?.kind.case === "allOf" ? target.node.kind.value.nodes : [];
  if (narrowed.parts.length > 0 && narrowed.parts.length !== operands.length) return [];
  if (narrowed.matches > 0) {
    const needed = target.pick.case === "at" ? target.pick.value.index + 1 : 1;
    const nodes = narrowed.matches === 1 ? "1 node matches" : `${narrowed.matches} nodes match`;
    return [`Only ${nodes} ${selector(target)}; its pick needs ${needed}`];
  }
  return [
    `No node matches ${selector(target)}${narrowed.parts.length > 0 ? "; each part:" : ""}`,
    ...narrowed.parts.map((part, i) => {
      const seen = part.nearMisses.map((v) => quote(v)).join(", ");
      return `${node(operands[i])}: ${part.alone} alone, ${part.without} without it${seen ? ` (seen: ${seen})` : ""}`;
    }),
  ];
}

/** A state wait's `Error.condition_holds`, one line per top-level part, with what the node had. */
function conditionParts(value: Command, error: CommandError): string[] {
  if (value.op.case !== "waitState") return [];
  const condition = value.op.value.condition;
  const kind = condition?.kind;
  const operands = kind?.case === "allOf" || kind?.case === "anyOf" ? kind.value.nodes : condition ? [condition] : [];
  if (error.conditionHolds.length === 0 || error.conditionHolds.length !== operands.length) return [];
  return operands.map((part, i) => {
    const had = observed(part, error.lastState);
    return `${error.conditionHolds[i] ? "✓" : "✗"} ${node(part)}${had !== undefined && !error.conditionHolds[i] ? ` (has ${had})` : ""}`;
  });
}

const SNAPSHOT_TEXT: Record<number, "text" | "contentDescription" | "hint" | "className" | "packageName"> = {
  [TextProperty.PROPERTY_TEXT]: "text",
  [TextProperty.PROPERTY_CONTENT_DESCRIPTION]: "contentDescription",
  [TextProperty.PROPERTY_HINT]: "hint",
  [TextProperty.PROPERTY_CLASS_NAME]: "className",
  [TextProperty.PROPERTY_PACKAGE_NAME]: "packageName",
};

/** What [state] had for a property, resource or flag part; undefined for relations and combinations. */
function observed(part: Node, state: ElementSnapshot | undefined): string | undefined {
  if (!state) return undefined;
  const kind = part.kind;
  const text = (v: string | undefined) => (v === undefined ? "none" : quote(v));
  if (kind.case === "match" && SNAPSHOT_TEXT[kind.value.property]) return text(state[SNAPSHOT_TEXT[kind.value.property]]);
  if (kind.case === "resource") return text(state.resourceName);
  if (kind.case === "flag") {
    const name = NodeFlag[kind.value.property]?.replace("FLAG_", "").toLowerCase().replace(/_([a-z])/g, (_, c: string) => c.toUpperCase());
    const flag = name ? (state as unknown as Record<string, unknown>)[name] : undefined;
    return typeof flag === "boolean" ? String(flag) : undefined;
  }
  return undefined;
}

/** One line: `Set text res("email") ← "ada@…" · NOT_FOUND`. */
export function summary(value: Described): string {
  let line = value.verb;
  if (value.target) line += ` ${value.target}`;
  if (value.input) line += ` ← ${value.input}`;
  if (value.outcome) line += ` · ${value.outcome}`;
  return line;
}

export function failed(event: LoggedEvent): boolean {
  return Boolean(event.error || event.failure);
}

/** A connection's name as people know it: "junit 4812 /home/a/fixture-tests" → "JUnit · fixture-tests". */
export function connectionLabel(name: string): string {
  const runner = /^(junit|pytest) \d+ (.+)$/.exec(name);
  if (runner) {
    const project = runner[2].split(/[\\/]/).filter(Boolean).at(-1) ?? runner[2];
    return `${runner[1] === "junit" ? "JUnit" : "pytest"} · ${project}`;
  }
  if (name === "tap-studio") return "Tap Studio";
  return name || "Unnamed connection";
}
