import { create } from "@bufbuild/protobuf";
import { describe as suite, expect, it } from "vitest";
import { connectionLabel, describe, failed, summary } from "./describe";
import { ErrorCode } from "./gen/command_pb";
import { LoggedEventSchema, type LoggedEvent } from "./gen/event_log_pb";
import { MatchMode, TextProperty } from "./gen/selector_pb";

const resource = (name: string) => ({ node: { kind: { case: "resource" as const, value: { name } } } });

function command(op: object, error?: { code: ErrorCode; message: string }): LoggedEvent {
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
