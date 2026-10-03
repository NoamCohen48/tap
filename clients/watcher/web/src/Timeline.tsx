// The selected device's actions, newest last, with search, a failures filter and the details of
// an explicitly selected action.

import { toJsonString } from "@bufbuild/protobuf";
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import type { Action } from "./activity";
import { clock, duration } from "./format";
import { LoggedEventSchema } from "./gen/event_log_pb";
import { store } from "./hooks";
import * as Icon from "./icons";

type Props = {
  actions: Action[];
  failures: number;
  dropped: bigint;
  selected?: Action;
  onSelect: (key: string | undefined) => void;
};

export function Timeline({ actions, failures, dropped, selected, onSelect }: Props) {
  const [query, setQuery] = useState("");
  const [onlyFailures, setOnlyFailures] = useState(false);
  const list = useRef<HTMLOListElement>(null);
  const following = useRef(true);

  const visible = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return actions.filter((action) => (!onlyFailures || action.failed) && (!needle || action.search.includes(needle)));
  }, [actions, query, onlyFailures]);

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
          <button aria-pressed={onlyFailures} onClick={() => setOnlyFailures(true)}>
            Failures <span className={`count${failures ? " bad" : ""}`}>{failures}</span>
          </button>
        </div>
      </div>
      {dropped > 0n && <p className="note">Some earlier activity was dropped before this page saw it.</p>}
      <ol
        ref={list}
        className="rows"
        role="listbox"
        aria-label="Actions on this device"
        onScroll={(event) => {
          const element = event.currentTarget;
          following.current = element.scrollHeight - element.scrollTop - element.clientHeight < 24;
        }}
      >
        {visible.map((action) => (
          <Row key={action.key} action={action} selected={action === selected} onSelect={onSelect} />
        ))}
        {!visible.length && <li className="empty">{actions.length ? "No action matches." : "No actions on this device yet. They appear here as tests run."}</li>}
      </ol>
      <p className="hint">
        <kbd>j</kbd>/<kbd>k</kbd> actions · <kbd>space</kbd> play · <kbd>←</kbd>/<kbd>→</kbd> frame · <kbd>L</kbd> live · <kbd>C</kbd> compare · <kbd>+</kbd>/<kbd>-</kbd> zoom
      </p>
      {selected && <Details action={selected} onClose={() => onSelect(undefined)} />}
    </aside>
  );
}

function Row({ action, selected, onSelect }: { action: Action; selected: boolean; onSelect: (key: string | undefined) => void }) {
  const { described } = action;
  return (
    <li
      role="option"
      aria-selected={selected}
      data-key={action.key}
      className={`row${action.failed ? " failed" : ""}${selected ? " selected" : ""}`}
      onClick={() => onSelect(selected ? undefined : action.key)}
    >
      <span className="row-time">{clock(action.event.atEpochMs)}</span>
      <span className="row-main">
        <span className="row-line">
          <strong>{described.verb}</strong>
          {described.target && <code>{described.target}</code>}
          {described.input && <span className="row-input">← {described.input}</span>}
        </span>
        <span className="row-meta">
          {action.failed && <span className="outcome">{described.outcome}</span>}
          <span>{store.label(action.connectionId)}</span>
        </span>
      </span>
      <span className="row-duration">{duration(Number(action.event.durationMs) / 1000)}</span>
    </li>
  );
}

function Details({ action, onClose }: { action: Action; onClose: () => void }) {
  const { described, event } = action;
  const json = useMemo(() => toJsonString(LoggedEventSchema, event, { prettySpaces: 2 }), [event]);
  return (
    <section className="details" aria-label="Selected action">
      <header>
        <h2>
          {described.verb} {described.target && <code>{described.target}</code>}
        </h2>
        <button className="icon-button" aria-label="Close details (Esc)" onClick={onClose}>
          <Icon.Close />
        </button>
      </header>
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
      <details>
        <summary>Raw event</summary>
        <pre>{json}</pre>
      </details>
    </section>
  );
}
