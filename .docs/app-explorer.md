# App Explorer — design handout and implementation roadmap

Status: **planning only; no implementation**. The owner agreed with the direction and requested
this handout in a separate worktree. Detailed mechanisms below are recommendations, not
approved API contracts. Resolve the open decisions before implementing the relevant phase.

## 1. Purpose

Build a new Android app-exploration client for Tap. It systematically discovers app behavior,
records an evidence-backed state/action graph, verifies routes through that graph, and helps
produce reusable screen-specific test APIs (Page Objects / the Robot pattern).

The motivating failure is an agent asked to explore an app through `tap-agent`: tools alone do
not provide persistent exploration memory, coverage accounting, route planning, recovery, or
verified reusable operations. The exact cause of that agent's failure has not been diagnosed;
this design addresses those missing capabilities rather than claiming to fix a known bug.

The central principle is:

> Deterministic code owns execution, planning, policy, persistence, and verification. AI interprets
> observations and proposes structured additions. The app itself need not be deterministic.

The explorer is an **app model builder**, not an autonomous authority on correct app behavior.
It discovers what happened. Requirements and human review establish what should happen.

## 2. Desired outputs

1. A persistent, resumable observed-state graph with actions, inputs, outcomes, and evidence.
2. A semantic screen catalog: Home, Login, Cart, Settings, and their state variants.
3. Explicit explored / unexplored / blocked / failed / uncertain action accounting.
4. Routes verified from a defined starting condition, with checks at each step.
5. Proposed intent-level robot operations, linked to the evidence supporting them.
6. An honest report of limitations: inaccessible controls, unresolved inputs, unstable states,
   unsafe actions, unreachable branches, and non-reproducible paths.

A large count of tapped controls is not success. Reproducible routes and useful operations are.

## 3. Existing Tap foundations

Reviewed code and records on the planning branch:

| Foundation | Source | Use |
|---|---|---|
| Structured snapshots and selector candidates | `clients/python/tap_e2e/device.py`, `models.py` | Discover visible nodes and potential element interactions |
| Ordinary selector-based mutations | Python `Screen` / `Element` APIs | Execute with Tap's exact-one safety |
| Held connections and agent steps | `clients/agent/tap_agent/core.py` | Session/lifecycle and evidence patterns; not a graph planner |
| Screenshot/snapshot frames | `clients/studio/tap_studio/screen.py` | Reference for collecting paired observation artifacts |
| Ordered replay, stopping on failure | `clients/studio/tap_studio/service.py`, `steps.py` | Reference for replay orchestration; not state restoration |
| Daemon event log | Python connection APIs; `.docs/agent-surface.md` | Supplemental execution evidence, not durable graph storage |

Related records: [agent surface](agent-surface.md), [recorder](recorder.md),
[app and screen](app-and-screen.md), [screen streaming](screen-streaming.md),
[protocol contract](protocol-contract.md), and [upstream audit](upstream-reference-audit.md).

Snapshots are diagnostic hierarchy-derived observations. Using them to discover selectors is
appropriate; generated tests must use ordinary selectors and assertions, not dumps as their
execution or assertion hot path. A selector unique in an observation still needs action-time
resolution. Refs are observation/session conveniences, not durable identities for robot code.

## 4. Scope and non-goals

### Initial scope

- One Android device, one configured app, one controlled test environment/account.
- One explicitly defined starting condition, usually reached by launching the app and checking
  configured landmarks. The main screen is not assumed if onboarding/login appears instead.
- Safe navigation, bounded scrolling, and approved form scenarios.
- Durable local artifacts, a graph, and a reviewable report.
- AI-assisted interpretation, with an offline/scripted provider for tests.
- Robot proposals after routes have been verified.

### Not promised by v1

- Complete exploration of every state or every possible input.
- Backend rollback, automatic account provisioning, or app-independent reset magic.
- Coordinate taps, XPath, or persistent node handles.
- Purchases, messaging, destructive actions, or unrestricted cross-app exploration.
- Business-correctness assertions invented from observed behavior.
- Automatically trustworthy generated test code without review.
- A browser UI, MCP interface, multi-device scheduling, or video transport.
- Changes to synchronization / sync-sdk / `awaitIdle`.

## 5. Recommended architecture

Proposed home: `clients/explorer`, a Python package provisionally called `tap-explorer`, built on
`tap-e2e`. Naming and release integration remain open decisions. Start with a headless core and
thin CLI; a visual inspector can be added once the exploration model works.

```text
CLI / later UI
       |
Exploration coordinator
       |-- persistent store + evidence
       |-- observation and state recognition
       |-- candidate enumeration and scenario expansion
       |-- safety policy
       |-- frontier scheduler and route planner
       |-- executor and postcondition checks
       |-- structured AI proposal adapter
       `-- screen catalog and robot proposals
       |
    tap-e2e
       |
   Tap daemon → host core → Android driver
```

No dependency from `host/*` to this client. Do not shell out to `tap-agent` or parse its rendered
text. Do not couple the explorer to Studio's private backend implementation. Reuse the existing
public Python APIs; extract shared client utilities only when a concrete need justifies it.
No daemon/proto change is assumed initially. If a genuine gap appears, document and approve it
rather than bypassing Tap invariants.

## 6. Two models: observations/states and semantic screens

### Observations

An observation is immutable evidence captured at a moment: snapshot, screenshot, device/context
metadata, capture timing, and any stability warning. Keep the original evidence even after a
state is classified or reclassified.

Snapshot and screenshot are separate calls, not an atomic device transaction. Serialize
explorer actions with capture, record acquisition ordering/times, and mark suspected drift.
AI must not treat a moving/mismatched pair as certain evidence of a control's location.

### Observed states

A state is a recognition model grouping observations believed equivalent for exploration.
It includes relevant UI conditions and known context. For example:

```text
Login: empty
Login: required-field errors visible
Login: credentials entered
Login: authentication failed
Login: submitting
Login: permission dialog covering it
```

Preserve modal/keyboard/system-window context when it changes action availability. The graph
is an observed approximation, not a complete representation of hidden backend/app state.

### Semantic screens

A screen groups related states under a human-meaningful concept, such as Login. This layer
organizes robots and reports. An AI-suggested name does not define state identity and must not
silently merge graph nodes.

### Transitions

Use a directed multigraph: the same source/action/input can have several observed outcomes.
Transitions may be self-loops. Opening a dialog, editing text, or receiving an error is behavior
even if navigation does not occur.

## 7. Logical persistence model

Recommended entities; these are not a committed serialization schema:

| Entity | Key contents |
|---|---|
| Exploration run | IDs, app/build/device context, starting-condition recipe, policy, budgets, algorithm/config versions |
| Observation | Artifact references, snapshot, capture timestamps, stability/drift status |
| State | Recognition signature, member observations, relevant context, confidence, merge/split history |
| Screen | Proposed/reviewed name, state membership, landmarks |
| Action candidate | Source state, verb, selector choices, capability, risk, preconditions, provenance |
| Scenario | Typed input parameters, value references, finite expansion policy |
| Attempt | Intent recorded before execution, command status, before/after observations, timing, errors |
| Transition | Source, action/scenario, observed destination(s), supporting attempts |
| Route | Starting condition, ordered actions/checks, prerequisites, verification history |
| AI proposal | Provider/model, prompt/schema version, input evidence, validated response, acceptance/rejection |
| Robot proposal | Screen, intent name, parameters, selectors, pre/postconditions, backing route, review status |

Keep command execution success separate from destination recognition and route verification.
An accepted tap is not proof that navigation occurred.

Action bookkeeping should distinguish pending, policy-blocked, attempted-with-outcome,
failed-before-input, indeterminate, and deferred. Attempts remain append-only; a later success
does not erase a prior failure.

Recommend transactional local storage (for example SQLite) plus an artifact directory and a
versioned export document. Final storage format is open. The daemon's bounded in-memory event
log cannot be the explorer's source of truth and is not a reliable crash-recovery ledger.

## 8. State recognition

Do not identify a state by screenshot hash, snapshot ref numbers, or raw hierarchy hash.
Clocks, animations, cursors, dynamic text, and list ordering produce false differences.
Conversely, overly broad normalization hides important errors or account-dependent behavior.

Recommended pipeline:

1. Keep all raw observation evidence.
2. Derive a versioned structural signature: package context, stable resource/role landmarks,
   selected flags, modal conditions, and explicitly relevant labels/values.
3. Normalize only known volatile fields; make rules inspectable and configurable.
4. Use matching signatures as candidates, then check discriminating landmarks/conditions.
5. Let AI propose semantic similarity or merge candidates, not authoritatively merge states.
6. Keep uncertain cases separate until evidence or human review resolves them.
7. Preserve merge/split history and rebuild frontier/route validity after reclassification.

Form values need a balance: record scenario/value classes where suitable rather than making
every arbitrary string a new state. Error labels and enabled/disabled controls may be important
state differences. App build, permissions, login context, and reset generation affect whether
previous routes remain applicable.

## 9. Action discovery and bounded scenarios

Derive initial candidates from node capabilities and enabled/visible status, not every node:
clickable → tap; long-clickable → optional long tap; editable → approved fill scenario;
scrollable → bounded scroll; checkable → state-aware toggle through supported APIs.
`interactive` alone does not establish safety or guarantee addressability.

- Bind actions to selector candidates; preserve quality/provenance and `by_index` fragility.
- Prefer stable resource IDs and semantic/ancestor constraints over list index picks.
- Re-observe/re-resolve before execution; never keep a live element handle across commands.
- Record non-addressable nodes as limitations. Vision may describe them but does not authorize
  coordinate input.
- Enumerate Back as a policy-controlled navigation action, not an assumed inverse edge.
- Initially exclude system panels and other packages except explicitly approved dialog flows.
- Detect repeated list content/no-progress scrolling; cap scrolls and representative items.

AI can group related fields and propose finite scenarios: empty submission, approved valid
credentials, invalid credentials, or boundary values. Reject unsupported verbs, malformed
selectors, unbounded scenarios, unknown value references, and unauthorized secrets/actions.
Avoid the Cartesian product of every field/value. Expansion must have a configured limit.

## 10. Deterministic coordinator

```text
Reach and verify configured starting condition
Observe → recognize/create state → enumerate candidates
Choose eligible frontier item with stable ordering
Reach its source using a verified route
Check source landmarks and action preconditions
Persist intent → execute one action once
Observe → record outcome → update graph/frontier
Repeat until budget exhausted, frontier resolved, or blocked
```

Use a stable priority policy and tie-breaking order. Prioritize safe unexplored navigation,
then approved scenarios; defer low-confidence or fragile targets according to policy. Record
scheduler decisions so an operator can understand why a branch was skipped.

Determinism means the same stored observations, configuration, and accepted proposals yield
the same decisions. It does not mean an AI service or real app produces identical results.
Persist AI responses; resume using accepted proposals rather than silently asking again.

Explicit budgets: actions, elapsed time, depth, scenarios per form, representative list items,
scrolls per container, repeated transitions, recovery attempts, AI calls/tokens/cost. Report
which limit stopped exploration. Check cancellation between steps.

## 11. Recovery, replay, and verification

A route is valid only with defined prerequisites and a checked starting condition. Check the
expected state before and after each route step; stop on mismatch. Do not blindly execute the
remaining route or use AI improvisation inside a route claimed to be verified.

Recovery order should be explicit and bounded: try a known verified return route; otherwise
invoke an approved starting-condition recipe and verify its result; otherwise stop as blocked.

Back is not undo. Cold launch does not reset app data. Clear-data can destroy onboarding/login
state but does not reset backend data. Account provisioning, fixtures, deep links, and backend
reset hooks are app-specific capabilities and must be declared rather than assumed.

Replay means a **new deliberate invocation** of a previously observed operation from checked
preconditions, with fresh request IDs. It never means transport retry of an accepted mutation.

If transport loss produces `INDETERMINATE`, mark the attempt uncertain, observe only if safe,
and require approved recovery/reconciliation. Do not resend that mutation. If the explorer
crashes after persisting intent but before persisting outcome, the pending attempt is also
uncertain on restart; lack of a local result is not proof that no device input occurred.

Verification stores separate runs/attempts and observed destinations. A route verified on one
build/account/device is not universally verified. Report reproducibility counts and context;
set the promotion threshold explicitly rather than claiming one successful run proves stability.

## 12. AI boundary and trust model

AI receives minimized observation evidence and returns schema-validated proposals: screen
classification, field groups, finite scenarios, state similarity candidates, or robot operations.
No direct device tool access is required in this design.

- Every proposal cites observation/node IDs and includes uncertainty.
- The core validates referenced nodes, capabilities, selectors, limits, and policy.
- AI text cannot change safety rules, grant approvals, or mark routes verified.
- App text/images are untrusted data. Treat instructions shown by the app as content, not
  commands to the explorer or model.
- Keep proposal provenance and operator edits; expose rejected proposals in diagnostics.
- Use a provider-neutral adapter with timeouts, error handling, a scripted fake, and cached
  accepted responses. Provider/model choice is still open.
- When AI is unavailable, retain deterministic discovery/execution for existing approved
  candidates; defer interpretation-dependent tasks rather than guessing.

## 13. Safety, secrets, and privacy

Use a dedicated test environment/account. Unknown-risk actions default to blocked for review.
Label-based AI risk classification is assistance, not a security boundary. Prefer explicit
allowlists/approved workflows and constrain packages/action classes.

Purchases, deletion, messages, uploads, logout, permission changes, and external-app navigation
need declared approval. A friendly-looking button is not evidence of harmless behavior.

Secrets are named runtime references, not literal values in graph exports or generated code.
Screenshots and snapshots can expose credentials/personal data; redaction must apply before
external AI submission. Artifacts need retention/access policy and sanitized report export.
Tap's existing daemon event log records typed text verbatim: the explorer cannot promise
end-to-end secret redaction merely by masking its own files. Resolve that limitation before
credential-sensitive use or use non-sensitive dedicated test values.

## 14. Robot generation and review

Do not generate one method per discovered button. Propose intent-level operations, separating
primitive helpers from verified multi-step workflows:

```text
LoginRobot
  enterCredentials(email, password)
  submit()
  expectRequiredFields()
  signIn(email, password)  [verified workflow, defined destination]

HomeRobot
  openCart()
  openProfile()
```

Every proposal includes parameters, source state/preconditions, selector quality, ordered
steps, postconditions, backing evidence, and verification context. Distinguish an observed
landmark used for route checking from a human-approved business assertion.

Do not embed refs, raw hierarchy assumptions, or secret literals. Flag index-dependent or
text-volatile selectors for review. Generated code must use public client APIs and normal
selector/assertion paths. Destination screen ownership and return types need a chosen coding
convention before language-specific generation.

Start with a language-neutral operation document. Python/Kotlin code generation, output shape,
and automatic file edits are separate decisions. The first robot deliverable can be a reviewable
proposal without emitting runnable source.

## 15. Example discovery

```text
Starting condition: dedicated logged-out account, Home visible
Home --open Profile--> Login(empty)
Login(empty) --submit--> Login(required-field errors)
Login(empty) --fill approved credentials--> Login(filled)
Login(filled) --submit--> Home(logged-in)
Home(logged-in) --open Cart--> Cart(empty)
```

The logged-in Home state is not automatically merged with logged-out Home. Each transition
has before/after evidence; credential values remain runtime references. Cart exploration may
require a different recipe if login changed the starting condition. AI proposes Login and Home
screen groups and `signIn`; replay verifies the workflow before it is offered as a verified
robot operation. Whether successful login is correct requires an approved expectation.

## 16. Implementation phases and acceptance gates

No phase is implemented by this handout. Each should be a bounded change with its own tests;
update this record with actual results, not just completed code.

### Phase 0 — contract and configuration

Choose package name, persistence/export schema, initial app/scenarios, reset recipe, safety
policy, AI provider/privacy rules, budgets, and robot proposal format. Define concrete starting
landmarks and result classifications. Acceptance: example documents/configurations validate,
and owner approval covers consequential choices.

### Phase 1 — offline model and durable store

Implement observations, attempts, multigraph, frontier, budgets, and import/export without a
device or AI provider. Acceptance: deterministic scheduling from recorded inputs; restart
preserves progress; interrupted attempt is uncertain; multiple outcomes/self-loops preserved.

### Phase 2 — observation and safe navigation

Add public `tap-e2e` adapter, explicit session lifecycle, capture, recognition, action discovery,
and a conservative navigation policy. Acceptance: fake-daemon tests plus captured fixture
observations cover dynamic text, dialogs, disabled nodes, missing selectors, ambiguous targets,
and failed/indeterminate commands. No mutation retransmission.

### Phase 3 — checked routes and recovery

Implement route planning, pre/postcondition checks, approved starting-condition recipes, and
bounded recovery. Acceptance: diverging route stops before its next mutation; stale routes
invalidate appropriately; Back/cold launch are not assumed to restore data; resume checks state.

### Phase 4 — structured AI and form scenarios

Add interpretation/proposal adapter, validation, caching, and bounded field-group scenarios.
Acceptance: scripted responses test malformed data, unknown nodes, risk-policy bypass attempts,
app-content prompt injection, provider timeout, and secret redaction. Deterministic core can
continue/defer cleanly without AI.

### Phase 5 — reports and robot proposals

Export screen catalog, graph/frontier summary, route evidence, selector warnings, and
language-neutral operation proposals. Acceptance: every proposed workflow traces to attempts
and context; incomplete exploration and unverified operations are visibly marked; no refs or
secret literals are promoted into durable test APIs.

### Phase 6 — explicitly requested device pilot

Use the fixture app first, then one owner-selected app with approved credentials/reset/safety
policy. Device execution requires a separate user request. Validate bounded exploration,
restart/resume, branching, dialogs/forms, and route reproduction on the approved matrix.
Record app/device/build and actual coverage; never claim exhaustive discovery.

Language-specific generators, a graph UI, MCP control, and multi-device exploration follow
only after the core demonstrates useful reproducible routes.

## 17. Verification strategy

- Pure unit tests: state signatures, conservative matching, frontier order, graph edits,
  budgets, scenario expansion, route validity, policy, persistence/export.
- Recorded-observation tests: timestamps/cursors do not create spurious states; validation
  errors/modal conditions are not accidentally erased; uncertain merges stay separate.
- Fake executor/daemon tests: exact-one rejection, stale refs/selectors, failures, cancellation,
  capture drift, interruption at each intent/execute/persist boundary, indeterminate recovery.
- Scripted AI tests: proposal schemas/provenance, caching, invalid responses, adversarial app
  content, secrets, provider failure, and limits.
- Report tests: deterministic sanitized exports and honest verification/coverage labels.
- Device tests only when requested: actual fixture routes and controlled target-app scenarios.

Track: eligible frontier completion, blocked/uncertain counts, route reproduction rate,
selector fragility, recognition disagreements, recovery failures, and AI cost. These metrics
are relative to the configured discovery policy, not a percentage of the app's total behavior.

## 18. Decisions still needed

Before implementation, obtain concrete answers for:

1. Initial target app, environment/account, and what is allowed to execute.
2. Starting-condition and reset capabilities, including backend state.
3. CLI-only first interface versus a required visual review surface.
4. AI provider/model, external-image/data policy, and cost budget.
5. Storage/export format and retention/redaction rules.
6. Human approval workflow for risk, state merges, and robot promotion.
7. Robot output language/conventions and required verification threshold.
8. Whether low-confidence/index-based interactions are allowed during discovery at all.

If external research informs implementation, use the pinned upstream audit commits and its
adopt/adapt/do-not-copy decisions. Existing tools are inspiration, not permission to import
XPath, persistent handles, coordinate actions, or retry-by-default behavior.

## 19. Handoff to the implementing agent

Read this record and the current public client APIs before making a plan. Preserve Tap's
layering, per-serial exclusivity, exact-one mutations, strictly increasing request IDs, and
no-replay-after-acceptance rule. Do not expand synchronization work. Do not treat this proposed
layout/schema as an already accepted public contract.

First resolve Phase 0 decisions with the owner; then implement the smallest vertical slice:
known starting condition → one safe branch → persistent observation/transition → checked
return/replay → honest report. Build outward from demonstrated behavior rather than attempting
an autonomous whole-app crawler in one change.
