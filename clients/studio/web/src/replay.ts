// Replaying recorded steps from the page: one Replay stream at a time, each step's state as it
// runs, and a summary when it ends. Stopping cancels the stream; the studio finishes the step
// that is running (a sent command is never interrupted).

import { Code, ConnectError } from "@connectrpc/connect";
import { useCallback, useRef, useState } from "react";
import type { StudioClient } from "./api";
import { errorMessage } from "./frames";
import type { Outcome } from "./gen/studio_pb";

export type StepState = "run" | "ok" | "fail";

export type ReplayRequest = { fromStepId?: string; only?: boolean; secretValues?: Record<string, string> };

export type ReplaySummary = { tone: "ok" | "fail" | "info"; text: string };

export const passed = (outcome: Outcome | undefined) => !!outcome && !outcome.error && !outcome.failure;

export function useReplay(client: StudioClient, onOutcome: (stepId: string, outcome: Outcome) => void) {
  const [running, setRunning] = useState(false);
  const [states, setStates] = useState<Record<string, StepState>>({});
  const [summary, setSummary] = useState<ReplaySummary | null>(null);
  const abort = useRef<AbortController | null>(null);

  const start = useCallback(
    async (request: ReplayRequest, number: (stepId: string) => number) => {
      if (abort.current) return;
      const controller = new AbortController();
      abort.current = controller;
      setRunning(true);
      setStates({});
      setSummary(null);
      let ran = 0;
      let last: string | null = null;
      let failure: { stepId: string; message: string } | null = null;
      try {
        const stream = client.replay(
          { fromStepId: request.fromStepId ?? "", only: request.only ?? false, secretValues: request.secretValues ?? {} },
          { signal: controller.signal },
        );
        for await (const event of stream) {
          if (!event.outcome) {
            last = event.stepId;
            setStates((s) => ({ ...s, [event.stepId]: "run" }));
            continue;
          }
          ran++;
          const ok = passed(event.outcome);
          setStates((s) => ({ ...s, [event.stepId]: ok ? "ok" : "fail" }));
          onOutcome(event.stepId, event.outcome);
          if (!ok) failure = { stepId: event.stepId, message: event.message };
        }
        if (failure) {
          setSummary({ tone: "fail", text: `Step ${number(failure.stepId)} failed. ${failure.message}` });
        } else {
          setSummary({ tone: "ok", text: ran === 1 ? "The step passed." : `All ${ran} steps passed.` });
        }
      } catch (e) {
        if (e instanceof ConnectError && e.code === Code.Canceled) {
          const stopped = last;
          setStates((s) => {
            if (!stopped || s[stopped] !== "run") return s;
            const { [stopped]: _running, ...rest } = s;
            return rest;
          });
          setSummary({ tone: "info", text: `Stopped after ${ran} step${ran === 1 ? "" : "s"}.` });
        } else {
          setSummary({ tone: "fail", text: errorMessage(e) });
        }
      } finally {
        abort.current = null;
        setRunning(false);
      }
    },
    [client, onOutcome],
  );

  const stop = useCallback(() => abort.current?.abort(), []);

  return { running, states, summary, start, stop, clearSummary: () => setSummary(null) };
}
