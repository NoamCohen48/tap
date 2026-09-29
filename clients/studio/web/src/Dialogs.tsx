import { useEffect, useId, useRef, useState, type ReactNode } from "react";

/** A small modal: a question and its answers. Escape and the backdrop cancel. */
function Modal({ title, children, onCancel }: { title: string; children: ReactNode; onCancel: () => void }) {
  const id = useId();
  const box = useRef<HTMLDivElement>(null);
  useEffect(() => {
    box.current?.querySelector<HTMLElement>("input, button.primary, button")?.focus();
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onCancel();
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onCancel]);
  return (
    <div className="modal-back" onClick={(e) => e.target === e.currentTarget && onCancel()}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby={id} ref={box}>
        <h2 id={id}>{title}</h2>
        {children}
      </div>
    </div>
  );
}

export type Choice = { label: string; primary?: boolean; onChoose: () => void };

export function ChoiceDialog({
  title,
  body,
  choices,
  onCancel,
}: {
  title: string;
  body: ReactNode;
  choices: Choice[];
  onCancel: () => void;
}) {
  return (
    <Modal title={title} onCancel={onCancel}>
      <div className="modal-body">{body}</div>
      <div className="modal-foot">
        <button type="button" className="btn" onClick={onCancel}>
          Cancel
        </button>
        {choices.map((choice) => (
          <button key={choice.label} type="button" className={`btn${choice.primary ? " primary" : ""}`} onClick={choice.onChoose}>
            {choice.label}
          </button>
        ))}
      </div>
    </Modal>
  );
}

/** Asks for the values of secrets the studio does not have (a recording opened from a file). */
export function SecretsDialog({
  names,
  onSubmit,
  onCancel,
}: {
  names: readonly string[];
  onSubmit: (values: Record<string, string>) => void;
  onCancel: () => void;
}) {
  const [values, setValues] = useState<Record<string, string>>({});
  const complete = names.every((name) => values[name]);
  return (
    <Modal title="Values for the secrets" onCancel={onCancel}>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (complete) onSubmit(values);
        }}
      >
        <div className="modal-body">
          <p>The recording names these secrets but not their values. They go to the device and are kept in memory only.</p>
          {names.map((name) => (
            <label key={name} className="field">
              <span className="mono">{`\${${name}}`}</span>
              <input
                type="password"
                autoComplete="off"
                value={values[name] ?? ""}
                onChange={(e) => setValues((v) => ({ ...v, [name]: e.target.value }))}
              />
            </label>
          ))}
        </div>
        <div className="modal-foot">
          <button type="button" className="btn" onClick={onCancel}>
            Cancel
          </button>
          <button type="submit" className="btn primary" disabled={!complete}>
            Replay
          </button>
        </div>
      </form>
    </Modal>
  );
}
