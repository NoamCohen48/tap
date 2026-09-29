// The steps the page asks the studio to perform. The back end completes them (the inferred wait,
// the default gesture distance), runs them and records the ones that pass: the page only says
// what the user did to which selector.

import { create, type MessageInitShape } from "@bufbuild/protobuf";
import { AppCallSchema } from "./gen/event_log_pb";
import { CommandSchema, type Direction } from "./gen/command_pb";
import type { Selector } from "./gen/selector_pb";
import {
  Condition,
  PerformRequestSchema,
  SelectorOrigin,
  StepSchema,
  type PerformRequest,
  type Step,
} from "./gen/studio_pb";

export type Gesture = "tap" | "longTap" | "clearText";

/** Text for a node: typed as-is, or the value of a named secret, which is sent to the device and
 *  never recorded. */
export type TextInput = { text: string } | { secret: string; value: string };

const origin = SelectorOrigin.SYNTHESIZED;

function request(step: Step, secretValue?: string): PerformRequest {
  return create(PerformRequestSchema, { step, secretValue });
}

type Op = NonNullable<MessageInitShape<typeof CommandSchema>["op"]>;

function action(op: Op, secret?: string): Step {
  return create(StepSchema, {
    kind: { case: "action", value: { command: create(CommandSchema, { op }), secret, selectorOrigin: origin } },
  });
}

export function gesture(selector: Selector, kind: Gesture): PerformRequest {
  return request(action({ case: kind, value: { selector } }));
}

export function scroll(selector: Selector, direction: Direction): PerformRequest {
  return request(action({ case: "scroll", value: { selector, direction } }));
}

export function swipe(selector: Selector, direction: Direction): PerformRequest {
  return request(action({ case: "swipe", value: { selector, direction } }));
}

export function pressKey(keyCode: number): PerformRequest {
  return create(PerformRequestSchema, {
    step: create(StepSchema, { kind: { case: "action", value: { command: create(CommandSchema, { op: { case: "pressKey", value: { keyCode } } }) } } }),
  });
}

/** `set_text`: one command, no keyboard, no focus needed. */
export function setText(selector: Selector, input: TextInput): PerformRequest {
  if ("secret" in input) {
    return request(action({ case: "setText", value: { selector, text: "" } }, input.secret), input.value);
  }
  return request(action({ case: "setText", value: { selector, text: input.text } }));
}

/** The SDKs' element `typeText`: tap, await focus, then `type_text`, for fields that react to keys. */
export function typeText(selector: Selector, input: TextInput): PerformRequest {
  const step = create(StepSchema, {
    kind: {
      case: "type",
      value: {
        selector,
        input: "secret" in input ? { case: "secret", value: input.secret } : { case: "text", value: input.text },
        selectorOrigin: origin,
      },
    },
  });
  return request(step, "secret" in input ? input.value : undefined);
}

export type AppOperation = "cold_launch" | "launch" | "force_stop" | "clear_data" | "grant_permission";

export function app(operation: AppOperation, packageName: string, permission?: string): PerformRequest {
  return request(create(StepSchema, { kind: { case: "app", value: create(AppCallSchema, { operation, packageName, permission }) } }));
}

export type Check =
  | { condition: Exclude<Condition, Condition.TEXT_EQUALS | Condition.TEXT_CONTAINS | Condition.COUNT> }
  | { condition: Condition.TEXT_EQUALS | Condition.TEXT_CONTAINS; text: string }
  | { condition: Condition.COUNT; count: number };

export function assertion(selector: Selector, check: Check): PerformRequest {
  const value = "text" in check ? { case: "text" as const, value: check.text } : "count" in check ? { case: "count" as const, value: check.count } : undefined;
  return request(
    create(StepSchema, {
      kind: { case: "assertion", value: { selector, condition: check.condition, value, selectorOrigin: origin } },
    }),
  );
}
