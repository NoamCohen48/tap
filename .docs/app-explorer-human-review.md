# App Explorer: human-in-the-loop review

Status: **proposed, not implemented**. This records the human-review approach discussed after
the Loop Habit Tracker trial. It complements [app-explorer.md](app-explorer.md), especially
sections 23–25; it does not change the current execution or identity contract. The offline
corpus/scorer foundation in section 25 is implemented; the review queue and reusable rules below
remain proposals.

## Goal

Make discovery collaborative and exception-driven. Automation gathers evidence and proposes
classifications; humans resolve ambiguity; fresh verification establishes which corrections
are reusable. Humans should not need to supervise every observation.

Classification review and action approval remain separate. Naming a state, grouping screens or
marking a control must never authorize device input.

## Review loop

### 1. Surface uncertain discoveries

Pause the affected path and create a review item containing:

- Current screenshot and relevant previous screenshot.
- Snapshot features, source action and append-only attempt/outcome evidence.
- Proposed classification and the reason for uncertainty.
- Any conflicting observed outcomes or failed expected conditions.

Example: “Save returned the same accessibility features; inspect whether the screenshot shows
validation feedback.”

Review can be requested by an operator or triggered by implemented signals such as conflicting
outcomes, unsupported controls and failed checks. Automatic visual-difference detection is not
currently validated. Identical accessibility features can conceal meaningful visual changes,
so the initial design also needs operator-requested review and sampling—not a claim that the
system reliably detects every uncertain observation.

### 2. Human classifies the observation

Offer these choices, with a short name and explanation:

| Classification | Meaning |
|---|---|
| Same state | No meaningful execution-state change was observed. |
| Different screen | A different screen family, even if generic structure looks similar. |
| Data variant | Same screen with different content or parameter bindings; not proof of route equivalence. |
| New variant | Validation error, selected option, loading state or another meaningful variation. |
| Temporary overlay | Toast or transient feedback associated with the underlying screen. |
| History-dependent | Identical-looking UI can behave differently because of prior actions or context. |
| Unknown | Leave unresolved and stop this path. |

Record the annotation with its evidence references and provenance. Preserve the raw
observation, original heuristic classification and execution outcomes. Corrections must not
silently rewrite historical evidence or imply that a successful input was business-correct.

Review may target a feature/slot and a scoped group of observations, not only one screenshot.
For example: “These six forms differ in targetInput text: input echo, data, volatile or
state-bearing?” Answers remain hypotheses with provenance and applicability limits. One answer
must not silently normalize every future value. Rank items by safety relevance, uncertainty and
affected evidence—not merely how many nodes a merge would remove.

### 3. Ask how to recognize the correction again

A classification label is not a reusable recognition rule. Ask:

> How can we recognize this state or condition on another observation?

Prefer native text, flags, field values or combinations of observable properties. Explicit
recorded context may also be relevant—for example, “submitted an empty name”—but it is not
proof that the app still has the expected hidden state after external changes or relaunch.

Where expressible, recognition conditions should use Tap selectors/assertions, not a parallel
selector language. A stored-observation check is offline evidence validation, not a substitute
for fresh native matching. Keep actual parameter bindings; “filled” alone does not establish
numeric threshold or backend-validation equivalence. Prefer `showing_hint` when available;
text equal to a hint is not a general empty-input detector.

If there is no reliable condition, retain an **observation-specific human confirmation**.
Do not automatically classify future screenshots from the label alone.

Separate screen-family grouping from execution-state identity: visually grouping variants in
the graph must not make their selectors, approvals or routes interchangeable.

### 4. Validate proposed reusable rules

Keep recognition rules provisional until they have been exercised on fresh observations and,
where necessary, explicitly approved fresh trials.

Check that:

- The proposed recognition condition holds on the intended evidence.
- It distinguishes relevant alternatives rather than matching everything.
- Expected transitions and independently specified business conditions hold.
- Conflicting outcomes are retained rather than hidden by the correction.

Verification failure keeps the rule provisional or rejects it. An uncertain transmitted
mutation is never replayed to validate a rule. A fresh trial requires its own authority,
preconditions, budget and persisted intent; it does not resume an uncertain run.

### 5. Resume inside reviewed boundaries

Use validated rules only within their declared scope. Request further review for new
uncertainty, conflicts or expired assumptions. Unresolved state identity cannot become a
trusted deterministic route merely because a human supplied a name.

## Human assistance beyond classification

- **Identify controls:** “This label opens habit details.” This can guide proposal generation,
  but execution still requires a unique native selector and fresh source/target checks. No
  coordinate, index or persistent-handle workaround.
- **Add assertions:** “After saving, this value must appear.” Verify independently of graph
  classification; do not equate command success with business correctness.
- **Explain context:** “Previous selections change what this screen does.” Record the context
  hypothesis and its verification limits; do not invent invisible backend-state equivalence.
- **Mark transient feedback:** Attach a toast to its underlying screen for presentation, while
  preserving the observation and avoiding broad suppression of real dialog/error messages.

## Workbench presentation

Add a **Review queue** beside the graph. Each item should show:

- Before/current evidence and the uncertainty reason.
- Classification choices, name and explanation.
- An optional recognition condition or context hypothesis.
- Verification status: observation-only, provisional, verified or rejected.
- Relevant attempts, conflicts and business assertions.

Keep classification review, rule verification and action approval visibly distinct. A
human-confirmed observation must not be displayed as a verified general rule. Reviewed
annotations should have an append-only revision trail, not overwrite previous decisions.

## Loop examples

- Mark the blank-name error screenshot as “Create habit / invalid name.” Its error icon was
  absent from existing snapshot features, so initially this is observation-specific evidence,
  not an automatic future classifier.
- Mark “Habit created” as temporary feedback attached to the saved-list screen. Do not exclude
  every message node: that could erase genuine validation errors.
- Identify a habit title as a detail-opening control, then require a safe native selector and
  a fresh verified trial before adding an executable transition.

## Suggested delivery order

1. Labeled corpus and offline screen/variant metrics (initial foundation implemented; Loop
   labels are provisional analyst annotations, not human approval).
2. Confirm/capture missing accessibility error, window and input signals with regressions.
3. Screen-family/variant grouping and feature roles, without changing execution identity.
4. Feature-level review queue and scoped Tap recognition/context proposals with provenance.
5. Fresh verification, conflict handling and application of validated rules.
6. Robot operations and generated tests only from verified contracts.

A skeleton match is a screen-family hypothesis, not deterministic execution equivalence.
Preserve screen family, observable variant, and execution context/parameter bindings separately.

This improves evidence quality and reduces repeated ambiguity. It does not guarantee hidden
state discovery, arbitrary-app understanding, full coverage or safe automatic action approval.
