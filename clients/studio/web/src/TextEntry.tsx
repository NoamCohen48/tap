import { useId, useState } from "react";
import type { ScreenNode } from "./gen/device_pb";
import type { TextInput } from "./steps";

export type TextHow = "set" | "type";

/** The value for an editable node, and how to enter it (interaction rule 2): **Set text**
 *  (Enter) is one command with no focus dependency; **Type keys** is the SDKs' `typeText`. A
 *  secret's value goes to the device only; the recording keeps its name. */
export function TextEntry({
  node,
  onSubmit,
  onCancel,
  autoFocus = false,
}: {
  node: ScreenNode;
  onSubmit: (how: TextHow, input: TextInput) => void;
  onCancel?: () => void;
  autoFocus?: boolean;
}) {
  const id = useId();
  const [value, setValue] = useState("");
  const [secret, setSecret] = useState(node.password);
  const [name, setName] = useState(node.password ? "password" : "secret");

  const submit = (how: TextHow) => {
    if (secret && !name.trim()) return;
    onSubmit(how, secret ? { secret: name.trim(), value } : { text: value });
  };

  return (
    <div className="textentry">
      <div className="textact">
        <input
          type={secret ? "password" : "text"}
          aria-label="Text to enter"
          placeholder="Text to enter"
          value={value}
          autoFocus={autoFocus}
          onChange={(e) => setValue(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              e.preventDefault();
              submit("set");
            }
            if (e.key === "Escape") onCancel?.();
          }}
        />
      </div>
      <div className="textact">
        <label className="chk">
          <input type="checkbox" checked={secret} onChange={(e) => setSecret(e.target.checked)} /> Secret
        </label>
        {secret && (
          <input
            type="text"
            id={id}
            aria-label="Secret name"
            title="The name the recording keeps instead of the value"
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
        )}
      </div>
      <div className="textact">
        <button type="button" className="btn primary" onClick={() => submit("set")}>
          Set text
        </button>
        <button type="button" className="btn" title="Tap, wait for focus, then type key by key" onClick={() => submit("type")}>
          Type keys
        </button>
        {onCancel && (
          <button type="button" className="btn ghost" onClick={onCancel}>
            Cancel
          </button>
        )}
      </div>
    </div>
  );
}
