// Streams and polls the page keeps open: activity (always), status and devices (polled), and
// the selected device's video.

import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { ActivityStore } from "./activity";
import { aborted, api, denied, errorText, permanent, sleep } from "./api";
import type { ListDevicesResponse } from "./gen/device_pb";
import type { StatusResponse } from "./gen/watcher_pb";
import { FrameBuffer, type Limits } from "./video";

export const store = new ActivityStore();

export type StreamState = { connected: boolean; error: string; denied: boolean };

/** Follows the back end's activity stream for the page's lifetime, resuming after the last seq. */
export function useActivity(): StreamState {
  const [state, setState] = useState<StreamState>({ connected: false, error: "", denied: false });
  useEffect(() => {
    const abort = new AbortController();
    (async () => {
      let retryMs = 500;
      while (!abort.signal.aborted) {
        try {
          let first = true;
          for await (const response of api.watch({ afterSeq: store.lastSeq }, { signal: abort.signal })) {
            store.receive(response, first);
            if (first) setState({ connected: true, error: "", denied: false });
            first = false;
            retryMs = 500;
          }
          setState({ connected: false, error: "The activity stream ended", denied: false });
        } catch (error) {
          if (aborted(error) || abort.signal.aborted) return;
          setState({ connected: false, error: errorText(error), denied: denied(error) });
          if (permanent(error)) return;
        }
        await sleep(retryMs, abort.signal);
        retryMs = Math.min(retryMs * 2, 5000);
      }
    })();
    return () => abort.abort();
  }, []);
  return state;
}

/** Re-renders when the activity store changes. */
export function useStore(): number {
  return useSyncExternalStore(store.subscribe, store.getVersion);
}

/** Calls [load] now and every [intervalMs]; keeps the last value and the last error. */
export function usePoll<T>(load: (signal: AbortSignal) => Promise<T>, intervalMs: number): { value?: T; error: string; denied: boolean; refresh: () => void } {
  const [value, setValue] = useState<T>();
  const [error, setError] = useState("");
  const [refused, setRefused] = useState(false);
  const [tick, setTick] = useState(0);
  const loader = useRef(load);
  loader.current = load;
  useEffect(() => {
    const abort = new AbortController();
    (async () => {
      while (!abort.signal.aborted) {
        try {
          const next = await loader.current(abort.signal);
          if (abort.signal.aborted) return;
          setValue(next);
          setError("");
        } catch (failure) {
          if (abort.signal.aborted) return;
          setError(errorText(failure));
          setRefused(denied(failure));
          if (permanent(failure)) return;
        }
        await sleep(intervalMs, abort.signal);
      }
    })();
    return () => abort.abort();
  }, [intervalMs, tick]);
  const refresh = useCallback(() => setTick((n) => n + 1), []);
  return { value, error, denied: refused, refresh };
}

export const useStatus = () => usePoll<StatusResponse>((signal) => api.status({}, { signal }), 2000);
export const useDevices = () => usePoll<ListDevicesResponse>((signal) => api.listDevices({}, { signal }), 5000);

const RENDER_MS = 250;

export type VideoFeed = {
  buffer: FrameBuffer;
  /** Bumped at most every 250 ms while frames arrive; nothing re-renders while the screen is still. */
  version: number;
  state: "connecting" | "streaming" | "unavailable";
  error: string;
};

/**
 * The selected device's video: mirrors the back end's history into a [FrameBuffer], then live.
 * A new stream (rotation, capture restart) continues the same buffer as a new segment.
 * [onFrames] runs synchronously after every new frame (the live player), outside React.
 */
export function useVideo(serial: string, limits: Limits | undefined, onFrames: (buffer: FrameBuffer) => void): VideoFeed {
  const buffer = useRef(new FrameBuffer());
  const [version, setVersion] = useState(0);
  const [state, setState] = useState<VideoFeed["state"]>("connecting");
  const [error, setError] = useState("");
  const listener = useRef(onFrames);
  listener.current = onFrames;
  if (limits) buffer.current.limits = limits;

  useEffect(() => {
    buffer.current = new FrameBuffer(buffer.current.limits);
    setVersion((n) => n + 1);
    setState("connecting");
    setError("");
    if (!serial) return;
    const abort = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    const render = () => {
      if (timer === undefined) timer = setTimeout(() => ((timer = undefined), setVersion((n) => n + 1)), RENDER_MS);
    };
    (async () => {
      let retryMs = 1000;
      while (!abort.signal.aborted) {
        try {
          for await (const response of api.watchVideo({ serial }, { signal: abort.signal })) {
            const update = response.update;
            if (update.case === "header") {
              buffer.current.setHeader(update.value);
              setState("streaming");
              setError("");
              retryMs = 1000;
            } else if (update.case === "frame") {
              if (buffer.current.append(update.value)) listener.current(buffer.current);
            }
            render();
          }
          setState("connecting");
        } catch (failure) {
          if (aborted(failure) || abort.signal.aborted) return;
          setState("unavailable");
          setError(errorText(failure));
          if (permanent(failure)) return;
        }
        await sleep(retryMs, abort.signal);
        retryMs = Math.min(retryMs * 2, 10000);
      }
    })();
    return () => {
      abort.abort();
      clearTimeout(timer);
    };
  }, [serial]);

  return { buffer: buffer.current, version, state, error };
}

/** The current time, refreshed every [ms] (for "12 s ago" labels). */
export function useNow(ms = 1000): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), ms);
    return () => clearInterval(timer);
  }, [ms]);
  return now;
}
