import { create } from "@bufbuild/protobuf";
import { describe as suite, expect, it } from "vitest";
import { connectionLabel, describe, failed, summary } from "./describe";
import { ErrorCode } from "./gen/command_pb";
import { LoggedEventSchema, type LoggedEvent } from "./gen/event_log_pb";
import { MatchMode, NodeFlag, TextProperty } from "./gen/selector_pb";

const resource = (name: string) => ({ node: { kind: { case: "resource" as const, value: { name } } } });

function command(op: object, error?: { code: ErrorCode; message: string } & Record<string, unknown>): LoggedEvent {
  return create(LoggedEventSchema, { seq: 1n, serial: "s", call: { case: "command", value: { op } as never }, error });
}

suite("describe", () => {
  it("names the target and the typed text", () => {
    const event = command({ case: "setText", value: { selector: resource("email"), text: "ada@example.com" } });
    expect(summary(describe(event))).toBe('Set text res("email") ← "ada@example.com"');
    expect(failed(event)).toBe(false);
  });

  it("shows the error code and keeps the detail", () => {
    const selector = { node: { kind: { case: "match", value: { property: TextProperty.PROPERTY_TEXT, mode: MatchMode.MATCH_CONTAINS, value: "Archive" } } }, pick: { case: "first", value: {} } };
    const event = command({ case: "tap", value: { selector } }, { code: ErrorCode.ERR_AMBIGUOUS, message: "2 matches" });
    const described = describe(event);
    expect(summary(described)).toBe('Tap textContains("Archive").first · AMBIGUOUS');
    expect(described.detail).toBe("2 matches");
    expect(failed(event)).toBe(true);
  });

  it("names the condition a state wait waits for", () => {
    const enabled = { kind: { case: "flag" as const, value: { property: NodeFlag.FLAG_ENABLED, value: true } } };
    const event = command({ case: "waitState", value: { selector: resource("pay"), condition: enabled } });
    expect(summary(describe(event))).toBe('Wait for res("pay") ← enabled');
    const total = { kind: { case: "match" as const, value: { property: TextProperty.PROPERTY_TEXT, mode: MatchMode.MATCH_CONTAINS, value: "€" } } };
    const both = { kind: { case: "allOf" as const, value: { nodes: [enabled, total] } } };
    const text = command({ case: "waitState", value: { selector: resource("total"), condition: both } });
    expect(summary(describe(text))).toBe('Wait for res("total") ← enabled & textContains("€")');
  });

  it("says which selector part ruled the nodes out", () => {
    const text = (value: string) => ({ kind: { case: "match" as const, value: { property: TextProperty.PROPERTY_TEXT, value } } });
    const both = { node: { kind: { case: "allOf" as const, value: { nodes: [resource("pay").node, text("Pay")] } } } };
    const narrowing = { selectorIndex: 0, matches: 0, parts: [{ alone: 1, without: 0, nearMisses: ["Paying…"] }, { alone: 0, without: 1, nearMisses: [] }] };
    const miss = describe(command({ case: "tap", value: { selector: both } }, { code: ErrorCode.ERR_NOT_FOUND, message: "no match", narrowing }));
    expect(miss.why).toEqual(['No node matches res("pay") & text("Pay"); each part:', 'res("pay"): 1 alone, 0 without it (seen: "Paying…")', 'text("Pay"): 0 alone, 1 without it']);

    // A drag's target is selector 1; an index past the pick's reach says how many it found.
    const at = { ...resource("row"), pick: { case: "at" as const, value: { index: 2 } } };
    const drag = describe(command({ case: "drag", value: { selector: resource("handle"), target: at } }, { code: ErrorCode.ERR_NOT_FOUND, message: "", narrowing: { selectorIndex: 1, matches: 2, parts: [] } }));
    expect(drag.why).toEqual(['Only 2 nodes match res("row")[2]; its pick needs 3']);

    // A narrowing that does not fit the command shows nothing rather than a wrong line.
    const stray = describe(command({ case: "tap", value: { selector: both } }, { code: ErrorCode.ERR_NOT_FOUND, message: "", narrowing: { selectorIndex: 1, matches: 0, parts: [] } }));
    expect(stray.why).toEqual([]);
  });

  it("lists a state wait's condition parts with what the node had", () => {
    const enabled = { kind: { case: "flag" as const, value: { property: NodeFlag.FLAG_ENABLED, value: true } } };
    const pay = { kind: { case: "match" as const, value: { property: TextProperty.PROPERTY_TEXT, value: "Pay" } } };
    const both = { kind: { case: "allOf" as const, value: { nodes: [enabled, pay] } } };
    const event = command(
      { case: "waitState", value: { selector: resource("pay"), condition: both } },
      { code: ErrorCode.ERR_WAIT_TIMEOUT, message: "state not reached", conditionHolds: [true, false], lastState: { text: "Paying…", enabled: true } },
    );
    expect(describe(event).why).toEqual(["✓ enabled", '✗ text("Pay") (has "Paying…")']);
    // No last state: the parts without what the node had.
    const bare = command({ case: "waitState", value: { selector: resource("pay"), condition: enabled } }, { code: ErrorCode.ERR_WAIT_TIMEOUT, message: "", conditionHolds: [false] });
    expect(describe(bare).why).toEqual(["✗ enabled"]);
  });

  it("names the panel's node of a tap outside it", () => {
    const event = command({ case: "tapOutsidePanel", value: { selector: resource("dialog_title") } });
    expect(summary(describe(event))).toBe('Tap outside panel res("dialog_title")');
  });

  it("shows a sensitive text as hidden", () => {
    const event = command({ case: "setText", value: { selector: resource("password"), text: "", sensitive: true } });
    expect(describe(event).input).toBe("(hidden)");
    expect(describe(command({ case: "typeText", value: { text: "", sensitive: true } })).input).toBe("(hidden)");
  });

  it("shortens long input", () => {
    const event = command({ case: "typeText", value: { text: "x".repeat(100) } });
    expect(describe(event).input).toHaveLength(42);
  });

  it("labels known runners by project", () => {
    expect(connectionLabel("junit 4812 /home/a/fixture-tests")).toBe("JUnit · fixture-tests");
    expect(connectionLabel("pytest 77 /work/app/")).toBe("pytest · app");
    expect(connectionLabel("tap-studio")).toBe("Tap Studio");
    expect(connectionLabel("my-script")).toBe("my-script");
  });
});
