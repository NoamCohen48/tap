// Editing recorded steps: what a step targets and types, changed copies of it, and the warnings
// it carries. The studio re-infers an edited action's wait (UpdateStep), so only the command's
// selector changes here.

import { clone, equals } from "@bufbuild/protobuf";
import type { Command, Direction } from "./gen/command_pb";
import type { ScreenNode } from "./gen/device_pb";
import { SelectorSchema, type Selector } from "./gen/selector_pb";
import { Check, SelectorOrigin, StepSchema, type Step } from "./gen/studio_pb";
import { looksDynamic, type Chip } from "./nodes";
import { DEFAULT_DISTANCE, DEFAULT_MAX_SCROLLS, type Target } from "./steps";

/** The command message of an action on an element (every recorded op but press_key and open_system_panel). */
function elementOp(command: Command | undefined) {
  const op = command?.op;
  switch (op?.case) {
    case "tap":
    case "longTap":
    case "setText":
    case "clearText":
    case "scroll":
    case "swipe":
      return op.value;
    default:
      return undefined;
  }
}

/** The selector a step acts on, waits for or checks (a scroll until's container); `undefined`
 *  for app steps, app waits and keys. */
export function stepSelector(step: Step): Selector | undefined {
  switch (step.kind.case) {
    case "action":
      return elementOp(step.kind.value.command)?.selector;
    case "type":
    case "wait":
    case "assertion":
      return step.kind.value.selector;
    case "scrollUntil":
      return step.kind.value.container;
    default:
      return undefined;
  }
}

export function stepOrigin(step: Step): SelectorOrigin {
  switch (step.kind.case) {
    case "action":
    case "type":
    case "wait":
    case "assertion":
    case "scrollUntil":
      return step.kind.value.selectorOrigin;
    default:
      return SelectorOrigin.UNSPECIFIED;
  }
}

/** A copy of the step on another selector. */
export function withSelector(step: Step, { selector, origin }: Target): Step {
  const copy = clone(StepSchema, step);
  switch (copy.kind.case) {
    case "action": {
      const op = elementOp(copy.kind.value.command);
      if (op) op.selector = selector;
      copy.kind.value.selectorOrigin = origin;
      break;
    }
    case "type":
    case "wait":
    case "assertion":
      copy.kind.value.selector = selector;
      copy.kind.value.selectorOrigin = origin;
      break;
    case "scrollUntil":
      copy.kind.value.container = selector;
      copy.kind.value.selectorOrigin = origin;
      break;
  }
  return copy;
}

/** A copy of a scroll until step scrolling to another target. */
export function withScrollTarget(step: Step, target: Selector): Step {
  const copy = clone(StepSchema, step);
  if (copy.kind.case === "scrollUntil") copy.kind.value.target = target;
  return copy;
}

/** How a swipe, a scroll or a scroll until moves: its direction and distance, and a scroll
 *  until's scroll budget. */
export type Movement = { direction: Direction; distance: number; maxScrolls?: number };

export function stepMovement(step: Step): Movement | null {
  if (step.kind.case === "scrollUntil") {
    const u = step.kind.value;
    return { direction: u.direction, distance: u.distancePercent ?? DEFAULT_DISTANCE, maxScrolls: u.maxScrolls ?? DEFAULT_MAX_SCROLLS };
  }
  const op = step.kind.case === "action" ? step.kind.value.command?.op : undefined;
  if (op?.case === "scroll" || op?.case === "swipe") return { direction: op.value.direction, distance: op.value.distancePercent ?? DEFAULT_DISTANCE };
  return null;
}

/** A copy of a swipe, scroll or scroll until moving another way. */
export function withMovement(step: Step, { direction, distance, maxScrolls }: Movement): Step {
  const copy = clone(StepSchema, step);
  if (copy.kind.case === "scrollUntil") {
    Object.assign(copy.kind.value, { direction, distancePercent: distance, maxScrolls: maxScrolls ?? copy.kind.value.maxScrolls });
  } else if (copy.kind.case === "action") {
    const op = copy.kind.value.command?.op;
    if (op?.case === "scroll" || op?.case === "swipe") Object.assign(op.value, { direction, distancePercent: distance });
  }
  return copy;
}

/** The text a step enters or checks: typed text, or the name of the secret it enters. */
export type StepText = { text: string } | { secret: string };

export function stepText(step: Step): StepText | null {
  switch (step.kind.case) {
    case "action": {
      const { command, secret } = step.kind.value;
      if (command?.op.case !== "setText") return null;
      return secret !== undefined ? { secret } : { text: command.op.value.text };
    }
    case "type": {
      const input = step.kind.value.input;
      if (input.case === "secret") return { secret: input.value };
      return { text: input.value ?? "" };
    }
    case "wait":
    case "assertion":
      return step.kind.value.value.case === "text" ? { text: step.kind.value.value.value } : null;
    default:
      return null;
  }
}

/** A copy of the step entering or checking other text. Only set_text and type steps take a secret. */
export function withText(step: Step, value: StepText): Step {
  const copy = clone(StepSchema, step);
  switch (copy.kind.case) {
    case "action": {
      const op = copy.kind.value.command?.op;
      if (op?.case !== "setText") break;
      op.value.text = "text" in value ? value.text : "";
      copy.kind.value.secret = "secret" in value ? value.secret : undefined;
      break;
    }
    case "type":
      copy.kind.value.input = "secret" in value ? { case: "secret", value: value.secret } : { case: "text", value: value.text };
      break;
    case "wait":
    case "assertion":
      if ("text" in value && copy.kind.value.value.case === "text") copy.kind.value.value.value = value.text;
      break;
  }
  return copy;
}

export const takesSecret = (step: Step) =>
  (step.kind.case === "action" && step.kind.value.command?.op.case === "setText") || step.kind.case === "type";

/** The node on this frame a selector was synthesized for, so its other candidates can be offered. */
export function nodeFor(nodes: readonly ScreenNode[], selector: Selector | undefined): ScreenNode | null {
  if (!selector) return null;
  const same = (other: Selector | undefined) => !!other && equals(SelectorSchema, other, selector);
  return nodes.find((n) => same(n.selector) || n.candidates.some((c) => same(c.selector))) ?? null;
}

/** The warnings a recorded step carries: fragile selectors, a secret without its value. */
export function stepChips(step: Step, missingSecrets: readonly string[]): Chip[] {
  const chips: Chip[] = [];
  const selector = stepSelector(step);
  if (selector?.pick.case === "at") {
    chips.push({ text: "by index", tone: "warn", title: "Correct when recorded; breaks when the order of matching nodes changes" });
  }
  if (looksDynamic(selector)) {
    chips.push({ text: "dynamic text", tone: "warn", title: "Contains digits: prices, counts and dates usually change between runs" });
  }
  if (stepOrigin(step) === SelectorOrigin.EDITED) chips.push({ text: "typed selector", tone: "info" });
  const text = stepText(step);
  if (text && "secret" in text) {
    const missing = missingSecrets.includes(text.secret);
    chips.push(
      missing
        ? { text: `\${${text.secret}} needs a value`, tone: "warn", title: "Opened from a file: a replay asks for the value" }
        : { text: "secret", tone: "info" },
    );
  }
  return chips;
}

const elements = (n: number) => `${n} element${n === 1 ? "" : "s"}`;

/** Whether a step needs exactly one match when it runs (or enough for its pick): actions, and
 *  assertions that read the one match's state. Waits, `exists` and `count` take any number. */
export function needsOne(step: Step): boolean {
  if (step.kind.case === "wait") return false;
  if (step.kind.case === "assertion") return step.kind.value.check !== Check.EXISTS && step.kind.value.check !== Check.COUNT;
  return true;
}

/**
 * What a live count of the selector means for a step. `Count` counts every match whatever the
 * pick, as the driver's waits do: a step that needs one match (`one`) needs exactly one, or with
 * `.first()` / `.at(i)` enough matches for the pick; any other step takes any number.
 */
export function countLine(count: number, selector: Selector, one: boolean): { text: string; warn: boolean } {
  const now = `Matches ${elements(count)} now`;
  if (!one) return { text: `${now}.`, warn: false };
  if (selector.pick.case === "first") {
    return count > 0 ? { text: `${now}; the step uses the first.`, warn: false } : { text: `${now}.`, warn: true };
  }
  if (selector.pick.case === "at") {
    const index = selector.pick.value.index;
    return count > index
      ? { text: `${now}; the step uses element ${index + 1}.`, warn: false }
      : { text: `${now}: .at(${index}) needs at least ${index + 1}.`, warn: true };
  }
  return count === 1 ? { text: `${now}.`, warn: false } : { text: `${now}: the step needs exactly one when it runs.`, warn: true };
}
