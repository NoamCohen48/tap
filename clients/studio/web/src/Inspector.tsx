import { useState } from "react";
import { describeSelector } from "./describe";
import { Direction } from "./gen/command_pb";
import type { ScreenNode } from "./gen/device_pb";
import { NodeFlag } from "./gen/selector_pb";
import type { PerformRequest } from "./gen/studio_pb";
import { isEditable, isScrollable, label, shortClass } from "./geometry";
import { candidateChips, checksFor, looksDynamic } from "./nodes";
import * as steps from "./steps";
import { TextEntry } from "./TextEntry";

type Tab = "element" | "tree";

const FLAG_NAMES: Partial<Record<NodeFlag, string>> = {
  [NodeFlag.FLAG_ENABLED]: "enabled",
  [NodeFlag.FLAG_CHECKED]: "checked",
  [NodeFlag.FLAG_CHECKABLE]: "checkable",
  [NodeFlag.FLAG_CLICKABLE]: "clickable",
  [NodeFlag.FLAG_FOCUSED]: "focused",
  [NodeFlag.FLAG_FOCUSABLE]: "focusable",
  [NodeFlag.FLAG_LONG_CLICKABLE]: "long-clickable",
  [NodeFlag.FLAG_SCROLLABLE]: "scrollable",
  [NodeFlag.FLAG_SELECTED]: "selected",
};

export function Inspector({
  node,
  onScreen,
  nodes,
  autPackage,
  busy,
  onSelect,
  onPerform,
}: {
  /** The selected node, as on the newest frame that had it. */
  node: ScreenNode | null;
  /** The newest frame still has it. */
  onScreen: boolean;
  nodes: readonly ScreenNode[];
  autPackage: string;
  busy: boolean;
  onSelect: (node: ScreenNode) => void;
  onPerform: (request: PerformRequest) => void;
}) {
  const [tab, setTab] = useState<Tab>("element");
  return (
    <section className="panel" aria-label="Inspector">
      <div className="panel-h">
        <div className="tabs" role="tablist">
          <button type="button" role="tab" aria-selected={tab === "element"} onClick={() => setTab("element")}>
            Element
          </button>
          <button type="button" role="tab" aria-selected={tab === "tree"} onClick={() => setTab("tree")}>
            Screen tree
          </button>
        </div>
      </div>
      <div className="panel-b" role="tabpanel">
        {tab === "tree" ? (
          <Tree nodes={nodes} selectedRef={node?.ref ?? null} onSelect={onSelect} />
        ) : node ? (
          <Element node={node} onScreen={onScreen} autPackage={autPackage} busy={busy} onPerform={onPerform} />
        ) : (
          <div className="empty">
            Hover the screen to see each element's selector. Click to act on it, or right-click to inspect it without acting.
          </div>
        )}
      </div>
    </section>
  );
}

function Element({
  node,
  onScreen,
  autPackage,
  busy,
  onPerform,
}: {
  node: ScreenNode;
  onScreen: boolean;
  autPackage: string;
  busy: boolean;
  onPerform: (request: PerformRequest) => void;
}) {
  const selector = node.selector;
  const usable = !!selector && onScreen && !busy;
  const candidates = node.candidates.length ? node.candidates : selector ? [{ selector, kind: 0 } as (typeof node.candidates)[number]] : [];
  return (
    <>
      <div className="node-head">
        <span className="cls">{shortClass(node)}</span>
        <span className="ref">@{node.ref}</span>
        {node.interactive && <span className="tag">interactive</span>}
        {isScrollable(node) && <span className="tag">scrollable</span>}
        {isEditable(node) && <span className="tag">editable</span>}
        {node.windowPackage !== autPackage && <span className="tag">window: {node.windowPackage}</span>}
        {!onScreen && <span className="tag warn">not on the current screen</span>}
      </div>

      <div className="section">
        <h4>Selector</h4>
        {candidates.length === 0 ? (
          <div className="gapnote">
            <b>No selector finds only this element.</b> It has no resource id, text or content description that tells it apart,
            so Tap cannot act on it. Give it a <code>contentDescription</code> (Views) or a <code>testTag</code> with{" "}
            <code>testTagsAsResourceId</code> (Compose).
          </div>
        ) : (
          <ul className="cands" aria-label="Selector candidates, the first is used">
            {candidates.map((candidate, i) => (
              <li key={i} className={`cand${i === 0 ? " first" : ""}`}>
                <code>{describeSelector(candidate.selector)}</code>
                <span className="chips">
                  {i === 0 && <span className="chip ok">used</span>}
                  {candidateChips(candidate).map((chip) => (
                    <span key={chip.text} className={`chip ${chip.tone}`} title={chip.title}>
                      {chip.text}
                    </span>
                  ))}
                </span>
              </li>
            ))}
          </ul>
        )}
      </div>

      {selector && (
        <>
          <div className="section">
            <h4>Act</h4>
            <div className="actions">
              <button type="button" className="btn primary" disabled={!usable} onClick={() => onPerform(steps.gesture(selector, "tap"))}>
                Tap
              </button>
              <button type="button" className="btn" disabled={!usable} onClick={() => onPerform(steps.gesture(selector, "longTap"))}>
                Long tap
              </button>
              {isScrollable(node) &&
                (
                  [
                    ["Scroll ↓", Direction.DIR_DOWN],
                    ["Scroll ↑", Direction.DIR_UP],
                  ] as const
                ).map(([text, direction]) => (
                  <button key={text} type="button" className="btn" disabled={!usable} onClick={() => onPerform(steps.scroll(selector, direction))}>
                    {text}
                  </button>
                ))}
              {(
                [
                  ["Swipe ←", Direction.DIR_LEFT],
                  ["Swipe →", Direction.DIR_RIGHT],
                ] as const
              ).map(([text, direction]) => (
                <button key={text} type="button" className="btn" disabled={!usable} onClick={() => onPerform(steps.swipe(selector, direction))}>
                  {text}
                </button>
              ))}
              {isEditable(node) && (
                <button type="button" className="btn" disabled={!usable} onClick={() => onPerform(steps.gesture(selector, "clearText"))}>
                  Clear
                </button>
              )}
            </div>
            {isEditable(node) && usable && (
              <TextEntry
                key={node.ref}
                node={node}
                onSubmit={(how, input) => onPerform(how === "set" ? steps.setText(selector, input) : steps.typeText(selector, input))}
              />
            )}
          </div>
          <div className="section">
            <h4>Assert</h4>
            <div className="actions">
              {checksFor(node).map((option) => (
                <button
                  key={option.label}
                  type="button"
                  className="btn assert"
                  disabled={!usable}
                  onClick={() => onPerform(steps.assertion(selector, option.check))}
                >
                  {option.label}
                </button>
              ))}
            </div>
          </div>
        </>
      )}

      <div className="section">
        <h4>Properties</h4>
        <dl className="props">
          <Property name="resource-id" value={node.resourceName} />
          <Property name="text" value={node.text} />
          <Property name="content-desc" value={node.contentDescription} />
          <Property name="hint" value={node.hint} />
          <Property name="class" value={node.className} />
          <Property name="window" value={node.windowPackage} />
          <Property
            name="bounds"
            value={node.bounds && `[${node.bounds.left},${node.bounds.top}][${node.bounds.right},${node.bounds.bottom}]`}
          />
          <dt>flags</dt>
          <dd>
            <span className="flags">
              {node.flags.length ? node.flags.map((f) => <span key={f} className="tag">{FLAG_NAMES[f] ?? f}</span>) : "none"}
              {node.password && <span className="tag">password</span>}
            </span>
          </dd>
        </dl>
      </div>
      {selector && looksDynamic(selector) && (
        <p className="hint-line">The selector matches text with digits, which usually changes between runs.</p>
      )}
    </>
  );
}

function Property({ name, value }: { name: string; value: string | undefined }) {
  return (
    <>
      <dt>{name}</dt>
      {value ? <dd>{value}</dd> : <dd className="none">—</dd>}
    </>
  );
}

function Tree({ nodes, selectedRef, onSelect }: { nodes: readonly ScreenNode[]; selectedRef: string | null; onSelect: (node: ScreenNode) => void }) {
  const [filter, setFilter] = useState("");
  const wanted = filter.trim().toLowerCase();
  const shown = wanted
    ? nodes.filter((n) => [n.text, n.contentDescription, n.resourceName, n.className, n.ref].some((v) => v?.toLowerCase().includes(wanted)))
    : nodes;
  return (
    <>
      <input
        className="filter"
        type="search"
        placeholder="Filter by text, id, class or @ref"
        aria-label="Filter nodes"
        value={filter}
        onChange={(e) => setFilter(e.target.value)}
      />
      <div className="tree" role="list">
        {shown.map((n) => (
          <button
            key={n.ref}
            type="button"
            role="listitem"
            className={n.ref === selectedRef ? "sel" : ""}
            style={{ paddingLeft: 6 + (wanted ? 0 : n.depth * 14) }}
            onClick={() => onSelect(n)}
          >
            <span className="r">@{n.ref}</span>
            <span className="k">{shortClass(n)}</span>
            <span className="l">{n.text || n.contentDescription ? `“${label(n)}”` : ""}</span>
            {n.resourceName && <span className="k">id={n.resourceName.replace(/^.*:id\//, "")}</span>}
            {n.interactive && !n.selector && <span className="x">no selector</span>}
          </button>
        ))}
      </div>
    </>
  );
}
