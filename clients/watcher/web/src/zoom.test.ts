import { describe, expect, it } from "vitest";
import { followView, panView, windowOf, zoomView } from "./zoom";

describe("windowOf", () => {
  it("shows everything without a view", () => {
    expect(windowOf(undefined, 60, false)).toEqual({ start: 0, end: 60 });
  });

  it("keeps a pinned window at the live end as the track grows", () => {
    expect(windowOf({ start: 10, end: 20 }, 90, true)).toEqual({ start: 80, end: 90 });
  });

  it("keeps an unpinned window where it is", () => {
    expect(windowOf({ start: 10, end: 20 }, 90, false)).toEqual({ start: 10, end: 20 });
  });

  it("clamps a window to a shorter track", () => {
    expect(windowOf({ start: 50, end: 70 }, 60, false)).toEqual({ start: 40, end: 60 });
    expect(windowOf({ start: 0, end: 70 }, 60, false)).toEqual({ start: 0, end: 60 });
  });
});

describe("zoomView", () => {
  it("keeps the anchor at the same place on screen", () => {
    // The anchor sits at 25% of the window before and after.
    expect(zoomView({ start: 0, end: 40 }, 40, 10, 0.5)).toEqual({ start: 5, end: 25 });
  });

  it("stays on the track", () => {
    expect(zoomView({ start: 0, end: 40 }, 40, 0, 0.5)).toEqual({ start: 0, end: 20 });
    expect(zoomView({ start: 0, end: 40 }, 40, 40, 0.5)).toEqual({ start: 20, end: 40 });
  });

  it("stops at the narrowest window", () => {
    expect(zoomView({ start: 10, end: 12 }, 40, 11, 0.1)).toEqual({ start: 10.5, end: 11.5 });
  });

  it("returns to the whole track when zooming out past it", () => {
    expect(zoomView({ start: 10, end: 30 }, 40, 20, 2)).toBeUndefined();
  });
});

describe("panView", () => {
  it("moves the window and stops at either end", () => {
    expect(panView({ start: 10, end: 20 }, 40, 5)).toEqual({ start: 15, end: 25 });
    expect(panView({ start: 10, end: 20 }, 40, -50)).toEqual({ start: 0, end: 10 });
    expect(panView({ start: 10, end: 20 }, 40, 50)).toEqual({ start: 30, end: 40 });
  });
});

describe("followView", () => {
  it("leaves a window that shows the head alone", () => {
    const view = { start: 10, end: 20 };
    expect(followView(view, 40, 15)).toBe(view);
  });

  it("pages to put a head that left the window near its start", () => {
    expect(followView({ start: 10, end: 20 }, 40, 25)).toEqual({ start: 24, end: 34 });
    expect(followView({ start: 10, end: 20 }, 40, 39)).toEqual({ start: 30, end: 40 });
  });
});
