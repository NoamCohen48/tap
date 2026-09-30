// The live screen: the studio's `Frames` stream as React state.

import { Code, ConnectError } from "@connectrpc/connect";
import { useEffect, useState } from "react";
import type { StudioClient } from "./api";
import type { ScreenNode } from "./gen/device_pb";

export type Frame = {
  sequence: bigint;
  /** An object URL for the PNG; revoked when the frame is replaced. */
  url: string;
  width: number;
  height: number;
  rotation: number;
  snapshotId: bigint;
  nodes: ScreenNode[];
  /** Taken while the screen was changing: its overlay is provisional (decision 4). */
  moving: boolean;
  takenAt: Date;
};

export type FrameState = { frame: Frame | null; error: string | null };

const RETRY_FIRST_MS = 500;
const RETRY_MAX_MS = 5000;

export const errorMessage = (error: unknown) =>
  error instanceof ConnectError ? error.rawMessage : error instanceof Error ? error.message : String(error);

/** Reads frames while `device` (the attached serial) is set. A broken stream is retried with
 *  back-off; it ends quietly when the device is released. */
export function useFrames(client: StudioClient, device: string | null): FrameState {
  const [state, setState] = useState<FrameState>({ frame: null, error: null });

  useEffect(() => {
    setState({ frame: null, error: null });
    if (device === null) return;
    const abort = new AbortController();
    const urls: string[] = [];
    const publish = (frame: Frame) => {
      urls.push(frame.url);
      // Keep the one on screen and the new one; the older ones are no longer shown.
      while (urls.length > 2) URL.revokeObjectURL(urls.shift()!);
      setState({ frame, error: null });
    };
    void (async () => {
      let retry = RETRY_FIRST_MS;
      while (!abort.signal.aborted) {
        try {
          for await (const f of client.frames({}, { signal: abort.signal })) {
            publish({
              sequence: f.sequence,
              url: URL.createObjectURL(new Blob([f.png as Uint8Array<ArrayBuffer>], { type: "image/png" })),
              width: f.width,
              height: f.height,
              rotation: f.rotation,
              snapshotId: f.snapshotId,
              nodes: f.nodes,
              moving: f.moving,
              takenAt: f.takenAt ? new Date(Number(f.takenAt.seconds) * 1000 + f.takenAt.nanos / 1e6) : new Date(),
            });
            retry = RETRY_FIRST_MS;
          }
          return; // the studio released the device
        } catch (error) {
          if (abort.signal.aborted) return;
          setState((s) => ({ ...s, error: errorMessage(error) }));
          if (error instanceof ConnectError && error.code === Code.FailedPrecondition) return; // nothing attached
          await new Promise((resolve) => setTimeout(resolve, retry));
          retry = Math.min(retry * 2, RETRY_MAX_MS);
        }
      }
    })();
    return () => {
      abort.abort();
      for (const url of urls) URL.revokeObjectURL(url);
    };
  }, [client, device]);

  return state;
}
