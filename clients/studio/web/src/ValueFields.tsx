// The values of a step, edited from its schema rather than a form per operation: a new field or
// operation is editable as soon as the proto has it, and the studio's validator stays the one
// judge of what is allowed (a refused save keeps the draft).

import { create, ScalarType, type DescField, type DescMessage, type DescOneof } from "@bufbuild/protobuf";
import { useState } from "react";
import { describeSelector } from "./describe";
import { AppCallSchema, DeviceCallSchema } from "./gen/event_log_pb";
import { CommandSchema, type Command } from "./gen/command_pb";
import { Check, Condition, DeviceAssertionStepSchema, type Step } from "./gen/studio_pb";
import { parseSelector } from "./parse";

// eslint-disable-next-line @typescript-eslint/no-explicit-any
type Values = Record<string, any>;

const words = (name: string) => {
  const spaced = name.replace(/([a-z0-9])([A-Z])/g, "$1 $2").replace(/_/g, " ").toLowerCase();
  return spaced[0]!.toUpperCase() + spaced.slice(1);
};

const SELECTOR = "tap.v1.Selector";

function Scalar({ field, label, values, name, onChange }: { field: DescField; label: string; values: Values; name: string; onChange: () => void }) {
  const set = (value: unknown) => {
    values[name] = value;
    onChange();
  };
  const value = values[name];
  const optional = field.proto.proto3Optional === true;
  if (field.fieldKind === "enum") {
    return (
      <label className="field">
        <span>{label}</span>
        <select value={value ?? 0} onChange={(e) => set(Number(e.target.value))}>
          {field.enum.values.map((v) => (
            <option key={v.number} value={v.number}>
              {v.number === 0 ? "Not set" : words(v.localName)}
            </option>
          ))}
        </select>
      </label>
    );
  }
  if (field.fieldKind !== "scalar") return null;
  if (field.scalar === ScalarType.BOOL) {
    if (optional) {
      return (
        <label className="field">
          <span>{label}</span>
          <select value={value === undefined ? "" : String(value)} onChange={(e) => set(e.target.value === "" ? undefined : e.target.value === "true")}>
            <option value="">Not set</option>
            <option value="true">On</option>
            <option value="false">Off</option>
          </select>
        </label>
      );
    }
    return (
      <label className="check">
        <input type="checkbox" checked={!!value} onChange={(e) => set(e.target.checked)} />
        {label}
      </label>
    );
  }
  if (field.scalar === ScalarType.STRING) {
    return (
      <label className="field">
        <span>{label}</span>
        <input value={value ?? ""} onChange={(e) => set(e.target.value === "" && optional ? undefined : e.target.value)} />
      </label>
    );
  }
  const big = field.scalar === ScalarType.INT64 || field.scalar === ScalarType.UINT64 || field.scalar === ScalarType.SINT64;
  return (
    <label className="field">
      <span>{label}</span>
      <input
        type="number"
        step={field.scalar === ScalarType.DOUBLE || field.scalar === ScalarType.FLOAT ? "any" : 1}
        value={value === undefined ? "" : String(value)}
        onChange={(e) => {
          const read = e.target.valueAsNumber;
          if (Number.isNaN(read)) set(optional ? undefined : big ? 0n : 0);
          else set(big ? BigInt(Math.trunc(read)) : read);
        }}
      />
    </label>
  );
}

/** A selector in a field of its own (a drag's destination); it changes when it parses. */
function SelectorField({ label, values, name, onChange }: { label: string; values: Values; name: string; onChange: () => void }) {
  const [typed, setTyped] = useState(values[name] ? describeSelector(values[name]) : "");
  const parsed = parseSelector(typed);
  return (
    <>
      <label className="field">
        <span>{label}</span>
        <input
          className="mono"
          spellCheck={false}
          value={typed}
          aria-invalid={"error" in parsed}
          onChange={(e) => {
            setTyped(e.target.value);
            const next = parseSelector(e.target.value);
            if ("selector" in next) {
              values[name] = next.selector;
              onChange();
            }
          }}
        />
      </label>
      {"error" in parsed && <div className="count-line warn">{`${parsed.error} (at ${parsed.at + 1})`}</div>}
    </>
  );
}

function Field({ field, values, onChange }: { field: DescField; values: Values; onChange: () => void }) {
  const label = words(field.localName);
  const name = field.localName;
  switch (field.fieldKind) {
    case "scalar":
    case "enum":
      return <Scalar field={field} label={label} values={values} name={name} onChange={onChange} />;
    case "message": {
      if (field.message.typeName === SELECTOR) return <SelectorField label={label} values={values} name={name} onChange={onChange} />;
      // Not stored until something is typed: looking at a step must not change it.
      const nested = values[name] ?? create(field.message);
      return (
        <fieldset>
          <legend>{label}</legend>
          <MessageFields
            schema={field.message}
            values={nested}
            onChange={() => {
              values[name] = nested;
              onChange();
            }}
          />
        </fieldset>
      );
    }
    case "list": {
      if (field.listKind === "scalar") {
        return (
          <label className="field">
            <span>{label} (comma separated)</span>
            <input
              value={(values[name] as string[]).join(", ")}
              onChange={(e) => {
                values[name] = e.target.value.split(",").map((part) => part.trim()).filter(Boolean);
                onChange();
              }}
            />
          </label>
        );
      }
      if (field.listKind !== "message") return null;
      const message = field.message;
      const rows = values[name] as Values[];
      return (
        <fieldset>
          <legend>{label}</legend>
          {rows.map((row, i) => (
            <div key={i} role="group" aria-label={`${label} ${i + 1}`}>
              <MessageFields schema={message} values={row} onChange={onChange} />
              <button
                type="button"
                className="btn small"
                onClick={() => {
                  rows.splice(i, 1);
                  onChange();
                }}
              >
                Remove
              </button>
            </div>
          ))}
          <button
            type="button"
            className="btn small"
            onClick={() => {
              rows.push(create(message));
              onChange();
            }}
          >
            Add
          </button>
        </fieldset>
      );
    }
    default:
      return null;
  }
}

function defaultOf(field: DescField): unknown {
  if (field.fieldKind === "enum") return field.enum.values[0]!.number;
  if (field.fieldKind === "message") return create(field.message);
  if (field.fieldKind !== "scalar") return undefined;
  switch (field.scalar) {
    case ScalarType.STRING:
      return "";
    case ScalarType.BOOL:
      return false;
    case ScalarType.INT64:
    case ScalarType.UINT64:
    case ScalarType.SINT64:
      return 0n;
    default:
      return 0;
  }
}

/** A choice of one field (a launch extra's type, an accessibility action or its label). */
function Oneof({ oneof, values, onChange }: { oneof: DescOneof; values: Values; onChange: () => void }) {
  const chosen = values[oneof.localName] as { case?: string; value?: unknown };
  const field = oneof.fields.find((f) => f.localName === chosen.case);
  // The chosen field, as a value of its own so the field controls can edit it by name.
  const holder: Values = field ? { [field.localName]: chosen.value } : {};
  return (
    <>
      <label className="field">
        <span>{words(oneof.localName)}</span>
        <select
          value={chosen.case ?? ""}
          onChange={(e) => {
            const next = oneof.fields.find((f) => f.localName === e.target.value);
            values[oneof.localName] = next ? { case: next.localName, value: defaultOf(next) } : { case: undefined };
            onChange();
          }}
        >
          <option value="">Not set</option>
          {oneof.fields.map((f) => (
            <option key={f.localName} value={f.localName}>
              {words(f.localName)}
            </option>
          ))}
        </select>
      </label>
      {field && (
        <Field
          field={field}
          values={holder}
          onChange={() => {
            values[oneof.localName] = { case: field.localName, value: holder[field.localName] };
            onChange();
          }}
        />
      )}
    </>
  );
}

/** The fields of a message, in the proto's order. */
export function MessageFields({
  schema,
  values,
  only,
  skip,
  onChange,
}: {
  schema: DescMessage;
  values: Values;
  /** Just these fields, by proto name. */
  only?: readonly string[];
  /** All but these (the step editor's own controls), by proto name. */
  skip?: readonly string[];
  onChange: () => void;
}) {
  return (
    <>
      {schema.members.map((member) => {
        if (member.kind === "oneof") {
          if (skip?.includes(member.name)) return null;
          return <Oneof key={member.name} oneof={member} values={values} onChange={onChange} />;
        }
        if (only && !only.includes(member.name)) return null;
        if (skip?.includes(member.name)) return null;
        return <Field key={member.name} field={member} values={values} onChange={onChange} />;
      })}
    </>
  );
}

const APP_FIELDS: Record<string, readonly string[]> = {
  launch: ["package_name", "activity", "extras"],
  cold_launch: ["package_name", "activity", "extras"],
  open_link: ["package_name", "uri", "any_app"],
  grant_permission: ["package_name", "permission"],
  revoke_permission: ["package_name", "permission"],
  set_locales: ["package_name", "locales"],
};

const DEVICE_FIELDS: Record<string, readonly string[]> = {
  set_animations: ["enabled"],
  set_dark_mode: ["enabled"],
  set_stay_awake: ["enabled"],
  set_font_scale: ["font_scale"],
  set_density: ["density_dpi"],
  set_network: ["airplane_mode", "wifi", "mobile_data"],
  set_system_locales: ["locales"],
  set_location: ["latitude", "longitude", "accuracy_m", "altitude_m"],
  set_accessibility_display: ["high_contrast_text", "color_inversion", "bold_text"],
};

/** What each command's own controls in the step editor already cover. */
const COMMAND_SKIP: Record<string, readonly string[]> = {
  scroll: ["selector", "direction", "distance_percent"],
  swipe: ["selector", "direction", "distance_percent"],
  setText: ["selector", "text"],
  multiTap: ["selector", "count", "interval_ms"],
  hold: ["selector", "duration_ms"],
  waitScreenStable: ["package_name", "stable_for_ms", "signal"],
};

function CommandFields({ command, onChange }: { command: Command; onChange: () => void }) {
  const op = command.op;
  if (!op.case) return null;
  const field = CommandSchema.oneofs.find((o) => o.name === "op")!.fields.find((f) => f.localName === op.case);
  if (field?.fieldKind !== "message") return null;
  return <MessageFields schema={field.message} values={op.value} skip={COMMAND_SKIP[op.case] ?? ["selector"]} onChange={onChange} />;
}

const TEXT_CHECKS: readonly number[] = [Check.TEXT_EQUALS, Check.TEXT_CONTAINS];
const TEXT_CONDITIONS: readonly number[] = [Condition.TEXT_EQUALS, Condition.TEXT_CONTAINS];

/** A wait's condition or an assertion's check, with the value it takes. */
function Expectation({ step, onChange }: { step: Step; onChange: () => void }) {
  if (step.kind.case !== "wait" && step.kind.case !== "assertion") return null;
  const value = step.kind.value;
  const wait = step.kind.case === "wait";
  const options = wait ? Condition : Check;
  const chosen = wait ? (value as { condition: number }).condition : (value as { check: number }).check;
  const takesText = wait ? TEXT_CONDITIONS.includes(chosen) : TEXT_CHECKS.includes(chosen);
  const takesCount = chosen === (wait ? Condition.COUNT : Check.COUNT);
  const names = Object.entries(options).filter(([, number]) => typeof number === "number" && number !== 0) as [string, number][];
  return (
    <fieldset>
      <legend>{wait ? "Wait until" : "Check"}</legend>
      <label className="field">
        <span>{wait ? "Condition" : "Check"}</span>
        <select
          value={chosen}
          onChange={(e) => {
            const next = Number(e.target.value);
            if (wait) (value as { condition: number }).condition = next;
            else (value as { check: number }).check = next;
            const text = wait ? TEXT_CONDITIONS.includes(next) : TEXT_CHECKS.includes(next);
            const count = next === (wait ? Condition.COUNT : Check.COUNT);
            const before = value.value;
            value.value = text ? { case: "text", value: before.case === "text" ? before.value : "" } : count ? { case: "count", value: before.case === "count" ? before.value : 0 } : { case: undefined };
            onChange();
          }}
        >
          {names.map(([name, number]) => (
            <option key={number} value={number}>
              {words(name)}
            </option>
          ))}
        </select>
      </label>
      {takesText && (
        <label className="field">
          <span>Expected text</span>
          <input
            value={value.value.case === "text" ? value.value.value : ""}
            onChange={(e) => {
              value.value = { case: "text", value: e.target.value };
              onChange();
            }}
          />
        </label>
      )}
      {takesCount && (
        <label className="field">
          <span>Expected count</span>
          <input
            type="number"
            min={0}
            step={1}
            value={value.value.case === "count" ? value.value.value : 0}
            onChange={(e) => {
              value.value = { case: "count", value: Number.isNaN(e.target.valueAsNumber) ? 0 : e.target.valueAsNumber };
              onChange();
            }}
          />
        </label>
      )}
    </fieldset>
  );
}

/** Every value of the step that the editor has no control of its own for. `step` is the draft: it
 *  is changed in place and `onChange` says so. */
export function StepValues({ step, onChange }: { step: Step; onChange: () => void }) {
  const kind = step.kind;
  switch (kind.case) {
    case "app":
      return (
        <fieldset>
          <legend>App call ({kind.value.operation})</legend>
          <MessageFields schema={AppCallSchema} values={kind.value} only={APP_FIELDS[kind.value.operation] ?? ["package_name"]} onChange={onChange} />
        </fieldset>
      );
    case "device":
      return (
        <fieldset>
          <legend>Device condition ({kind.value.operation})</legend>
          <MessageFields schema={DeviceCallSchema} values={kind.value} only={DEVICE_FIELDS[kind.value.operation] ?? []} onChange={onChange} />
        </fieldset>
      );
    case "action":
    case "appWait":
    case "deviceWait":
      return kind.value.command ? (
        <fieldset className="step-values">
          <legend>Values</legend>
          <CommandFields command={kind.value.command} onChange={onChange} />
        </fieldset>
      ) : null;
    case "type":
      return (
        <label className="check">
          <input
            type="checkbox"
            checked={kind.value.skipFocusWait}
            onChange={(e) => {
              kind.value.skipFocusWait = e.target.checked;
              onChange();
            }}
          />
          Skip the focus wait
        </label>
      );
    case "wait":
    case "assertion":
      return <Expectation step={step} onChange={onChange} />;
    case "deviceAssertion":
      return (
        <fieldset>
          <legend>Device check</legend>
          <MessageFields schema={DeviceAssertionStepSchema} values={kind.value} onChange={onChange} />
        </fieldset>
      );
    default:
      return null;
  }
}
