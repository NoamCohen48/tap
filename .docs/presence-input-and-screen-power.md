# Accessibility presence, input values and screen power

Status: proposed API; not implemented by this document. The user requested an API proposal,
not SDK changes. No new runtime guarantees or completed acceptance tests are claimed here.

## Problem and current behavior

The existing APIs need clearer guarantees, with new names only where useful:

- Python `ElementWait.visible()` / Kotlin `ElementWait.visible()` wait for at least one
  selector match in the accessibility search, not proof that a finger can reach it.
- `Element.text()` returns raw accessibility text. Android can report a hint as text while
  an input is empty; the existing snapshot also exposes `showing_hint` / `showingHint`.
- `Device.sleep()` injects `KEYCODE_SLEEP`; it does not pause the test.

The driver requires API 26+, where Android exposes the hint flag. `QueryCommands.snapshotOf`
reads raw node text and that flag from the same resolved node. Both SDKs already decode them.
No new driver operation, protobuf field, capability or protocol version is needed for the
proposed SDK additions.

Relevant existing contracts: [app and screen](app-and-screen.md), public
[elements](../docs/guide/elements.md), [waits](../docs/guide/waits.md),
[selectors](../docs/guide/selectors.md) and [device control](../docs/guide/device-control.md).
This proposal does not change selector matching or existing gesture checks.

## Proposed API

New members, with Python seconds and Kotlin `Duration` as in existing methods:

| Owner | Python | Kotlin |
|---|---|---|
| `Element` | `input_value(timeout: float \| None = None) -> str \| None` | `suspend fun inputValue(timeout: Duration? = null): String?` |
| `ElementSnapshot` | read-only property `input_value: str \| None` | computed `val inputValue: String?` |
| `Device` | `screen_off() -> None` | `suspend fun screenOff(): Unit` |

Keep `exists()`, `text()`, `visible()`, `sleep()`, `wake()`, `one()`, `gone()` and the text
waits. Keep `visible()` as the standard wait name; do not add `present()` or a wait named
`exists()`. The existing immediate `exists()` query already serves its role.
Do not add an input-value selector or wait, a generic `interactable()` query/wait, or a
screen-on alias in this change.

### Keep visible, clarify its guarantee

Keep `visible()` unchanged as the standard at-least-one-match wait. User-facing wording:

> Wait until Tap can find the element on screen. This does not guarantee it can be tapped.

Users should not need to understand the accessibility tree to choose a wait. Keep the
implementation details below in advanced documentation, not in API names or routine examples:

- Use the existing `WaitVisible` command with `exactly_one = false`, in one RPC with
  device-side polling and the existing timeout budget.
- Return a lazy `Element`, not a bound node or a guarantee that a later lookup still matches.
- Accept multiple matches; a subsequent mutation must independently resolve exactly one.
- Preserve `NO_MATCH`, existing timeout diagnostics and "to be visible" descriptions;
  wire/event operation names stay unchanged.

Technically, `visible()` means **returned by Tap's UiAutomator lookup for this selector**,
not every view in the app or every node the app could expose. Android/UiAutomator visibility
filtering still applies. Virtualized off-screen rows and fully covered nodes may be absent;
a partly covered node can match while its touch point is obstructed. Do not promise pixel
visibility, enabled state, focus, clickability, touch reachability, animation completion or
application readiness.

`one()` proves cardinality at one poll, not that an action can run. Remove the current public
wording "the wait that proves it can run" and qualify "what tap needs" as only the uniqueness
prerequisite. `enabled()` and `clickable()` are accessibility properties, not touchability
certificates. `gone()` means no matching exposed node, not proof that the app deleted a view.
`App.await_visible()` / `awaitVisible()` concern package/window visibility and are unchanged;
do not rename them as part of the element-presence change.

### Input value and hints

Define a pure projection on `ElementSnapshot`:

```python
@property
def input_value(self) -> str | None:
    return "" if self.showing_hint else self.text
```

Kotlin's computed property is equivalent: `if (showingHint) "" else text`.
`Element.input_value(timeout)` / `inputValue(timeout)` obtains exactly one fresh
`snapshot(timeout)` and returns its computed value. No second read for hint state, no
additional RPC, polling, automatic focus, mutation or local caching.

| Snapshot text | Showing hint | Computed input value |
|---|---|---|
| `"Name"` | true | `""` |
| `"Name"` | false | `"Name"` (including when the hint is also `"Name"`) |
| `"hello"` | false | `"hello"` |
| `""` | false | `""` |
| absent / null | false | `None` / `null` (unknown or not exposed, **not** proven empty) |
| absent / null | true | `""` (the explicit hint flag controls the projection) |

Do not infer a hint by comparing `text` with `hint`, by class name, by nonempty hint metadata,
or by substituting a content description. Preserve whitespace and reported strings exactly.
A nonempty `hint` with `showing_hint = false` does not suppress entered text.

This is **hint-filtered accessibility text**, intended for input assertions, not access to an
app's backing model. Custom widgets, WebViews and password fields may omit, mask or misreport
text/hint state; do not unmask, fall back to shell/IME state, or claim the actual stored value.
The accessor does not certify editability. It applies the same projection to any matching node;
a non-input label therefore retains its text. Enforcing editable-only targets would require
an independently designed editable signal and error contract, not class-name heuristics.

`Element.input_value()` retains `snapshot()`'s timeout and resolution/error semantics:
`NOT_FOUND`, `AMBIGUOUS`, explicit `first()` / `at(n)` choices, and transport errors propagate
unchanged. The computed snapshot property performs no I/O and adds no serialized field or
constructor argument; existing proto/JSON snapshots and recording formats stay unchanged.

`text()`, text selectors, `text_equals()` / `textEquals()` and text-contains waits remain raw
accessibility-text operations. Document explicitly that they can match a hint and must not
be used to prove a value was entered. A future input-value wait must use this projection,
distinguish null from empty, specify its polling/error contract, and test the hint-equals-value
case; it must not silently alias the existing raw-text wait.

### Screen off, not a delay

`screen_off()` / `screenOff()` sends exactly one existing `PressKey(KEYCODE_SLEEP)` command,
using the same budget and error/indeterminate semantics as `sleep()`. Keep `sleep()` as a
compatibility alias to the canonical new method so there is one implementation path.

Document it as **request screen off using Android's SLEEP key**, not a duration, power toggle,
keyguard lock API or confirmed screen-state wait. The key has no effect when already off;
the SDK still transmits the command and does not pre-read/retry it. Completion reports key
injection, not an independently observed power transition. Tests that require the state must
observe `device.info().screen_on` / `screenOn` separately. Waking does not dismiss a keyguard.
Normal `time.sleep(...)` / coroutine `delay(...)` remain explicit delays, not recommendations
for synchronizing tests.

### Interactability is action-specific

Use these separate terms in docs:

1. **Presence:** accessibility lookup returns at least one match.
2. **Uniqueness:** the selector resolves one target (or uses an explicit positional pick).
3. **Reported state:** enabled/clickable/focused flags, without a promise of input acceptance.
4. **Action-specific eligibility:** the target satisfies the checks implemented for that
   particular operation at execution time. A touch gesture checks its actual touch point;
   accessibility set-text is not a touch gesture and has different checks.
5. **Observed effect:** the application reached a separately asserted outcome after the action.

Presence, uniqueness and enabled/clickable flags together still do not prove touchability.
Existing `TouchReachability` checks detect covering windows and the supported DrawerLayout
case, not all same-window overlays or all display/window conditions; retain the limits in
[app and screen](app-and-screen.md#limits). Do not sell those checks as a general hit test.
The `ScreenNode.interactive` snapshot classification is a set of reported affordances, not
proof of action eligibility; clarify its documentation too.

No general `is_interactable()` / `wait().interactable()` is proposed. Before introducing one,
define the action it predicts, geometry/occlusion coverage, unsupported/unknown states,
cardinality, stale-node handling, diagnostics and timeout semantics. Even an action-specific
preflight cannot reserve the UI: the real mutation must recheck, and input is never replayed.

## Compatibility and adoption

This is additive. `visible()` remains the standard name, not a compatibility alias.
Keep `sleep()` working without runtime deprecation warnings or Kotlin deprecation annotations
for now; label only `sleep()` a compatibility name and prefer `screen_off()` / `screenOff()`
in new code. Do not announce removal or change existing meanings.
Avoid a repository-wide mechanical rename of historical examples, wire names or evidence.

During implementation:

- Update Python docstrings and Kotlin KDoc together, including `exists`, `one`, `gone`,
  raw text helpers and snapshot properties. Update public guide tables/examples and
  `coming-from.md`: accessibility presence is not WebDriver visibility or clickability.
- In form examples, assert `input_value()` / `inputValue()` rather than a raw-text equality.
- Update `clients/agent/api-coverage.json`: classify `screen_off` under the existing screen
  tool and `input_value` as a projection of inspection; keep `visible` under `wait_for`. If the
  registry cannot express the computed value through inspection directly, record it as a
  client-only convenience with the derivation, rather than claiming a new agent endpoint.
- Keep agent CLI/MCP `visible` state compatibility, screen `off`, and snapshot output formats.
  Clarify the presence/raw-text semantics in help and references; route screen `off` through
  the canonical SDK method. No new CLI state/tool or recording action schema is required.
- Keep Studio-generated SDK waits using `visible()`. Do not rename recorded actions or
  change replay semantics. Audit form
  assertion export so it does not claim raw-text checks prove entered values; if value
  assertions need a recording-schema change, leave them unsupported pending that design.

## Implementation sequence and acceptance

One implementation task/PR, with SDK code, tests, adapter classifications and public docs
in the same behavioral commit. This proposal does not authorize device tests.

1. Add the computed snapshot properties and element accessors in both SDKs. Add
   `screen_off`/`screenOff`, making `sleep()` delegate without duplicate commands. Keep
   `visible()` and its implementation unchanged.
2. Add fake-daemon regression tests, update guides/KDoc/docstrings and adapter/codegen checks.
3. Run local unit and documentation checks. Only with explicit user permission, exercise
   the fixture on the API 29/API 34 device matrix and record the results.

Required regression cases (not yet executed):

| Area | Acceptance evidence |
|---|---|
| Visibility wait | Existing `visible()` wait unchanged; one RPC; returned lazy selector unchanged; multiple matches pass; no match preserves `NO_MATCH` diagnostics; no new wait alias |
| Uniqueness | `one()` still waits for one match; neither it nor `visible()` is documented/tested as a touchability guarantee |
| Input projection | Both SDKs cover every truth-table row, whitespace, hint metadata with flag false, and entered text equal to hint |
| Consistent observation | Accessor sends one snapshot command with the caller's timeout; computed property sends none; no separate text/hint reads |
| Resolution and errors | Missing/ambiguous targets propagate errors; positional picks preserved; no new retries or mutation |
| Serialization | Computed snapshot property does not alter constructor, protobuf or existing JSON/recording output |
| Raw-text compatibility | Hint still returned by `text()` and matched by text selectors/waits; value accessor alone suppresses it |
| Screen off | New/old names each send one `KEYCODE_SLEEP`, no pre-read, duration, toggle, hidden state wait or replay; errors propagate |
| Adapters/codegen | Coverage registry passes; agent help states the real semantics; generated SDK waits keep `visible()`; existing recording replay remains compatible |
| Optional device form flow | Empty hinted field -> value `""`; enter the literal hint -> that value; clear -> `""`; cover Views and Compose fields where available |
| Optional device touch flow | Partly covered target passes presence/uniqueness but tap remains `NOT_INTERACTABLE` / `OBSCURED`, without input |
| Optional device power flow | Screen-off then separately observe off; repeat while off; restore with wake and required keyguard handling in cleanup |

Local check targets for implementation:

```sh
.venv/bin/python -m pytest clients/python/tests/unit
./gradlew :clients:kotlin:sdk:test
.venv/bin/python -m pytest clients/agent/tests
.venv/bin/python -m pytest clients/studio/tests
# In clients/studio/web, if generated SDK/export code there changes:
bun run test
bun run build
# From the repository root:
scripts/build-docs.sh
```

Before using these commands, confirm module names and dependencies in `CONTRIBUTING.md` and
the current build. No proto stubs, wire golden bytes, release versions or device state should
change for these SDK projections and aliases.
