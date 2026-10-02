// What the page offers for a node: the waits and assertions that fit it and the warnings its selectors carry.

import { SelectorKind, type ScreenNode, type SelectorCandidate } from "./gen/device_pb";
import { NodeFlag, TextProperty, type Node, type Selector } from "./gen/selector_pb";
import { Check, Condition } from "./gen/studio_pb";
import { isCheckable } from "./geometry";
import type { Expect, Until } from "./steps";

export type WaitOption = { label: string; until: Until; title?: string };
export type AssertOption = { label: string; expect: Expect };

const has = (node: ScreenNode, flag: NodeFlag) => node.flags.includes(flag);

/** Whether the selector picks among several matches. Waits count every match whatever the pick. */
export const picks = (selector: Selector | undefined) => selector?.pick.case === "first" || selector?.pick.case === "at";

/**
 * The waits offered for a node (text and count take a value, so the page asks for those apart).
 * A wait is for what is about to happen, so both states of a flag are offered, and Gone too:
 * waiting for a spinner to go is the point. No "Exactly one" for a selector that picks: it
 * matches several nodes, so the wait could only time out.
 */
export function waitsFor(node: ScreenNode, selector: Selector | undefined): WaitOption[] {
  const options: WaitOption[] = [{ label: "Visible", until: { condition: Condition.VISIBLE } }];
  if (!picks(selector)) options.push({ label: "Exactly one", until: { condition: Condition.ONE } });
  options.push(
    { label: "Gone", until: { condition: Condition.GONE }, title: "Until no element matches: times out while this one stays" },
    { label: "Enabled", until: { condition: Condition.ENABLED } },
    { label: "Disabled", until: { condition: Condition.DISABLED } },
  );
  if (isCheckable(node)) {
    options.push({ label: "Checked", until: { condition: Condition.CHECKED } }, { label: "Unchecked", until: { condition: Condition.UNCHECKED } });
  }
  if (has(node, NodeFlag.FLAG_FOCUSABLE) || has(node, NodeFlag.FLAG_FOCUSED)) {
    options.push({ label: "Focused", until: { condition: Condition.FOCUSED } });
  }
  return options;
}

/**
 * The assertions offered for a node: the state it is in on this frame, so recording one passes
 * (an assertion does not wait for a change). Text and count take a value, asked for apart.
 */
export function assertsFor(node: ScreenNode): AssertOption[] {
  const options: AssertOption[] = [{ label: "Exists", expect: { check: Check.EXISTS } }];
  options.push(
    has(node, NodeFlag.FLAG_ENABLED) ? { label: "Is enabled", expect: { check: Check.ENABLED } } : { label: "Is disabled", expect: { check: Check.DISABLED } },
  );
  if (isCheckable(node)) {
    options.push(
      has(node, NodeFlag.FLAG_CHECKED) ? { label: "Is checked", expect: { check: Check.CHECKED } } : { label: "Is unchecked", expect: { check: Check.UNCHECKED } },
    );
  }
  if (has(node, NodeFlag.FLAG_FOCUSED)) options.push({ label: "Is focused", expect: { check: Check.FOCUSED } });
  return options;
}

function texts(node: Node | undefined): string[] {
  if (!node) return [];
  switch (node.kind.case) {
    case "match":
      return node.kind.value.property === TextProperty.PROPERTY_TEXT || node.kind.value.property === TextProperty.PROPERTY_CONTENT_DESCRIPTION
        ? [node.kind.value.value]
        : [];
    case "allOf":
    case "anyOf":
      return node.kind.value.nodes.flatMap(texts);
    case "related":
      return texts(node.kind.value.node);
    default:
      return [];
  }
}

/** Matches on text with digits (prices, counts, dates, times), which usually change between runs. */
export function looksDynamic(selector: Selector | undefined): boolean {
  return texts(selector?.node).some((value) => /\d/.test(value));
}

export type Chip = { text: string; tone: "ok" | "warn" | "info"; title?: string };

export function candidateChips(candidate: SelectorCandidate): Chip[] {
  const chips: Chip[] = [];
  if (candidate.kind === SelectorKind.COMBINED) chips.push({ text: "combined", tone: "info" });
  if (candidate.kind === SelectorKind.ANCESTOR) chips.push({ text: "by ancestor", tone: "info" });
  if (candidate.kind === SelectorKind.BY_INDEX) {
    chips.push({ text: "by index", tone: "warn", title: "Correct now; breaks when the order of matching nodes changes" });
  }
  if (looksDynamic(candidate.selector)) {
    chips.push({ text: "dynamic text", tone: "warn", title: "Contains digits: prices, counts and dates usually change between runs" });
  }
  return chips;
}
