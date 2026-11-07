// The opened device's actions, newest last, one line each, with where sessions began and
// ended, search, a failures filter and the selected action's details under its row. Mounted
// per device (a new device starts unfiltered).

import { toJsonString } from "@bufbuild/protobuf";
import { memo, useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import type { Action, SessionMark } from "./activity";
import { clock, duration } from "./format";
import { LoggedEventSchema } from "./gen/event_log_pb";
import { store } from "./hooks";
import * as Icon from "./icons";
import { listItems, nearestFailure, type Item } from "./steps";

type Props = {
  actions: Action[];
  marks: SessionMark[];
  failures: number;
  /** Whether a connection holds the device (an empty list says why it is empty). */
  held: boolean;
  dropped: bigint;
  selected?: Action;
  onSelect: (key: string | undefined) => void;
};

function TimelineView({ actions, marks, failures, held, dropped, selected, onSelect }: Props) {
  const [query, setQuery] = useState("");
  const [onlyFailures, setOnlyFailures] = useState(false);
  const list = useRef<HTMLOListElement>(null);
  const following = useRef(true);

  const visible = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return actions.filter((action) => (!onlyFailures || action.failed) && (!needle || action.search.includes(needle)));
  }, [actions, query, onlyFailures]);
  // Sessions show in the whole list; a filtered one names each connection where it starts.
  const filtered = onlyFailures || Boolean(query.trim());
  const items = useMemo(() => listItems(visible, marks, !filtered), [visible, marks, filtered]);

  // A selection the filter hides is cleared, so the details never show a hidden row.
  useEffect(() => {
    if (selected && !visible.includes(selected)) onSelect(undefined);
  }, [visible, selected, onSelect]);

  // Follow the newest row while the list is scrolled to the bottom.
  useLayoutEffect(() => {
    const element = list.current;
    if (element && following.current && !selected) element.scrollTop = element.scrollHeight;
  }, [visible, selected]);

  // Closing the details resumes following.
  const hadSelection = useRef(false);
  useLayoutEffect(() => {
    if (hadSelection.current && !selected && list.current) {
      following.current = true;
      list.current.scrollTop = list.current.scrollHeight;
    }
    hadSelection.current = Boolean(selected);
  }, [selected]);

  useEffect(() => {
    if (!selected) return;
    list.current?.querySelector(`[data-key="${CSS.escape(selected.key)}"]`)?.scrollIntoView({ block: "nearest" });
  }, [selected]);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.target instanceof HTMLElement && event.target.closest("input, textarea, select")) return;
      if (event.metaKey || event.ctrlKey || event.altKey) return;
      if (event.key === "Escape" && selected) {
        onSelect(undefined);
      } else if ((event.key === "j" || event.key === "k") && visible.length) {
        const index = selected ? visible.indexOf(selected) : -1;
        const next = event.key === "j" ? (index < 0 ? visible.length - 1 : Math.min(index + 1, visible.length - 1)) : index < 0 ? visible.length - 1 : Math.max(index - 1, 0);
        onSelect(visible[next].key);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [visible, selected, onSelect]);

  return (
    <aside className="timeline" aria-label="Actions">
      <div className="timeline-head">
        <label className="search">
          <Icon.Search />
          <span className="sr-only">Search actions</span>
          <input type="search" placeholder="Search actions" value={query} onChange={(event) => setQuery(event.target.value)} />
        </label>
        <div className="segmented" role="group" aria-label="Show">
          <button aria-pressed={!onlyFailures} onClick={() => setOnlyFailures(false)}>
            All <span className="count">{actions.length}</span>
          </button>
          <button
            aria-pressed={onlyFailures}
            onClick={() => {
              // A step the filter would hide gives way to the failure nearest it.
              if (selected && !selected.failed) onSelect(nearestFailure(actions, selected)?.key);
              setOnlyFailures(true);
            }}
          >
            Failures <span className={`count${failures ? " bad" : ""}`}>{failures}</span>
          </button>
        </div>
      </div>
      {dropped > 0n && <p className="note">Some earlier activity was dropped before this page saw it.</p>}
      <ol
        ref={list}
        className="rows"
        aria-label="Actions on this device"
        onScroll={(event) => {
          const element = event.currentTarget;
          following.current = element.scrollHeight - element.scrollTop - element.clientHeight < 24;
        }}
      >
        {items.map((item) => (item.kind === "action" ? <Row key={item.key} action={item.action} selected={item.action === selected} onSelect={onSelect} /> : <Separator key={item.key} item={item} />))}
        {!visible.length && (
          <li className="empty">
            {actions.length ? "No action matches." : held ? "No actions on this device yet. They appear here as tests run." : "No connection holds this device, so nothing is logged."}
          </li>
        )}
      </ol>
    </aside>
  );
}

/** Where a session began or ended, or whose actions follow. */
function Separator({ item }: { item: Exclude<Item, { kind: "action" }> }) {
  if (item.kind === "connection") {
    return (
      <li className="separator">
        <span>{store.label(item.connectionId)}</span>
      </li>
    );
  }
  const { mark } = item;
  const why = mark.kind === "detached" && mark.reason && mark.reason !== "detached" ? `: ${mark.reason}` : "";
  return (
    <li className={`separator ${mark.kind}`}>
      <span>
        {mark.kind === "attached" ? "Attached by" : "Detached by"} {store.label(mark.connectionId)}
        {why}
      </span>
      <time>{clock(mark.atEpochMs)}</time>
    </li>
  );
}

function Row({ action, selected, onSelect }: { action: Action; selected: boolean; onSelect: (key: string | undefined) => void }) {
  const { described } = action;
  return (
    <li data-key={action.key} className={`item${action.failed ? " failed" : ""}${selected ? " selected" : ""}`}>
      <button className="row" aria-expanded={selected} onClick={() => onSelect(selected ? undefined : action.key)}>
        <span className="row-time">{clock(action.event.atEpochMs)}</span>
        <span className="row-line">
          <strong>{described.verb}</strong>
          {described.target && <code>{described.target}</code>}
          {described.input && <span className="row-input">← {described.input}</span>}
        </span>
        {action.failed && <span className="outcome">{described.outcome}</span>}
        <span className="row-duration">{duration(Number(action.event.durationMs) / 1000)}</span>
      </button>
      {selected && <Details action={action} />}
    </li>
  );
}

/** The selected action, under its row. */
function Details({ action }: { action: Action }) {
  const { described, event } = action;
  const json = useMemo(() => toJsonString(LoggedEventSchema, event, { prettySpaces: 2 }), [event]);
  return (
    <section className="details" aria-label="Selected action">
      {action.failed ? (
        <p className="outcome-line bad">
          <Icon.Alert /> <strong>{described.outcome}</strong> {described.detail}
        </p>
      ) : (
        <p className="outcome-line ok">
          <Icon.Check /> Succeeded
        </p>
      )}
      <dl>
        {described.input && (
          <>
            <dt>Input</dt>
            <dd>{described.input}</dd>
          </>
        )}
        <dt>Started</dt>
        <dd>{clock(event.atEpochMs)}</dd>
        <dt>Took</dt>
        <dd>{duration(Number(event.durationMs) / 1000)}</dd>
        <dt>By</dt>
        <dd>{store.label(action.connectionId)}</dd>
      </dl>
      <p className="details-keys">
        <kbd>c</kbd> before / after · <kbd>n</kbd> next failure · <kbd>Esc</kbd> close
      </p>
      <details>
        <summary>Raw event</summary>
        <pre>{json}</pre>
      </details>
    </section>
  );
}

/** Re-renders only when its device's actions or the selection change, not on every page tick. */
export const Timeline = memo(TimelineView);
