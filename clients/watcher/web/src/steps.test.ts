import { describe, expect, it } from "vitest";
import type { Action, SessionMark } from "./activity";
import { axisTicks, listItems, nearestFailure, openingFailure, stepFailure, unheldSpans } from "./steps";

/** Only the fields the helpers read. */
function action(key: string, atEpochMs: number, failed = false, connectionId = "c1"): Action {
  return { key, connectionId, failed, event: { atEpochMs: BigInt(atEpochMs) } } as unknown as Action;
}

function mark(key: string, kind: SessionMark["kind"], atEpochMs: number, connectionId = "c1"): SessionMark {
  return { key, kind, atEpochMs, connectionId, reason: kind === "detached" ? "detached" : "" };
}

const actions = [action("a", 1), action("b", 2, true), action("c", 3), action("d", 4), action("e", 5, true), action("f", 6)];

describe("steps", () => {
  it("moves failure to failure, and starts from the newest", () => {
    expect(stepFailure(actions, undefined, 1)?.key).toBe("e");
    expect(stepFailure(actions, undefined, -1)?.key).toBe("e");
    expect(stepFailure(actions, actions[3], -1)?.key).toBe("b");
    expect(stepFailure(actions, actions[3], 1)?.key).toBe("e");
    expect(stepFailure(actions, actions[4], 1)).toBeUndefined();
    expect(stepFailure([action("x", 1)], undefined, 1)).toBeUndefined();
  });

  it("finds the failure nearest a step, the newer on a tie", () => {
    expect(nearestFailure(actions, actions[2])?.key).toBe("b");
    expect(nearestFailure(actions, actions[5])?.key).toBe("e");
    const tied = [action("a", 1, true), action("b", 2), action("c", 3, true)];
    expect(nearestFailure(tied, tied[1])?.key).toBe("c");
    const quiet = [action("a", 1), action("b", 2)];
    expect(nearestFailure(quiet, quiet[0])).toBeUndefined();
  });

  it("opens on the newest unseen failure whose video is kept, else the newest", () => {
    const unseen = [action("old", 1_000, true), action("kept", 100_000, true), action("gone", 5_000, true)];
    expect(openingFailure(unseen, 150_000, 120_000)?.key).toBe("kept");
    expect(openingFailure(unseen, 500_000, 120_000)?.key).toBe("gone");
    expect(openingFailure([], 0, 1)).toBeUndefined();
  });

  it("lists sessions between the steps and names a connection once", () => {
    const steps = [action("a", 10), action("b", 11), action("c", 30, false, "c2"), action("d", 31, false, "c2")];
    const marks = [mark("1", "attached", 5), mark("2", "detached", 20), mark("3", "attached", 25, "c2")];
    expect(listItems(steps, marks, true).map((item) => item.key)).toEqual(["mark:1", "a", "b", "mark:2", "mark:3", "c", "d"]);
    // Filtered: no marks, so each connection is named where it starts.
    expect(listItems(steps, marks, false).map((item) => item.key)).toEqual(["conn:a", "a", "b", "conn:c", "c", "d"]);
    // A detach after the last step still shows.
    expect(listItems(steps.slice(0, 2), marks.slice(0, 2), true).map((item) => item.key)).toEqual(["mark:1", "a", "b", "mark:2"]);
  });

  it("knows when nobody held the device", () => {
    const marks = [mark("1", "attached", 5), mark("2", "detached", 20), mark("3", "attached", 25), mark("4", "detached", 40)];
    expect(unheldSpans(marks)).toEqual([{ from: 20, to: 25 }, { from: 40 }]);
    expect(unheldSpans([])).toEqual([]);
  });

  it("labels the scrubber back from the newest frame at a round step", () => {
    expect(axisTicks(0, 120, 120)).toEqual([
      { at: 0, label: "−2:00" },
      { at: 30, label: "−1:30" },
      { at: 60, label: "−1:00" },
      { at: 90, label: "−30 s" },
      { at: 120, label: "now" },
    ]);
    // Zoomed into an older stretch: labels stay counted from the newest frame.
    expect(axisTicks(40, 50, 120).map((tick) => tick.label)).toEqual(["−1:20", "−1:15", "−1:10"]);
    expect(axisTicks(0, 0, 0)).toEqual([]);
    expect(axisTicks(0, 120, 120, 2)).toEqual([
      { at: 0, label: "−2:00" },
      { at: 120, label: "now" },
    ]);
  });
});
