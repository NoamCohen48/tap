// The steps the page asks the studio to perform. The back end completes them (the inferred wait,
// the default gesture distance), runs them and records the ones that pass: the page only says
// what the user did to which selector.

import { create, type MessageInitShape } from "@bufbuild/protobuf";
import { AppCallSchema } from "./gen/event_log_pb";
import { CommandSchema, StabilitySignal, type Direction, type SystemPanel } from "./gen/command_pb";
import type { Selector } from "./gen/selector_pb";
import { Check, Condition, PerformRequestSchema, SelectorOrigin, StepSchema, type PerformRequest, type Step } from "./gen/studio_pb";

export type Gesture = "tap" | "longTap" | "clearText";

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

export function pressKey(keyCode: number): PerformRequest {
  return create(PerformRequestSchema, {
    step: create(StepSchema, {
      kind: { case: "action", value: { command: create(CommandSchema, { op: { case: "pressKey", value: { keyCode } } }) } },
    }),
  });
}

/** `open_system_panel`: the notification shade or quick settings, no target. */
export function openSystemPanel(panel: SystemPanel): PerformRequest {
  return create(PerformRequestSchema, {
    step: create(StepSchema, {
      kind: { case: "action", value: { command: create(CommandSchema, { op: { case: "openSystemPanel", value: { panel } } }) } },
    }),
  });
}

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

export type AppOperation = "cold_launch" | "launch" | "force_stop" | "clear_data" | "grant_permission";

/** An app step. `permission` goes with grant_permission; `activity` with the launches (absent:
 *  the launcher activity; `.ui.Settings` is in the package). */
export function app(operation: AppOperation, packageName: string, { permission, activity }: { permission?: string; activity?: string } = {}): PerformRequest {
  return request(create(StepSchema, { kind: { case: "app", value: create(AppCallSchema, { operation, packageName, permission, activity }) } }));
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
