import { useEffect, useState } from "react";
import type { StudioClient } from "./api";
import { errorMessage } from "./frames";
import type { Range, StandardAction } from "./gen/command_pb";
import type { Selector } from "./gen/selector_pb";

export type Count = { state: "idle" } | { state: "counting" } | { state: "done"; count: number } | { state: "failed"; message: string };

/** How many nodes the selector matches now, asked a moment after it stops changing. */
export function useCount(client: StudioClient, selector: Selector | undefined): Count {
  const [count, setCount] = useState<Count>({ state: "idle" });
  useEffect(() => {
    if (!selector) return setCount({ state: "idle" });
    let cancelled = false;
    setCount({ state: "counting" });
    const timer = setTimeout(() => {
      client.count({ selector }).then(
        (r) => !cancelled && setCount({ state: "done", count: r.count }),
        (e: unknown) => !cancelled && setCount({ state: "failed", message: errorMessage(e) }),
      );
    }, 300);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [client, selector]);
  return count;
}

/** What the one node the selector matches offers: its accessibility actions and its range. */
export type ElementInfo =
  | { state: "idle" }
  | { state: "done"; actions: StandardAction[]; customActions: string[]; range?: Range }
  | { state: "failed"; message: string };

/** `DescribeElement` for the selector, asked a moment after it stops changing. */
export function useElementInfo(client: StudioClient, selector: Selector | undefined): ElementInfo {
  const [info, setInfo] = useState<ElementInfo>({ state: "idle" });
  useEffect(() => {
    setInfo({ state: "idle" });
    if (!selector) return;
    let cancelled = false;
    const timer = setTimeout(() => {
      client.describeElement({ selector }).then(
        (r) => !cancelled && setInfo({ state: "done", actions: r.actions, customActions: r.customActions, range: r.range }),
        (e: unknown) => !cancelled && setInfo({ state: "failed", message: errorMessage(e) }),
      );
    }, 300);
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [client, selector]);
  return info;
}
