import { useState } from "react";
import type { ScreenNode } from "./gen/device_pb";
import { NodeFlag } from "./gen/selector_pb";
import { label, shortClass } from "./geometry";

type Tab = "properties" | "tree";

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

/** The selected element's properties, and every element of the screen as a tree. */
export function Inspector({
  node,
  nodes,
  appPackage,
  onSelect,
}: {
  /** The selected node, as on the newest frame that had it. */
  node: ScreenNode | null;
  nodes: readonly ScreenNode[];
  appPackage: string;
  onSelect: (node: ScreenNode) => void;
}) {
  const [tab, setTab] = useState<Tab>("properties");
  return (
    <section className="panel inspector" aria-label="Inspector">
      <div className="panel-h">
        <div className="tabs" role="tablist">
          <button type="button" role="tab" aria-selected={tab === "properties"} onClick={() => setTab("properties")}>
            Properties
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
          <Properties node={node} appPackage={appPackage} />
        ) : (
          <div className="empty">Hover the screen to see each element's selector; click one to select it.</div>
        )}
      </div>
    </section>
  );
}

function Properties({ node, appPackage }: { node: ScreenNode; appPackage: string }) {
  return (
    <>
      <div className="flags">
        {node.interactive && <span className="tag">interactive</span>}
        {node.windowPackage !== appPackage && <span className="tag">window: {node.windowPackage}</span>}
      </div>
      <dl className="props">
        <Property name="resource-id" value={node.resourceName} />
        <Property name="text" value={node.text} />
        <Property name="content-desc" value={node.contentDescription} />
        <Property name="hint" value={node.hint} />
        <Property name="class" value={node.className} />
        <Property name="window" value={node.windowPackage} />
        <Property name="bounds" value={node.bounds && `[${node.bounds.left},${node.bounds.top}][${node.bounds.right},${node.bounds.bottom}]`} />
        <dt>flags</dt>
        <dd>
          <span className="flags">
            {node.flags.length
              ? node.flags.map((f) => (
                  <span key={f} className="tag">
                    {FLAG_NAMES[f] ?? f}
                  </span>
                ))
              : "none"}
            {node.password && <span className="tag">password</span>}
          </span>
        </dd>
      </dl>
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

function Tree({
  nodes,
  selectedRef,
  onSelect,
}: {
  nodes: readonly ScreenNode[];
  selectedRef: string | null;
  onSelect: (node: ScreenNode) => void;
}) {
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
