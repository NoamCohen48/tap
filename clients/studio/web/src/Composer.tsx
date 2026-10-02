import { create, equals } from "@bufbuild/protobuf";
import { useId, useState } from "react";
import type { StudioClient } from "./api";
import { AppControls } from "./AppControls";
import { DIRECTIONS, Directions, Group, Row, directionName } from "./controls";
import { useCount } from "./count";
import { describeSelector } from "./describe";
import type { Direction } from "./gen/command_pb";
import { SelectorCandidateSchema, type ScreenNode } from "./gen/device_pb";
import { SelectorSchema } from "./gen/selector_pb";
import { Check, Condition, SelectorOrigin, type PerformRequest } from "./gen/studio_pb";
import { isEditable, isScrollable, scrollableAround, shortClass, tapLabel } from "./geometry";
import { Arrow } from "./icons";
import { assertsFor, candidateChips, looksDynamic, waitsFor } from "./nodes";
import type { Intent } from "./ScreenView";
import * as steps from "./steps";
import type { Target } from "./steps";
import { TextEntry } from "./TextEntry";

/** The composer's tabs: the three kinds of step on the selected element, then the app. */
export type Tab = Intent | "app";

export const TABS: { tab: Tab; label: string; key: string; title: string }[] = [
  { tab: "act", label: "Act", key: "1", title: "Perform an action on the element" },
  { tab: "assert", label: "Assert", key: "2", title: "Check the element as it is now" },
  { tab: "wait", label: "Wait", key: "3", title: "Wait until something happens to the element" },
  { tab: "app", label: "App", key: "4", title: "Launch, stop or wait for the app" },
];

/**
 * A scroll until being set up: the container has been scrolled `scrolls` times towards
 * `direction` (probes, not recorded), and the next element picked on the screen is its target.
 */
export type Seek = {
  container: Target;
  /** The container as when the search started; the screen follows it on newer frames. */
  containerNode: ScreenNode;
  direction: Direction;
  distance: number;
  scrolls: number;
  /** Why the last pick could not be used. */
  note?: string;
};

type Props = {
  client: StudioClient;
  tab: Tab;
  onTab: (tab: Tab) => void;
  /** The selected node, as on the newest frame that had it. */
  node: ScreenNode | null;
  /** The newest frame still has it. */
  onScreen: boolean;
  /** The newest frame's nodes, to find the scrollable container of an element. */
  nodes: readonly ScreenNode[];
  onSelect: (node: ScreenNode) => void;
  busy: boolean;
  /** What a step on the node targets: its first candidate, or the one picked here. */
  target: Target | null;
  /** Picks the candidate steps on the node use. */
  onChoose: (node: ScreenNode, index: number) => void;
  onPerform: (request: PerformRequest) => void;
  /** How far swipes and scrolls move, in percent of the element. */
  distance: number;
  onDistance: (percent: number) => void;
  /** The scroll until being set up, if any. */
  seek: Seek | null;
  /** Starts a scroll until on the selected (scrollable) element: one probe scroll, then a pick. */
  onSeek: (direction: Direction) => void;
  onSeekAgain: () => void;
  onSeekCancel: () => void;
  /** The app under test (the App tab). */
  appPackage: string;
  onAppPackage: (pkg: string) => void;
  /** The packages of the windows on screen, offered as the app. */
  packages: string[];
};

/**
 * Where steps are made: the selected element and its selector, then what to do with it. **Act**
 * performs an action, **Assert** checks the element as it is now, **Wait** waits until something
 * happens to it. **App** is apart from any element: launch, stop or wait for the app. Each runs on
 * the device and, while recording, is added when it passes. The device's own buttons are under
 * the phone.
 */
export function Composer(props: Props) {
  const { tab, node, target } = props;
  const count = useCount(props.client, props.onScreen ? target?.selector : undefined);
  const live = count.state === "done" ? count.count : null;
  const usable = !!target && props.onScreen && !props.busy;
  const panel = useId();
  return (
    <section className={`panel composer tone-${tab}`} aria-label="Composer">
      <div className="intents" role="tablist" aria-label="Step kind">
        {TABS.map((i) => (
          <button
            key={i.tab}
            type="button"
            role="tab"
            id={`${panel}-${i.tab}`}
            className={i.tab === "app" ? "apart" : undefined}
            aria-selected={tab === i.tab}
            aria-controls={panel}
            aria-keyshortcuts={i.key}
            disabled={!!props.seek && tab !== i.tab}
            title={`${i.title} (key ${i.key})`}
            onClick={() => props.onTab(i.tab)}
          >
            {i.label} <kbd aria-hidden="true">{i.key}</kbd>
          </button>
        ))}
      </div>
      <div className="panel-b" role="tabpanel" id={panel} aria-labelledby={`${panel}-${tab}`}>
        {tab === "app" ? (
          <AppControls appPackage={props.appPackage} onAppPackage={props.onAppPackage} packages={props.packages} busy={props.busy} onPerform={props.onPerform} />
        ) : props.seek ? (
          <SeekCard seek={props.seek} busy={props.busy} onAgain={props.onSeekAgain} onCancel={props.onSeekCancel} />
        ) : (
          <>
            <Selected node={node} onScreen={props.onScreen} target={target} count={count} onChoose={props.onChoose} />
            {node && target && (
              <div key={`${tab}-${node.ref}`}>
                {tab === "act" && (
                  <ActOnElement
                    node={node}
                    target={target}
                    usable={usable}
                    container={isScrollable(node) ? null : scrollableAround(props.nodes, node)}
                    pressTarget={pressTarget(props.nodes, node, target)}
                    onSelect={props.onSelect}
                    distance={props.distance}
                    onDistance={props.onDistance}
                    onPerform={props.onPerform}
                    onSeek={props.onSeek}
                  />
                )}
                {tab === "assert" && <AssertOnElement node={node} target={target} usable={usable} live={live} onPerform={props.onPerform} />}
                {tab === "wait" && <WaitOnElement node={node} target={target} usable={usable} live={live} onPerform={props.onPerform} />}
              </div>
            )}
            {tab === "assert" && node && (
              <p className="hint-line">An assertion checks the element as it is now and fails at once. For something that is still on its way, use Wait.</p>
            )}
          </>
        )}
      </div>
    </section>
  );
}

function Selected({
  node,
  onScreen,
  target,
  count,
  onChoose,
}: {
  node: ScreenNode | null;
  onScreen: boolean;
  target: Target | null;
  count: ReturnType<typeof useCount>;
  onChoose: (node: ScreenNode, index: number) => void;
}) {
  const id = useId();
  if (!node) {
    return (
      <div className="selected none">
        <b>No element selected.</b> Click one on the screen (a right-click reaches every element) or pick it in the screen tree.
      </div>
    );
  }
  const candidates = node.candidates.length
    ? node.candidates
    : node.selector
      ? [create(SelectorCandidateSchema, { selector: node.selector })]
      : [];
  const selector = target?.selector;
  return (
    <div className="selected">
      <div className="node-head">
        <span className="cls">{shortClass(node)}</span>
        <span className="ref">@{node.ref}</span>
        {node.text && <span className="label">“{node.text}”</span>}
        {isScrollable(node) && <span className="tag">scrollable</span>}
        {isEditable(node) && <span className="tag">editable</span>}
        {!onScreen && <span className="tag warn">not on the current screen</span>}
      </div>
      {candidates.length === 0 ? (
        <div className="gapnote">
          <b>No selector finds only this element.</b> It has no resource id, text or content description that tells it apart, so Tap cannot
          act on it. Give it a <code>contentDescription</code> (Views) or a <code>testTag</code> with <code>testTagsAsResourceId</code>{" "}
          (Compose).
        </div>
      ) : (
        <>
          <ul className="cands choose" aria-label="Selector candidates">
            {candidates.map((candidate, i) => {
              const used = !!selector && !!candidate.selector && equals(SelectorSchema, candidate.selector, selector);
              return (
                <li key={i} className={`cand${used ? " first" : ""}`}>
                  <label>
                    <input type="radio" name={`${id}-candidate`} checked={used} onChange={() => onChoose(node, i)} />
                    <code>{describeSelector(candidate.selector)}</code>
                  </label>
                  <span className="chips">
                    {candidateChips(candidate).map((chip) => (
                      <span key={chip.text} className={`chip ${chip.tone}`} title={chip.title}>
                        {chip.text}
                      </span>
                    ))}
                  </span>
                </li>
              );
            })}
          </ul>
          <div className="count-line" aria-live="polite">
            {count.state === "done"
              ? `Matches ${count.count} element${count.count === 1 ? "" : "s"} now.`
              : count.state === "failed"
                ? count.message
                : " "}
            {selector && looksDynamic(selector) && " The text has digits, which usually change between runs."}
          </div>
        </>
      )}
    </div>
  );
}

/** A row whose only selector is a synthesized index pick is pressed through its label, which
 *  survives the list scrolling (`tapLabel`); anything else is pressed as itself. */
function pressTarget(nodes: readonly ScreenNode[], node: ScreenNode, target: Target): Target {
  if (target.selector.pick.case !== "at" || target.origin !== SelectorOrigin.SYNTHESIZED) return target;
  const label = tapLabel(nodes, node);
  return label?.selector ? steps.synthesized(label.selector) : target;
}

function ActOnElement({
  node,
  target,
  usable,
  container,
  pressTarget,
  onSelect,
  distance,
  onDistance,
  onPerform,
  onSeek,
}: {
  node: ScreenNode;
  target: Target;
  usable: boolean;
  /** The scrollable node holding a node that does not scroll itself. */
  container: ScreenNode | null;
  /** What a tap or long press targets: the element, or the label it is pressed through. */
  pressTarget: Target;
  onSelect: (node: ScreenNode) => void;
  distance: number;
  onDistance: (percent: number) => void;
  onPerform: (r: PerformRequest) => void;
  onSeek: (direction: Direction) => void;
}) {
  const id = useId();
  const scrollable = isScrollable(node);
  return (
    <Group title="On the element">
      <Row label="Press">
        <button type="button" className="btn primary" disabled={!usable} onClick={() => onPerform(steps.gesture(pressTarget, "tap"))}>
          Tap
        </button>
        <button type="button" className="btn" disabled={!usable} onClick={() => onPerform(steps.gesture(pressTarget, "longTap"))}>
          Long press
        </button>
      </Row>
      <Row label="Swipe">
        <Directions verb="Swipe" disabled={!usable} onDirection={(d) => onPerform(steps.swipe(target, d, distance))} />
      </Row>
      <Row label="Scroll" why={scrollable || container ? undefined : "Not scrollable, and nothing around it scrolls."}>
        {scrollable ? (
          <Directions verb="Scroll" disabled={!usable} onDirection={(d) => onPerform(steps.scroll(target, d, distance))} />
        ) : container ? (
          <>
            <span className="unit">This element does not scroll.</span>
            <button type="button" className="btn" onClick={() => onSelect(container)}>
              Select the {shortClass(container)} around it
            </button>
          </>
        ) : (
          <Directions verb="Scroll" disabled onDirection={() => undefined} />
        )}
      </Row>
      <div className="crow">
        <label className="rl" htmlFor={`${id}-distance`}>
          Distance
        </label>
        <div className="actions distance">
          <input
            id={`${id}-distance`}
            type="range"
            min={10}
            max={100}
            step={5}
            value={distance}
            aria-valuetext={`${distance} percent of the element`}
            onChange={(e) => onDistance(Number(e.target.value))}
          />
          <output htmlFor={`${id}-distance`} className="unit">
            {distance} % of the element
          </output>
          {/* Always laid out (hidden at the default), so the row keeps its height when it shows. */}
          <button
            type="button"
            className={distance === steps.DEFAULT_DISTANCE ? "btn ghost idle" : "btn ghost"}
            onClick={() => onDistance(steps.DEFAULT_DISTANCE)}
          >
            Reset
          </button>
        </div>
        <div className="why">How far the finger moves in a swipe or a scroll. Short is gentle; long flings further.</div>
      </div>
      {scrollable && (
        <Row label="Scroll until" why="Scrolls once, then you click the element to bring into view, or scroll again.">
          <Directions verb="Find by scrolling" disabled={!usable} onDirection={onSeek} />
        </Row>
      )}
      {isEditable(node) && (
        <Row label="Text">
          <TextEntry
            key={node.ref}
            node={node}
            disabled={!usable}
            onSubmit={(how, input) => onPerform(how === "set" ? steps.setText(target, input) : steps.typeText(target, input))}
          >
            <button type="button" className="btn" disabled={!usable} onClick={() => onPerform(steps.gesture(target, "clearText"))}>
              Clear
            </button>
          </TextEntry>
        </Row>
      )}
    </Group>
  );
}

/** The scroll until being set up: what has been scrolled, and what to do next. */
function SeekCard({ seek, busy, onAgain, onCancel }: { seek: Seek; busy: boolean; onAgain: () => void; onCancel: () => void }) {
  const to = DIRECTIONS.find((d) => d.direction === seek.direction)!.to;
  const times = seek.scrolls === 1 ? "once" : `${seek.scrolls} times`;
  return (
    <section className="seek" aria-label="Scroll until" aria-live="polite">
      <h3>Scroll until</h3>
      <p>
        {busy && seek.scrolls === 0 ? "Scrolling " : "Scrolled "}
        <code>{describeSelector(seek.container.selector)}</code> {directionName(seek.direction)}
        {seek.scrolls > 0 && ` ${times}`}.
      </p>
      <p className="ask">
        <b>Click the element you are looking for</b> on the screen, inside the outlined container. It is recorded as one{" "}
        <code>scrollUntil</code> step.
      </p>
      {seek.note && <p className="why bad">{seek.note}</p>}
      <div className="actions">
        <button type="button" className="btn primary" disabled={busy} onClick={onAgain}>
          <Arrow to={to} />
          Scroll again
        </button>
        <button type="button" className="btn" onClick={onCancel}>
          Cancel
        </button>
      </div>
      <p className="why">The scrolls so far ran on the device and are not recorded. Esc cancels.</p>
    </section>
  );
}

/** A text or count to check, and the two ways to check text (equals or contains). */
function ValueChecks({
  node,
  live,
  usable,
  verb,
  onText,
  onCount,
}: {
  node: ScreenNode;
  live: number | null;
  usable: boolean;
  verb: string;
  onText: (text: string, contains: boolean) => void;
  onCount: (count: number) => void;
}) {
  const id = useId();
  const [text, setText] = useState(node.text ?? "");
  const [contains, setContains] = useState(false);
  const [countText, setCountText] = useState<string | null>(null);
  const shown = countText ?? (live !== null ? String(live) : "");
  const count = /^\d+$/.test(shown) ? Number(shown) : null;
  return (
    <>
      <form
        className="crow"
        aria-label={`${verb} text`}
        onSubmit={(e) => {
          e.preventDefault();
          onText(text, contains);
        }}
      >
        <label className="rl" htmlFor={`${id}-text`}>
          Text
        </label>
        <div className="actions">
          <span className="seg" role="group" aria-label="How the text matches">
            <button type="button" aria-pressed={!contains} onClick={() => setContains(false)}>
              equals
            </button>
            <button type="button" aria-pressed={contains} onClick={() => setContains(true)}>
              contains
            </button>
          </span>
          <input id={`${id}-text`} className="grow" value={text} onChange={(e) => setText(e.target.value)} />
          <button type="submit" className={`btn ${verb === "Assert" ? "assert" : "wait"}`} disabled={!usable}>
            {verb}
          </button>
        </div>
      </form>
      <form
        className="crow"
        aria-label={`${verb} count`}
        onSubmit={(e) => {
          e.preventDefault();
          if (count !== null) onCount(count);
        }}
      >
        <label className="rl" htmlFor={`${id}-count`}>
          Count
        </label>
        <div className="actions">
          <input
            id={`${id}-count`}
            className="num"
            inputMode="numeric"
            value={shown}
            aria-invalid={count === null && shown !== ""}
            onChange={(e) => setCountText(e.target.value)}
          />
          <span className="unit">matching elements</span>
          <button type="submit" className={`btn ${verb === "Assert" ? "assert" : "wait"}`} disabled={!usable || count === null}>
            {verb}
          </button>
        </div>
      </form>
    </>
  );
}

function AssertOnElement({
  node,
  target,
  usable,
  live,
  onPerform,
}: {
  node: ScreenNode;
  target: Target;
  usable: boolean;
  live: number | null;
  onPerform: (r: PerformRequest) => void;
}) {
  return (
    <Group title="On the element" note="its state now">
      <Row label="State">
        {assertsFor(node).map((option) => (
          <button key={option.label} type="button" className="btn assert" disabled={!usable} onClick={() => onPerform(steps.assertion(target, option.expect))}>
            {option.label}
          </button>
        ))}
      </Row>
      <ValueChecks
        node={node}
        live={live}
        usable={usable}
        verb="Assert"
        onText={(text, contains) => onPerform(steps.assertion(target, { check: contains ? Check.TEXT_CONTAINS : Check.TEXT_EQUALS, text }))}
        onCount={(count) => onPerform(steps.assertion(target, { check: Check.COUNT, count }))}
      />
    </Group>
  );
}

function WaitOnElement({
  node,
  target,
  usable,
  live,
  onPerform,
}: {
  node: ScreenNode;
  target: Target;
  usable: boolean;
  live: number | null;
  onPerform: (r: PerformRequest) => void;
}) {
  return (
    <Group title="Until the element is" note="up to the device's wait timeout">
      <Row label="State">
        {waitsFor(node, target.selector).map((option) => (
          <button
            key={option.label}
            type="button"
            className="btn wait"
            title={option.title}
            disabled={!usable}
            onClick={() => onPerform(steps.wait(target, option.until))}
          >
            {option.label}
          </button>
        ))}
      </Row>
      <ValueChecks
        node={node}
        live={live}
        usable={usable}
        verb="Wait"
        onText={(text, contains) => onPerform(steps.wait(target, { condition: contains ? Condition.TEXT_CONTAINS : Condition.TEXT_EQUALS, text }))}
        onCount={(count) => onPerform(steps.wait(target, { condition: Condition.COUNT, count }))}
      />
    </Group>
  );
}
