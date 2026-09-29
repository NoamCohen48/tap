import { useEffect, useMemo, useRef, useState, type MouseEvent as ReactMouseEvent, type PointerEvent as ReactPointerEvent } from "react";
import { describeSelector } from "./describe";
import type { Frame } from "./frames";
import { Direction } from "./gen/command_pb";
import type { ScreenNode } from "./gen/device_pb";
import type { PerformRequest } from "./gen/studio_pb";
import { box, dragDirection, hit, isEditable, label, scrollableAt, shortClass, toFrame, type Point } from "./geometry";
import { AppIcon, Back, Home } from "./icons";
import { checksFor } from "./nodes";
import * as steps from "./steps";
import { TextEntry } from "./TextEntry";

export type Mode = "act" | "assert" | "inspect";
export type OverlayFilter = "interactive" | "all" | "off";

type Popover = { kind: "text" | "assert"; node: ScreenNode };

/** A drag shorter than this share of the frame's width is a click. */
const DRAG_MINIMUM = 0.04;
/** One wheel gesture records one scroll: further wheel events within this time are ignored. */
const WHEEL_QUIET_MS = 700;

const KEY_HOME = 3;
const KEY_BACK = 4;

type Props = {
  frame: Frame | null;
  error: string | null;
  autPackage: string;
  mode: Mode;
  overlay: OverlayFilter;
  onOverlay: (overlay: OverlayFilter) => void;
  selectedRef: string | null;
  busy: boolean;
  onSelect: (node: ScreenNode) => void;
  onPerform: (request: PerformRequest) => void;
  onNotice: (text: string) => void;
};

function useNow(intervalMs: number): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(timer);
  }, [intervalMs]);
  return now;
}

export function ScreenView(props: Props) {
  const { frame, mode, overlay, busy } = props;
  const screen = useRef<HTMLDivElement>(null);
  const [hover, setHover] = useState<{ node: ScreenNode; at: Point } | null>(null);
  const [popover, setPopover] = useState<Popover | null>(null);
  const drag = useRef<{ client: Point; frame: Point } | null>(null);
  const lastWheel = useRef(0);
  const now = useNow(1000);

  const nodes = frame?.nodes ?? [];
  const interactive = useMemo(() => nodes.filter((n) => n.interactive), [nodes]);
  // Act mode clicks what the overlay offers; assert and inspect reach every node (a label is
  // worth asserting on).
  const pool = mode === "act" && overlay !== "all" ? interactive : nodes;
  const shown = overlay === "off" ? [] : overlay === "all" ? nodes : interactive;

  useEffect(() => {
    if (!popover) return;
    const close = (e: KeyboardEvent) => e.key === "Escape" && setPopover(null);
    document.addEventListener("keydown", close);
    return () => document.removeEventListener("keydown", close);
  }, [popover]);

  useEffect(() => setPopover(null), [mode]);

  const pointOf = (client: Point): Point | null => {
    if (!frame || !screen.current) return null;
    return toFrame(client, screen.current.getBoundingClientRect(), frame.width, frame.height);
  };

  const perform = (request: PerformRequest) => {
    setPopover(null);
    props.onPerform(request);
  };

  const click = (node: ScreenNode, alt: boolean) => {
    props.onSelect(node);
    if (mode === "inspect") return;
    if (!node.selector) {
      props.onNotice("No selector finds only this element: see the Element panel");
      return;
    }
    if (mode === "assert") return setPopover({ kind: "assert", node });
    if (isEditable(node) && !alt) return setPopover({ kind: "text", node });
    perform(steps.gesture(node.selector, alt ? "longTap" : "tap"));
  };

  const onPointerDown = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (e.button !== 0 || !frame) return;
    const at = pointOf({ x: e.clientX, y: e.clientY });
    if (!at) return;
    drag.current = { client: { x: e.clientX, y: e.clientY }, frame: at };
    e.currentTarget.setPointerCapture?.(e.pointerId);
  };

  const onPointerUp = (e: ReactPointerEvent<HTMLDivElement>) => {
    const start = drag.current;
    drag.current = null;
    if (!start || !frame || busy) return;
    const end = pointOf({ x: e.clientX, y: e.clientY });
    if (!end) return;
    const direction = dragDirection(start.frame, end, frame.width * DRAG_MINIMUM);
    if (direction === null) {
      const node = hit(pool, start.frame);
      if (node) click(node, e.altKey);
      return;
    }
    if (mode !== "act") return;
    const node = scrollableAt(nodes, start.frame) ?? hit(pool, start.frame);
    if (!node?.selector) return props.onNotice("Nothing with a selector to swipe there");
    props.onSelect(node);
    perform(steps.swipe(node.selector, direction));
  };

  const onPointerMove = (e: ReactPointerEvent<HTMLDivElement>) => {
    const at = pointOf({ x: e.clientX, y: e.clientY });
    const node = at && hit(pool, at);
    if (!screen.current) return;
    const rect = screen.current.getBoundingClientRect();
    setHover(node ? { node, at: { x: e.clientX - rect.left, y: e.clientY - rect.top } } : null);
  };

  // React's wheel listener is passive, and a scroll over the screen must not scroll the page.
  useEffect(() => {
    const element = screen.current;
    if (!element) return;
    const onWheel = (e: WheelEvent) => {
      if (mode !== "act" || !frame) return;
      const at = pointOf({ x: e.clientX, y: e.clientY });
      const node = at && scrollableAt(frame.nodes, at);
      if (!node?.selector) return;
      e.preventDefault();
      const time = Date.now();
      if (busy || time - lastWheel.current < WHEEL_QUIET_MS) return;
      lastWheel.current = time;
      const direction =
        Math.abs(e.deltaX) > Math.abs(e.deltaY)
          ? e.deltaX > 0
            ? Direction.DIR_RIGHT
            : Direction.DIR_LEFT
          : e.deltaY > 0
            ? Direction.DIR_DOWN
            : Direction.DIR_UP;
      props.onSelect(node);
      perform(steps.scroll(node.selector, direction));
    };
    element.addEventListener("wheel", onWheel, { passive: false });
    return () => element.removeEventListener("wheel", onWheel);
  });

  const onContextMenu = (e: ReactMouseEvent) => {
    e.preventDefault();
    const at = pointOf({ x: e.clientX, y: e.clientY });
    const node = at && hit(nodes, at);
    if (node) props.onSelect(node);
  };

  const boxes = useMemo(() => {
    if (!frame) return [];
    const extra = nodes.filter((n) => (n.ref === props.selectedRef || n.ref === hover?.node.ref) && !shown.includes(n));
    return [...shown, ...extra]
      .map((node) => ({ node, box: box(node, frame.width, frame.height) }))
      .filter((b) => b.box !== null)
      .sort((a, b) => a.node.depth - b.node.depth);
  }, [frame, nodes, shown, props.selectedRef, hover]);

  const age = frame ? Math.max(0, Math.round((now - frame.takenAt.getTime()) / 1000)) : 0;

  return (
    <div className="panel">
      <div className="device-stage">
        <div className="frame-status">
          {props.error ? (
            <span className="pill bad" title={props.error}>
              no frames
            </span>
          ) : (
            <span className={`pill ${frame?.moving ? "moving" : "settled"}`}>{frame ? (frame.moving ? "changing…" : "settled") : "waiting…"}</span>
          )}
          {frame && (
            <span className="mono">
              frame {String(frame.sequence)} · {age} s ago · {interactive.length} interactive / {nodes.length} nodes
            </span>
          )}
          <span className="spacer" />
          <span className="seg" role="group" aria-label="Overlay">
            {(["interactive", "all", "off"] as const).map((o) => (
              <button key={o} type="button" aria-pressed={overlay === o} onClick={() => props.onOverlay(o)}>
                {o === "interactive" ? "Interactive" : o === "all" ? "All nodes" : "Off"}
              </button>
            ))}
          </span>
        </div>
        <div className="phone">
          <div className={`screen mode-${mode}${frame?.moving ? " provisional" : ""}`} ref={screen}>
            {frame ? (
              <img src={frame.url} width={frame.width} height={frame.height} alt="The device's screen" draggable={false} />
            ) : (
              <div className="waiting">{props.error ?? "Waiting for the first frame…"}</div>
            )}
            {frame && (
              <div
                className={`overlay${busy ? " busy" : ""}`}
                data-testid="overlay"
                onPointerDown={onPointerDown}
                onPointerUp={onPointerUp}
                onPointerMove={onPointerMove}
                onPointerLeave={() => setHover(null)}
                onContextMenu={onContextMenu}
              >
                {boxes.map(({ node, box: b }) => (
                  <div
                    key={node.ref}
                    className={[
                      "ob",
                      node.interactive ? "" : "passive",
                      node.selector ? "" : "gap",
                      node.ref === props.selectedRef ? "sel" : "",
                      node.ref === hover?.node.ref ? "hover" : "",
                    ]
                      .filter(Boolean)
                      .join(" ")}
                    style={{ left: `${b!.left}%`, top: `${b!.top}%`, width: `${b!.width}%`, height: `${b!.height}%` }}
                  />
                ))}
              </div>
            )}
            {hover && !popover && (
              <div className="tip" role="tooltip" style={{ left: Math.max(4, hover.at.x - 40), top: hover.at.y + 18 }}>
                <b>@{hover.node.ref}</b>{" "}
                {hover.node.selector ? (
                  <>
                    {describeSelector(hover.node.selector)}
                    {hover.node.byIndex && <span className="bad"> · by index</span>}
                  </>
                ) : (
                  <>
                    {shortClass(hover.node)} <span className="bad">· no selector</span>
                  </>
                )}
              </div>
            )}
            {popover && frame && (
              <PopoverView popover={popover} frame={frame} onClose={() => setPopover(null)} onPerform={perform} />
            )}
          </div>
        </div>
        <DeviceBar autPackage={props.autPackage} busy={busy} onPerform={perform} />
      </div>
    </div>
  );
}

function PopoverView({
  popover,
  frame,
  onClose,
  onPerform,
}: {
  popover: Popover;
  frame: Frame;
  onClose: () => void;
  onPerform: (request: PerformRequest) => void;
}) {
  const { node } = popover;
  const selector = node.selector!;
  const b = box(node, frame.width, frame.height) ?? { left: 0, top: 0, width: 0, height: 0 };
  // Below the node, or above it when the node is low on the screen.
  const place = b.top + b.height > 60 ? { bottom: `calc(${100 - b.top}% + 6px)` } : { top: `calc(${b.top + b.height}% + 6px)` };
  const style = { left: `max(4px, min(${b.left}%, calc(100% - 274px)))`, ...place };
  return (
    <div className="pop" role="dialog" aria-label={popover.kind === "text" ? `Text for ${label(node)}` : `Check ${label(node)}`} style={style}>
      <h3>
        {popover.kind === "text" ? "Text for " : "Check "}
        <code>{describeSelector(selector)}</code>
      </h3>
      {popover.kind === "text" ? (
        <TextEntry
          node={node}
          autoFocus
          onCancel={onClose}
          onSubmit={(how, input) => onPerform(how === "set" ? steps.setText(selector, input) : steps.typeText(selector, input))}
        />
      ) : (
        <div className="opts">
          {checksFor(node).map((option, i) => (
            <button key={option.label} type="button" autoFocus={i === 0} onClick={() => onPerform(steps.assertion(selector, option.check))}>
              {option.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

const APP_OPERATIONS: { operation: steps.AppOperation; label: string; note?: string }[] = [
  { operation: "cold_launch", label: "Cold launch", note: "force-stop, then launch: a new process" },
  { operation: "launch", label: "Launch" },
  { operation: "force_stop", label: "Force stop" },
  { operation: "clear_data", label: "Clear data", note: "also revokes permissions" },
];

function DeviceBar({ autPackage, busy, onPerform }: { autPackage: string; busy: boolean; onPerform: (request: PerformRequest) => void }) {
  const [open, setOpen] = useState(false);
  const [permission, setPermission] = useState("");
  const wrap = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const close = (e: MouseEvent | KeyboardEvent) => {
      if (e instanceof KeyboardEvent ? e.key === "Escape" : !wrap.current?.contains(e.target as globalThis.Node)) setOpen(false);
    };
    document.addEventListener("mousedown", close);
    document.addEventListener("keydown", close);
    return () => {
      document.removeEventListener("mousedown", close);
      document.removeEventListener("keydown", close);
    };
  }, [open]);

  const run = (request: PerformRequest) => {
    setOpen(false);
    onPerform(request);
  };

  return (
    <div className="devbar">
      <button type="button" className="btn" disabled={busy} title="press_key BACK" onClick={() => run(steps.pressKey(KEY_BACK))}>
        <Back />
        Back
      </button>
      <button type="button" className="btn" disabled={busy} title="press_key HOME" onClick={() => run(steps.pressKey(KEY_HOME))}>
        <Home />
        Home
      </button>
      <div className="menu-wrap" ref={wrap}>
        <button type="button" className="btn" disabled={busy} aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen(!open)}>
          <AppIcon />
          App
        </button>
        {open && (
          <div className="menu" role="menu" aria-label={`App ${autPackage}`}>
            {APP_OPERATIONS.map(({ operation, label, note }) => (
              <button key={operation} type="button" role="menuitem" onClick={() => run(steps.app(operation, autPackage))}>
                {label}
                {note && <small>{note}</small>}
              </button>
            ))}
            <form
              onSubmit={(e) => {
                e.preventDefault();
                if (permission.trim()) run(steps.app("grant_permission", autPackage, permission.trim()));
              }}
            >
              <input
                aria-label="Permission to grant"
                placeholder="android.permission.CAMERA"
                value={permission}
                onChange={(e) => setPermission(e.target.value)}
              />
              <button type="submit" className="btn small">
                Grant
              </button>
            </form>
          </div>
        )}
      </div>
    </div>
  );
}
