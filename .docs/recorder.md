# Recorder — Tap Studio, an interactive inspector and action recorder

Date: 2026-09-29. Decision record, **accepted** 2026-09-29 (owner approved the design and the UI
demo and asked to start). Owner answers of 2026-09-29 are folded in (separate package, React
front end on a Python back end, a live screen as paired frames for v1, JSON output only, one
device, experimental). The screen research and its decision are in `screen-streaming.md`; the
clickable UI demo is `studio-demo.html` (open it in a browser; a mock app, no device).

Tap Studio (`tap-studio`) is a new client: a local web app that shows a device's screen live,
overlays its accessibility nodes, lets the user act on them by clicking in the browser, and
records each action as an element interaction (`tap res("search")`, `set_text res("query")
"jonson"`), never as coordinates. The output is JSON meant to become a test or a replayable
script. It is the reverse of callstack/agent-device's demo: there an agent drives the phone and
a human watches; here a human drives through Tap and Tap writes down what they did.

**Ground rule (owner, 2026-09-29).** Tap is alpha: nothing here is constrained by backward
compatibility or by what is quickest to build. Where the right design needs a daemon, proto or
driver change, it gets one.

## Research summary

### Recorders and inspectors

| Tool | What it is | Taken / avoided |
|---|---|---|
| Maestro Studio | Was a browser UI served by the CLI (Ktor back end, React front end): screenshot + element overlay, click to build a command, record into a YAML flow. Removed from the CLI in May 2026 in favour of a desktop app (Maestro `f0da81b`). | Taken: overlay + click-to-act loop, selector suggestions. Avoided: YAML output, implicit settle/retry. |
| Appium Inspector | Desktop/web app: screenshot, element tree, suggested locators, optional recorder generating client code | Taken: inspecting is the everyday use (hover → selector, match count). Avoided: XPath suggestions, coordinate taps, element handles. |
| Espresso Test Recorder (Android Studio) | Recorded on-device interactions into Espresso code; no longer offered | Lesson: actions without waits or assertions, with fragile locators, do not survive as tests. |
| openatx weditor (uiautomator2) | Browser inspector with click-to-snippet | Taken: snippet ergonomics. Avoided: coordinates and XPath. |
| callstack/agent-device | Agent CLI/MCP: `@eN` refs, settle-then-diff, replay scripts | Already adapted in `agent-surface.md`; the recorder reuses those daemon pieces. |

### How the others show a live screen

See `screen-streaming.md`: Maestro's web Studio (paired snapshot + screenshot frames over
server-sent events), Appium's in-driver MJPEG, scrcpy's encoded video and `screenrecord`, with
the options for Tap and why v1 uses paired frames.

## What already exists

| Studio need | Existing piece |
|---|---|
| Nodes to overlay, with bounds and "can I act on it" | `DeviceService.ScreenSnapshot`: `ScreenNode{ref, window_package, class, resource, text, desc, hint, bounds, flags, password, interactive, change}`, `rotation` |
| An element identity, not a point | `ScreenNode.selector`: synthesised by the daemon, unique in the dump (resource id → text/desc/pairs/hint → + ancestor → `At` pick, flagged `by_index`) |
| Identity that survives refreshes | refs aligned across snapshots, never reused for another node |
| Performing the action | `ResolveRef` → ordinary `Execute`; the driver still demands exactly one match |
| A still picture | `DeviceService.Screenshot` |
| A session that outlives the page | held connections (`ConnectRequest.hold`, `resume(name)`) |
| A raw record of what ran | the per-connection event log (`Events`, `tap-events/1`) |

v1 needs no new daemon RPC for the screen: the paired-frame loop is `ScreenSnapshot` +
`Screenshot` (decision 3). The one daemon change is selector candidates (decision 8).

## Decision

1. **The user acts in the browser, never on the phone.** A click on the screen view is
   hit-tested against that frame's snapshot and selects that node; the user then picks the step
   to record in the composer, which sends it through the normal `Execute` path (2026-10-02:
   clicks no longer act, see "Select, then choose" under The UI). Recording touches made on the device would need input or
   accessibility-event interception and a guess at which element a point meant — the
   coordinate recording this client exists to avoid. The picture is for the user; the snapshot
   is what the user acts on.

2. **A separate client package, `clients/studio` (`tap-studio`).** Its own release family
   (`release-engineering.md`), experimental like `tap-agent`. It depends on `tap-e2e` only;
   nothing under `host/` knows about it.
   - **One contract: `clients/studio/proto/studio.proto`** (`tap.studio.v1`, owner 2026-09-29).
     It defines the page's API (`StudioService`) and the `tap-recording/1` document
     (`Recording`), and imports `tap.v1` from `contracts/proto`, so selectors, commands and
     errors are the daemon's own messages, typed on both sides. Both sides are generated from it
     (`scripts/gen_protos.py`, committed output, `--check` in CI), as the repository already does
     with one proto schema. Protobuf was chosen over FastAPI + OpenAPI (the first plan): with
     OpenAPI the embedded `Selector`/`Command` were untyped JSON to the page, and the page must
     show, edit and count selectors. If protobuf proves a burden, the fallback is FastAPI with
     OpenAPI → TypeScript through orval (it needs no TypeScript compiler API).
   - **Back end: Python** on `tap-e2e`: Starlette on uvicorn, loopback only, serving
     `StudioService` over **Connect** (connect-python, beta, pinned to its minor release), the
     login route and the built page. A per-launch token in the link it prints (`/login?t=…`)
     becomes an HttpOnly SameSite=Strict cookie; a host check (DNS rebinding) and a same-origin
     check (other localhost ports share cookies) guard every request. The daemon's token never
     reaches the browser. `tap-e2e` is synchronous; blocking calls run in a thread pool and the
     frame loop on its own thread. The generated Python imports `tap.v1` from `tap-e2e`'s
     public `tap_e2e.proto` (its generated modules), because protobuf registers each `.proto`
     file once per process.
   - **Front end: React + TypeScript**, built with Vite, with **Bun** as package manager and
     script runner and **TypeScript 7** as the type checker; messages and the client from
     protobuf-es + Connect-Web. The built assets ship inside the wheel, so users need no Bun or
     Node; they are needed only to develop the page. CI builds the page before the wheel and
     fails on type errors.

3. **The live screen is paired frames (v1, `screen-streaming.md`).** The back end loops
   *snapshot → screenshot* on the attached device and pushes each pair to the page as a
   `StudioService` server stream (the PNG as protobuf `bytes`), as Maestro's web Studio did with
   server-sent events. Each frame is a picture and the nodes it shows, so the
   overlay is aligned by construction. Actions take priority: the loop pauses while an action is
   in flight (the driver has one command executor, so a frame must never queue ahead of the
   user's tap) and resumes after the post-action settle. The rate adapts: fast while the
   snapshot diff shows changes, slower on an unchanged screen, stopped when no page is open.
   Encoded video (`DeviceService.ScreenStream`, scrcpy or `screenrecord`) is a later layer on top
   when this proves too slow; it would not replace the loop, because the overlay and clicks still
   need snapshots.

4. **Clicks are resolved on the newest frame.** A click is hit-tested against the snapshot of
   the frame the user clicked on. A frame taken while the screen was changing is marked moving
   and its overlay drawn as provisional. A stale overlay can at worst make an action fail
   cleanly: the driver still demands exactly one match and answers `AMBIGUOUS` / `NOT_FOUND`
   before input, so it never hits the wrong node. No dump on the action path (the action is
   `Execute` with a selector).

5. **The recording is the studio's own, not the daemon's event log.** The studio keeps an
   ordered list of *steps*, because a step carries intent the daemon log cannot: inferred waits,
   assertions, the selector the user chose, secrets, notes. The studio's housekeeping calls
   (settles, snapshots, counts) are not steps. The daemon event log stays the raw audit trail.

6. **Every action is preceded by a wait the recording can justify.** Tap has no implicit waits,
   so bare taps fail with `NOT_FOUND` on the next screen when replayed. When the user acts on a
   node, that node was on screen and matched once by its selector, so the step records
   `await(selector).one()` then the action. This is evidence, not a guess. No sleeps are ever
   recorded. A selector with a `first()` / `at(i)` pick matches several nodes by design, and the
   driver's waits count every match whatever the pick, so it records `await(selector).visible()`
   instead and the action applies the pick (found on the device run, phase 6).

7. **Assertions and waits are first-class steps, and they are different steps.** An
   *assertion* checks the screen as it is now and fails at once (exists, enabled / disabled,
   checked / unchecked, focused, text equals / contains, count), the SDKs' queries under
   `assertTrue` / `assertEquals`. A *wait* waits up to the device's wait timeout for something to
   happen: the SDKs' element waits (visible, exactly one, gone, enabled, disabled, checked,
   unchecked, focused, text, count) and the app waits (`awaitVisible`, `awaitScreenStable`,
   `awaitSettled`, `awaitAnimationEnd`). Until 2026-10-02 the recording's "assertion" was the
   element wait; the owner asked for the two to be separate (an assertion that waits hides a slow
   screen, a wait that is called an assertion reads wrong in a test).

8. **The selector is shown and editable before it is recorded.** Hover shows the synthesised
   selector and its live match count (`Count`). The UI warns on `by_index` picks and on text that
   looks dynamic (digits, dates, prices) and offers the other candidates (resource id, text,
   desc, with an ancestor). Candidate generation belongs in the daemon's synthesis
   (`SelectorSynthesis.kt`), which already computes them and checks uniqueness, so
   `ScreenNode` gains the ranked candidate list rather than the studio re-deriving it. Nodes
   without a selector are shown but cannot be acted on: they are the app's accessibility gaps,
   surfaced rather than worked around.

9. **Every interaction is element-relative.**
   - click → `tap`; long-press → `long_tap`;
   - typing into a node → `set_text` (default) or the SDKs' element `typeText` shape (tap,
     await focused, `type_text`);
   - scroll up / down / left / right on a scrollable node → `scroll` on that node; swipe in any
     direction on a node → `swipe`; *scroll until* a target selector is in a scrollable node →
     the SDKs' `Element.scrollUntil` (a `scroll_until` step);
   - back / home / recent apps / enter → `press_key`; the notifications and quick settings
     buttons → `open_system_panel`;
   - app lifecycle buttons → launch, cold launch, force stop, clear data, grant permission.
   Nothing takes a coordinate.

10. **Output: `tap-recording/1` JSON only** (format below). Steps embed the `tap.v1` messages in
    proto3 JSON (as `tap-events/1` does), so any language can read them and a step replays by
    sending its messages unchanged. No Kotlin or Python is generated (owner, 2026-09-29);
    conversion is the user's or an agent's job.

11. **Secrets.** Typed text is recorded verbatim unless the step is marked secret; then the
    step names the secret (`"secret": "password"`), the command carries no text, and the real
    value goes to the device but is never written. A replayer supplies the value by name. A
    named field rather than `${password}` inside the text: nothing to escape, and a literal
    `${…}` typed by the user stays literal. Password nodes (`ScreenNode.password`) default to
    secret.

12. **One device per recording** for now; multi-role recording is a later extension of the
    step model (a `role` per step).

## The UI

The first version followed the demo (`studio-demo.html`): a click mode in the top bar (Act /
Assert / Inspect) decided what a click on the screen did, the wheel scrolled, a drag swiped,
Alt-click long-tapped and a rail beside the phone held Back, Home, the panels and an App menu.
The demo is kept as the record of that design; it no longer matches the page.

**Select, then choose (owner, 2026-10-02).** Clicking the phone image to act made the
recordable steps whatever a mouse gesture could express (no swipe up or down, no scroll until,
no wait that is not an assertion) and made a misclick a recorded step. Now:

- **Top bar:** the device; Record / Pause (paused: steps still run, nothing is recorded).
- **Screen:** the latest frame with its overlay; select-only. A click selects (Act hits
  interactive nodes, or all nodes when the overlay shows all; Assert and Wait hit every node);
  a right-click selects from every node. Frame status: *settled*, or *changing…* with a dashed,
  faded overlay (decision 4). Overlay filter: interactive / all nodes / off. Hover shows the
  selector that would be recorded. Nodes with no unique selector get a red hatched box
  (decision 8). The selection takes the active tab's colour. Under the phone, as its navigation
  bar: Back, Home, Recent apps, Notifications, Quick settings (owner, 2026-10-02: the device's
  buttons must not blend with the element's), and **More keys**: named keys (Enter, Delete, Tab,
  Space, Escape, Search, volume) or any key code, recorded as `press_key` and shown as
  `pressKey(66 /* Enter */)`.
- **Composer** (middle column, top): the selected element (class, `@ref`, the ranked selector
  candidates as a radio list with their chips, the live match count of the chosen one) and five
  tabs, keys 1–5, each button of which runs its step and records it. The first three act on the
  element; the last two, set apart, on the app and on the device:
  - **Act** (coral): Tap, Long press; Swipe ↑ ↓ ← →; Scroll ↑ ↓ ← → on a scrollable node (on a
    node inside one, a button selects the smallest scrollable node around it, since its rows
    usually cover a list); a Distance slider (10–100 %, default 80, the SDKs' `distancePercent`;
    the SDKs have no speed) for swipes, scrolls and scroll until; Scroll until (below); on
    editable nodes the text line (value, Secret, name, Set text, Wait for focus + Type keys
    (`skip_focus_wait` when off), Clear, Submit = `perform_ime_action`). Also Double tap; Drag
    to… (a pick mode like scroll until: the source is outlined, the next click is the drop
    target, Esc cancels; one `drag` step); Pinch open / close across the Distance; Fling ↑ ↓ ← →
    on a scrollable node or one inside a list; and, from `DescribeElement` (the one match's
    snapshot), its accessibility actions (custom ones first: they stand in for a swipe) and, on a
    range node, a Value slider in its own units (`set_progress`).
  - **Assert** (blue): the states the node is in now, text equals / contains (prefilled with its
    text), count (prefilled with the live count).
  - **Wait** (violet): the element waits that fit the node, text and count.
  - **App** (neutral; owner, 2026-10-02: the app's controls must not blend with the element's,
    and a panel of their own left the inspector no room): the package (suggesting the packages on
    screen, remembered per browser, not part of the attach), Cold launch / Launch (with an
    optional activity, `AppCall.activity`, and extras, key / type / value rows), Foreground /
    Background (`press_key` HOME, the SDKs' `background()`), Open link (Any app), Force stop /
    Clear data, Grant / Revoke a permission, the app's languages (`set_locales`, empty = follow
    the system), and the app waits (`awaitVisible`, `awaitScreenStable`, `awaitSettled`,
    `awaitAnimationEnd`). Selecting an element on the screen leaves it for Act.
  - **Device** (neutral, as App; 2026-10-02): first a read-back of the device (`GetDeviceStatus`:
    screen and keyguard, rotation, keyboard, the foreground activity), asked again after every
    step. Every condition is a segmented choice whose current value carries the read-back's dot;
    choosing a value records it even when the device already has it, because a test sets what
    it relies on. Groups: Screen (orientation, rotation / Auto, Wake / Sleep keys, Unlock), Keyboard
    and clipboard, Permission dialog (await, then choose with an optional accuracy), Notifications
    and toasts (`ListNotifications`: each with Wait for, Open, its action buttons, Dismiss,
    matched by title or text and package; a toast wait), Conditions (held until release:
    animations, dark mode, stay awake, font scale, density, airplane / Wi-Fi / mobile data, the
    accessibility display settings, system languages, mock location), and Assert (foreground
    activity prefilled from the read-back, keyboard shown / hidden, clipboard equals).
  Only what the SDKs have is offered; new gestures arrive with the SDK.
- **Inspector** (middle column, below): *Properties* and *Screen tree* (every node, filterable,
  click to select).
- **Steps:** each recorded step with its inferred wait (`after await(res("search")).one()`),
  warnings and outcome; move / delete; **Replay**; **Export** shows the `tap-recording/1` JSON.

**Scroll until is shown, not typed (owner, 2026-10-02).** A first cut asked for the target as a
typed selector. Now a direction starts a search: the studio scrolls the container once as a
*probe* (`PerformRequest.skip_recording`: it runs, is never recorded, whether recording is on or
not), the screen outlines the container and dims the rest, and the composer asks for the target
with **Scroll again** and **Cancel** (Esc). A click inside the container records one
`scroll_until` step with that element's selector as the target, the probe's direction and
distance, and `max_scrolls` = max(20, twice the probes). Running it right away passes at once
(the target is already in view), so what is recorded is what the user saw; a replay from the
top scrolls as far as it needs. The probes are not undone. The target becomes the selection, so
the next step (usually a tap) is one click.

Interaction rules:

1. **One click selects, one click records.** Recording a step is two clicks (element, then
   step); repeating a step on the same element is one. Number keys switch the tab.
2. **Text:** **Set text** is the default (Enter): one command, no keyboard, no focus
   dependency. **Type keys** records the SDKs' element `typeText` (tap, await focused,
   `type_text`) for fields that react to key events.
3. **Gestures are element-relative**, with the default distance. No gesture records a point.
4. **Editing steps** (phase 5): reorder, delete, change the selector (another candidate, or typed
   in with a live match count), change a value, the scroll-until target, a swipe's, scroll's or scroll until's direction and
   distance and a scroll until's max scrolls, toggle secret, insert
   (new steps go after the selected step), a free-text note per step.
5. **Replay** runs from the first step and stops at the first failure; *Run from here* and *Run
   step* for iterating. If the recording does not start with an app step, the UI offers to
   prepend a cold launch, because a replay from an unknown screen is not reproducible.
6. Windows of other packages (a system dialog over the app) are shown with their package, and a
   step there records a selector bound to that package; rotation (frames carry it; the overlay
   follows); saving and reopening a recording file to continue it.

## `tap-recording/1`

The document is `tap.studio.v1.Recording` (`clients/studio/proto/studio.proto`) in proto3 JSON
with the original snake_case field names, as `tap-events/1`, so the embedded `tap.v1` messages
and the studio's own fields are one encoding. The proto is the definition; the rules it cannot
state (listed on its fields) are checked by `tap_studio.recording.validate`, which reports every
problem with its step. Written in canonical form: enums by name, defaults left out, `int64` as
strings (proto3 JSON).

```json
{
  "format": "tap-recording/1",
  "recorded_at": "2026-09-29T17:42:10Z",
  "recorder": "tap-studio 0.0.1",
  "device": {"serial": "emulator-5554", "api_level": 34, "manufacturer": "Google", "model": "sdk_gphone64_x86_64"},
  "secrets": ["password"],
  "steps": [
    {"id": "s1", "outcome": {"duration_ms": 1830},
     "app": {"operation": "cold_launch", "package_name": "com.example.basket"}},
    {"id": "s2", "action": {
      "command": {"set_text": {"selector": {"node": {"all_of": {"nodes": [{"resource": {"name": "search"}}, {"match": {"property": "PROPERTY_PACKAGE_NAME", "value": "com.example.basket", "mode": "MATCH_EXACT"}}]}}}, "text": "wool"}},
      "wait": {"wait_visible": {"selector": {"node": {"all_of": {"nodes": [{"resource": {"name": "search"}}, {"match": {"property": "PROPERTY_PACKAGE_NAME", "value": "com.example.basket", "mode": "MATCH_EXACT"}}]}}}, "exactly_one": true}},
      "selector_origin": "SELECTOR_ORIGIN_SYNTHESIZED"}},
    {"id": "s3", "type": {"selector": {"node": {"all_of": {"nodes": [{"resource": {"name": "password"}}, {"match": {"property": "PROPERTY_PACKAGE_NAME", "value": "com.example.basket", "mode": "MATCH_EXACT"}}]}}},
      "secret": "password", "selector_origin": "SELECTOR_ORIGIN_SYNTHESIZED"}},
    {"id": "s4", "action": {"command": {"press_key": {"key_code": 4}}}},
    {"id": "s5", "note": "the confirmation screen",
     "outcome": {"duration_ms": 5003, "error": {"code": "ERR_WAIT_TIMEOUT", "message": "no match", "match_count": 0}},
     "wait": {"selector": {"node": {"match": {"property": "PROPERTY_TEXT", "value": "Order placed"}}},
      "condition": "CONDITION_VISIBLE"}},
    {"id": "s6", "assertion": {"selector": {"node": {"resource": {"name": "total"}}},
      "check": "CHECK_TEXT_EQUALS", "text": "€42"}}
  ]
}
```

- **Header:** `format` (exactly `tap-recording/1`), `recorded_at` (UTC), `recorder` (tool and
  version), `device` (provenance only: a replay may use any device), `secrets`
  (exactly the names the steps use, each once, so a replayer can ask for all of them up front).
  A recording names no app under test (protocol 5.0, 2026-10-01): each selector carries its
  package predicate, so one recording can pass through a system dialog or a second app, and
  code generation writes a bound selector as `app("pkg").element(…)` and an unbound one as
  `screen.element(…)`.
- **Steps** have a unique `id` (stable across edits, ≤ 64 chars), an optional `note`, once run an
  `outcome` (`duration_ms`, and a `tap.v1` `Error` or `Failure`, or a `mismatch` message when
  an assertion did not hold; none of them = passed),
  and exactly one kind:
  - `app`: a `tap.v1.AppCall` (the event log's message): cold_launch, launch (both with
    `activity` and `extras`), foreground, force_stop, clear_data, grant_permission,
    revoke_permission, open_link (`uri`, `any_app`), set_locales (`locales`, empty = follow the
    system).
  - `action`: one `tap.v1.Command` in `command`, either on an element (tap, long_tap,
    double_tap, set_text, clear_text, scroll, swipe, fling, pinch, drag, perform_ime_action,
    perform_accessibility_action, set_progress) or on the device (press_key, open_system_panel,
    set_orientation, set_display_rotation, unfreeze_rotation, dismiss_keyguard, hide_keyboard,
    set_clipboard, choose_permission, open_notification, dismiss_notification); an element op
    has the recorded precondition in `wait` (a
    `Command` holding `wait_visible` of the same selector, with `exactly_one` unless the selector
    has a `first` / `at` pick, decision 6). A
    replayer sends `wait` then `command`, unchanged. With `secret` (set_text only),
    `set_text.text` is empty and the value is the named secret's.
  - `type`: the element `typeText` flow, three commands: tap `selector`, await it focused
    (unless `skip_focus_wait`, the SDKs' `await_focus = false`), then `type_text` of `text` or of
    the named `secret`. The wait before the tap is implied, as for an action.
  - `wait`: `selector` + `condition`, the SDKs' element waits (`CONDITION_VISIBLE`, `ONE`,
    `GONE`, `ENABLED`, `DISABLED`, `CHECKED`, `UNCHECKED`, `FOCUSED`, `TEXT_EQUALS` /
    `TEXT_CONTAINS` with `text`, `COUNT` with `count`), replayed as the SDK wait of the same name
    with the device's default wait timeout.
  - `assertion`: `selector` + `check` (`CHECK_EXISTS`, `ENABLED`, `DISABLED`, `CHECKED`,
    `UNCHECKED`, `FOCUSED`, `TEXT_EQUALS` / `TEXT_CONTAINS` with `text`, `COUNT` with `count`),
    one query of the screen as it is (`exists`, `is_enabled`, `is_checked`, `text`, `count`, the
    snapshot's focus), no wait. A check that does not hold is the outcome's `mismatch`, and the
    page does not record it. A `/1` file written before 2026-10-02 whose `assertion` holds a
    `condition` is refused ("assertion.check is required"), not read as a check: rename it to
    `wait`.
  - `scroll_until`: `container`, `target`, `direction`, `max_scrolls` (0..1000, default 20) and
    `distance_percent` (1..100, default 80, as for scroll and swipe), replayed as
    `Element.scrollUntil`: wait for exactly one container (at least one with a pick), then scroll
    it until the target exists.
  - `app_wait`: one `tap.v1.Command`, `wait_app_visible` or `wait_screen_stable` (with its
    `stable_for_ms`, 1..30000, default 500, and its signal), with a package name.
  - `device_wait`: one `tap.v1.Command`, `await_toast`, `await_notification` or
    `wait_permission_prompt`, replayed with the device's wait timeout. A match's `mode` is set
    exactly when it has a title or text (as the SDKs send it).
  - `device`: a `tap.v1.DeviceCall` (the event log's message) for a condition held until
    release: set_animations, set_dark_mode, set_stay_awake (`enabled`), set_font_scale,
    set_density (no `density_dpi` = the display's own), set_network (at least one of the three),
    set_system_locales, set_location (`accuracy_m` / `altitude_m` optional),
    set_accessibility_display (at least one of the three).
  - `device_assertion`: `check` (`DEVICE_CHECK_FOREGROUND_ACTIVITY` with `text` =
    `package/class`, `KEYBOARD_SHOWN`, `KEYBOARD_HIDDEN`, `CLIPBOARD_EQUALS` with `text`), one
    read, no wait; a mismatch is the outcome's `mismatch`.
  - `selector_origin` on steps with a selector: `SYNTHESIZED` (the daemon's first choice),
    `ALTERNATIVE` (another candidate the user picked) or `EDITED` (typed in). Warnings such as
    *by index* are derived from the selector itself and not stored.
- Studio housekeeping (snapshots, screenshots, counts, settles) never appears.
- Versioning: readers ignore unknown fields, so additive changes keep `/1`; anything a `/1`
  reader would misread bumps it.

## Shape

```
tap-studio [--port N] [--no-open] [--page-origin URL]      (phase 1)
           [--serial S]                                     (phase 3: attach at start)
  → prints and opens http://127.0.0.1:N/login?t=<launch token>

page (React, clients/studio/web)
  ├── device picker, attach / release (no app); the App menu's package
  ├── screen: latest frame (screenshot) + its node overlay (provisional while the screen moves)
  ├── side panel: node properties, selector candidates + match count, action / assert buttons
  └── steps: recorded steps; reorder / delete / edit; replay; export tap-recording/1

back end (Python, Starlette + connect-python, on tap-e2e)
  /login                              launch token → cookie
  /tap.studio.v1.StudioService/*      Connect: Info, GetRecording (phase 1); GetSession,
                                      ListDevices, Attach, Release, Frames (server stream),
                                      Count, Perform, SetRecording, NewRecording (phase 3);
                                      UpdateStep, DeleteStep, MoveStep, OpenRecording,
                                      Replay (server stream) (phase 5); DescribeElement,
                                      GetDeviceStatus, ListNotifications (device actions)
  /                                   the built page

daemon
  ScreenSnapshot (+ selector candidates, new), Screenshot, ResolveRef, Execute, App*
```

## Options considered

- **Record on-device touches**: rejected, decision 1.
- **A `tap-agent studio` verb**: rejected by the owner; a separate package with its own UI,
  server and format.
- **FastAPI + OpenAPI → TypeScript** (the first plan): replaced by the shared proto (decision
  2); openapi-typescript also required TypeScript 5. Kept as the fallback, with orval.
- **Plain HTML/JS front end**: rejected by the owner; the UI is large enough that a framework
  and types pay for themselves, and migrating later would cost more.
- **Encoded video now (scrcpy / `screenrecord` behind a `ScreenStream` RPC)**: deferred; the
  higher frame rate is not needed to record interactions, and video would still need the
  paired-frame loop for the overlay (`screen-streaming.md`).
- **MJPEG from the driver (Appium)**, **the studio running scrcpy or `adb` itself**: rejected
  (`screen-streaming.md`).
- **Kotlin, inside the daemon; a desktop app**: rejected for v1: a web server and UI do not
  belong in the native binary, and a loopback page needs no install beyond the wheel.

## Phases (one commit each)

1. Contract: this record approved; `tap-recording/1` written down; the package skeleton
   (Python back end, React/Vite front end, the wheel with built assets, CI). **Done
   2026-09-29 (`37f1d1e`):** `proto/studio.proto` with `Info` and `GetRecording`, the generator,
   `tap_studio.recording` (load/dump/validate) and the guarded server with 64 unit tests, the page
   shell calling `Info` (Vitest), the `studio` CI job; checked in a browser against a local
   `tap-studio` (no device).
2. Selector candidates in `ScreenSnapshot` (daemon synthesis + unit tests on the recorded
   dumps). **Done 2026-09-29:** `ScreenSnapshotRequest.selector_candidates` (off by default)
   fills `ScreenNode.candidates` with every `SelectorCandidate{selector, kind}` that is unique in
   the dump, in the synthesis's rank order, so the first is `ScreenNode.selector`. Minimal only:
   a candidate that adds predicates to an earlier one is dropped, and the `At` pick is offered
   only when nothing else is unique. `SelectorSynthesisTest` checks, on every recorded dump, that
   each candidate is valid, native and matches its node alone; `tap-e2e` exposes them as
   `screen_snapshot(selector_candidates=True)` → `ScreenNode.candidates`.
3. Back end: attach / frame stream / act / assert / recording RPCs, unit tests on the
   Python client's fake daemon; per-frame cost measured on the matrix (`screen-streaming.md`;
   ask the owner before using the devices). `tap-e2e` gains a public name for its `tap.v1`
   modules and the studio stops importing `tap_e2e._gen`.
   **Done 2026-09-29.** Per-frame cost measured on 85e49002 (`screen-streaming.md`: ~430 ms a
   frame, mostly the screenshot); emulator-5554 not yet.
   `tap_e2e.proto` re-exports the generated modules, with `Selector.from_proto` /
   `to_proto`. `tap_studio.service.Studio` is one observed `tap-studio` connection, at most one
   attached device (attaching again releases the previous one) and one recording;
   `tap_studio.screen.DeviceWorker` runs every device call on one thread per device;
   `tap_studio.steps` completes and runs a step through the typed `tap-e2e` API (an action is
   `wait(selector).one()` then the element call, so the device sees exactly the recorded
   commands). Settled while building it:
   - `Perform` and `Count` are user calls and go ahead of the frame loop; a snapshot whose
     screenshot would come after a user call is dropped rather than paired with a later picture.
     Only `Perform` wakes a settled loop; `Count` cannot change the screen, and waking the loop
     put the next count behind a screenshot (263 → 98 ms median on 85e49002).
   - The loop resumes at once after a user call instead of after a separate post-action
     settle: frames are `moving` (nodes added, removed or moved; not picture-only changes, see
     below) until the screen
     settles, then the delay doubles from 0.25 s to 2 s; a `Perform` resets it. It runs only
     while a page reads `Frames`.
   - The wait is always inferred (`wait_visible exactly_one` on the action's selector); a
     request that carries one is refused. Gesture distance defaults to 80 %.
   - A failed step runs and is reported but is not recorded; with recording paused, steps run
     and are not recorded either.
   - A recording belongs to its app: after re-attaching for another package, `Perform` is
     refused while recording until the user pauses or starts a new recording.
   - Secret values are held in memory only (for replay in this process); the recording keeps
     the names.
   - `--session NAME` (resume a named recording) is dropped for now: the page exports and
     will import recordings (phase 5).
4. Front end: frame + overlay + hover + click-to-act, steps list, export. (Click-to-act was
   replaced by select-then-choose on 2026-10-02; see The UI.)
   **Done 2026-09-29.** Device picker and package, then the three areas of the demo: the screen
   with its overlay, the inspector (element / screen tree) and the steps with export. Page tests
   (Vitest, 36) run the whole app against a fake `StudioService` on a Connect router transport:
   attach, tap, long tap, a node without a selector, a secret, assert mode, inspect and
   right-click, a drag swipe, a failed and a paused step, export, Back, release. No device run
   yet (phase 6). Settled while building it:
   - Steps and selectors are shown as the Kotlin SDK calls they replay as
     (`element(res("go")).tap()`, `await(res("go")).one()`), rendered from the proto by
     `describe.ts`; the JSON is only in the export.
   - Hit-testing is in the page: one pointer layer over the frame, boxes in percent of the
     frame (bounds and screenshot are both in display orientation, so rotation needs nothing),
     the smallest node under the pointer wins, then the deeper one, within the window on top
     there: the dump has no z-order, so of the windows whose root contains the point the one
     with the smallest root (a dialog, popup, keyboard or status bar over the activity); a
     click never reaches a node behind a dialog. Act mode hits interactive nodes (all nodes
     when the overlay shows all); Assert and Inspect hit every node.
   - A tap or long tap on a node whose selector is an index pick (a preference or list row: a
     layout with nothing of its own) is recorded through its first label with a selector of
     its own whose centre hits the row (`tapLabel`): `text("Apps")` instead of
     `className("…LinearLayout").at(9)`, which changes as soon as the list scrolls.
   - Hover does not call `Count`: every candidate is unique in its snapshot by construction
     (phase 2), so the hover shows the selector and its ref. `Count` stays for typed selectors
     (phase 5).
   - Assert mode, the text popover (Set text / Type keys) and secrets came into this phase:
     without them no useful flow can be recorded. The assertions offered are the ones that hold
     on the node as shown (visible, exactly one, text is, enabled or disabled, checked or not,
     focused); *gone* is not offered on a node that is on screen, since it could only time out.
   - A drag of at least 4 % of the frame width is a swipe on the scrollable under its start, or
     else the node under it; the wheel scrolls the scrollable under the pointer once it has been
     quiet for 700 ms.
   - Export shows `GetRecordingResponse.document`, the file exactly as the back end's `dumps`
     writes it, so there is one serializer; Download names it
     `<package>-<time>.tap-recording.json`.
   - No Refresh button: the frame loop already follows the screen.
5. Recording model: candidate choice and warnings, step editing, import; replay from the page.
   **Done 2026-09-29.** New RPCs `UpdateStep`, `DeleteStep`, `MoveStep`, `OpenRecording` and
   `Replay` (server stream: a response when each step starts and one with its outcome);
   `Perform` takes `before_step_id` and `GetRecording` reports `missing_secrets`. 128 back-end
   and 68 page tests; checked in a browser against a fake daemon (no device). Settled while
   building it:
   - A typed selector is Kotlin SDK DSL, the same language the page shows (`parse.ts` reads what
     `describe.ts` writes, round-trip tested): infix `and`/`or`, `descendant`/`child`, match
     modes as `MatchMode.X`, Kotlin strings with `$` escaped. A parse error points at its
     column; a valid selector shows a debounced `Count` ("Matches 1 element now.").
   - The origin follows the choice: the node's first candidate is `SYNTHESIZED`, another
     candidate `ALTERNATIVE`, anything typed `EDITED`. Choosing a candidate in the inspector
     applies to the next step recorded on that node; in the step editor it rewrites the step.
   - An edit that changes what runs clears the step's outcome and re-infers its wait, keeping
     the wait's `timeout_ms`; an edit to the note alone keeps the outcome. Saving never runs the
     step: the editor says to replay it.
   - Warnings are derived chips on the step, never stored: *by index*, *dynamic text* (a
     text selector with digits: prices, counts and dates change between runs), *typed*, *secret*, and *`name` needs a value* for a
     secret whose value this process does not hold (an opened file has names only).
   - New steps go after the selected step (`before_step_id`), and the selection moves to the new
     step, so a run of insertions stays in order; *Add at the end* clears the selection.
   - Replay runs on the attached device only for the recording's own package, stops at the
     first failure and stores each outcome. While it runs, every call that changes the
     recording or its steps is refused (`FAILED_PRECONDITION`); Stop cancels the stream; a
     step already sent to the device finishes, and nothing after it runs. Values for the secrets in range are asked for up front, once.
   - Replaying from the start when the first step is not an app step offers to prepend a cold
     launch: it is performed (and so recorded) as step 1, then the replay continues from the old
     first step, so the launch does not run twice.
   - Open reads a `tap-recording/1` file through `OpenRecording`, which validates it with the
     same `loads` as everything else and reports every problem; it replaces the recording and
     forgets the secret values.
6. Device run on the local matrix and docs (`docs/guide/`, release family).
   **Device run on emulator-5554 (API 34), 2026-09-29, and 85e49002 (Samsung, API 29), 2026-09-30.** On the fixture app,
   in the browser, with the frame loop running: a tap, a set text with `$` in it, a tap retargeted
   to a typed `text("AMBIGUOUS TAP").at(1)`, a wheel scroll of the Compose list and two text
   assertions. Replay with the cold launch prepended passed (7 steps); the exported file,
   replayed through `tap-e2e` in a fresh connection after releasing the device, passed the same
   7 steps. It found and fixed:
   - The inferred wait was always `exactly_one`, which a selector with a pick can never satisfy
     (the driver's waits ignore the pick): a picked selector now waits for any match (decision
     6), the validator enforces the rule, and the page offers no *Exactly one* check on such a
     target and reads a live count against the pick ("the step uses element 2").
   - SIGTERM or Ctrl-C with a page open did not stop the studio: uvicorn waits for open
     responses before shutting the app down, and `Frames` only ends when the device is released,
     so the device stayed attached until the process was killed. The exit signal now releases
     the device first (and uvicorn cuts what is left after 5 s).
   - A replay that failed after a prepended cold launch named the step by its old number, and
     the summary did not count the launch.
   - The mode switch's shortcut numbers read as counts; they are keycaps now.
   The second pass (export through the UI, then 85e49002) found more: Download never produced
   a file (the blob URL was revoked as soon as the link was clicked, and the link was not in the
   document), and with a real recording the export drawer's JSON pushed Copy and Download below
   the window (its grid row grew with the content). New and Open asked through a blocking
   `window.confirm`; they use the page's own dialog now, with Cancel focused. On 85e49002 the
   same flow (with the `.at(1)` step) was recorded, exported with Download, reopened after New,
   and replayed from a cold launch (6 steps passed); the downloaded file replayed through
   `tap-e2e` in a fresh connection passed too. Copy JSON could not be checked here (the test
   browser's clipboard reads back empty even for its own writes); the owner confirmed it works
   in a real browser (2026-09-30).
   The owner's own use (2026-09-30) found two more: with a focused text field the frame stayed
   *changing…* forever, because the blinking cursor gave every screenshot a new hash, which also
   kept the loop at full rate; and the frame status line wrapped differently for *changing…*
   than for *settled*, so the screen jumped under the pointer. A frame is now `moving` only when
   nodes were added, removed or moved (bounds compared by ref), which is what can put the
   overlay out of place; the status line has a fixed height. On wide screens (three columns)
   the page is now exactly the window and never scrolls: the phone scales to the free height of
   its column and the inspector and steps scroll inside their panels.

## Verification

- Unit: step model (wait inference, secret masking, JSON round trip), the `StudioService`
  methods and the frame stream against the fake daemon, selector candidates on recorded dumps, the frame
  loop's pause-on-action and adaptive rate.
- Front end: type-checked build in CI; component tests for hit-testing and overlay scaling
  across rotations.
- Device (emulator-5554 + 85e49002, never rebooted; only with the owner's go-ahead): record a fixture-app flow in the browser
  (tap, fill, scroll a list, an assertion) with the frame loop running, export it, replay it from the studio
  and replay the exported commands through `tap-e2e` in a fresh session: same outcome, no
  `NOT_FOUND` from a missing wait, no action delayed behind a frame.
