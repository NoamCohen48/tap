# Custom Android E2E Test Framework — Design Notes

Status: pre-decision design notes. Captures the reasoning from discussion, including
rejected options and open questions.

---

## 1. Motivation

Existing options and why they fail for this use case:

**Appium** — poor scripting ergonomics; slow. Root cause of the slowness is architectural
(see §3.2), not a tuning problem. Broad multi-language support is part of *why* the
experience is mediocre (see §8).

**Maestro** — stable and fast, good developer feel, but:
- Flows are YAML, not a programming language. No loops, helpers, debugger.
- No multi-device support. The flow model is a tape, not a program, so it structurally
  cannot express "device A does X while device B observes Y".

Requirement that neither satisfies: **interleaved multi-device tests** (not just
sequential hand-offs). This is already a first-class concern in the manual QA tooling
(Flowdeck), so it is not an exotic need.

---

## 2. Key insight

Appium and Maestro are the same shape underneath, and it is not a complicated shape:

```
  HOST                                    DEVICE
  ┌────────────────────┐                  ┌──────────────────────────────┐
  │ test logic         │   adb forward    │ instrumentation APK          │
  │ client library     │ ◄──────────────► │  - command server            │
  │ runner / reporting │   (socket)       │  - androidx.test.uiautomator │
  └────────────────────┘                  └──────────────────────────────┘
```

- Appium: `appium-uiautomator2-server`, HTTP/netty over adb port-forward.
- Maestro: its own driver APK, gRPC.

**All intelligence lives on the host. The device side is a dumb RPC executor.**

Both original complaints dissolve as a consequence of this split:

- *Real language* — the host side is an ordinary program. Loops, conditionals, helpers,
  and a debugger are free, not features to be built.
- *Multi-device* — the host holds N clients keyed by device serial. Interleaving becomes
  ordinary sequential code across N concurrent tasks.

Neither is a promise about execution quality, so neither can disappoint.

---

## 3. The two decisions that matter

Everything downstream is shaped by these. Both are cheap on day one and expensive to
retrofit.

### 3.1 Waiting semantics

The framework must have a first-class wait primitive, and it must be shorter to type than
sleeping.

Maestro's stability is largely "every find and assertion retries until a deadline."

**Decision taken: no-retry by default, retry available as an option.** This is a
legitimate choice, but be clear about the tradeoff:

> A no-retry default does not remove the waiting problem, it relocates it into
> `sleep(2000)` calls written by whoever is in a hurry. Those are invisible in review,
> unfixable later, and are the usual reason suites become slow *and* flaky at once.

Mitigation: make `waitFor(...)` obvious and shorter to type than `sleep(...)`. If
flakiness shows up in month two, look here before blaming the framework.

Implementation note: retry must be a **parameter on the primitive**, not a wrapper layer.
That keeps the default flippable later.

### 3.2 Selectors evaluate on the device — never serialize the tree

This is the single most important performance decision.

UiAutomator does not read pixels or touch the app's view tree. It sits on the
accessibility framework. Every property read on an `AccessibilityNodeInfo` is an **IPC
call into the app process**. That one fact explains the entire performance cliff.

**The slow path (what Appium does):**

`dumpWindowHierarchy()` walks every node in every window, reads every property on each,
serializes to XML. On a dense screen: thousands of IPC round-trips, 1–5 MB of XML, crossed
over adb, parsed on host, then XPath'd. **500 ms – 3 s per query — for every single
find.** A 40-step test spends most of its life here.

**The fast path:**

`findObject(BySelector)` runs the same traversal *inside the device process*,
short-circuits at the first match, and reads only the properties the selector actually
mentions. **~50–150 ms.** Nothing serialized, nothing transferred. Same semantics, ~20×
faster, and no XPath anywhere.

Dumps are not removed — they are reserved for the inspector tool (§6, Phase 4), where a
human requests one on demand. **Keep them off the hot path.**

---

## 4. Protocol and element model

### The selector is the wire format

Host sends a structured selector; device resolves it, performs the action, returns a
result. One round trip per action.

```
{ "action": "tap",
  "selector": { "text": "Play", "resId": "com.x:id/play_btn",
                "childOf": { "resId": "com.x:id/hero" } } }
```

### Element = lazy selector, NOT a handle

Tempting alternative: device resolves once, stores the `UiObject2` in a map, returns an
ID; host holds an `Element` with `.click()` / `.getText()`.

**Do not do this.** `UiObject2` goes stale the moment a view is recycled or recomposed,
and the team gets `StaleObjectException` at random.

**Instead:** the host-side `Element` is a value object wrapping a selector, re-resolved
fresh on every use. Handles may exist only *inside* a single compound command on the
device. This is exactly the "just elements" feel that works well in Maestro, and it is why
Maestro's don't go stale.

### Composition without XPath

`BySelector` already chains: `hasChild`, `hasDescendant`, `hasParent`, `hasAncestor`, plus
depth bounds and index. That covers essentially every relational query real tests need.

And because the device side is our own Kotlin: anything `BySelector` lacks ("the button
nearest this label", "next sibling") can be written directly against
`AccessibilityNodeInfo` and added to the protocol. Not constrained by a query language we
didn't design.

### Two gotchas that bite in week one

- `res()` matching needs the **fully-qualified** `com.your.pkg:id/name`, not the bare name.
- **Compose**: `Modifier.testTag` is invisible to UiAutomator unless
  `testTagsAsResourceId = true` is set in the semantics. Otherwise you are matching on
  text and content descriptions only.

---

## 5. Multi-device and synchronization

### Interleaving

One host coroutine per device; structured concurrency for fan-out; `coroutineScope`
cancellation for teardown when one device fails. Interleaving then reads as ordinary
sequential code in a test function.

**The real trap:** per-device timeouts that deadlock the whole test. Give each command its
own deadline and let cancellation propagate.

### Cross-device propagation

"A publishes, B should see it" depends on backend push latency, not on the framework.
Handle with `awaitUntil` on device B. **Never a sleep.**

### The app-side idling hook — highest-leverage item on the list

Since these are our own apps, expose a debug-only busy counter (OkHttp dispatcher +
in-flight coroutines) over a ContentProvider. This is the Espresso `IdlingResource` trick.

~3 days on the app side. Kills the entire class of "assertion ran during a network call".

**This is what would put the framework *above* Maestro on reliability, because Maestro
cannot do it.** It is also the easiest item to skip. If reliability is the priority, do it
in Phase 1, not "later".

Caveat: only works for apps we own. Third-party apps fall back to black-box polling.

---

## 6. Effort estimate

Focused engineering days, not calendar days.

| Phase | Work | Days |
|---|---|---|
| 0 | **Spike** | 1–2 |
| 1 | Device side | 4–6 |
| 2 | Host library | 4–6 |
| 3 | Runner + artifacts | 4–6 |
| 4 | Inspector (optional) | 3–5 |

**Phase 0 — spike.** An `androidTest` APK whose single test method never returns: opens a
`ServerSocket` and loops. Launch via `adb shell am instrument -w`, connect via
`adb forward`. Prove one `find` + one `tap` over the wire. *This is the only part with
genuine unknown-unknowns, and it is what makes everything after it boring. Do it first.*
It also lets you measure the §3.2 speed claim directly.

**Phase 1 — device side.** Selector deserialization → `BySelector`; action set (tap,
longTap, text input, swipe, scrollUntil, waitFor, assertions); screenshot; app lifecycle
(launch / stop / clear-data); `dumpWindowHierarchy` reserved for the inspector. Mostly
thin wrappers over UiAutomator. High AI leverage.

**Phase 2 — host library.** Device discovery and adb plumbing, connection lifecycle, the
lazy-selector `Element` type, the DSL, one coroutine per device. *adb plumbing is fiddlier
than it sounds:* port allocation, orphaned instrumentation processes, reconnect after a
device drops.

**Phase 3 — runner and artifacts.** Test discovery, per-device logcat capture,
screenshot-on-failure, JSON event log, HTML report. Mechanical, high AI leverage, and the
piece testers judge the whole thing by. Video via chunked `adb shell screenrecord` (note
the ~3 min cap per file).

**Phase 4 — inspector.** Dump-based tree browser that generates selector code (the Maestro
Studio equivalent). Skip initially; add when people start asking "why didn't it find the
button".

**Total: ≈3–4 weeks focused → 2–3 months part-time.** Then a genuine long tail of a day
here and there for ~6 months as real screens surface real edge cases. That tail is
unavoidable and is not a sign anything went wrong.

Incremental edge-case work folded into the above: scroll-to-offscreen
(`BySelector.scrollUntil`, `UiScrollable`), permission dialogs (known-selector helper),
keyboard state, animations, ANR/crash detection. ~1 day each, built as encountered, not
upfront.

---

## 7. Fast and reliable?

**Original two problems: solved, solidly.** Both are inherent to the architecture rather
than features to be built.

**Fast: yes**, and verifiable in Phase 0. Should land around Maestro's speed, since it's
doing the same thing Maestro does.

**Reliable: hold this one loosely.** Not because the design is wrong, but because
reliability is not a property of a design — it is the accumulated residue of edge cases.
Maestro is stable partly because thousands of people found its edge cases for them. Ours
get found one at a time, in CI, at inconvenient moments.

Two things working against us specifically: the no-retry default (§3.1), and the risk of
skipping the idling hook (§5).

**Honest expectation:**

- Month 1: flakier than Maestro.
- Month 4: better than Maestro *on our apps specifically*, because we can synchronize
  against internals it cannot see.
- The gap between those two is real work, not a formality.

**What stays genuinely hard:**

- **WebView support** — needs CDP bridging via chromedriver. Defer until actually needed.
- **Apps we don't own** — no idling hook, back to black-box polling.

---

## 8. Multi-language support

Technically trivial: the protocol is the boundary, the device side never changes. A client
is "open socket, serialize selector JSON, read response". ~1 day per language.

**The trap:** Appium's scripting experience is bad partly *because* it supports eight
languages. A protocol designed to be bindable everywhere degrades to the lowest common
denominator — and the ergonomics that make Maestro feel good are exactly what doesn't
survive that. Build for three languages on day one and you'll design the wire protocol
first and the DSL second, landing closer to Appium than intended.

The hard part isn't the RPC client, it's the **concurrency model**, which is where
interleaved multi-device lives:

| Language | Concurrency fit |
|---|---|
| Kotlin | Best by a wide margin. Structured concurrency, cancellation propagation, `coroutineScope`. One device failing tears down the others cleanly. |
| Python | `asyncio.TaskGroup` is a close analogue. Workable. |
| TypeScript | No real cancellation primitive. `Promise.all` doesn't stop other devices when one throws; hand-rolled `AbortController` through every call. Interleaved multi-device is meaningfully worse. |

Each language also needs its own runner integration and reporting (JUnit / pytest /
vitest) — Phase 3 duplicated per ecosystem, not shared.

**Recommendation:** design the protocol to be language-neutral now (versioned, JSON or
protobuf, documented as the contract — costs nothing, it's happening anyway), then ship
**exactly one binding**. Adding the second later is a week, whenever someone actually
needs it, and by then the DSL will have proven itself.

Which one depends on who writes the tests:
- **Android devs** → Kotlin. Shared types with the device side (the selector data class
  can literally be shared), best concurrency.
- **The ~7 manual testers graduating into automation** → Python. Lower barrier, and their
  interleaving needs are probably coarse enough for asyncio.

*Open question: who is actually writing these tests?*

---

## 9. Rejected / deferred alternatives

**Maestro as per-device executor, custom orchestrator on top.** A host program shells out
to `maestro --device <serial> test flow.yaml` per step group, with barriers between them.
Ugly, keeps YAML at the leaves, but delivers multi-device in a day and inherits Maestro's
stability work.

- Covers **coarse-grained** hand-offs (A does a sequence, then B reacts).
- Does **not** cover fine-grained interleaving — which is the actual requirement.

**Keep this in the back pocket.** If a continuous three weeks isn't available, this is the
better option: a half-finished test framework is worse than the YAML being escaped.

**Plain UiAutomator instrumentation tests via Gradle.** Already a real language, no
framework needed — but the test runs *on-device*, so coordinating two devices needs a host
orchestrator anyway. That orchestrator is this project.

---

## 10. Non-technical risk

The real concern isn't difficulty — this is buildable. It's that a tooling problem becomes
**a product the unit owns forever**. Maestro's YAML is annoying; a bespoke framework whose
only expert is one person is a different kind of annoying, and it lands on whoever
inherits it.

Mitigations, cheap if decided now:

1. Keep the device server genuinely dumb, so it stays replaceable.
2. Document the protocol as **the contract**.
3. Get a second person writing tests in **week two**, not month three.

**Boundary with Flowdeck:** the automation layer emits results in Flowdeck's result shape
and stays a separate tool. Resist merging them — that's where scope doubles.

Also worth weighing: this competes for the same hours as Flowdeck and the streaming app.

---

## 11. Where AI help fits

**Good fit:** protocol design, host library, the UiAutomator server APK, DSL ergonomics,
and all of Phase 3. Well-specified code, producible in bulk.

**Weak fit:** flakiness debugging. It's empirical and device-specific, and "it fails 1 in 8
runs" is a category of problem that doesn't respond well to text-based reasoning.

---

## Open questions

- [ ] Who writes the tests — Android devs or the manual testers? (Decides §8.)
- [ ] Is a continuous ~3 weeks available? If not, take the §9 Maestro-orchestrator path.
- [ ] Are all products in Flowdeck's scope built in-house? (Decides whether the §5 idling
      hook covers everything or only part.)
- [ ] Is the no-retry default worth revisiting after the first month of real use?
