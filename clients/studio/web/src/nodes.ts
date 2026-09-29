// What the page offers for a node: the checks that fit it and the warnings its selectors carry.

import { SelectorKind, type ScreenNode, type SelectorCandidate } from "./gen/device_pb";
import { NodeFlag, TextProperty, type Node, type Selector } from "./gen/selector_pb";
import { Condition } from "./gen/studio_pb";
import { isCheckable } from "./geometry";
import type { Check } from "./steps";

export type CheckOption = { label: string; check: Check };

const has = (node: ScreenNode, flag: NodeFlag) => node.flags.includes(flag);

/** Whether the selector picks among several matches. Waits count every match whatever the pick. */
export const picks = (selector: Selector | undefined) => selector?.pick.case === "first" || selector?.pick.case === "at";

/**
 * The assertions that hold on the node as it is on this frame, so recording one passes. No
 * "Exactly one" for a selector that picks: it matches several nodes, so the check could only fail.
 */
export function checksFor(node: ScreenNode, selector: Selector | undefined): CheckOption[] {
  const options: CheckOption[] = [{ label: "Visible", check: { condition: Condition.VISIBLE } }];
  if (!picks(selector)) options.push({ label: "Exactly one", check: { condition: Condition.ONE } });
  if (node.text) options.push({ label: `Text is “${node.text}”`, check: { condition: Condition.TEXT_EQUALS, text: node.text } });
  options.push(
    has(node, NodeFlag.FLAG_ENABLED)
      ? { label: "Enabled", check: { condition: Condition.ENABLED } }
      : { label: "Disabled", check: { condition: Condition.DISABLED } },
  );
  if (isCheckable(node)) {
    options.push(
      has(node, NodeFlag.FLAG_CHECKED)
        ? { label: "Checked", check: { condition: Condition.CHECKED } }
        : { label: "Unchecked", check: { condition: Condition.UNCHECKED } },
    );
  }
  if (has(node, NodeFlag.FLAG_FOCUSED)) options.push({ label: "Focused", check: { condition: Condition.FOCUSED } });
  // No "Gone": the node is on this frame, so the check would time out and not be recorded.
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
