# App Explorer — design handout and implementation roadmap

Status: **offline foundation and bounded sample-device pilot implemented and exercised**.
The owner approved the Python/SQLite/JSON core, then requested a sample app and real-device
validation. Two physical-device runs passed; see section 21. General app discovery, AI and robot
generation remain unimplemented. Detailed mechanisms below are recommendations unless recorded
as implemented in the milestone sections. Resolve remaining decisions before expanding scope.

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

The offline milestone below implements a bounded subset of this roadmap. Each phase should
be a separate change with its own tests; record actual results, not just completed code.

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

Offline decisions approved: Python `clients/explorer`, SQLite, versioned JSON, offline core
before device/AI execution. The package is `tap-explorer` (development only, not tagged).
Before live execution, AI, or robot generation, obtain concrete answers for:

1. Initial target app, environment/account, and what is allowed to execute.
2. Starting-condition and reset capabilities, including backend state.
3. CLI-only first interface versus a required visual review surface.
4. AI provider/model, external-image/data policy, and cost budget.
5. Artifact retention/redaction rules and any evolution of the initial export schema.
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

Offline Phase 0 choices are approved. Resolve live/AI Phase 0 decisions with the owner; then
implement the smallest device-connected vertical slice:
known starting condition → one safe branch → persistent observation/transition → checked
return/replay → honest report. Build outward from demonstrated behavior rather than attempting
an autonomous whole-app crawler in one change.

## 20. Offline milestone — implemented and exercised

The owner selected **offline core first**: Python, SQLite, versioned JSON, no device/AI execution.
`clients/explorer` now provides `GraphStore` and a thin local `tap-explorer` CLI:

- One run per SQLite file, whole-document transactional storage and validated
  `tap-exploration/1` metadata import/export; artifact files are not copied or read.
- Immutable observations, explicit state classification/membership, no automatic merging.
- Blocked-by-default candidates and recorded explicit approval.
- Stable priority/depth/source-ID/action-ID scheduling, action and caller-supplied depth limits.
- Intent-before-execution recording, atomic scheduling/budget checks, write serialization,
  immutable completed outcomes, and explicit orphaned-intent recovery.
- Global stop on unfinished/indeterminate attempts; recovery never requeues or retransmits.
- Separately approved new trials, preserving repeated outcomes and self-loops in a multigraph.
- Distinct command success and recognized destination; no route-verification claims.
- CLI init/status/export/import/recover and public development-only documentation.

Verification: **41 explorer tests**, and **168 combined Python client/agent/explorer unit tests**
passed on Python 3.12. Project-scoped Pyright reports zero errors/warnings. Wheel and sdist
build successfully. The Python CI lane installs/builds the package, runs these tests on its
3.10/3.13 matrix, and runs its project-scoped type check (CI results must be checked separately).
Device matrix: **not run**. No device input or AI call was made.

At the offline milestone, deliberately incomplete: coordinator/device executor, state-signature
algorithm, route planner, AI adapter, screen catalog, robot generation, artifact management/redaction, semantic
scenario validation, elapsed/scroll/AI budgets, automatic reconciliation, or tagged release.
Targets/scenarios are opaque offline metadata and must not be fed to an executor without a
future typed validation/policy layer. An uncertain run remains blocked; opening a database does
not assert ownership of an executor or automatically recover it. The whole-document store is
for small offline runs and will need measured scaling work before large app crawls.

That next milestone was subsequently implemented as the bounded pilot below. The generic
state-signature, AI, robot, reconciliation and artifact-redaction work remains outstanding.

## 21. Bounded sample-device pilot — implemented and physically verified

Owner request: create a sample app and test on a real device. The owner also allowed a complex
online APK; the controlled sample was chosen first because it supplies a known correctness
oracle without media/account/permission risks. No third-party APK was downloaded or installed.

### Implemented slice (partial phases 2/3, not general app exploration)

- `samples/explorer-app`: separate native Android AUT, package
  `io.github.noamcohen48.tap.explorer.sample`, no permissions/network/accounts/services or
  persisted values. Eight observable states, seventeen explicitly approved tap/fill scenarios:
  Home, About dialog, Profile empty/error/ready, Welcome, Preferences off/on.
- `tap_explorer.live`: operator-defined screen/action rules; immutable policy names, package
  ownership, unique resource IDs, enabled/capability/selector-quality checks. Password/index
  targets and all unsupported verbs are refused. Unknown controls are never trusted by default.
- Explicit TREE settle, snapshot→PNG→snapshot acquisition and app-node drift checks. Captures
  are not atomic and a changing pair stops execution. All screenshots/nodes stay on disk.
- Configured recognition through resource/text landmarks, then ordinary native selector checks
  of source/state and approved business values (error message, Ada, greeting, toggle). No dump
  on the assertion/action hot path and no persistent UI handles.
- Candidate scheduling through `GraphStore`, graph discovery from actual destinations, BFS
  routing over succeeded observed edges, fresh approved route trials and native checks at every
  step. No inferred Back/reset edge. Divergence stops before the next input.
- Persisted intent before each tap/fill. No mutation retransmission. Known success with failed
  post-observation has no destination and stops; uncertain command failure stops and blocks.
  The pilot refuses existing graphs and a second `run()` call: automatic resume is not implemented.
- `tap-explorer sample`: explicit serial/APK/new output. Sample install/cold-launch/clean stop
  are approved bootstrap calls outside the graph's tap/fill budget and recorded in the log.
  The client never starts/stops the daemon; CI/operator orchestration does so explicitly.
- Fixture oracle checks the full configured state/action/edge sets and invalid-submit self-loop.
  A protobuf-aware command audit verifies every actual input against the ordered persisted
  attempts, exact sample-package resource selectors, no picks, and no evictions/extra inputs.

The configured state names/actions are seeded by a human. **This is not automatic discovery of
unknown screens or intent**, a safe arbitrary-APK crawler, AI vision, or generated robots.
The whole-device screenshot may include status/navigation bars; drift checks concern app nodes,
not a claim that every pixel is static. Unredacted artifacts must remain non-sensitive/local.

### Actual verification

Physical device: **85e49002, Samsung SM-J810G, Android API 29, 720×1480**. JVM daemon 0.0.2,
protocol 5.0, built from this worktree. Sample debug APK SHA-256:
`2abfcadf42aec3b3c9f1f3dc11b2db374d32f60cca57cce6774ef38815082b52`.

| Run | Outcome | States / candidates | Attempts | Actual input | Captures | Time |
|---|---|---|---|---|---|---|
| `device-sample-run-2` | passed | 8 / 17 | 29 | 26 taps + 3 fills | 59 PNG/JSON pairs | 106.186 s |
| `device-sample-run-3` | passed | 8 / 17 | 29 | 26 taps + 3 fills | 59 PNG/JSON pairs | 104.924 s |

Both runs: 17 discovery attempts + 12 route trials; 29 successful observed edges including
repeats/self-loops; zero blocked candidates/uncertain outcomes/log evictions; three approved
bootstrap calls. State/action sets and all 29 ordered action/status/destination outcomes match
across fresh runs. Every PNG header/dimension and snapshot file was checked, all drift flags
were false, and About/error/greeting/toggle images were visually inspected. Native checks
passed independently of the hierarchy-derived classification. Device returned FREE, no explorer
connections remained, and the sample was force-stopped after success.

Evidence (local, ignored, not committed):
`.tap/explorer/device-sample-run-{2,3}/{graph.db,graph.json,events.json,report.json,observations/}`.
SQLite reopen/export equality and a fresh import round-trip were verified. Run 3's report embeds
the command audit; run 2's retained log was audited retrospectively by the same protobuf-aware
checker. No input was invented, retried, or omitted from persisted attempts.

`device-sample-run-1` failed **before any device input**: the initially shared daemon had ended
between inspection and invocation. Its empty graph/failure report is retained. The operator
explicitly built/started this worktree's daemon and used new output directories; the client did
not auto-start or retry a transmitted mutation. This is recorded as an environment failure,
not hidden as a successful device run.

Local checks: **64 explorer tests / 191 combined Python client-agent-explorer tests**, Pyright
zero errors/warnings; sample `assembleDebug` + `lintDebug`; JVM daemon `installDist`; shell/YAML
validation; package build. Fake-daemon coverage includes checked routes, divergence, action
budget exhaustion during replay, ambiguous/rejected/indeterminate commands, frame drift,
unknown post-state, duplicate/disabled/password/index targets, real SetText serialization,
modal priority, resume refusal, protobuf audit spelling, extra inputs and log eviction.

Device matrix: **run on the physical API 29 device only**. Emulator/other real apps/native-image
smoke were not run locally. No reboot, permission grant, clear-data, uninstall or emulator input.
CI now builds/lints the sample and runs a configured API 34 emulator smoke; its result must be
checked separately, not assumed from this physical result.

### Next decisions

Choose a reputable target APK and controlled data/reset/safety policy before generalizing.
Replace seeded recognition/action catalogs with conservative structured discovery and reviewed
AI proposals. Select an AI provider/privacy/cost policy before transmitting any observations.
Do not mistake this fixture result for validated generic app identity, backend restoration,
state merging, multi-package dialogs, infinite-list exploration, or robot generation.

## 22. Discovery/workbench implementation checkpoint (not a completed device milestone)

Operator selected OpenAI vision and the **smallest economical test model only**. The prototype
pins `gpt-5-nano-2025-08-07`, not a flagship, with four calls / 2048 output tokens / low-detail
reviewed images. The snapshot is deprecated: unavailability fails closed, never upgrades.
`OPENAI_API_KEY` is absent, so no real paid call has been made. Fake-provider tests cost zero.

Operator approved unfamiliar-APK checks on **85e49002 only**, restricted: no broad media/storage
permissions, personal-file access, accounts, purchases, deletion or uploads. No unfamiliar APK
has yet been installed or explored in this milestone. Vanilla Music 1.3.2/build 13200 is a
candidate, not a verified artifact; official download/hash/manifest and package absence must
be checked first. Permission barriers may honestly limit the resulting graph.

Implemented, uncommitted checkpoint:

- `discovery.py`: conservative versioned structural/text identity; explicit reviewed volatile
  resource exclusions; snapshot capability-derived blocked tap/fill proposals. Password,
  disabled, duplicate, index and unsupported targets remain blocked.
- `session.py`: fresh run only; native source/selector checks; reviewed finite fill scenarios;
  durable intents, append-only outcomes, finite action/depth/elapsed bounds, uncertainty stop;
  checked observed routes, no invented Back/reset or mutation retransmission.
- `ai.py`: strict structured proposals/local validation, no tools/selector/approval authority;
  durable bounded calls, review gate, image/evidence/model/prompt-bound cache and no retry.
- `web.py` plus `clients/explorer/web`: separate graph-first viewer/review session, single-worker
  SDK/SQLite ownership, loopback Host/Origin/token guards, constrained screenshot paths, CSP;
  React Flow/Dagre, retained screenshots, state search, attempt selection, selector provenance,
  explicit approvals and one-at-a-time execution. Offline system fonts and Tap palette; panel-local
  scrolling, keyboard access, light/dark and mobile details. Browser metadata is schema-validated.
- CLI `view` acquires no device; `explore` requires named serial, trusted APK, absent installed
  package and new output. Clients do not start the daemon. Built static assets go in the wheel,
  not git. Wheel/sdist and an isolated no-SDK viewer initialization passed; CI integration
  still needs its final verification.

Checks so far: 108 explorer / 235 combined Python client-agent-explorer tests, zero Pyright
errors/warnings; six frontend projection/boundary checks and production build. Loopback security/read-only/artifact-path tests use no device.
Playwright inspection of actual `device-sample-run-3`: eight nodes, seventeen observed edges,
twenty-nine retained attempts, no browser errors; light/dark screenshots, state search and
attempt selection passed; 390×844 mobile details had no horizontal overflow. No new device
input or AI call occurred. Local screenshots: `.tap/explorer/workbench-{light,dark,mobile}.png`.

Outstanding before publishing this milestone: final frontend/error/approval regression coverage,
CI/docs checks; pinned unfamiliar-APK restricted physical validation; actual nano call
when a credential is supplied. Robots/Page Objects remain a later evidence-and-contract phase,
not output generated from unverified proposals. This checkpoint is not proof of arbitrary-app
understanding, backend restoration, business assertions, or complete app coverage.

## 23. Complex state-machine assessment and physical evidence

The operator requested a measured assessment of heuristic discovery on complex logic, then
explicitly approved fresh cold-launched trials of the disposable fixture after uncertainty.
No uncertain run was resumed, no transmitted mutation retried, and no personal app, media,
permission grant, clear-data, uninstall or reboot was used. No real AI calls were made.

### What was tested

`samples/explorer-machine` is a separate memory-only, no-permission Android AUT. Its ordinary
UI exposes a branching request wizard: empty/valid names; Basic/Pro plans; fictional terms;
conditional errors; plan details; retained edits; review/receipt; cancellation and fresh entry.
There are no explorer state IDs in its UI. The core receives no screen catalog or transition
map: snapshots derive state signatures and controls, and routes come only from observed edges.

Execution authority is **not unseeded**: a fixture-only reviewed resource allowlist authorizes
local tap/fill controls, with two finite literal values (`""`, `"Ada"`). The final business-flow
policy excludes text-field focus taps. Those proposals remain blocked and visible; no cursor
window is silently removed from signatures. This is not a safety policy for downloaded apps.

Offline fake-gRPC traversal resolves 30 oracle-visible states, 120 distinct edges and 245
executions (including focus taps), with zero measured false UI merges/splits. Separate hidden
history tests deliberately reuse identical UI with different outcomes: unseen divergence stops
a checked route after the command; known multi-destination source/action pairs are excluded
from route planning before input. This does not discover invisible backend state magically.

### Preserved failures and fixes

| Physical run | Result | States / attempts | Lesson |
|---|---|---|---|
| `native-machine-run-1` | failed before install/input | 0 / 0 | API 29 `pm path` reports absent package as exit 1 with empty output. |
| `native-machine-run-2` | stopped on uncertain text clear | 8 / 23 (22 succeeded, 1 indeterminate) | Reapplying an already exact package predicate forced traversal; stale AndroidX nodes must not trigger replay. |
| `native-machine-run-3` | stopped on route divergence | 24 / 126 (all command successes) | Focus taps produce transient cursor-window outcomes; an observed edge is not a deterministic guarantee. |
| `native-machine-run-4` | finite elapsed budget exhausted | 36 / 269 (268 recognized destinations) | The last known-success command has no post-capture. Six extra states were real fixture validation-message variants, not heuristic splits. |
| `native-machine-run-5` | **reviewed frontier resolved** | **30 / 236** | Fixed fixture, business-controls policy and cached graph reads; complete bounded run. |

Host fix: `Adb.isInstalled` accepts only exit-1/empty-output as known absence; other nonzero
results remain typed errors. Host regression tests and physical missing=False/existing=True
queries passed. Only the owned, idle daemon was explicitly restarted by the operator flow.
Python fix: package binding is idempotent only for an already guaranteed top-level exact owner
(including unspecified/exact semantics). Picks, OR branches and different-owner constraints
are preserved. No driver retry or postcondition assumption was introduced. Inspected the
pinned AndroidX 2.4.0 source artifact; a fresh set/focus/clear probe passed.

Fixture fix: inside `CheckBox.apply`, unqualified `error = ""` updated the widget's error rather
than `MainActivity.error`. The discovered extra validation states exposed this bug. Explicit
activity assignment fixes it; only the owned no-permission fixture APK was updated.

Performance: the saved run-4 graph is about 10 MB. Ten complete reads took 2.930 s; ten warm
reads with validated immutable JSON caching took 0.522 s on this machine. SQLite data_version
and connection total_changes invalidate the cache for external/own writes. Detached exports,
rollback, corrupt writes and cross-connection intent fencing are tested. The one-row JSON
storage model remains a small-run foundation; this is not a large-app scalability claim.

### Final physical result

Samsung SM-J810G/API 29, **85e49002**, 720×1480. Fixed APK SHA-256:
`01b3484a9aed3d5431a0a3096b6d5c18ec71e40890bffa6bf2c43e4b528b2283`.
Earlier fixture APK SHA-256:
`d7e7b0d27ebfe7f4572a2542c0dbef18bca59873df56ad5039dfb2573dac66b6`.

Final run: **30 UI states, 108 distinct labeled transitions, 236 successful mutations,
473 stable PNG/snapshot pairs, 1121.367 seconds**, no behavioral conflicts, uncertain outcomes
or log evictions. The protobuf event audit matches all ordered mutations to persisted attempts.
All 473 PNG headers/dimensions, snapshot files, stability flags and SQLite reopen/export
identity passed. Conditional error, retained-edit error and Pro review screenshots were inspected.
Page variants: Home 1, About 1, identity 12, options 5, details 5, review 3, receipt 3. Thirty
visible business-feature groups map one-to-one to the thirty final UI states; this is specific
to the fixture, not proof of hidden-state equivalence elsewhere.

A separate **seeded business oracle** passed 11 assertions in 21 audited mutations: validation
recovery, terms gating, retained choices/name, text clear and Basic/Pro paths. It is independent
of discovery classification and is not supplied as the explorer's transition map. Final fixture
force-stop succeeded; phone returned FREE with no remaining connections.

Evidence, local and ignored: `.tap/explorer/native-machine-run-{1,2,3,4,5}/`,
`native-machine-business-check-1/`, `text-clear-probe-1/`, and the explicit fixture update log.
Local checks: **245 combined Python tests**, explorer Pyright clean, machine assemble/lint,
host AdbTest suite and daemon distribution. Workbench browser check against the final native
graph rendered 30 nodes/108 edges with no errors, five plan variants searchable and attempts
inspectable; screenshot `.tap/explorer/native-machine-workbench.png`. The dense overview is
still visually crowded; variant grouping and route-focused views need improvement. No
emulator/reboot/real-provider matrix was run.

**Assessment:** the mechanism handles this finite visible-state wizard, conditional branches,
cycles, invalid inputs and retained edits under reviewed execution. It is still slow (18.7 min),
can over-explore transient UI, and cannot establish Markov identity or business correctness from
screenshots alone. Unfamiliar APK validation, real nano integration, broader control types,
robust context/belief-state reasoning and robot generation remain separate outstanding work.

## 24. First real-world app trial: Loop Habit Tracker — partial, not proven

The operator chose a reputable open-source APK under the existing restricted physical scope.
Selected **Loop Habit Tracker 2.3.1 / 20301**, `org.isoron.uhabits`, from
`https://f-droid.org/repo/org.isoron.uhabits_20301.apk`. SHA-256:
`3903d3be3db0f1622ad61cf5bb267515c95a4069e8dd46f73249721b1bb8173e`.
F-Droid build metadata pins upstream commit
`516bf394f85a5a3ab25f476da230ae2a93815a40`. APK signature verification passed;
recorded certificate SHA-256
`9f338a863b0152c881e04954d07ae67f62afd11e064fb3ac99a8bd1d2842a395`.
Acquired over official HTTPS; no separate signed-index/keychain verification was performed.

Inspected actual manifest: no Internet, storage, contacts, location, camera or microphone
permissions. It declares notification/boot/alarm/vibration permissions. On API 29 we did not
request runtime permissions, configure reminders or schedule them; form reminder values stayed
**Off**. Queried the named physical phone only: FREE/no connections and package absent before
installation. No existing installation was replaced. Created three explicitly fictional local
habits; no personal app/files, account, deletion, import/export, external link, upload, clear-data,
uninstall, reboot or coordinate input was used. No provider call or expensive model was used.

### Failure that the fixture missed

Initial capture discovered a swipe-only onboarding capability with verb `unsupported`.
`GraphStore` rejected that diagnostic verb, causing the initial session to fail **before any UI
input**. Retained run-1 graph/evidence/events. Added the diagnostic enum value to the offline
format validator; it stays blocked and `DiscoverySession.approve`/`step` cannot execute it.
A fake-gRPC session regression verifies persistence, refusal and continued supported navigation.
The JSON change is additive; no protobuf/driver protocol edit was made.

Stopped the initial owner. A new graph/connection/cold launch used only the installation whose
APK hash and lifecycle were recorded in run-1; no reinstall, replay or old graph resume. Loop
had marked onboarding as seen on the first launch, despite no UI action. The fresh starting
condition was therefore the empty habit list, not a restored onboarding state.

### Native trial and independent observations

**Samsung 85e49002/API 29: 24 recorded states, 24 distinct transitions, 25 successful mutations
(18 taps, 7 set_text), 54 stable PNG/snapshot pairs.** Every ordered native mutation matches its
persisted approved selector/value attempt, with exact package binding, no picks, no log
evictions, no uncertainty and a recognized destination. Graph reopen equality and all retained
PNG dimensions/snapshot files passed. There was one fresh checked route invocation; no
transmitted command was retried. Already-attempted direct approval was refused before input.

Core recognition received no screen catalog/transition map. The **operator chose the flows and
individually approved input**, from retained snapshots/screenshots; this is not autonomous full
frontier discovery. Separate post-run assertions never fed the classifier or scheduler.
Fourteen checks passed: Yes/No versus Measurable field shape; blank-name Save remaining in the
form; retained fictional name; 3/week radio selection and saved frequency; reminders Off;
saved binary record; numeric fields; zero-target acceptance (observed, not assumed invalid);
At most comparison; weekly period; target 5/unit retention; and all three saved fictional names.

### Accuracy gaps: do not call this proven complex-app support

1. **Missed validation variant.** Blank-name Save displays a red error icon in the retained PNG
   but exposes no changed text/flags in our features. Pre-error and error observations share the
   same state signature. The refusal was visible, but the heuristic did not represent this
   visual-only error state. Screenshot retention is not accurate classification by itself.
2. **Transient splits.** `Habit created` toast text appears in signatures; read-only settled
   observations then classify separate states of the same underlying saved list. No broad
   text/resource suppression was applied; unsafe suppression could hide real dialog errors.
3. **Control coverage barrier.** Habit/day custom views expose index-only selectors; habit
   title labels are noninteractive in the snapshot and yield no detail-opening candidate.
   Filter rows (Hide archived / Hide completed / Sort) expose indexed clickable ancestors;
   child checkbox diagnostics are unsupported. They were **not executed**. No coordinates,
   direct injected selector, invented Back or index workaround bypassed the gates.

Thus forms, nested frequency/radio dialogs and local conditional values worked, but habit
history/details, check-ins and filter branching were not validated. There is no justified
whole-app accuracy percentage, complete coverage claim or claim that 24 equals all real states.
The evidence supports a narrower conclusion: native execution of addressable reviewed controls
is reliable in this trial; recognition and proposal coverage need additional real-app work.

At the barrier, gracefully closed the owner, then explicitly force-stopped only this newly
installed owned app under a separate audited connection. Phone FREE/no connections; fictional
records remain installed, not deleted. Artifacts: `.tap/explorer/real-app-loop/` (`provenance`,
manifest/signature reports, failed `run-1`, fresh `run-2`, post-run `score.py`/report and explicit
cleanup log). Local suites: **246 Python tests**, explorer Pyright clean. Address validation
metadata, meaningful native child/ancestor selectors and transient context with regressions
before extending the reliability claim. Existing UI graph density is a separate presentation
issue; collapsing variants visually must not silently merge their execution identities.

## 25. Offline identity regression foundation

Following the reviewed report/assessment in `app-explorer-review/`, the first implementation is
`clients/explorer/tap_explorer/benchmark.py` and the versioned corpus under
`clients/explorer/benchmarks/`. `tap-explorer benchmark --corpus ...` uses only standard-library
code. No device/provider call, database write, execution identity change or coarse route was
introduced. The human-review delivery plan now puts measurement before automatic refinement.

`tap-discovery-corpus/1`: **114 retained observations**. Wizard first/last captures of each native
state give 60 independently fixture-oracle-labeled cases (7 screen families, 30 variants).
All 54 Loop observations have **provisional analyst** screen/condition annotations, not human
approvals. The labels preserve literal form bindings and distinguish the inspected name-error
icon; presentation-only list labels collapse record data/creation toast feedback. Source
Graph/APK hashes pin the annotation ranges. No APKs, screenshots, device serials or absolute
artifact paths are committed. Original retained features remain unchanged.

The baseline experiment confirms the report's counts but exposes why counts alone are unsafe:

- Wizard exact screen hypotheses: 30 groups vs. 7 labels, 368 split observation pairs; exact
  variants retain all 30 source-defined conditions with zero measured merge/split pairs.
- Loop skeleton hypotheses: 8 groups vs. 8 labels, **yet 4 wrongly merged screen pairs and 18
  split pairs**. Target-comparison and numeric-period menus share the same skeleton; list/toast
  observations create an extra skeleton. A matching total conceals both mistakes.
- Loop exact variants miss **4 error-icon pairs** and split 31 presentation-equivalent pairs.
- Treating wizard skeletons as variants would wrongly merge **368 condition pairs**. The
  skeleton-plus-exact baseline fixes only wizard presentation grouping, not missing Loop signals.

Metrics use Counter algebra checked against independent pair enumeration; reports include
per-source results, provenance/omitted-label counts, corpus digest and example case IDs. Ratios
with no denominator are null. They are dataset-weighted pair metrics, not app accuracy, whole
coverage, independent-trial reliability or proof of hidden-state equivalence. Policy prediction
receives features only, not ground-truth labels. Bare Python without site packages passes.

Local checks: **274 combined Python tests**, explorer Pyright clean, pinned corpus rebuild
identical to retained corpus JSON. Selector stability, assertion discrimination, visual false-alarm
rates and held-out/fresh robot verification were not measured at this checkpoint.

## 26. Similarity candidate and operator decisions

The follow-up review supplied a real competitor and corrected two points: "Habit created" is
an app-window **Snackbar** (`snackbar_text` in saved nodes), not a toast; and single-link
connected components are order-independent, but permit transitive chaining. Its proposed
0.9 structure / 0.5 text thresholds are now a fixed **offline candidate**, not a live policy.

`similarity.py` re-clusters all observations with deterministic complete-link agglomeration.
Every cross-cluster pair must pass both thresholds; canonical case-ID tie-breaking and explicit
A~B, B~C, A!~C/shuffle tests guard chaining and arrival-order dependence. Text exclusions use
recorded fills, not hand-entered values or truth labels. Exact variant features are preserved.

Corpus v2 keeps the same 114 cases/labels and adds complete owned raw snapshot nodes, including
bounds and native selector proposals. Foreign phone status-bar/notification text and selectors
are omitted; original snapshot SHA256 and removal counts are recorded. The builder checks
owned snapshot features against the pinned graph. The compact ~2.8 MB self-contained JSON is a
deliberate alternative to an enormous pretty-printed feature file plus duplicated raw evidence.

On supplied labels, similarity yields **7 wizard families / 8 Loop families, zero screen
merge/split pairs**. Variant errors are unchanged: Loop still has **4 merge / 31 split pairs**.
This reproduces the review's screen result without single-link chaining; it does not establish
independent accuracy. Reports now count unique merged truth-family pairs, impure prediction
clusters and split truth families alongside pair counts, separately per source. Any screen
merge fails the gate; `--require-no-screen-merges` returns 1, regardless of split improvement.
A deliberate typed-literal/static-control collision test demonstrates a remaining merge failure.

No human review or held-out set exists yet. Loop annotations—including the hard-coded error
range—stay provisional. Screen/condition labels are not robot-class ground truth; shared dialog
helpers need a separate reuse scope. Raw evidence enables future selector/landmark checks but
does not implement or prove them. Live execution identity, routes and approval gates are unchanged.

The operator approved a **separate disposable emulator with its own snapshot restores**,
deferred AI, approved diagnostic-only `error`/`content_invalid`/`showing_hint` driver enrichment,
and agreed to personally review labels when the compact review view is ready. Existing/shared
emulators and the phone remain restricted; broader app risk policies need separate review.
Details: [operator decisions](app-explorer-review/operator-decisions.md). No emulator or driver
change is in this offline PR scope. An explicitly unverified Loop robot draft should follow
this step, not wait for every later gap to close. Reports/assessment copies remain as requested.

Local checks: **298 combined Python tests**, explorer Pyright clean, corpus rebuild byte-identical.
No new device input, reset, app installation, provider calls or generated robot execution.

## 27. List rows, role-normalized variants and robot drafts

**List-row targets.** Loop's habit rows have no clickable node with a label, so discovery found
no way to open a habit. `discovery.py` now adds two proposals with their own provenance:

- `ancestor-text/1`: the clickable ancestor carrying the label, as
  `class_name(cls).clickable().has_descendant(text(label))`. It is used only when exactly one
  such ancestor exists.
- `list-row-text/1`: the label's own selector, for a row of a RecyclerView/ListView/GridView
  that has no clickable ancestor inside the row.

Both still pass the normal `_quality` blocking. Before input, `session.step` now re-derives
candidates from the fresh snapshot and requires exactly one unblocked candidate with the
approved verb and selector. It does not compare frame JSON. The exact-one `wait(...).one()`
check and the pick check stay.

**Role-normalized variants (`similarity-roles`).** Variant identity now uses
`similarity.variant_items`:

- list rows collapse to empty/nonempty;
- Snackbar views and their content-free wrappers are dropped;
- editable values stay literal input bindings;
- typed echoes become `<typed>`;
- checkable, checked, selected and enabled state are kept, as are captured error and
  content-invalid.

On corpus v2 this removes all 31 Loop variant splits. Screens are unchanged (0/0), and the
4 error-icon merge pairs remain until the approved diagnostic driver capture of `error` /
`content_invalid` / `showing_hint` lands. The rules are in-sample.

Complete-link clustering is now a heap with Lance-Williams updates over passing pairs, after
deduplicating identical evidence. A randomized test checks it against the reference O(n^4) scan
and it clusters 1,200 observations quickly. Membership is unchanged.

**Robot drafts (`tap-explorer robots`, `robots.py`, `tap-robots/1`).** These implement section
14's language-neutral document plus a Python emitter. All output is an unverified draft.

- One robot per similarity screen, named from the first static title its observations share.
  A clash adds the first text unique to that screen; a title-less dialog or menu is named from
  its options.
- `verify()` is a greedy discriminating set of up to three unique-once predicates, with a
  review item when other observations still match it.
- Methods come only from succeeded attempts. They are named from the app's resource ID, with
  `button`/`action`/`Input`/`Picker` affixes stripped, and fall back to the label. List-row
  targets take a `label` parameter.
- A control with several observed destinations becomes one method per outcome. Each records the
  greedy conjunction of source facts that separates it from the other outcomes, preferring
  inputs, then control state, then text. An input that held a value where the other outcome
  had it empty reads `X is not empty`.
- Expectations are screen-level and come only from what varied:
  - several text values give `expect_<id>(value)`;
  - varying checked or enabled state gives `expect_<id>_checked(checked)`;
  - a text that appeared only sometimes gives a named check plus `expect_no_<id>()`.

  Inputs the test sets are never expectations.
- `review.json` (`tap-robots-review/1`) renames, drops, confirms preconditions and promotes
  expectations. It is applied on every regeneration and never rewritten.

Results from retained runs (no device use):

- **Wizard** (run 5): 7 robots, 21 methods, 10 expectations.
  - ChooseAPlan "Review" is rejected when `terms unchecked and tier checked`, which is the
    fixture's actual rule.
  - Its forward outcome is a disjunction, so the draft reports "no common source condition"
    instead of guessing.
  - YourName "Continue" is rejected when `name is empty`, and the forward outcome needs
    `name is not empty`.
  - The 8 summary-text variants collapse into `expect_summary(value)`.
- **Loop** (run 2): 8 robots, 18 methods, 5 expectations. "SAVE" is rejected when
  `nameInput is empty`, and the frequency dialog radio states became checked expectations.

These come from one run per app and are still hypotheses for review. No generated robot has
been executed yet.

**Next steps: interactive, human-approved exploration around the draft.**

1. Run explore, then generate the draft, then review it. The reviewer answers `REVIEW.md` items
   in priority order: landmarks, outcome preconditions, selectors, inferred targets, frontier,
   names.
2. Turn the review into exploration intents. A confirmed precondition becomes a targeted trial
   on the counter-case (for example `name is not empty` with terms unchecked). Approving a
   frontier control schedules it from its robot.
3. Re-explore only those intents, regenerate, and review the diff. Show confirmed items as
   settled, and reopen a confirmed precondition when new evidence contradicts it.
4. Replay each robot method on the disposable emulator snapshot (`verify()` → action →
   destination `verify()`). A method becomes *verified* only after it passes from a restored
   snapshot.
