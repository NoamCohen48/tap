import { create } from "@bufbuild/protobuf";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { CommandSchema, StabilitySignal } from "./gen/command_pb";
import { StepSchema } from "./gen/studio_pb";
import { ExecutionControls, requestBudgets, stepBudgets, validBudgets, withBudgets } from "./ExecutionControls";
import { PressControls, stepPress, validPress, withPress } from "./PressControls";
import { AppControls } from "./AppControls";
import { describeStep } from "./describe";
import { parseSelector } from "./parse";
import { countedTap, hold, synthesized, typeText } from "./steps";
import { StepEditor } from "./StepEditor";
import type { StudioClient } from "./api";

afterEach(cleanup);
const parsed = parseSelector('res("field")');
if ("error" in parsed) throw new Error(parsed.error);
const target = synthesized(parsed.selector);

describe("counted and timed presses", () => {
  it("sends one bounded request with the chosen values", () => {
    const send = vi.fn();
    render(<PressControls target={target} disabled={false} onPerform={send} />);
    fireEvent.change(screen.getByLabelText("Tap count"), { target: { value: "4" } });
    fireEvent.change(screen.getByLabelText("Release-to-press interval (ms)"), { target: { value: "125" } });
    fireEvent.click(screen.getByRole("button", { name: "Tap sequence" }));
    const sequence = send.mock.calls[0]![0].step.kind.value.command.op;
    expect(sequence.case).toBe("multiTap");
    expect(sequence.value.count).toBe(4);
    expect(sequence.value.intervalMs).toBe(125n);
    fireEvent.change(screen.getByLabelText("Hold duration (ms)"), { target: { value: "1500" } });
    fireEvent.click(screen.getByRole("button", { name: "Hold for duration" }));
    expect(send.mock.calls[1]![0].step.kind.value.command.op.value.durationMs).toBe(1500n);
  });
  it("uses the ordinary tap for one and refuses invalid or overlong sequences", () => {
    expect(countedTap(target, 1).step?.kind.case).toBe("action");
    expect(describeStep(countedTap(target, 1).step!)).toContain(".tap()");
    for (const p of [{ count: 0, interval: 100 }, { count: 101, interval: 100 }, { count: 3, interval: 39 }, { count: 100, interval: 1000 }, { duration: 0 }, { duration: 30001 }]) expect(validPress(p)).toBe(false);
    const send = vi.fn();
    render(<PressControls target={target} disabled={false} onPerform={send} />);
    fireEvent.change(screen.getByLabelText("Tap count"), { target: { value: "100" } });
    fireEvent.change(screen.getByLabelText("Release-to-press interval (ms)"), { target: { value: "1000" } });
    expect((screen.getByRole("button", { name: "Tap sequence" }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "Tap sequence" }));
    expect(send).not.toHaveBeenCalled();
  });
  it("edits values and describes the actual operation", () => {
    const sequence = withPress(countedTap(target, 3).step!, { count: 5, interval: 90 });
    expect(stepPress(sequence)).toEqual({ count: 5, interval: 90 });
    expect(describeStep(sequence)).toContain("count = 5, interval = 90.milliseconds");
    expect(describeStep(hold(target, 1200).step!)).toContain("longTap(duration = 1200.milliseconds)");
    expect(stepPress(withPress(sequence, { count: 1, interval: 90 }))).toBeNull();
  });
});

describe("execution budgets", () => {
  it("keeps action and inferred wait budgets separate without sending a user wait", () => {
    const request = requestBudgets(countedTap(target, 3), { timeout: 5000, precondition: 700 });
    expect(request.preconditionTimeoutMs).toBe(700n);
    if (request.step?.kind.case !== "action") throw new Error("action");
    expect(request.step.kind.value.wait).toBeUndefined();
    expect(request.step.kind.value.command?.timeoutMs).toBe(5000n);
    request.step.kind.value.wait = create(CommandSchema, { timeoutMs: 700n });
    expect(stepBudgets(request.step)).toEqual({ timeout: 5000, precondition: 700 });
    expect(describeStep(request.step)).toContain("timeout = 5000 ms, precondition = 700 ms");
  });
  it("keeps typing phases and leaves fixed-budget keys unchanged", () => {
    const typed = withBudgets(typeText(target, { secret: "password", value: "synthetic" }).step!, { timeout: 300, precondition: 400, focus: 500 });
    expect(stepBudgets(typed)).toEqual({ timeout: 300, precondition: 400, focus: 500 });
    const key = create(StepSchema, { kind: { case: "action", value: { command: { op: { case: "pressKey", value: { keyCode: 4 } } } } } });
    expect(withBudgets(key, { timeout: 1 })).toEqual(key);
    expect(validBudgets({ timeout: NaN })).toBe(false);
    expect(validBudgets({ timeout: 120001 })).toBe(false);
    expect(() => withBudgets(typed, { timeout: 0 })).toThrow();
  });
  it("renders named bounded inputs with clear SDK defaults", () => {
    const change = vi.fn();
    render(<ExecutionControls values={{}} onChange={change} />);
    fireEvent.change(screen.getByLabelText("Typing focus budget (ms)"), { target: { value: "1200" } });
    expect(change).toHaveBeenCalledWith({ focus: 1200 });
  });
  it("saves gesture and budget edits rather than merely changing the labels", async () => {
    const client = { count: vi.fn().mockResolvedValue({ count: 1 }) } as unknown as StudioClient;
    const saved = vi.fn().mockResolvedValue(true);
    const step = countedTap(target, 3).step!;
    if (step.kind.case !== "action") throw new Error("action");
    step.id = "s1";
    step.kind.value.wait = create(CommandSchema, { op: { case: "waitVisible", value: { selector: target.selector, exactlyOne: true } } });
    render(<StepEditor step={step} nodes={[]} client={client} busy={false} onSave={saved} />);
    await screen.findByText("Matches 1 element now.");
    fireEvent.change(screen.getByLabelText("Tap count"), { target: { value: "5" } });
    fireEvent.change(screen.getByLabelText("Element precondition budget (ms)"), { target: { value: "800" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(stepPress(saved.mock.calls[0]![0])).toEqual({ count: 5, interval: 100 });
    expect(stepBudgets(saved.mock.calls[0]![0]).precondition).toBe(800);
    expect(stepPress(step)).toEqual({ count: 3, interval: 100 });
  });
});

it("records whole-screen stability, the chosen signal and quiet period", () => {
  const perform = vi.fn();
  render(<AppControls appPackage="com.example" onAppPackage={() => {}} packages={[]} busy={false} onPerform={perform} />);
  fireEvent.click(screen.getByLabelText("Whole screen"));
  fireEvent.change(screen.getByLabelText("Quiet period (ms)"), { target: { value: "1200" } });
  fireEvent.click(screen.getByRole("button", { name: "Settled" }));
  const wait = perform.mock.calls[0]![0].step.kind.value.command.op.value;
  expect(wait.packageName).toBe("");
  expect(wait.stableForMs).toBe(1200n);
  expect(wait.signal).toBe(StabilitySignal.STABILITY_TREE);
});

it("rejects unsafe typed selector values locally", () => {
  for (const text of ['res("x").at(2147483648)', 'res("x").at(9007199254740993)', 'text("\\ud800")', `text("${"x".repeat(1025)}")`, `${"(".repeat(40)}res("x")${")".repeat(40)}`, Array.from({ length: 257 }, () => 'res("x")').join(" or ")]) expect(parseSelector(text)).toHaveProperty("error");
  expect(parseSelector('text("😀")')).toHaveProperty("selector");
});
