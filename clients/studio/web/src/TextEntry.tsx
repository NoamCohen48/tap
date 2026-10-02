import { useId, useState, type ReactNode } from "react";
import type { ScreenNode } from "./gen/device_pb";
import type { TextInput } from "./steps";

export type TextHow = "set" | "type";

/** The value for an editable node, and how to enter it (interaction rule 2): **Set text**
 *  (Enter) is one command with no focus dependency; **Type keys** is the SDKs' `typeText`. A
 *  secret's value goes to the device only; the recording keeps its name. */
export function TextEntry({
  node,
  onSubmit,
  disabled = false,
  children,
}: {
  node: ScreenNode;
  onSubmit: (how: TextHow, input: TextInput) => void;
  disabled?: boolean;
  /** More buttons for the action line. */
  children?: ReactNode;
}) {
  const id = useId();
  const [value, setValue] = useState("");
  const [secret, setSecret] = useState(node.password);
  const [name, setName] = useState(node.password ? "password" : "secret");

  const submit = (how: TextHow) => {
    if (disabled || (secret && !name.trim())) return;
    onSubmit(how, secret ? { secret: name.trim(), value } : { text: value });
  };

  return (
    <div className="textentry">
      <input
        type={secret ? "password" : "text"}
        aria-label="Text to enter"
        placeholder="Text to enter"
        value={value}
        onChange={(e) => setValue(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter") {
            e.preventDefault();
            submit("set");
          }
        }}
      />
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
        <span className="spacer" />
        <button type="button" className="btn primary" disabled={disabled} onClick={() => submit("set")}>
          Set text
        </button>
        <button type="button" className="btn" disabled={disabled} title="Tap, wait for focus, then type key by key" onClick={() => submit("type")}>
          Type keys
        </button>
        {children}
      </div>
    </div>
  );
}
