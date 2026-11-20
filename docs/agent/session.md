# A session, step by step

Below is a real session on an API 34 emulator, run from the command line. Over MCP the same
steps are tool calls with the same output.

## Attach a device

```console
$ tap-agent devices
85e49002  FREE
emulator-5554  FREE
$ tap-agent attach emulator-5554
attached emulator-5554 to session 'agent', idle timeout 15m
```

A **session** is a held connection on the server. It outlives each CLI call, is shared by the
CLI and the MCP server, and holds the device exclusively, as a test does, until `release` or
15 idle minutes. `--session NAME` (or `TAP_AGENT_SESSION`) keeps several apart.

### Force a fresh driver

With the server's [experimental runtime reuse](../guide/server.md#driver-runtime-reuse-experimental)
enabled, a new attachment may reuse clean instrumentation, but never the previous session's
credentials or state. To start fresh instead:

```sh
tap-agent attach emulator-5554 --driver-start cold
```

MCP: `attach` with `driver_start="cold"`. Detach or release an existing attachment first;
cold mode refuses to replace a live session silently. Installation is separate: cold startup
does not reinstall unchanged APKs. The runtime idle timeout applies after detach/release,
not between calls on your held connection.

### A quarantined device

`devices` lists a device Tap could not prove clean after a session as `QUARANTINED` with the
reason. An agent can look at it and fix it before it goes back into service:

```console
$ tap-agent attach 85e49002 --quarantined
attached 85e49002 to session 'agent', idle timeout 15m
85e49002 is quarantined (BOOT_CHANGED: the device rebooted during a session): inspect and fix it; it stays quarantined after detach until `unquarantine 85e49002`
$ tap-agent screenshot          # snapshot, act, as usual
$ tap-agent detach
$ tap-agent unquarantine 85e49002
85e49002 is back in service (it was quarantined: BOOT_CHANGED: the device rebooted during a session)
  - stopped the driver
```

MCP: `attach` with `inspect_quarantined`, then `unquarantine`. A device marked
`retried on attach` needs none of this: the next attach retries its settings.

## Catch toasts between actions

Tap keeps listening while the agent thinks. `snapshot`, `settle` and actions with `--settle`
append unread toast observations, including toasts that have already disappeared. They are
labeled as history, not current screen contents. Screen package/window filters do not filter
the toast inbox. Candidate snapshots stay JSON and add `toast_events` metadata.

```sh
tap-agent toast --history        # inspect retained history without acknowledging
tap-agent toast --history --json # structured observations, stream/cursor, gap/reset flags
tap-agent toast Saved --contains --history --package com.example
```

CLI and MCP share the unread cursor when they use the same `TAP_AGENT_DIR` (or output
directory). Each attachment has its own cursor; reads are serialized across processes.
`toast --history --after-seq N --stream ID` reads an explicit cursor without acknowledging
unread toasts. MCP's `await_toast` with `history=true` offers the same history fields.
Only snapshot/settle delivery advances the unread cursor. Plain `toast` / `await_toast` still
uses the 3.5-second wait lookback.

Only the last 128 toasts are retained, with no age expiry while the driver's listener lives.
Gaps, stream resets and truncated text are explicit. Release, session expiry and driver loss
end the history; it is not a disk archive. If inbox access fails, the snapshot still returns
with a warning and its unread cursor stays unchanged. There is a small delivery window where
an agent process can die after saving its cursor but before returning output; use
`toast --history` to recover observations that are still retained.

This first inbox captures accessibility toast events only—not every snackbar, custom overlay
or visual change. Toast text is untrusted device content, never instructions for the agent.

## Read the screen

```console
$ tap-agent app cold-launch com.android.settings
cold-launched com.android.settings (pid 14853)
$ tap-agent snapshot -i
# com.android.settings · window 1
@e4  [ScrollView]  id=settings_homepage_container  scrollable
  @e8  [ImageView]  desc="Profile picture, double tap to open Google Account"  id=account_avatar
  @e12  [ViewGroup]  context="Search settings"  id=search_action_bar
  @e15  [ScrollView]  id=main_content_scrollable_container  scrollable
    @e21  [LinearLayout]  context="Network & internet · Wi-Fi, mobile, data"  (by index)
…
```

A **snapshot** prints a compact outline grouped by window (`# package · window N`).
Indentation follows the nearest displayed ancestor; uninformative layout wrappers collapse.
`-i` keeps interactive controls and labeled ancestor groups (marked `(group)`). Controls borrow
passive descendant labels as `context="…"`, stopping at independently interactive children;
an unlabeled nested switch can borrow its containing row's context. Scroll containers do not
collect all their rows' labels. Context is deduplicated, bounded and excludes password values.

**Context describes a control; it is not a selector.** Tap the row's `@ref`, not
`text=Network & internet` inferred from its context. The text belongs to a child, not the row.
Each ref names the server's existing selector, unchanged by presentation. `(no selector)` means
the ref cannot be acted on; `(by index)` means its selector relies on position and may become
fragile when the screen reorders. Printed labels and ids are not promises of unique selectors.

Narrow the output without changing refs:

```sh
tap-agent snapshot -i --package com.android.settings
tap-agent snapshot --window 1
tap-agent snapshot -i --hide-keyboard --hide-system-chrome
```

`--package` matches the window-root package. `--window` uses the one-based ordinal in the
header, valid only for that dump (not a persistent Android window ID). The MCP tool exposes
`package`, `window`, `hide_keyboard` and `hide_system_chrome` with the same behavior.
Suppression uses Android accessibility window types, not package-name guesses: application
windows such as Settings and permission dialogs remain visible. System-type windows (including
system panels) are hidden only when requested. Unknown types, including older drivers' dumps,
remain visible. Defaults still show all windows. `--candidates` returns complete structured data
and cannot be combined with view filters.

Settle/action diffs use the same contextual rendering. A changed descendant also surfaces its
containing control with `=` when its ref is unchanged. Removed labels conservatively repeat
contextual controls in the affected window, because the removed list is only a partial tree.

An empty field showing its hint prints `empty  hint="Name"`, rather than presenting the hint
as a typed value. App validation state prints `invalid` and `error="Cannot be blank"`.

## Read a collection

For an app exposing product titles, prices or checkboxes, `elements` reads every match in one
bulk device command. It returns a JSON array of SDK element snapshots, not diagnostic tree
nodes or refs. Use `--texts` to project raw text:

```sh
tap-agent elements id=title,pkg=com.shop --texts
tap-agent elements '{"id":"price","ancestor":{"id":"products"},"pkg":"com.shop"}' --texts
tap-agent elements checkable=true,pkg=com.shop --timeout 3s
```

Full state includes `checkable`, `checked`, `enabled` and `selected`; read it once to compare
several properties together. The read-only MCP tool is `elements`, with `target`, `texts`
(default false), `timeout_seconds`, `session` and `device`. A target is a written selector,
including existing package/relational predicates; snapshot refs, `first` and `index` are
rejected. Without `pkg` it searches the whole screen, as other selector tools do.

Results follow accessibility order: service-reported window order, then post-order inside
each window (matching children before their matching parent). This is not necessarily visual
or backing-dataset order. Virtualized lists expose only current accessibility nodes; no
auto-scroll or implicit wait occurs. Every call resolves afresh, retains no handles and reads
sequentially, not as an atomic UI transaction. The timeout bounds the read, not a wait for
content or stability. No matches returns `[]`. Text projection keeps duplicates, empty strings
and nulls; Android may report an empty field's hint as text (`showing_hint` in full state).
Staleness or overflow (more than 1000 matches / a snapshot list over 1 MiB) fails the whole
operation; there are no partial or silently truncated values. Like `inspect`, full state may
include sensitive text. Reads do not create recorded action/wait steps.

## Act, and see what changed

```console
$ tap-agent tap @e12
tapped @e12
$ tap-agent fill id=open_search_view_edit_text dark --settle
filled id=open_search_view_edit_text
# com.google.android.settings.intelligence
+ @e266  [EditText]  "dark"  hint="Search settings"  id=open_search_view_edit_text  focused
+ @e267  [ImageButton]  desc="Clear text"  id=open_search_view_clear_button
+ @e272  [TextView]  "Dark theme"  id=android:id/title
+ @e273  [TextView]  "Display"  id=breadcrumb
+ @e279  [TextView]  "Enable dark mode"  id=android:id/title
…
# removed
- @e108  [EditText]  "Search settings"  id=open_search_view_edit_text  focused
…
```

A **target** is a ref or `key=value` terms joined by commas: `id=`, `text=`, `text~=`
(contains), `desc=`, `desc~=`, `hint=`, `class=`, `pkg=`, `index=`. A target matches anywhere
on the screen; `pkg=` keeps it to one app's nodes.

Boolean predicates include `enabled=true`, `checked=false`, `focused=true`, `checkable`,
`clickable`, `focusable`, `long_clickable`, `scrollable` and `selected` (each true/false).
Use `text:starts_with=Order`, `desc:ends_with=button` or `text:regex=Order \\d+` for other
match modes; `exact`, `contains`, `starts_with`, `ends_with` and RE2 full-match `regex` work
with text, desc, hint, class and pkg. Escape a comma as `\,`; other regex backslashes stay
literal. A simple `class=Button` retains its class-name suffix shorthand.

For nested predicates, supply a JSON selector string (also accepted as the MCP target):

```sh
tap-agent tap '{"any":[{"text":"Save"},{"text":"Submit"}],"enabled":true,"ancestor":{"id":"dialog"}}'
```

JSON accepts the same property names, `{"text":{"value":"Order","mode":"starts_with"}}`,
`all`/`any` arrays of at least two predicates, and `parent`/`ancestor`/`child`/`descendant`
objects constraining this node's relatives. `index` or `first:true` is allowed only on the
outer object. Targets are at most 64 KiB; the SDK/driver's 32-level, 256-node and 1024-character
string limits still apply. There is no XPath or raw command escape hatch.

`--settle` waits for the screen to stop changing after the action and prints the difference:
`+` added and `-` removed nodes. Nodes with validation errors are also shown, marked `=` if
their ref is unchanged; validation state does not change a node's identity. That is usually
enough to pick the next step without another
snapshot. A screen that never goes quiet (a blinking cursor, a spinner) prints
`(screen still changing after 10s)` with the difference so far. If Android reports no focused
app, settling fails with an actionable error instead of sending an empty package name. Use
`snapshot` to inspect that state; do not repeat an action just because its settling step failed.

## Check the result

```console
$ tap-agent wait "text=Dark theme"
text=Dark theme is visible
```

`wait` polls on the device until the target is visible (`--gone`, `--one`), up to `--timeout`
(10 s by default, 1ms..120s). Use it instead of a sleep, and to confirm what an action was supposed to do.

`inspect <target>` returns the exact node's SDK state as JSON (text, bounds, flags, actions and
range); no match or ambiguity remains an error. This explicitly reads accessibility text,
which may contain sensitive data: do not copy it to shared logs. `exists` and `count` are
immediate read-only queries. To wait for a node's state use:

```sh
tap-agent wait id=save --state enabled
tap-agent wait id=terms --state checked
tap-agent wait id=status --state text --value Saved
tap-agent wait class=Button --state count --value 3
tap-agent wait id=pay --until enabled=true,text=Pay
tap-agent wait id=status --until '{"any":[{"text":"Saved"},{"text":"Up to date"}]}'
```

`--until` waits until the target's one node satisfies a condition written in the target
grammar, every part at the same moment (MCP `wait_for` takes it as `until`). A condition has no
ref, `index` / `first` or `pkg`: those choose nodes, so they go on the target.

States also include disabled, unchecked, focused and text-contains. Text/count states require
`--value`; other states reject it. Every wait polls on the device except `--state count`,
which the SDK polls from the host. A state wait whose node never reached the state reports how
the node looked at the end. CLI/core/MCP command budgets share the protocol's
120-second maximum. Standalone MCP `settle` accepts `timeout_seconds`, matching CLI `--timeout`.

## See the events a step causes

Some results never show in a snapshot: a toast, a dialog that comes and goes. Ask the step for
the device events it causes, or require one:

```console
$ tap-agent tap "text=Save" --expect toast:saved --events window
tapped text=Save
events:
  window state com.example.EditActivity (com.example)
✓ toast "Draft saved" (com.example)
```

Listening starts before the tap, so nothing it causes is missed. `--expect KIND[:TEXT]` fails the
step (exit 1, still listing what did happen) when no such event arrives within
`--event-timeout` (10 s). Kinds are `toast`, `window`, `content`, `click`, `scroll` and `text`.
`wait-event KIND [TEXT]` waits for the next event from now on, and `watch KIND…` streams them,
one line each, until you stop it: run it in the background to hear about a push message or a
background sync while the session goes on. Events are hints: check the screen before acting on
one. MCP tools take `events`, `expect_events` and `event_timeout_seconds`; MCP `watch` collects
for at most 120 s.

## When a step fails

```console
$ tap-agent tap "text=Missing thing"
error: NOT_FOUND during tap node { match { property: PROPERTY_TEXT value: "Missing thing" mode: MATCH_EXACT } } on emulator-5554 (request 13, generation 312, 104 ms)
nothing matches now; run `snapshot` to see the screen
$ tap-agent tap "text~=dark"
error: AMBIGUOUS during tap node { match { property: PROPERTY_TEXT value: "dark" mode: MATCH_CONTAINS } } on emulator-5554 (request 14, generation 312, 113 ms)
several nodes match; use a ref from `snapshot` or a narrower selector
```

As in tests, every action needs exactly one match and fails before touching the device
otherwise. The error names the code and a next step for the agent. A tap whose point another
window covers (a dialog, the shade) fails as `NOT_INTERACTABLE` / `OBSCURED`. `tap TARGET
--outside-panel` touches outside the dialog, menu, drawer or sheet that holds the target, and fails
the same way, with `NO_OUTSIDE_POINT` or `TOUCH_REACHES_BELOW`, when it has nowhere safe to touch.
Exit codes: `0` ok, `1` the step failed, `2` usage, `3` no server running, `4` the step ran but a
watched app crashed or stopped responding ([below](#when-the-app-crashes)).

`screenshot` and `capture` (screenshot, hierarchy, device info, driver log) save evidence
under `.tap/agent/` and print the paths.

### When the app crashes

The server keeps a record of every crash and ANR while the device is attached. Each step ends
with the deaths of watched apps (launched by the session or named by a target) that no earlier
step reported, whether the step itself failed or not:

```console
$ tap-agent tap text=Pay
tapped text=Pay
! com.example.shop crashed (pid 23554): java.lang.IllegalStateException: Out of stock
    at com.example.shop.Cart.pay(Cart.kt:41)
    at com.example.shop.CheckoutActivity.onPay(CheckoutActivity.kt:88)
    at android.view.View.performClick(View.java:7659)
    at android.view.View.performClickInternal(View.java:7636)
    at android.view.View.access$3600(View.java:829)
  … 18 more frames; full trace: .tap/agent/deaths/emulator-5554-0001-com.example.shop.txt
  its screen is gone; `app launch com.example.shop` to continue
```

The step's action ran, so its output stays on stdout, but it exits `4` (MCP: an error result):
a script or an agent must not go on as if the app were alive. The full trace goes to an
owner-only file so it stays out of the agent's context until it is needed. A wait that is
running when the app dies ends at once with `AUT_CRASHED` or `AUT_ANR`; its error names the
death, and the frames, trace file and hint follow. Once reported, a death explains no later
failure.

To reproduce a crash on purpose, add `--expect-death` (MCP `expect_death: true`): the step then
needs a watched app to die, during it or within 10 s after it, prints that death as
`✓ … (expected)`, and does not fail for the command the death ended. `deaths` lists the
record (`--package`, `--all` for apps the session did not watch, `--trace SEQ`, `--json`), and
each step in an `export` has an `app_deaths` list of the deaths it reported.

To be told of a crash between steps, run `tap-agent deaths --wait` in the background (an agent
that can run commands in the background is notified when it exits):

```console
$ tap-agent deaths --wait --timeout 30m
#3 com.example.shop crashed (pid 23554): java.lang.IllegalStateException: Out of stock
  `tap-agent deaths --trace 3` for its stack trace; the next step reports it
```

It exits `4` when a watched app dies (`--all` for any app, `--package` for one), and `0` when
the session ends or the timeout passes (by default it waits until the session ends). It only
notifies: the death stays for the next step to report and take. The wait runs on the server and
does not count as use of the session, so a forgotten waiter never keeps the device held past
the idle timeout. MCP has no background calls, so it is a CLI command only.

## Step history, export and release

Every action and explicit wait is recorded as a step from the first one; nothing has to be
started. `export` writes the history, also after `release`:

```console
$ tap-agent export -o session.json
exported step history 'agent-20261114-101502' (9 steps) to session.json
$ tap-agent release
released session 'agent' (detached emulator-5554)
```

The device log and the server's record of each call end with the session, so export them first:
`release --export flow.json` writes `export --logs --calls` to a new file, then releases.

`log mark NAME` puts a marker in the device log and in the history; `export --since NAME`
exports from it, and `--logs` / `--calls` add the device log and the server's record of each
call while the session is live. `history` lists the saved histories. Only actions and explicit
waits are steps, not inspections/polls.
[Record steps and turn them into tests](export.md) explains persistence, redaction and outcomes.

## SDK workflows and diagnostics

`devices` includes API level, model/manufacturer and emulator/physical properties when available.
`server-info` and `device-info` return the SDK's complete metadata as JSON. With several devices,
pass `--device SERIAL`; `detach --device SERIAL` releases only that device (including its temporary
settings/files), leaving the session and other attachments alive. `release` still ends them all.

```sh
tap-agent app installed com.example.app
tap-agent app process com.example.app --timeout 3s
tap-agent app visible com.example.app --timeout 5s
tap-agent scroll id=list down --percent 40 --timeout 3s
tap-agent scroll-until id=list text=Last --max-scrolls 12 --percent 50 --timeout 15s
tap-agent type hello --target id=search --focus-timeout 2s --timeout 3s
tap-agent settle --package com.example.app --signal all --stable-for 300ms --timeout 5s
```

Installed/process are SDK queries, not launch operations; visible waits without foregrounding.
Element actions accept `--timeout` (at most 120s); automatic `--settle` is a separate default
10-second tree wait. Targeted typing reuses the SDK's **tap → focus wait → type** helper, not an
atomic command: `--timeout` is per input step, `--focus-timeout` bounds the separate SDK wait
(default 10s). `--no-await-focus` explicitly skips it when focus belongs to a child/input view.
Focus is not proof of IME readiness: explicitly observe the app/keyboard state when needed.
Use `--sensitive` for secrets, then assert their effect without echoing them to logs.

Scroll-until finds an existing descendant through bounded SDK queries and gestures. Count and
time bound the loop; it does not detect the end of a list or replay uncertain input. Scroll/swipe
`--percent` is distance across the node (1..100), not movement proof. Observe the expected result.

Standalone settle accepts an explicit package, a 1ms..30s quiet period no longer than its timeout,
and `tree`, `pixels` or `all` signals. The package must be the focused window; nothing launches
or changes focus implicitly. As before, a changing-screen timeout returns a note, not a successful
assertion about app state. These are ordinary SDK waits, **not** synchronization/`awaitIdle`.

`snapshot --candidates` returns structured SDK screen data with selector candidates instead of
rendered lines; use the default view. `log driver` saves the Tap driver's own log and
`log evidence` the device's failure evidence (log, crashes and ANRs, activity and window state)
to new mode-0600 files, not inline text. `-o PATH` chooses a new file, never overwrites.
`capture` saves the hierarchy dump. Accessibility data and logs can contain secrets; keep them local.

`log` prints the device log since the attach, inline: the app's logcat lines with Tap's own
actions and markers between them, secrets redacted. Each entry starts with the time the server
got it, on the host's clock (local time), the clock of the steps in an export; a device whose
own clock is far off is noted once at the top.

```console
$ tap-agent tap 'text=Pay'
$ tap-agent log --last-action --package com.shop --priority warn
14:01:47.636 ── tap  text="Pay"
14:01:47.880 W Payments: retrying (timeout)
14:01:47.881 ── tap  → ok  243 ms
cursor 1843
$ tap-agent log --after 1843 --package com.shop     # what came since
```

A read returns at most 200 entries (`--limit`, up to 5000). A read with a start (`--since`,
`--after`, `--step`, `--last-action`) returns the **first** ones from that start and ends with
how many more follow; read on from the cursor it prints. A read without one returns the newest.
A busy device logs hundreds of lines a second, so narrow with `--package` or page on:

```console
$ tap-agent log --since checkout
14:01:47.600 ── marker checkout
…
(4210 more entries after this; read on with: log --after 2039)
cursor 2039
$ tap-agent log --after 2039
```

The hint repeats the read's end and filters (`log --after 2039 --until @2711 --package
com.shop`), so the next page stops where this read would have. The Tap driver's own logcat
lines (UiAutomator's node lookups) are left out; its action entries say what it did, and
`--driver-lines` keeps them.

The session's own steps are named where their actions begin, and `--step` prints one step's
part of the log, by its number or id:

```console
$ tap-agent log --step 3
── step 3 fill  [5f0c2a9d41b7e6a3]
── set_text  res="card"
12:01:49.120 D Payments: card field changed
── set_text  → ok  88 ms
cursor 1863
```

`log mark NAME` writes a marker and `log --since NAME` reads from it; `--until OTHER` ends the
read at a second marker instead of now. A name means the newest marker with it, so marking the
same name again moves it (`log mark` says so). `mark` prints the marker's cursor: `--since @1840`
and `--until @1844` name exactly that marker, whatever happened since; in `log` and `export`
alike, `@C` must be a marker's cursor (to start after any entry, use `--after C`). `--tag` and `--grep`
narrow further. `log redact REGEX` hides matches in the whole log, lines already captured
included, until the device is detached; it cannot be undone, and `--grep` then matches the
redacted text.

`--json` returns the same entries as data, for filtering or computing on them:

```console
$ tap-agent log mark checkout
$ tap-agent tap 'text=Pay'
$ tap-agent log mark paid
$ tap-agent log --since checkout --until paid --package com.shop --json
{
  "dropped": 0,
  "omitted": 0,
  "more": 0,
  "next": null,
  "device_clock_offset_s": 0.012,
  "steps": {"5f0c2a9d41b7e6a3": {"seq": 4, "action": "tap"}},
  "entries": [
    {"cursor": 1840, "at": "2026-11-14T12:01:47.600Z", "kind": "marker", "marker": "checkout"},
    {"cursor": 1841, "at": "2026-11-14T12:01:47.636Z", "kind": "action", "action": {"operation": "tap", "target": "text=\"Pay\"", "finished": false, "request_id": 42, "step": "5f0c2a9d41b7e6a3"}},
    {"cursor": 1842, "at": "2026-11-14T12:01:47.880Z", "kind": "line", "line": {"priority": "W", "tag": "Payments", "message": "retrying (timeout)", "packages": ["com.shop"], "uid": 10123, "pid": 2346, "tid": 2357, "device_time": 1794657707.879}},
    {"cursor": 1843, "at": "2026-11-14T12:01:47.881Z", "kind": "action", "action": {"operation": "tap", "finished": true, "outcome": "ok", "duration": 0.243, "request_id": 42, "step": "5f0c2a9d41b7e6a3"}},
    {"cursor": 1844, "at": "2026-11-14T12:01:48.300Z", "kind": "marker", "marker": "paid"}
  ],
  "cursor": 1844
}
```

Each entry has its `kind` and the matching one of `line`, `action`, `marker` or `gap`, its
`cursor` and `at` (the server's clock, UTC). A line also has `packages` (the apps installed under its uid; none for a system
process) and `device_time` (the device's clock, in seconds; `device_clock_offset_s` is how far
it is ahead of the host's). `steps` names the session's steps the actions refer to. `omitted`
counts earlier entries left out by `--limit`, `more` later ones and `next` the flags that read
them (`--after` the `cursor`, the same end and filters), `dropped` entries the server no longer
has (it keeps the newest 16 MiB). `--tail N` counts actions and markers too. See
[Device logs](../guide/logs.md).
MCP screenshot/capture now also accept `path`, matching CLI `-o`.

## Record an owned device

```sh
tap-agent media-record start --max-seconds 10
# Perform explicit app actions.
tap-agent media-record stop -o evidence.mp4
```

Recording uses the daemon's configured scrcpy, never a watcher or an implicit app lifecycle.
Default: silent video, up to 30 seconds/16 MiB. Add `--audio-source output` (API 30+, **mutes
speakers**), `playback` (API 33+, retains sound but apps can opt out) or `mic` (**captures the
microphone**). `--no-video --audio-source mic` records audio only, up to 60 seconds/3 MiB.
`--max-seconds` is an integer within the track limit. Options for tracks/duration belong to start;
`-o` belongs to stop. Video-only is MP4, video+audio MKV, audio-only Opus.

Start acknowledges spawning, not first-frame readiness; refusal can surface on stop. Stop before
detach/release to retrieve the clip: unfinished recordings are discarded. The SDK verifies bytes,
then the agent saves a private canonical copy (0700 directory, 0600 file) under `.tap/agent/`.
An optional new `-o` copy never overwrites. If that copy fails, the error names the retained
canonical artifact: copy it locally, **do not repeat the device stop**. No failed start/stop is
replayed automatically. The existing recorder startup/shutdown timing limits still apply.

`export` reads the stored step history and does not need a live session; `export --calls` adds
the server's raw record of each call while the session lives.

## What else it can do

Beyond reading and tapping, the agent can drive most of what a test can. Each line links to
its commands in the [reference](commands.md).

| Area | Commands |
|---|---|
| [Elements](commands.md#acting-on-elements) | `tap` (`--long`, `--double`), `fill`, `type`, `clear`, `submit`, accessibility `action`s, slider `progress`, `keyboard` |
| [Gestures](commands.md#gestures) | `scroll`, `swipe`, `fling`, `drag`, `pinch`, always on a node, never at coordinates |
| [Apps and permissions](commands.md#apps-and-permissions) | `app launch`, `cold-launch` (with intent extras), `stop`, `clear`, `install`, `grant`, `open-link`, app languages; the runtime-permission dialog |
| [System UI and display](commands.md#keys-system-panels-and-the-display) | `key back`, `home`, `recents`, …; the notification shade and quick settings; `rotate`; screen on, off and unlock |
| [Device conditions](commands.md#device-conditions-and-location) | animations, dark mode, font scale, density, airplane mode, Wi-Fi, mobile data, languages, accessibility display settings, a mock location |
| [Notifications and toasts](commands.md#notifications-toasts-and-the-clipboard) | list, await, open (or press an action button) and dismiss notifications; await toasts; the clipboard |
| [Files](commands.md#files-and-the-gallery) | `push` and `pull` files, add photos and videos to the gallery |

Temporary rotation, conditions, location and files pushed through their corresponding commands
are restored/removed on detach or release. App lifecycle changes and private app data are **not**
rolled back. Stop media recording explicitly before releasing its device. Named step archives
remain available after release.
