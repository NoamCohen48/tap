import { useEffect, useMemo, useRef, useState, type CSSProperties, type ReactNode, type MouseEvent as ReactMouseEvent, type PointerEvent as ReactPointerEvent } from "react";
import { describeSelector } from "./describe";
import type { Frame } from "./frames";
import type { ScreenNode } from "./gen/device_pb";
import { box, hit, shortClass, toFrame, within, type Point } from "./geometry";
import type { Target } from "./steps";

/** The composer's tab: what a step on the selected element will be. It tints the selection. */
export type Intent = "act" | "assert" | "wait";
export type OverlayFilter = "interactive" | "all" | "off";

type Props = {
  frame: Frame | null;
  error: string | null;
  intent: Intent;
  overlay: OverlayFilter;
  onOverlay: (overlay: OverlayFilter) => void;
  selectedRef: string | null;
  busy: boolean;
  onSelect: (node: ScreenNode) => void;
  /** What a step on the node targets: its first candidate, or the one picked in the composer. */
  targetOf: (node: ScreenNode) => Target | null;
  /** The container of a scroll until being set up: it is outlined, and a click picks among the
   *  elements inside it (the target), whatever the overlay filter. */
  seeking: ScreenNode | null;
  /** The element a drag being set up starts on: it is outlined, and a click picks where it drops. */
  dragging?: ScreenNode | null;
  /** Under the phone: the device's buttons. */
  children?: ReactNode;
};

function useNow(intervalMs: number): number {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), intervalMs);
    return () => clearInterval(timer);
  }, [intervalMs]);
  return now;
}

/**
 * The device's screen with its elements overlaid. A click only selects an element: nothing runs
 * on the device from here. The composer beside it says what to do with the selection.
 */
export function ScreenView(props: Props) {
  const { frame, intent, overlay, busy } = props;
  const screen = useRef<HTMLDivElement>(null);
  const [hover, setHover] = useState<{ node: ScreenNode; at: Point } | null>(null);
  const now = useNow(1000);

  const nodes = frame?.nodes ?? [];
  const interactive = useMemo(() => nodes.filter((n) => n.interactive), [nodes]);
  // Acting reaches what the overlay offers; asserts and waits reach every node (a label is worth
  // checking). A right-click always reaches every node.
  const { seeking } = props;
  const inside = useMemo(() => (seeking ? within(nodes, seeking) : null), [nodes, seeking]);
  const pickable = useMemo(() => {
    if (inside) {
      const held = new Set(inside);
      return (n: ScreenNode) => held.has(n);
    }
    return intent === "act" && overlay !== "all" ? (n: ScreenNode) => n.interactive : undefined;
  }, [inside, intent, overlay]);
  const shown = inside ?? (overlay === "off" ? [] : overlay === "all" ? nodes : interactive);

  const pointOf = (client: Point): Point | null => {
    if (!frame || !screen.current) return null;
    return toFrame(client, screen.current.getBoundingClientRect(), frame.width, frame.height);
  };

  const onPointerUp = (e: ReactPointerEvent<HTMLDivElement>) => {
    if (e.button !== 0) return;
    const at = pointOf({ x: e.clientX, y: e.clientY });
    const node = at && hit(nodes, at, pickable);
    if (node) props.onSelect(node);
  };

  const onPointerMove = (e: ReactPointerEvent<HTMLDivElement>) => {
    const at = pointOf({ x: e.clientX, y: e.clientY });
    const node = at && hit(nodes, at, pickable);
    if (!screen.current) return;
    const rect = screen.current.getBoundingClientRect();
    setHover(node ? { node, at: { x: e.clientX - rect.left, y: e.clientY - rect.top } } : null);
  };

  const onContextMenu = (e: ReactMouseEvent) => {
    e.preventDefault();
    const at = pointOf({ x: e.clientX, y: e.clientY });
    const node = at && hit(nodes, at, inside ? pickable : undefined);
    if (node) props.onSelect(node);
  };

  const hoverTarget = hover ? props.targetOf(hover.node) : null;

  const boxes = useMemo(() => {
    if (!frame) return [];
    const extra = nodes.filter((n) => ((n.ref === props.selectedRef && !seeking) || n.ref === hover?.node.ref) && !shown.includes(n));
    return [...shown, ...extra]
      .map((node) => ({ node, box: box(node, frame.width, frame.height) }))
      .filter((b) => b.box !== null)
      .sort((a, b) => a.node.depth - b.node.depth);
  }, [frame, nodes, shown, props.selectedRef, hover, seeking]);

  const age = frame ? Math.max(0, Math.round((now - frame.takenAt.getTime()) / 1000)) : 0;
  const details = frame ? `frame ${frame.sequence} · ${age} s ago · ${interactive.length} interactive / ${nodes.length} nodes` : "";

  return (
    <div className="panel device-panel" style={{ "--ar": frame ? frame.width / frame.height : 9 / 19 } as CSSProperties}>
      <div className="device-stage">
        <div className="frame-status">
          {props.error ? (
            <span className="pill bad" title={props.error}>
              no frames
            </span>
          ) : (
            <span className={`pill ${frame?.moving ? "moving" : "settled"}`}>
              {frame ? (frame.moving ? "changing…" : "settled") : "waiting…"}
            </span>
          )}
          <span className="mono" title={details}>
            {details || " "}
          </span>
          <span className="seg" role="group" aria-label="Overlay">
            {(["interactive", "all", "off"] as const).map((o) => (
              <button key={o} type="button" aria-pressed={overlay === o} onClick={() => props.onOverlay(o)}>
                {o === "interactive" ? "Interactive" : o === "all" ? "All nodes" : "Off"}
              </button>
            ))}
          </span>
        </div>
        {/* The phone fits the space left in the panel (the page does not scroll on wide
            screens): its aspect ratio is the frame's. */}
        <div className="phone-fit">
          <div className="phone">
            <div className={`screen tone-${intent}${frame?.moving ? " provisional" : ""}${seeking || props.dragging ? " seeking" : ""}`} ref={screen}>
              {frame ? (
                <img src={frame.url} width={frame.width} height={frame.height} alt="The device's screen" draggable={false} />
              ) : (
                <div className="waiting">{props.error ?? "Waiting for the first frame…"}</div>
              )}
              {frame && (
                <div
                  className={`overlay${busy ? " busy" : ""}`}
                  data-testid="overlay"
                  onPointerUp={onPointerUp}
                  onPointerMove={onPointerMove}
                  onPointerLeave={() => setHover(null)}
                  onContextMenu={onContextMenu}
                >
                  {seeking && frame && <ContainerBox node={seeking} width={frame.width} height={frame.height} />}
                  {props.dragging && frame && <ContainerBox node={props.dragging} width={frame.width} height={frame.height} source />}
                  {boxes.map(({ node, box: b }) => (
                    <div
                      key={node.ref}
                      className={[
                        "ob",
                        node.interactive ? "" : "passive",
                        node.selector ? "" : "gap",
                        node.ref === props.selectedRef && !seeking ? "sel" : "",
                        node.ref === hover?.node.ref ? "hover" : "",
                      ]
                        .filter(Boolean)
                        .join(" ")}
                      style={{ left: `${b!.left}%`, top: `${b!.top}%`, width: `${b!.width}%`, height: `${b!.height}%` }}
                    />
                  ))}
                </div>
              )}
              {hover && (
                <div className="tip" role="tooltip" style={{ left: Math.max(4, hover.at.x - 40), top: hover.at.y + 18 }}>
                  <b>@{hover.node.ref}</b>{" "}
                  {hoverTarget ? (
                    <>
                      {describeSelector(hoverTarget.selector)}
                      {hoverTarget.selector.pick.case === "at" && <span className="bad"> · by index</span>}
                    </>
                  ) : (
                    <>
                      {shortClass(hover.node)} <span className="bad">· no selector</span>
                    </>
                  )}
                </div>
              )}
            </div>
          </div>
        </div>
        {props.children}
      </div>
    </div>
  );
}

function ContainerBox({ node, width, height, source }: { node: ScreenNode; width: number; height: number; source?: boolean }) {
  const b = box(node, width, height);
  if (!b) return null;
  return <div className={source ? "container-box source" : "container-box"} style={{ left: `${b.left}%`, top: `${b.top}%`, width: `${b.width}%`, height: `${b.height}%` }} />;
}
