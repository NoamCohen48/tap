import { describe, expect, it } from "vitest";
import { frameOffset } from "./format";

describe("comparison frame offsets", () => {
  it("rounds sub-50 ms offsets to the call boundary", () => {
    expect(frameOffset(-49, "started")).toBe("as it started");
    expect(frameOffset(0, "ended")).toBe("as it ended");
    expect(frameOffset(49, "ended")).toBe("as it ended");
  });

  it("keeps the offset's direction even when no frame arrived after the reply", () => {
    expect(frameOffset(-400, "started")).toBe("0.4 s before it started");
    expect(frameOffset(600, "ended")).toBe("0.6 s after it ended");
    expect(frameOffset(-1200, "ended")).toBe("1.2 s before it ended");
  });
});
