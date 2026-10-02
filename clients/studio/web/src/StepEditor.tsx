import { clone, equals } from "@bufbuild/protobuf";
import { useId, useState } from "react";
import type { StudioClient } from "./api";
import { useCount } from "./count";
import { describeSelector } from "./describe";
import { DIRECTIONS } from "./controls";
import { countLine, needsOne, nodeFor, stepMovement, stepOrigin, stepSelector, stepText, takesSecret, withMovement, withScrollTarget, withSelector, withText, type Movement } from "./edit";
import type { ScreenNode } from "./gen/device_pb";
import { SelectorSchema, type Selector } from "./gen/selector_pb";
import { SelectorOrigin, StepSchema, type Step } from "./gen/studio_pb";
import { candidateChips } from "./nodes";
import { parseSelector } from "./parse";
import type { Target } from "./steps";

/** Edits one recorded step: its selector (another candidate of its node, or typed), what a scroll
 *  until scrolls to, how a swipe, scroll or scroll until moves, the text it enters or checks (or a
 *  secret), and its note. Saving does not run it. */
export function StepEditor({
  step,
  nodes,
  client,
  busy,
  onSave,
}: {
  step: Step;
  nodes: readonly ScreenNode[];
  client: StudioClient;
  busy: boolean;
  onSave: (step: Step, secretValue?: string) => void;
}) {
  const id = useId();
  const original = stepSelector(step);
  const node = nodeFor(nodes, original);
  const [target, setTarget] = useState<Target | null>(original ? { selector: original, origin: stepOrigin(step) } : null);
  const [typed, setTyped] = useState(original ? describeSelector(original) : "");
  const [typedError, setTypedError] = useState<string | null>(null);
  const count = useCount(client, target?.selector);
  const counted =
    count.state === "done" && target ? countLine(count.count, target.selector, needsOne(step)) : null;

  const originalTarget = step.kind.case === "scrollUntil" ? step.kind.value.target : undefined;
  const [scrollTo, setScrollTo] = useState(originalTarget ? describeSelector(originalTarget) : "");
  const parsedScrollTo = originalTarget ? parseSelector(scrollTo) : null;

  const initialMovement = stepMovement(step);
  const [movement, setMovement] = useState<Movement | null>(initialMovement);
  const move = (change: Partial<Movement>) => setMovement((m) => (m ? { ...m, ...change } : m));
  const movementError =
    movement && !(Number.isInteger(movement.distance) && movement.distance >= 1 && movement.distance <= 100)
      ? "The distance is 1 to 100 percent."
      : movement?.maxScrolls !== undefined && !(Number.isInteger(movement.maxScrolls) && movement.maxScrolls >= 1 && movement.maxScrolls <= 1000)
        ? "Max scrolls is 1 to 1000."
        : null;

  const initialText = stepText(step);
  const [text, setText] = useState(initialText && "text" in initialText ? initialText.text : "");
  const [secret, setSecret] = useState<string | null>(initialText && "secret" in initialText ? initialText.secret : null);
  const [secretValue, setSecretValue] = useState("");
  const [note, setNote] = useState(step.note ?? "");

  const originOf = (selector: Selector): SelectorOrigin => {
    const index = node?.candidates.findIndex((c) => c.selector && equals(SelectorSchema, c.selector, selector)) ?? -1;
    if (index === 0) return SelectorOrigin.SYNTHESIZED;
    if (index > 0) return SelectorOrigin.ALTERNATIVE;
    return original && equals(SelectorSchema, original, selector) ? stepOrigin(step) : SelectorOrigin.EDITED;
  };

  const type = (value: string) => {
    setTyped(value);
    const parsed = parseSelector(value);
    if ("error" in parsed) {
      setTypedError(`${parsed.error} (at ${parsed.at + 1})`);
      return;
    }
    setTypedError(null);
    setTarget({ selector: parsed.selector, origin: originOf(parsed.selector) });
  };

  const choose = (selector: Selector) => {
    setTarget({ selector, origin: originOf(selector) });
    setTyped(describeSelector(selector));
    setTypedError(null);
  };

  let edited = step;
  if (target && original && !(equals(SelectorSchema, target.selector, original) && target.origin === stepOrigin(step))) {
    edited = withSelector(edited, target);
  }
  if (parsedScrollTo && "selector" in parsedScrollTo && originalTarget && !equals(SelectorSchema, parsedScrollTo.selector, originalTarget)) {
    edited = withScrollTarget(edited, parsedScrollTo.selector);
  }
  const moved =
    movement &&
    initialMovement &&
    (movement.direction !== initialMovement.direction || movement.distance !== initialMovement.distance || movement.maxScrolls !== initialMovement.maxScrolls);
  if (moved) edited = withMovement(edited, movement);
  if (initialText) edited = withText(edited, secret !== null ? { secret: secret.trim() } : { text });
  if ((edited.note ?? "") !== note) {
    edited = clone(StepSchema, edited);
    edited.note = note || undefined;
  }
  const changed = !equals(StepSchema, edited, step) || (secret !== null && secretValue !== "");
  const scrollToError = parsedScrollTo && "error" in parsedScrollTo ? `${parsedScrollTo.error} (at ${parsedScrollTo.at + 1})` : null;
  const valid = !typedError && !scrollToError && !movementError && (secret === null || secret.trim() !== "");

  const movementFields = movement && (
    <>
      <div className={movement.maxScrolls !== undefined ? "row three" : "row"}>
        <label className="field">
          <span>Direction</span>
          <select value={movement.direction} onChange={(e) => move({ direction: Number(e.target.value) })}>
            {DIRECTIONS.map((d) => (
              <option key={d.to} value={d.direction}>
                {d.name[0]!.toUpperCase() + d.name.slice(1)}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          <span>Distance (%)</span>
          <input
            type="number"
            min={1}
            max={100}
            value={Number.isNaN(movement.distance) ? "" : movement.distance}
            aria-invalid={movementError?.startsWith("The distance") ?? false}
            onChange={(e) => move({ distance: e.target.valueAsNumber })}
          />
        </label>
        {movement.maxScrolls !== undefined && (
          <label className="field">
            <span>Max scrolls</span>
            <input
              type="number"
              min={1}
              max={1000}
              value={Number.isNaN(movement.maxScrolls) ? "" : movement.maxScrolls}
              aria-invalid={movementError?.startsWith("Max") ?? false}
              onChange={(e) => move({ maxScrolls: e.target.valueAsNumber })}
            />
          </label>
        )}
      </div>
      {movementError && <div className="count-line warn">{movementError}</div>}
    </>
  );

  return (
    <form
      className="step-editor"
      aria-label="Edit the step"
      onSubmit={(e) => {
        e.preventDefault();
        if (changed && valid) onSave(edited, secret !== null && secretValue !== "" ? secretValue : undefined);
      }}
    >
      {target && (
        <fieldset>
          <legend>Selector</legend>
          {node && node.candidates.length > 1 && (
            <ul className="cands choose" aria-label="Candidates for this element">
              {node.candidates.map((candidate, i) => {
                const selector = candidate.selector!;
                const checked = equals(SelectorSchema, selector, target.selector);
                return (
                  <li key={i} className={`cand${checked ? " first" : ""}`}>
                    <label>
                      <input type="radio" name={`${id}-candidate`} checked={checked} onChange={() => choose(selector)} />
                      <code>{describeSelector(selector)}</code>
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
          )}
          <label className="field">
            <span>{node ? "Or type one" : "Selector (Kotlin DSL)"}</span>
            <input
              className="mono"
              value={typed}
              spellCheck={false}
              aria-invalid={!!typedError}
              aria-describedby={`${id}-count`}
              onChange={(e) => type(e.target.value)}
            />
          </label>
          <div id={`${id}-count`} className={`count-line${typedError || counted?.warn ? " warn" : ""}`} aria-live="polite">
            {typedError ??
              (count.state === "counting" ? "Counting…" : count.state === "done" ? counted?.text : count.state === "failed" ? count.message : "")}
          </div>
        </fieldset>
      )}

      {originalTarget && (
        <fieldset>
          <legend>Scroll until</legend>
          <label className="field">
            <span>Element to bring into view</span>
            <input
              className="mono"
              value={scrollTo}
              spellCheck={false}
              aria-invalid={!!scrollToError}
              onChange={(e) => setScrollTo(e.target.value)}
            />
          </label>
          {scrollToError && <div className="count-line warn">{scrollToError}</div>}
          {movementFields}
        </fieldset>
      )}

      {movement && step.kind.case !== "scrollUntil" && (
        <fieldset>
          <legend>Gesture</legend>
          {movementFields}
        </fieldset>
      )}

      {initialText && (
        <fieldset>
          <legend>{step.kind.case === "wait" || step.kind.case === "assertion" ? "Expected text" : "Text"}</legend>
          {secret === null ? (
            <label className="field">
              <span className="sr-only">Text</span>
              <input value={text} onChange={(e) => setText(e.target.value)} />
            </label>
          ) : (
            <div className="row">
              <label className="field">
                <span>Secret name</span>
                <input className="mono" value={secret} onChange={(e) => setSecret(e.target.value)} aria-invalid={!valid} />
              </label>
              <label className="field">
                <span>Value (kept in memory only)</span>
                <input
                  type="password"
                  value={secretValue}
                  autoComplete="off"
                  placeholder="unchanged"
                  onChange={(e) => setSecretValue(e.target.value)}
                />
              </label>
            </div>
          )}
          {takesSecret(step) && (
            <label className="check">
              <input
                type="checkbox"
                checked={secret !== null}
                onChange={(e) => {
                  if (e.target.checked) {
                    setSecret("secret");
                    setSecretValue(text);
                  } else {
                    setSecret(null);
                  }
                }}
              />
              Secret: the recording keeps only its name
            </label>
          )}
        </fieldset>
      )}

      <label className="field">
        <span>Note</span>
        <input value={note} placeholder="Why this step is here" onChange={(e) => setNote(e.target.value)} />
      </label>

      <div className="actions">
        <button type="submit" className="btn primary small" disabled={!changed || !valid || busy}>
          Save
        </button>
        {changed && (
          <button
            type="button"
            className="btn small"
            onClick={() => {
              if (original) choose(original);
              setTarget(original ? { selector: original, origin: stepOrigin(step) } : null);
              setText(initialText && "text" in initialText ? initialText.text : "");
              setSecret(initialText && "secret" in initialText ? initialText.secret : null);
              setSecretValue("");
              setNote(step.note ?? "");
              setScrollTo(originalTarget ? describeSelector(originalTarget) : "");
              setMovement(initialMovement);
            }}
          >
            Undo changes
          </button>
        )}
        <span className="hint">Saving does not run the step; replay it to check.</span>
      </div>
    </form>
  );
}
