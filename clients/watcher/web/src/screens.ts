// The wall's screens: one back-end stream (WatchScreens) carries every tile's video, tagged by
// serial, because a browser keeps at most six connections to a host. The hub keeps a short
// buffer per serial and tells that serial's tile when a frame arrives.

import { createContext } from "react";
import type { ScreenUpdate } from "./gen/watcher_pb";
import { FrameBuffer } from "./video";

/** A tile shows the screen as it is now: about one GOP. */
export const TILE_LIMITS = { maxSeconds: 3, maxBytes: 8 * 1024 * 1024 };

export type Screen = { buffer: FrameBuffer; error: string };
type Listener = (screen: Screen) => void;

export class ScreenHub {
  private readonly screens = new Map<string, Screen>();
  private readonly listeners = new Map<string, Set<Listener>>();

  get(serial: string): Screen {
    let screen = this.screens.get(serial);
    if (!screen) {
      screen = { buffer: new FrameBuffer(TILE_LIMITS), error: "" };
      this.screens.set(serial, screen);
    }
    return screen;
  }

  receive(update: ScreenUpdate) {
    const screen = this.get(update.serial);
    const value = update.update;
    if (value.case === "header") {
      screen.buffer.setHeader(value.value);
      screen.error = "";
    } else if (value.case === "frame") {
      if (!screen.buffer.append(value.value)) return;
      screen.error = "";
    } else if (value.case === "error") {
      screen.error = value.value;
    } else {
      return;
    }
    this.listeners.get(update.serial)?.forEach((listener) => listener(screen));
  }

  /** The stream itself failed: every serial says so until it reconnects. */
  fail(serials: string[], message: string) {
    for (const serial of serials) {
      const screen = this.get(serial);
      screen.error = message;
      this.listeners.get(serial)?.forEach((listener) => listener(screen));
    }
  }

  subscribe(serial: string, listener: Listener): () => void {
    let set = this.listeners.get(serial);
    if (!set) this.listeners.set(serial, (set = new Set()));
    set.add(listener);
    return () => set.delete(listener);
  }
}

export const ScreensContext = createContext<ScreenHub | undefined>(undefined);
