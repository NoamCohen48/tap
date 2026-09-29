import { clone, equals } from "@bufbuild/protobuf";
import { useEffect, useId, useState } from "react";
import type { StudioClient } from "./api";
import { describeSelector } from "./describe";
import { nodeFor, stepOrigin, stepSelector, stepText, takesSecret, withSelector, withText } from "./edit";
import { errorMessage } from "./frames";
import type { ScreenNode } from "./gen/device_pb";
import { SelectorSchema, type Selector } from "./gen/selector_pb";
import { SelectorOrigin, StepSchema, type Step } from "./gen/studio_pb";
import { candidateChips } from "./nodes";
import { parseSelector } from "./parse";
import type { Target } from "./steps";

type Count = { state: "idle" } | { state: "counting" } | { state: "done"; count: number } | { state: "failed"; message: string };

/** How many nodes the selector matches now, asked a moment after it stops changing. */
function useCount(client: StudioClient, selector: Selector | undefined): Count {
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

/** Edits one recorded step: its selector (another candidate of its node, or typed), the text it
 *  enters or checks (or a secret), and its note. Saving does not run it. */
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
  if (initialText) edited = withText(edited, secret !== null ? { secret: secret.trim() } : { text });
  if ((edited.note ?? "") !== note) {
    edited = clone(StepSchema, edited);
    edited.note = note || undefined;
  }
  const changed = !equals(StepSchema, edited, step) || (secret !== null && secretValue !== "");
  const valid = !typedError && (secret === null || secret.trim() !== "");

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
          <div
            id={`${id}-count`}
            className={`count-line${typedError || (count.state === "done" && count.count !== 1) ? " warn" : ""}`}
            aria-live="polite"
          >
            {typedError ??
              (count.state === "counting"
                ? "Counting…"
                : count.state === "done"
                  ? count.count === 1
                    ? "Matches 1 element now."
                    : `Matches ${count.count} elements now: an action needs exactly one when it runs.`
                  : count.state === "failed"
                    ? count.message
                    : "")}
          </div>
        </fieldset>
      )}

      {initialText && (
        <fieldset>
          <legend>{step.kind.case === "assertion" ? "Expected text" : "Text"}</legend>
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
