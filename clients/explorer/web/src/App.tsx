import { memo, useEffect, useMemo, useState } from "react";
import {
  Background,
  Controls,
  Handle,
  Position,
  ReactFlow,
  type NodeProps,
} from "@xyflow/react";
import { ArrowClockwise } from "@phosphor-icons/react/dist/csr/ArrowClockwise";
import { ArrowRight } from "@phosphor-icons/react/dist/csr/ArrowRight";
import { Eye } from "@phosphor-icons/react/dist/csr/Eye";
import { Graph as GraphIcon } from "@phosphor-icons/react/dist/csr/Graph";
import { ListBullets } from "@phosphor-icons/react/dist/csr/ListBullets";
import { MagnifyingGlass } from "@phosphor-icons/react/dist/csr/MagnifyingGlass";
import { ShieldCheck } from "@phosphor-icons/react/dist/csr/ShieldCheck";
import { Sparkle } from "@phosphor-icons/react/dist/csr/Sparkle";
import { WarningCircle } from "@phosphor-icons/react/dist/csr/WarningCircle";
import {
  buildGraph,
  imageUrl,
  lastObservation,
  request,
  stateTitle,
  type Run,
  type StateNode,
} from "./model";
import "@xyflow/react/dist/style.css";
import "./styles.css";

function EvidenceImage({ observation }: { observation: string }) {
  const [missing, setMissing] = useState(false);
  if (missing)
    return (
      <p className="empty" role="status">
        Screenshot unavailable for {observation}.
      </p>
    );
  return (
    <img
      src={imageUrl(observation)}
      alt={`Actual retained screenshot for ${observation}`}
      onError={() => setMissing(true)}
    />
  );
}

const StateCard = memo(function StateCard({
  data,
  selected,
}: NodeProps<StateNode>) {
  return (
    <article
      className={`state-card ${selected ? "selected" : ""} ${data.current ? "current" : ""}`}
    >
      <Handle type="target" position={Position.Left} />
      <div className="node-header">
        <span className="node-kind">
          {data.current
            ? "Current observation"
            : data.suggested
              ? "AI-suggested name"
              : "Observed state"}
        </span>
        <strong>{data.title}</strong>
      </div>
      <div className="node-image">
        {data.observation ? (
          <img
            src={imageUrl(data.observation)}
            alt={`Captured ${data.title}`}
            loading="lazy"
            draggable={false}
          />
        ) : (
          <span>No image</span>
        )}
      </div>
      <footer>
        <span>{data.actionCount} candidates</span>
        <span>
          {data.pendingCount
            ? `${data.pendingCount} approved`
            : "Evidence retained"}
        </span>
      </footer>
      <Handle type="source" position={Position.Right} />
    </article>
  );
});
const nodeTypes = { state: StateCard };
const errorText = (error: unknown) =>
  error instanceof Error ? error.message : "Operation failed";

export function App() {
  const [run, setRun] = useState<Run | null>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const [selected, setSelected] = useState("");
  const [attemptId, setAttemptId] = useState("");
  const [query, setQuery] = useState("");
  const [tab, setTab] = useState<"actions" | "attempts" | "interpretation">(
    "actions",
  );
  const [mobile, setMobile] = useState<"graph" | "details">("graph");
  const [reviewed, setReviewed] = useState(false);
  const [reason, setReason] = useState("");
  const [fill, setFill] = useState("");
  const [theme, setTheme] = useState<"system" | "light" | "dark">("system");
  useEffect(() => {
    let active = true;
    void request()
      .then((data) => {
        if (active) setRun(data);
      })
      .catch((e) => {
        if (active) setError(errorText(e));
      });
    return () => {
      active = false;
    };
  }, []);
  useEffect(() => {
    if (theme === "system") delete document.documentElement.dataset.theme;
    else document.documentElement.dataset.theme = theme;
  }, [theme]);
  useEffect(() => {
    setReviewed(false);
    setReason("");
    setFill("");
  }, [selected, attemptId]);
  const graph = useMemo(
    () => (run ? buildGraph(run) : { nodes: [], edges: [] }),
    [run],
  );
  const stateId =
    selected || run?.current || Object.keys(run?.graph.states ?? {})[0] || "";
  const states = run
    ? Object.keys(run.graph.states).filter((id) =>
        `${stateTitle(run, id).title} ${id}`
          .toLowerCase()
          .includes(query.toLowerCase()),
      )
    : [];
  const title = run && stateId ? stateTitle(run, stateId) : null;
  const attempt = attemptId ? run?.graph.attempts[attemptId] : null;
  const observation = attempt
    ? (attempt.after ?? attempt.before)
    : run
      ? (lastObservation(run.graph, stateId) ?? run.observation)
      : null;
  const state = run?.graph.states[stateId];
  const actions = Object.entries(run?.graph.actions ?? {}).filter(
    ([, action]) => action.source === stateId,
  );
  const attempts = Object.entries(run?.graph.attempts ?? {}).filter(
    ([, value]) =>
      run?.graph.actions[value.action]?.source === stateId ||
      value.destination === stateId,
  );
  const interpretation = observation ? run?.interpretations[observation] : null;
  async function operate(
    operation?: string,
    body: Record<string, unknown> = {},
  ) {
    if (busy) return;
    setBusy(true);
    setError("");
    try {
      const next = await request(operation, body);
      setRun(next);
      if (
        operation === "step" ||
        operation === "navigate" ||
        operation === "observe"
      ) {
        setSelected(next.current ?? "");
        setAttemptId("");
      }
    } catch (e) {
      setError(errorText(e));
      try {
        setRun(await request());
      } catch {
        /* Preserve last actual graph on disconnected backend. */
      }
    } finally {
      setBusy(false);
    }
  }
  function choose(id: string) {
    setSelected(id);
    setAttemptId("");
    setMobile("details");
  }
  const mutationsEnabled = Boolean(run?.live && !run.halted && !busy);
  return (
    <>
      <a className="skip" href="#workbench">
        Skip to workbench
      </a>
      <header className="topbar">
        <div className="brand">
          <span className="wordmark">tap</span>
          <h1>Explorer</h1>
          <span className="session-mode">
            <Eye size={14} />
            {run?.live ? "Review session" : "Evidence viewer"}
          </span>
        </div>
        <div className="toolbar">
          <label className="theme-label">
            Theme
            <select
              aria-label="Theme"
              value={theme}
              onChange={(e) => setTheme(e.target.value as typeof theme)}
            >
              <option value="system">System</option>
              <option value="light">Light</option>
              <option value="dark">Dark</option>
            </select>
          </label>
          <button disabled={busy} onClick={() => void operate()}>
            <ArrowClockwise size={16} />
            Refresh
          </button>
          {run?.live && (
            <button
              className="primary"
              disabled={!mutationsEnabled}
              onClick={() => void operate("observe")}
            >
              <Eye size={16} />
              Observe
            </button>
          )}
        </div>
      </header>
      <nav className="mobile-tabs" aria-label="Workspace panels">
        <button
          aria-pressed={mobile === "graph"}
          onClick={() => setMobile("graph")}
        >
          <GraphIcon />
          Graph
        </button>
        <button
          aria-pressed={mobile === "details"}
          onClick={() => setMobile("details")}
        >
          <ListBullets />
          Details
        </button>
      </nav>
      <main id="workbench" className={`workspace show-${mobile}`}>
        <aside className="state-list">
          <div className="panel-heading">
            <h2>States</h2>
            <span className="count">
              {Object.keys(run?.graph.states ?? {}).length}
            </span>
          </div>
          <label className="search">
            <MagnifyingGlass size={16} />
            <input
              aria-label="Find a state"
              type="search"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Find a state"
            />
          </label>
          <div className="scroll states">
            {!run && !error && (
              <p className="empty">Loading retained evidence…</p>
            )}
            {run && !states.length && (
              <p className="empty">
                {query
                  ? "No matching states."
                  : "No classified states yet. Inspect the latest capture."}
              </p>
            )}
            {states.map((id) => {
              const info = stateTitle(run!, id);
              const observed = lastObservation(run!.graph, id);
              return (
                <button
                  className={`state-row ${id === stateId ? "selected" : ""}`}
                  key={id}
                  aria-pressed={id === stateId}
                  onClick={() => choose(id)}
                >
                  {observed && (
                    <img src={imageUrl(observed)} alt="" loading="lazy" />
                  )}
                  <span>
                    <strong>{info.title}</strong>
                    <small>
                      {info.suggested
                        ? "Suggested name"
                        : `${run!.graph.states[id].observations.length} observations`}
                    </small>
                    <code>{id.startsWith("s-") ? id.slice(0, 10) : id}</code>
                  </span>
                  {run!.current === id && (
                    <span className="current-mark" aria-label="Current state" />
                  )}
                </button>
              );
            })}
          </div>
          <footer className="scope">
            <ShieldCheck size={17} />
            <p>
              {run?.live
                ? "Unknown actions require explicit review. No automatic permission grants."
                : "Read-only. No device acquired and no input can be sent."}
            </p>
          </footer>
        </aside>
        <section className="graph-panel" aria-label="Observed transition graph">
          <div className="graph-heading">
            <div>
              <h2>
                {String(run?.graph.context.package ?? "Exploration graph")}
              </h2>
              <p>
                {run
                  ? `${Object.keys(run.graph.states).length} states / ${graph.edges.length} observed transitions / ${Object.keys(run.graph.attempts).length} attempts`
                  : "States and transitions backed by retained observations"}
              </p>
            </div>
            <span className="meta-chip">
              {run?.live
                ? run.next.reason.replaceAll("_", " ")
                : "Recorded evidence"}
            </span>
          </div>
          {error && (
            <div className="error-banner" role="alert">
              <WarningCircle size={18} />
              <span>{error}</span>
              <button onClick={() => setError("")} aria-label="Dismiss error">
                Dismiss
              </button>
            </div>
          )}
          {run?.halted && (
            <div className="error-banner" role="status">
              Stopped: {run.halted}. No automatic retry.
            </div>
          )}
          {run?.initial_error && (
            <div className="warning-banner" role="status">
              Capture needs review: {run.initial_error}
            </div>
          )}
          <div className="canvas">
            {graph.nodes.length ? (
              <ReactFlow<StateNode>
                nodes={graph.nodes.map((node) => ({
                  ...node,
                  selected: node.id === stateId,
                }))}
                edges={graph.edges}
                nodeTypes={nodeTypes}
                fitView
                minZoom={0.18}
                maxZoom={1.6}
                nodesDraggable={false}
                nodesConnectable={false}
                onNodeClick={(_, node) => choose(node.id)}
                onEdgeClick={(_, edge) => {
                  const ids = edge.data?.attempts as string[] | undefined;
                  if (ids?.length) {
                    setSelected(edge.source);
                    setAttemptId(ids.at(-1)!);
                    setTab("attempts");
                    setMobile("details");
                  }
                }}
              >
                <Background gap={24} size={1} />
                <Controls showInteractive={false} />
              </ReactFlow>
            ) : (
              <div className="canvas-empty">
                <GraphIcon size={36} weight="light" />
                <h2>{run ? "No observed graph yet" : "Loading graph"}</h2>
                <p>
                  {run
                    ? "A stable target-app observation creates the first state. Foreign focus or drift blocks input."
                    : "Reading the selected run, without connecting to a device."}
                </p>
              </div>
            )}
          </div>
          <footer className="graph-footer">
            <span>Edges are observed outcomes, not assumed navigation.</span>
            <span>Repeated outcomes are retained.</span>
          </footer>
        </section>
        <aside className="inspector" aria-label="Selected state and evidence">
          <div className="inspector-title">
            <span className="node-kind">
              {attempt
                ? "Recorded attempt"
                : title?.suggested
                  ? "AI-suggested name"
                  : "Selected state"}
            </span>
            <h2>
              {attempt
                ? (run?.graph.actions[attempt.action]?.target.label ??
                  attempt.action.split("/").at(-1))
                : (title?.title ?? "Latest capture")}
            </h2>
            <p className="mono">
              {attemptId || stateId || observation || "No observation"}
            </p>
          </div>
          <div className="scroll inspector-scroll">
            {observation ? (
              <figure className="evidence">
                <a
                  href={imageUrl(observation)}
                  target="_blank"
                  rel="noopener noreferrer"
                >
                  <EvidenceImage key={observation} observation={observation} />
                </a>
                <figcaption>
                  {observation}{" "}
                  <a
                    href={imageUrl(observation)}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    Open full size <ArrowRight size={12} />
                  </a>
                </figcaption>
                <p className="caption">
                  Paired capture, not an atomic frame. Missing images are
                  unavailable, never substituted.
                </p>
              </figure>
            ) : (
              <p className="empty">
                Select a state to inspect its screenshot and candidates.
              </p>
            )}
            {attempt && (
              <section className="attempt-summary">
                <dl>
                  <dt>Outcome</dt>
                  <dd
                    className={
                      attempt.status === "succeeded" ? "ok" : "warning"
                    }
                  >
                    {attempt.status.replaceAll("_", " ")}
                  </dd>
                  <dt>Before</dt>
                  <dd>
                    <a
                      href={imageUrl(attempt.before)}
                      target="_blank"
                      rel="noopener noreferrer"
                    >
                      {attempt.before}
                    </a>
                  </dd>
                  <dt>After</dt>
                  <dd>
                    {attempt.after ? (
                      <a
                        href={imageUrl(attempt.after)}
                        target="_blank"
                        rel="noopener noreferrer"
                      >
                        {attempt.after}
                      </a>
                    ) : (
                      "No recognized post-observation"
                    )}
                  </dd>
                </dl>
                {attempt.note && <p>{attempt.note}</p>}
              </section>
            )}
            <nav className="detail-tabs" aria-label="State details">
              {(["actions", "attempts", "interpretation"] as const).map(
                (name) => (
                  <button
                    key={name}
                    aria-pressed={tab === name}
                    onClick={() => setTab(name)}
                  >
                    {name === "interpretation"
                      ? "Interpretation"
                      : name === "actions"
                        ? "Candidates"
                        : "Attempts"}
                  </button>
                ),
              )}
            </nav>
            {tab === "actions" && (
              <section className="candidates">
                <p className="section-note">
                  Capabilities suggest actions. They do not establish safety or
                  authorize input.
                </p>
                {run?.live && (
                  <div className="review-fields">
                    <label htmlFor="reason">Operator approval reason</label>
                    <textarea
                      id="reason"
                      value={reason}
                      onChange={(e) => setReason(e.target.value)}
                      placeholder="Why is this action safe in this test?"
                      maxLength={1000}
                    />
                    <label htmlFor="fill">Non-sensitive fill value</label>
                    <input
                      id="fill"
                      value={fill}
                      onChange={(e) => setFill(e.target.value)}
                      maxLength={200}
                    />
                    <small>
                      Values remain verbatim in the graph and daemon log. No
                      credentials.
                    </small>
                  </div>
                )}
                {!actions.length && (
                  <p className="empty">No candidates for this state.</p>
                )}
                {actions.map(([id, action]) => (
                  <article className="candidate" key={id}>
                    <div className="candidate-head">
                      <strong>
                        {action.target.label ??
                          action.target.resource ??
                          id.split("/").at(-1)}
                      </strong>
                      <span className={`status ${action.status}`}>
                        {action.status}
                      </span>
                    </div>
                    <p>
                      {action.verb}{" "}
                      {action.scenario.value !== undefined && (
                        <code>{JSON.stringify(action.scenario.value)}</code>
                      )}
                    </p>
                    {action.target.blocked_reason && (
                      <p className="warning">{action.target.blocked_reason}</p>
                    )}
                    {action.approval && (
                      <small>Approval: {action.approval}</small>
                    )}
                    {run?.live && (
                      <div className="candidate-controls">
                        {action.status === "blocked" &&
                          !action.target.blocked_reason &&
                          action.verb !== "unsupported" && (
                            <button
                              disabled={!mutationsEnabled || !reason.trim()}
                              onClick={() =>
                                void operate("approve", {
                                  candidate: id,
                                  reason,
                                  ...(action.verb === "fill"
                                    ? { value: fill }
                                    : {}),
                                })
                              }
                            >
                              Approve scenario
                            </button>
                          )}
                        {action.status === "pending" && (
                          <button
                            className="primary"
                            disabled={
                              !mutationsEnabled ||
                              run.next.action !== id ||
                              run.current !== action.source
                            }
                            onClick={() =>
                              void operate("step", { candidate: id })
                            }
                          >
                            Execute once <ArrowRight size={13} />
                          </button>
                        )}
                        {action.status === "pending" && (
                          <button
                            disabled={!run.live || busy}
                            onClick={() =>
                              void operate("withdraw", { candidate: id })
                            }
                          >
                            Withdraw
                          </button>
                        )}
                      </div>
                    )}
                    <details>
                      <summary>Selector and provenance</summary>
                      <pre>{JSON.stringify(action.target, null, 2)}</pre>
                    </details>
                  </article>
                ))}
              </section>
            )}
            {tab === "attempts" && (
              <section className="attempt-list">
                <p className="section-note">
                  Append-only executions, including fresh route trials. No
                  transport retries.
                </p>
                {!attempts.length && (
                  <p className="empty">No input attempts recorded.</p>
                )}
                {attempts.map(([id, value]) => (
                  <button
                    key={id}
                    className={`attempt-row ${id === attemptId ? "selected" : ""}`}
                    aria-pressed={id === attemptId}
                    onClick={() => setAttemptId(id)}
                  >
                    <span>
                      <strong>
                        {run?.graph.actions[value.action]?.target.label ??
                          value.action.split("/").at(-1)}
                      </strong>
                      <small className="mono">{id}</small>
                    </span>
                    <span className={`status ${value.status}`}>
                      {value.status.replaceAll("_", " ")}
                    </span>
                  </button>
                ))}
              </section>
            )}
            {tab === "interpretation" && (
              <section className="interpretation">
                <div className="section-head">
                  <Sparkle size={18} />
                  <h3>Reviewed AI proposals</h3>
                </div>
                <p className="section-note">
                  Nano model only. Names and risk guesses cannot approve
                  actions, merge states or verify routes.
                </p>
                {interpretation?.proposal ? (
                  <>
                    <strong>{interpretation.proposal.title}</strong>
                    <p>{interpretation.proposal.summary}</p>
                    <small className="mono">{interpretation.model}</small>
                    {interpretation.proposal.actions.map((item) => (
                      <article className="candidate" key={item.candidate}>
                        <strong>{item.intent}</strong>
                        <span className="status blocked">
                          Suggested: {item.risk}
                        </span>
                        <p>{item.reason}</p>
                      </article>
                    ))}
                  </>
                ) : (
                  <p className="empty">
                    {interpretation
                      ? `Interpretation ${interpretation.status}: ${interpretation.error ?? "no accepted proposal"}`
                      : "No AI interpretation submitted for this observation."}
                  </p>
                )}
                {run?.live && observation && (
                  <>
                    <label className="privacy-review">
                      <input
                        type="checkbox"
                        checked={reviewed}
                        onChange={(e) => setReviewed(e.target.checked)}
                      />
                      I reviewed this screenshot and nodes; they contain no
                      sensitive or personal data.
                    </label>
                    <button
                      disabled={!reviewed || busy}
                      onClick={() =>
                        void operate("interpret", {
                          observation,
                          reviewed_non_sensitive: true,
                        })
                      }
                    >
                      <Sparkle size={16} />
                      Ask nano model
                    </button>
                    <small>
                      Requires OPENAI_API_KEY on the backend. No automatic retry
                      or model upgrade.
                    </small>
                  </>
                )}
              </section>
            )}
            {state && (
              <details className="identity">
                <summary>Retained state identity</summary>
                <p>Observed UI features, not proof of equal backend state.</p>
                <code>{state.signature}</code>
                <p>
                  Depth {state.depth}; {state.observations.length} observations.
                </p>
              </details>
            )}
            {run?.live && stateId && run.current !== stateId && (
              <button
                className="route-button"
                disabled={!mutationsEnabled}
                onClick={() => void operate("navigate", { state: stateId })}
              >
                Follow observed route <ArrowRight size={14} />
              </button>
            )}
          </div>
          <footer className="inspector-footer">
            {busy
              ? "Operation in progress; input controls are locked."
              : run?.live
                ? "One reviewed operation at a time."
                : "Historical evidence. This view cannot replay input."}
          </footer>
        </aside>
      </main>
    </>
  );
}
