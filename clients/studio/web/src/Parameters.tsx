import { create } from "@bufbuild/protobuf";
import { useEffect, useState } from "react";
import { ParameterSchema, type Parameter } from "./gen/studio_pb";

type Row = { name: string; value: string; description: string };

const rowsOf = (parameters: readonly Parameter[]): Row[] => parameters.map((p) => ({ name: p.name, value: p.value, description: p.description ?? "" }));

/** The recording's named test data. Written to the file, so never for secrets. */
export function Parameters({
  parameters,
  busy,
  onSave,
}: {
  parameters: readonly Parameter[];
  busy: boolean;
  /** Resolves whether it was saved; a refused save keeps the rows. */
  onSave: (parameters: Parameter[]) => Promise<boolean>;
}) {
  const [rows, setRows] = useState<Row[]>(() => rowsOf(parameters));
  const saved = JSON.stringify(rowsOf(parameters));
  const changed = JSON.stringify(rows) !== saved;
  // Follows the recording (undo, another tab, an opened file) until something is typed here.
  const [editing, setEditing] = useState(false);
  useEffect(() => {
    if (!editing) setRows(JSON.parse(saved) as Row[]);
  }, [saved, editing]);
  const change = (next: Row[]) => {
    setEditing(true);
    setRows(next);
  };
  const set = (index: number, values: Partial<Row>) => change(rows.map((row, i) => (i === index ? { ...row, ...values } : row)));
  return (
    <details className="parameters">
      <summary>Parameters ({parameters.length})</summary>
      <p className="hint">Test data a step can use instead of holding it. Written to the recording file: use a secret for anything private.</p>
      {rows.map((row, i) => (
        <div key={i} className="row three" role="group" aria-label={`Parameter ${i + 1}`}>
          <label className="field">
            <span>Name</span>
            <input className="mono" spellCheck={false} value={row.name} onChange={(e) => set(i, { name: e.target.value })} />
          </label>
          <label className="field">
            <span>Value</span>
            <input value={row.value} onChange={(e) => set(i, { value: e.target.value })} />
          </label>
          <label className="field">
            <span>Description</span>
            <input value={row.description} onChange={(e) => set(i, { description: e.target.value })} />
          </label>
          <button type="button" className="btn small" aria-label={`Remove parameter ${i + 1}`} onClick={() => change(rows.filter((_, at) => at !== i))}>
            Remove
          </button>
        </div>
      ))}
      <div className="actions">
        <button type="button" className="btn small" onClick={() => change([...rows, { name: "", value: "", description: "" }])}>
          Add parameter
        </button>
        <button
          type="button"
          className="btn small primary"
          disabled={!changed || busy}
          onClick={() => {
            void onSave(rows.map((row) => create(ParameterSchema, { name: row.name.trim(), value: row.value, description: row.description || undefined }))).then((ok) => {
              if (ok) setEditing(false);
            });
          }}
        >
          Save parameters
        </button>
      </div>
    </details>
  );
}
