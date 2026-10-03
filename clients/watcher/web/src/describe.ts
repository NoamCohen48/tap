// Human words for what a connection did: "Tap res("email")", "Set text res("email") ← "ada@…"".
// A lean describer of tap.v1 messages; it never invents what the event does not carry.

import type { LoggedEvent } from "./gen/event_log_pb";
import { ErrorCode, type Command } from "./gen/command_pb";
import { FailureReason } from "./gen/failure_pb";
import { MatchMode, NodeFlag, Relation, TextProperty, type Node, type Selector } from "./gen/selector_pb";

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
    case "clearText":
    case "exists":
    case "count":
    case "snapshot":
    case "waitGone":
      return { verb, target: selector(op.value.selector), input: "" };
    case "waitVisible":
      return { verb: "Wait for", target: selector(op.value.selector), input: "" };
    case "setText":
      return { verb, target: selector(op.value.selector), input: quote(op.value.text) };
    case "typeText":
      return { verb, target: "", input: quote(op.value.text) };
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
  return { ...parts, outcome, detail };
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
