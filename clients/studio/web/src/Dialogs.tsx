import { useEffect, useId, useRef, useState, type ReactNode, type RefObject } from "react";
import { createPortal } from "react-dom";

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * What makes a dialog modal: focus moves in and stays in, the rest of the page is inert, Escape
 * cancels, and closing puts focus back where it was. Once per dialog, not per render: frames
 * re-render the page, and focus must not jump while someone types.
 */
export function useModal(box: RefObject<HTMLElement | null>, onCancel: () => void, first = "input, button.primary, button") {
  const cancel = useRef(onCancel);
  cancel.current = onCancel;
  useEffect(() => {
    const dialog = box.current;
    const before = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const others = Array.from(document.body.children).filter((el): el is HTMLElement => el instanceof HTMLElement && !el.contains(dialog) && !el.hasAttribute("inert"));
    for (const el of others) el.setAttribute("inert", "");
    dialog?.querySelector<HTMLElement>(first)?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") return cancel.current();
      if (e.key !== "Tab" || !dialog) return;
      const stops = Array.from(dialog.querySelectorAll<HTMLElement>(FOCUSABLE));
      const [start, end] = [stops[0], stops[stops.length - 1]];
      if (!start || !end) return e.preventDefault();
      if (e.shiftKey && (document.activeElement === start || !dialog.contains(document.activeElement))) {
        e.preventDefault();
        end.focus();
      } else if (!e.shiftKey && (document.activeElement === end || !dialog.contains(document.activeElement))) {
        e.preventDefault();
        start.focus();
      }
    };
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("keydown", onKey);
      for (const el of others) el.removeAttribute("inert");
      before?.focus();
    };
  }, [box, first]);
}

/** A small modal: a question and its answers. Escape and the backdrop cancel. */
function Modal({ title, children, onCancel }: { title: string; children: ReactNode; onCancel: () => void }) {
  const id = useId();
  const box = useRef<HTMLDivElement>(null);
  useModal(box, onCancel);
  return createPortal(
    <div className="modal-back" onClick={(e) => e.target === e.currentTarget && onCancel()}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby={id} ref={box}>
        <h2 id={id}>{title}</h2>
        {children}
      </div>
    </div>,
    document.body,
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
