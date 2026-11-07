import { clone } from "@bufbuild/protobuf";
import { PerformRequestSchema, StepSchema, type PerformRequest, type Step } from "./gen/studio_pb";

export type BudgetKey = "timeout" | "precondition" | "focus";
export type Budgets = Partial<Record<BudgetKey, number>>;
const KEYS: BudgetKey[] = ["timeout", "precondition", "focus"];
const LABELS: Record<BudgetKey, string> = { timeout: "Command / wait budget (ms)", precondition: "Element precondition budget (ms)", focus: "Typing focus budget (ms)" };
export const validBudgets = (values: Budgets) => KEYS.every((k) => values[k] === undefined || (Number.isInteger(values[k]) && values[k]! >= 1 && values[k]! <= 120000));
const ms = (value: number | undefined) => value === undefined ? undefined : BigInt(value);
const number = (value: bigint | undefined) => value === undefined ? undefined : Number(value);

export function budgetFields(step: Step): BudgetKey[] {
  const kind = step.kind;
  switch (kind.case) {
    case "action": return kind.value.command?.op.value && "selector" in kind.value.command.op.value ? ["timeout", "precondition"] : [];
    case "type": return kind.value.skipFocusWait ? ["timeout", "precondition"] : KEYS;
    case "scrollUntil": return ["timeout", "precondition"];
    case "wait":
    case "assertion":
    case "appWait":
    case "deviceWait": return ["timeout"];
    case "app": return ["cold_launch", "launch", "foreground", "force_stop", "clear_data", "open_link"].includes(kind.value.operation) ? ["timeout"] : [];
    default: return [];
  }
}

export function stepBudgets(step: Step): Budgets {
  switch (step.kind.case) {
    case "action": return { timeout: number(step.kind.value.command?.timeoutMs), precondition: number(step.kind.value.wait?.timeoutMs) };
    case "type": return { timeout: number(step.kind.value.timeoutMs), precondition: number(step.kind.value.preconditionTimeoutMs), focus: number(step.kind.value.focusTimeoutMs) };
    case "scrollUntil": return { timeout: number(step.kind.value.timeoutMs), precondition: number(step.kind.value.preconditionTimeoutMs) };
    case "wait":
    case "assertion":
    case "app": return { timeout: number(step.kind.value.timeoutMs) };
    case "appWait":
    case "deviceWait": return { timeout: number(step.kind.value.command?.timeoutMs) };
    default: return {};
  }
}

/** Preserve separate budgets; unsupported fixed-budget SDK calls receive no invented option. */
export function withBudgets(step: Step, values: Budgets): Step {
  if (!validBudgets(values)) throw new Error("Budgets must be whole milliseconds in 1..120000, or empty for SDK defaults.");
  const copy = clone(StepSchema, step);
  switch (copy.kind.case) {
    case "action":
      if (budgetFields(copy).length && copy.kind.value.command) {
        copy.kind.value.command.timeoutMs = ms(values.timeout);
        if (copy.kind.value.wait) copy.kind.value.wait.timeoutMs = ms(values.precondition);
      }
      break;
    case "type":
      Object.assign(copy.kind.value, { timeoutMs: ms(values.timeout), preconditionTimeoutMs: ms(values.precondition), focusTimeoutMs: copy.kind.value.skipFocusWait ? undefined : ms(values.focus) });
      break;
    case "scrollUntil":
      Object.assign(copy.kind.value, { timeoutMs: ms(values.timeout), preconditionTimeoutMs: ms(values.precondition) });
      break;
    case "wait":
    case "assertion": copy.kind.value.timeoutMs = ms(values.timeout); break;
    case "app": if (budgetFields(copy).length) copy.kind.value.timeoutMs = ms(values.timeout); break;
    case "appWait":
    case "deviceWait": if (copy.kind.value.command) copy.kind.value.command.timeoutMs = ms(values.timeout); break;
  }
  return copy;
}

export function requestBudgets(request: PerformRequest, values: Budgets): PerformRequest {
  const copy = clone(PerformRequestSchema, request);
  if (copy.step) {
    copy.step = withBudgets(copy.step, values);
    if (copy.step.kind.case === "action" && budgetFields(copy.step).includes("precondition")) copy.preconditionTimeoutMs = ms(values.precondition);
  }
  return copy;
}

export function ExecutionControls({ values, onChange, fields = KEYS }: { values: Budgets; onChange: (values: Budgets) => void; fields?: BudgetKey[] }) {
  if (!fields.length) return null;
  return <fieldset className="execution-controls">
    <legend>Execution budgets</legend>
    <div className="row">
      {fields.map((key) => <label className="field" key={key}>
        <span>{LABELS[key]}</span>
        <input type="number" min={1} max={120000} step={1} placeholder="SDK default" value={Number.isNaN(values[key]) ? "" : values[key] ?? ""}
          aria-invalid={values[key] !== undefined && !(Number.isInteger(values[key]) && values[key]! >= 1 && values[key]! <= 120000)}
          onChange={(e) => onChange({ ...values, [key]: e.target.value === "" ? undefined : e.target.valueAsNumber })} />
      </label>)}
    </div>
    {!validBudgets(values) && <p role="alert">Budgets must be whole milliseconds in 1..120000.</p>}
    <p className="hint">Empty uses SDK defaults. Each phase has its own budget, not one total deadline. Host-poll/search budgets are checked between SDK calls; an in-flight call keeps its SDK budget. Keys and fixed-budget device calls keep their SDK behavior. Focus is not IME readiness.</p>
  </fieldset>;
}
