import { useEffect, useState } from "react";
import type { StudioClient } from "./api";
import { errorMessage } from "./frames";
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
