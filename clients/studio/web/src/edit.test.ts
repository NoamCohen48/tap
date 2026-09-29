import { create } from "@bufbuild/protobuf";
import { describe, expect, it } from "vitest";
import { ScreenNodeSchema } from "./gen/device_pb";
import { NodeFlag, type Selector } from "./gen/selector_pb";
import { countLine } from "./edit";
import { checksFor } from "./nodes";
import { parseSelector } from "./parse";

function sel(text: string): Selector {
  const parsed = parseSelector(text);
  if ("error" in parsed) throw new Error(parsed.error);
  return parsed.selector;
}

describe("countLine", () => {
  it("wants exactly one match for an action without a pick", () => {
    expect(countLine(1, sel('res("go")'), false)).toEqual({ text: "Matches 1 element now.", warn: false });
    expect(countLine(3, sel('res("go")'), false).warn).toBe(true);
    expect(countLine(0, sel('res("go")'), false).warn).toBe(true);
  });

  it("wants enough matches for the pick, since the count ignores it", () => {
    expect(countLine(2, sel('text("Add").at(1)'), false)).toEqual({ text: "Matches 2 elements now; the step uses element 2.", warn: false });
    expect(countLine(1, sel('text("Add").at(1)'), false)).toEqual({ text: "Matches 1 element now: .at(1) needs at least 2.", warn: true });
    expect(countLine(4, sel('text("Add").first()'), false)).toEqual({ text: "Matches 4 elements now; the step uses the first.", warn: false });
    expect(countLine(0, sel('text("Add").first()'), false).warn).toBe(true);
  });

  it("does not ask an assertion for exactly one", () => {
    expect(countLine(3, sel('res("row")'), true)).toEqual({ text: "Matches 3 elements now.", warn: false });
  });
});

describe("checksFor", () => {
  const node = create(ScreenNodeSchema, { ref: "e1", text: "Add", flags: [NodeFlag.FLAG_ENABLED] });

  it("offers exactly one only for a selector without a pick", () => {
    expect(checksFor(node, sel('text("Add")')).map((o) => o.label)).toEqual(["Visible", "Exactly one", "Text is “Add”", "Enabled"]);
    expect(checksFor(node, sel('text("Add").at(1)')).map((o) => o.label)).toEqual(["Visible", "Text is “Add”", "Enabled"]);
  });
});
