import { useEffect, useState } from "react";
import type { StudioClient } from "./api";
import type { InfoResponse } from "./gen/studio_pb";

type Status = { kind: "loading" } | { kind: "ready"; info: InfoResponse } | { kind: "failed"; message: string };

export function App({ client }: { client: StudioClient }) {
  const [status, setStatus] = useState<Status>({ kind: "loading" });

  useEffect(() => {
    let cancelled = false;
    client.info({}).then(
      (info) => !cancelled && setStatus({ kind: "ready", info }),
      (error: unknown) =>
        !cancelled && setStatus({ kind: "failed", message: error instanceof Error ? error.message : String(error) }),
    );
    return () => {
      cancelled = true;
    };
  }, [client]);

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <Logo />
          Tap Studio
        </div>
        <span className="status" role="status">
          {status.kind === "loading" && "Connecting…"}
          {status.kind === "ready" && `${status.info.recorder} · writes ${status.info.format}`}
          {status.kind === "failed" && `Cannot reach tap-studio: ${status.message}`}
        </span>
      </header>
      <main className="placeholder">The screen, inspector and steps arrive in the next phases.</main>
    </div>
  );
}

function Logo() {
  return (
    <svg viewBox="0 0 64 64" aria-hidden="true">
      <rect x="15" y="5" width="34" height="54" rx="8" fill="none" stroke="currentColor" strokeWidth="4" />
      <line x1="27" y1="52" x2="37" y2="52" stroke="currentColor" strokeWidth="3" strokeLinecap="round" />
      <circle cx="32" cy="29" r="11.5" fill="none" stroke="#FF6A3D" strokeWidth="3" />
      <circle cx="32" cy="29" r="5" fill="#FF6A3D" />
    </svg>
  );
}
