// The page's one client: the watcher back end on the page's own origin (same-origin cookie).

import { Code, ConnectError, createClient } from "@connectrpc/connect";
import { createConnectTransport } from "@connectrpc/connect-web";
import { WatcherService } from "./gen/watcher_pb";

export const api = createClient(WatcherService, createConnectTransport({ baseUrl: window.location.origin }));

export function errorText(error: unknown): string {
  if (error instanceof ConnectError) return error.rawMessage || Code[error.code];
  return error instanceof Error ? error.message : "Request failed";
}

/** Errors a retry cannot fix: stop and show them. */
export function permanent(error: unknown): boolean {
  return error instanceof ConnectError && [Code.Unauthenticated, Code.PermissionDenied, Code.InvalidArgument, Code.Unimplemented].includes(error.code);
}

/** The page has no login cookie (or a stale one). */
export function denied(error: unknown): boolean {
  return error instanceof ConnectError && error.code === Code.Unauthenticated;
}

export function aborted(error: unknown): boolean {
  return error instanceof ConnectError ? error.code === Code.Canceled : error instanceof DOMException && error.name === "AbortError";
}

export const sleep = (ms: number, signal: AbortSignal) =>
  new Promise<void>((resolve) => {
    const timer = setTimeout(resolve, ms);
    signal.addEventListener("abort", () => (clearTimeout(timer), resolve()), { once: true });
  });
