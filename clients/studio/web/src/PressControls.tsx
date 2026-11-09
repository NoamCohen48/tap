import { clone, create } from "@bufbuild/protobuf";
import { useState } from "react";
import { Row } from "./controls";
import { TapSchema } from "./gen/command_pb";
import { StepSchema, type PerformRequest, type Step } from "./gen/studio_pb";
import { countedTap, hold, tapOutsidePanel, type Target } from "./steps";

export type Press = { count: number; interval: number } | { duration: number };
export function validPress(value: Press): boolean {
  return "duration" in value
    ? Number.isInteger(value.duration) && value.duration >= 1 && value.duration <= 30000
    : Number.isInteger(value.count) && value.count >= 1 && value.count <= 100 && Number.isInteger(value.interval) && value.interval >= 40 && value.interval <= 1000 && value.count * 50 + (value.count - 1) * value.interval <= 30000;
}
export function stepPress(step: Step): Press | null {
  const op = step.kind.case === "action" ? step.kind.value.command?.op : undefined;
  if (op?.case === "multiTap") return { count: op.value.count, interval: Number(op.value.intervalMs ?? 100n) };
  if (op?.case === "hold") return { duration: Number(op.value.durationMs) };
  return null;
}
export function withPress(step: Step, press: Press): Step {
  const copy = clone(StepSchema, step);
  const command = copy.kind.case === "action" ? copy.kind.value.command : undefined;
  if (!command) return copy;
  if (command.op.case === "hold" && "duration" in press) command.op.value.durationMs = BigInt(press.duration);
  if (command.op.case === "multiTap" && "count" in press) {
    const selector = command.op.value.selector;
    command.op = press.count === 1 ? { case: "tap", value: create(TapSchema, { selector }) } : { ...command.op, value: { ...command.op.value, count: press.count, intervalMs: BigInt(press.interval) } };
  }
  return copy;
}
export function PressFields({ value, onChange }: { value: Press; onChange: (value: Press) => void }) {
  return <div className="row press-fields">
    {"duration" in value ? <label className="field"><span>Hold duration (ms)</span><input type="number" min={1} max={30000} step={1} value={Number.isNaN(value.duration) ? "" : value.duration} onChange={(e) => onChange({ duration: e.target.valueAsNumber })} /></label> : <>
      <label className="field"><span>Tap count</span><input type="number" min={1} max={100} step={1} value={Number.isNaN(value.count) ? "" : value.count} onChange={(e) => onChange({ ...value, count: e.target.valueAsNumber })} /></label>
      <label className="field"><span>Release-to-press interval (ms)</span><input type="number" min={40} max={1000} step={1} value={Number.isNaN(value.interval) ? "" : value.interval} onChange={(e) => onChange({ ...value, interval: e.target.valueAsNumber })} /></label>
    </>}
    {!validPress(value) && <p role="alert">Use 1–100 taps, a 40–1000 ms interval, or a 1–30000 ms hold; the sequence must fit within 30 seconds.</p>}
  </div>;
}
export function PressControls({ target, disabled, onPerform }: { target: Target; disabled: boolean; onPerform: (request: PerformRequest) => void }) {
  const [sequence, setSequence] = useState<Press>({ count: 3, interval: 100 });
  const [timed, setTimed] = useState<Press>({ duration: 1200 });
  return <>
    <Row label="Counted taps"><PressFields value={sequence} onChange={setSequence} /><button type="button" className="btn" disabled={disabled || !validPress(sequence)} onClick={() => { if ("count" in sequence) onPerform(countedTap(target, sequence.count, sequence.interval)); }}>Tap sequence</button></Row>
    <Row label="Physical hold"><PressFields value={timed} onChange={setTimed} /><button type="button" className="btn" disabled={disabled || !validPress(timed)} onClick={() => { if ("duration" in timed) onPerform(hold(target, timed.duration)); }}>Hold for duration</button></Row>
  </>;
}

/** A tap outside a panel's `allow_reach_below`, or null for any other step. */
export function stepReachBelow(step: Step): boolean | null {
  const op = step.kind.case === "action" ? step.kind.value.command?.op : undefined;
  return op?.case === "tapOutsidePanel" ? op.value.allowReachBelow : null;
}
export function withReachBelow(step: Step, allowReachBelow: boolean): Step {
  const copy = clone(StepSchema, step);
  const op = copy.kind.case === "action" ? copy.kind.value.command?.op : undefined;
  if (op?.case === "tapOutsidePanel") op.value.allowReachBelow = allowReachBelow;
  return copy;
}
export const REACH_BELOW_LABEL = "Also when the touch reaches the screen below";
const REACH_BELOW_HINT = "The panel lets touches through (a dropdown, a popup that does not take focus): the touch also presses what is under it. Without this, such a panel is refused.";
export function ReachBelowField({ checked, disabled, onChange }: { checked: boolean; disabled?: boolean; onChange: (checked: boolean) => void }) {
  return <label className="check" title={REACH_BELOW_HINT}><input type="checkbox" checked={checked} disabled={disabled} onChange={(e) => onChange(e.target.checked)} />{REACH_BELOW_LABEL}</label>;
}
/** One tap outside the dialog, menu, drawer or sheet that holds the element. */
export function OutsidePanelControls({ target, disabled, onPerform }: { target: Target; disabled: boolean; onPerform: (request: PerformRequest) => void }) {
  const [reachBelow, setReachBelow] = useState(false);
  return <Row label="Outside panel">
    <button type="button" className="btn" disabled={disabled} title="Tap outside the dialog, menu, drawer or sheet this element is in" onClick={() => onPerform(tapOutsidePanel(target, reachBelow))}>Tap outside panel</button>
    <ReachBelowField checked={reachBelow} disabled={disabled} onChange={setReachBelow} />
  </Row>;
}
