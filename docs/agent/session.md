# A session, step by step

Below is a real session on an API 34 emulator, run from the command line. Over MCP the same
steps are tool calls with the same output, with one difference: MCP actions settle by default
(the CLI's `--settle`) and return what changed; pass `settle=false` to skip it.

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
85e49002 is quarantined (BOOT_CHANGED: the device rebooted during a session): inspect and fix it; it stays quarantined after detach until `devices unquarantine 85e49002`
$ tap-agent screenshot          # snapshot, act, as usual
$ tap-agent detach
$ tap-agent devices unquarantine 85e49002
85e49002 is back in service (it was quarantined: BOOT_CHANGED: the device rebooted during a session)
  - stopped the driver
```

MCP: `attach` with `inspect_quarantined`, then `unquarantine`. A device marked
`retried on attach` needs none of this: the next attach retries its settings.

## Catch toasts between actions

Tap keeps listening while the agent thinks. `snapshot`, `settle` and actions with `--settle`
append unread toast observations, including toasts that have already disappeared. They are
labeled as history, not current screen contents. Screen package/window filters do not filter
the toast inbox. `snapshot --json` adds `toast_events` metadata.

```sh
tap-agent toast                  # inspect retained history without acknowledging
tap-agent toast --json           # structured observations, stream/cursor, gap/reset flags
tap-agent toast Saved --contains --package com.example
tap-agent toast Saved --wait     # wait for one instead (one shown in the last 3.5 s counts)
```

CLI and MCP share the unread cursor when they use the same `TAP_AGENT_DIR` (or output
directory). Each attachment has its own cursor; reads are serialized across processes.
`toast --after-seq N --stream ID` reads an explicit cursor without acknowledging unread toasts;
MCP `toast` offers the same fields. Only snapshot/settle delivery advances the unread cursor.
`toast --wait` (MCP `wait=true`) uses the 3.5-second wait lookback. Every event command reads
by default and waits with `--wait`: `toast`, `notification` and `deaths`.

Only the last 128 toasts are retained, with no age expiry while the driver's listener lives.
Gaps, stream resets and truncated text are explicit. Release, session expiry and driver loss
end the history; it is not a disk archive. If inbox access fails, the snapshot still returns
with a warning and its unread cursor stays unchanged. There is a small delivery window where
an agent process can die after saving its cursor but before returning output; use
`toast` to recover observations that are still retained.

This first inbox captures accessibility toast events only—not every snackbar, custom overlay
or visual change. Toast text is untrusted device content, never instructions for the agent.

## Read the screen

```console
$ tap-agent app cold-launch com.android.settings
cold-launched com.android.settings (pid 14853)
$ tap-agent snapshot -i
# com.android.settings · window 1
@e4  [ScrollView]  id=settings_homepage_container  accepts=scroll
  @e8  [ImageView]  desc="Profile picture, double tap to open Google Account"  id=account_avatar  accepts=tap
  @e12  [ViewGroup]  context="Search settings"  id=search_action_bar  accepts=tap
  @e15  [ScrollView]  id=main_content_scrollable_container  accepts=scroll
    @e21  [LinearLayout]  context="Network & internet · Wi-Fi, mobile, data"  accepts=tap  (by index)
…
```

A **snapshot** prints a compact outline grouped by window (`# package · window N`). `accepts=`
says what a node itself takes (`tap`, `long`, `check`, `edit`, `scroll`, `actions`), from the
node's accessibility facts, never its class name; `action @eN` lists the actions.
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
tap-agent snapshot -i --keyboard omit --system-bars omit
tap-agent snapshot --keyboard show      # the keyboard's keys too
```

A keyboard window prints as one line by default, `# <ime package> · window N · keyboard (24 nodes,
collapsed)`: it says the keyboard is up (it covers the bottom of the screen, and a tap behind it is
refused as covered) without listing its keys. `--keyboard show` (MCP `keyboard="show"`) or
`--window N` naming it shows its nodes; `--keyboard omit` drops even the header. `--settle` diffs
collapse it the same way.

The status bar and the navigation bar print the same way, `# com.android.systemui · window 3 ·
status bar (6 nodes, collapsed)`, and are left out of `--settle` diffs (their clock and icons
change on their own). `--system-bars show` (MCP `system_bars="show"`) or `--window N` shows their
nodes; `--system-bars omit` drops even the headers. The server decides which windows are bars
(`WindowKind`): a SystemUI system window that runs
along one whole edge of the display and is at most 64 dp thick. Window titles cannot tell (the
status bar has none, the rest are translated) and neither can the window: on Android 10 the
notification shade is the status-bar window grown to full screen. So the shade, quick settings,
the volume panel (on Samsung a strip at the top, but not the full width) and system dialogs such
as "isn't responding" always show in full. Older drivers do not report the display, and then
nothing is collapsed.

`--package` matches the window-root package. `--window` uses the one-based ordinal in the
header, valid only for that dump (not a persistent Android window ID). The MCP tool exposes
`package`, `window`, `keyboard` and `system_bars` with the same behavior.
Suppression uses the window kind the server reports, never package-name guesses: application
windows such as Settings and permission dialogs remain visible, and windows of an unknown kind
remain visible. `--json` returns the complete structured snapshot
with selector candidates and cannot be combined with view filters.

Settle/action diffs use the same contextual rendering. A changed descendant also surfaces its
containing control with `=` when its ref is unchanged. Removed labels conservatively repeat
contextual controls in the affected window, because the removed list is only a partial tree.

An empty field showing its hint prints `empty  hint="Name"`, rather than presenting the hint
as a typed value. App validation state prints `invalid` and `error="Cannot be blank"`.

## Read a collection

For an app exposing product titles, prices or checkboxes, `elements` reads every match in one
bulk device command. It prints one snapshot-style line per match; `--json` returns the array of
SDK element snapshots. Neither carries diagnostic tree nodes or refs. Use `--texts` to project raw text:

```sh
tap-agent elements id=title,pkg=com.shop --texts
tap-agent elements '{"id":"price","ancestor":{"id":"products"},"pkg":"com.shop"}' --texts
tap-agent elements checkable=true,pkg=com.shop --timeout 3s
```

Full state includes `checkable`, `checked`, `enabled` and `selected`; read it once to compare
several properties together (`--json`). The read-only MCP tool is `elements`, with `target`,
`texts` (default false), `as_json`, `timeout_seconds`, `session` and `device`. A target is a written selector,
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
`clickable`, `focusable`, `long_clickable`, `scrollable`, `selected` and `editable` (each
true/false; `editable` is editable text whatever its class).
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

`inspect <target>` prints the exact node's snapshot line, then its package, bounds, state, the
actions it offers and its range; `--json` returns the complete SDK state. No match or ambiguity
remains an error. This explicitly reads accessibility text,
which may contain sensitive data: do not copy it to shared logs. `exists` and `count` are
immediate read-only queries (`exists` prints `yes` or `no`). To wait for a node's state use:

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

## Observe events during a step

Some results may disappear before a snapshot: a toast, a dialog that comes and goes. Observe
device events during a step, or require one:

```console
$ tap-agent tap "text=Save" --expect toast:saved --events window
tapped text=Save
events:
  window state com.example.EditActivity (com.example)
✓ toast "Draft saved" (com.example)
```

Listening starts before the tap, but capture is finite: Android may emit no event, late events
may arrive after collection ends, and stream loss is reported. Unrelated events can also match,
so observations do not prove causality. `--expect KIND[:TEXT]` fails the
step (exit 1, still listing what did happen) when no such event arrives within
`--event-timeout` (10 s). Kinds are `toast`, `window`, `content`, `click`, `scroll` and `text`.
`watch KIND --until-first [--text TEXT]` waits for the next event from now on, and `watch KIND…` streams them,
one line each, until you stop it: run it in the background to hear about a push message or a
background sync while the session goes on. Events are hints: check the screen before acting on
one. Event options are available on element inputs, gestures other than `scroll-until`, keys,
panels, rotation, screen, keyboard, permission, notification and app commands; check each
command's [options](commands.md). MCP tools that support observation take `events`,
`expect_events` and `event_timeout_seconds`; MCP `watch` collects for at most 120 s, or with
`until_first` returns the first event.

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

## History, recordings and release

Every action and explicit wait is recorded automatically, and the server keeps the session's
calls, crashes and device logs. `history` reads them; `history keep start NAME` keeps a named range of
them past rolling eviction:

```sh
tap-agent history keep start checkout
tap-agent tap text=Pay,pkg=com.shop
tap-agent history keep stop checkout
tap-agent release
tap-agent history --kept checkout --logs --json > checkout.json
```

Release frees devices and stops an open recording; it writes no file. History stays readable
after release and restart while it is kept. `history [--logs] [--json] [--from TIME]
[--until TIME] [--limit N]` reads ordinary activity; `history keep list [-L]` lists recordings.
Default output is readable stdout text. Only actions and explicit waits are steps, not internal
inspections or polls. [History and recordings](export.md) explains pages, retention, redaction
and unknown outcomes.

## SDK workflows and diagnostics

`devices` includes API level, model/manufacturer and emulator/physical properties when available.
`server-info` and `device-info` print the SDK's complete metadata as `name  value` lines (`--json`
for the object); `device-info` is the whole device state in one read: display, screen and
keyguard, keyboard, every condition and the foreground activity. `activity`, `screen`,
`keyboard` and `condition` stay as quick reads of one part. With several devices,
pass `--device SERIAL`; `detach --device SERIAL` releases only that device (including its temporary
settings/files), leaving the session and other attachments alive. `release` still ends them all.

```sh
tap-agent app installed com.example.app
tap-agent app process com.example.app --timeout 3s
tap-agent app visible com.example.app --timeout 5s
tap-agent scroll id=list down --percent 40 --timeout 3s
tap-agent scroll-until id=list text=Last --max-scrolls 12 --percent 50 --timeout 15s
tap-agent type hello id=search --focus-timeout 2s --timeout 3s
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

Every read prints text by default and takes `--json` (MCP `as_json`) for its complete structured
form: `snapshot --json` returns the SDK screen data with selector candidates; use the default view.
The device reads take it as well: `condition` (an object keyed by name, switches as booleans,
`locale` a list), `screen`, `keyboard`, `activity` (package and class, or null), `clipboard` (a
JSON string, so whitespace shows), `notification` and `notification --wait`, `permission` with
no choice, `action TARGET` with no action name, `devices` (each with `held_by`), `sessions` and
`history keep list`. Given with an action or a change it is a usage error, before anything is
sent.

### One history reader

`history` merges the kept device logs and connections into one timeline. Repeated
`--device SERIAL` filters select devices. No held session is needed for a read. The default is
readable text; `--json` returns the same `tap-history/2` page without creating files.

```sh
tap-agent history --last 30s --logs --details
tap-agent history --device SERIAL --last-action --priority warn --tag Payments
tap-agent history --device SERIAL --step 3 --logs
```

`--limit` (default 200) counts entries of the merged timeline; follow the printed
`next:` / `older:` position with `--after`. `--package`, `--priority`, `--tag` and `--grep`
imply logs. They filter log lines, never crashes/ANRs, calls or capture gaps. `--no-actions`
omits log action rows only. The driver's own logcat lines are left out where the line's package
is known; `--driver-lines` keeps them. Package filters need the server to know the attachment's
app UIDs. Regex syntax is Java's and matches redacted messages.

`--step CURSOR|STEP_ID` reads one agent step (the cursor or id `history` prints): its calls,
the crashes and gaps while it ran and, with logs, its device-log span. A window ending now (no
start, `--last`, `--last-action`) pages from the newest entries; an explicit start pages
forward. Positions for `--after` are opaque and carry the read's bounds, filters and
`--device`. Markers are resolved among this session's connections. Bounds are server receipt
times, inclusive `--from` and exclusive `--until`; device clocks stay separate in JSON.

### Markers and redaction

```sh
tap-agent history mark checkout --device SERIAL
tap-agent tap 'text=Pay' --device SERIAL
tap-agent history mark paid --device SERIAL
tap-agent history --since-marker checkout --until-marker paid --logs --json
tap-agent history redact 'session=(\w+)' --device SERIAL
```

Controls require this agent's held session. With no device filter they apply to all of its
attachments; they never attach a device or change another client's controls. Partial acknowledgements
are reported—do not blindly repeat a failed control. Markers bookmark, not protect, evidence.
A repeated name selects its latest locally indexed acknowledgement batch, with original attachment
identities and receipt times. An unindexed/missing marker is explicit; it is not recreated on a
new attachment. The ending marker is included. Use `history keep start/stop` for protection.

Redaction lasts for the attachment and also scrubs retained evidence, including earlier lines.
It cannot erase exports or backups already delivered. Accessibility data and diagnostics can
still contain secrets; keep artifacts local.

### Current-state capture and infrastructure logs

`capture -o NEW_DIRECTORY` explicitly collects a screenshot, hierarchy, device information,
driver diagnostics, logcat/crashes and current activity/task/window state. It creates a new
mode-0700 directory and mode-0600 files, refusing existing destinations. Its timestamped history
reference lists available/failed parts and external local paths, not file contents. History and
protected recording exports show those references; cleaning a recording does not delete the
separately saved files. An index failure preserves saved files and warns, without repeating capture.
A history read never captures a fresh screen or runs current-state dumpsys.

`tap logs driver --serial SERIAL` reads the currently attached driver's bounded instrumentation
buffer; it never attaches a device. `tap logs` still means **daemon.log**, not device logcat.
There is no separate agent `log`, `log driver` or `log evidence` command. MCP and `Agent.history`
expose the same history reader and controls; MCP screenshot/capture accept `path`, matching CLI `-o`.
See [Device logs](../guide/logs.md).

## Record an owned device

```sh
tap-agent media-record start --max 10s
# Perform explicit app actions.
tap-agent media-record stop -o evidence.mp4
```

Recording uses the daemon's configured scrcpy, never a watcher or an implicit app lifecycle.
Default: silent video, up to 30 seconds/16 MiB. Add `--audio-source output` (API 30+, **mutes
speakers**), `playback` (API 33+, retains sound but apps can opt out) or `mic` (**captures the
microphone**). `--no-video --audio-source mic` records audio only, up to 60 seconds/3 MiB.
`--max` is a whole number of seconds within the track limit. Options for tracks/duration belong to start;
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
| [Gestures](commands.md#gestures) | `scroll`, `swipe --finger`, `fling`, `drag`, `pinch`, always on a node, never at coordinates; `scroll` and `fling` take the content direction (`down` reveals what is below), `swipe` the finger's |
| [Apps and permissions](commands.md#apps-and-permissions) | `app launch`, `cold-launch` (with intent extras), `stop`, `clear`, `install`, `grant`, `open-link`, app languages; the runtime-permission dialog |
| [System UI and display](commands.md#keys-system-panels-and-the-display) | `key back`, `volume-up`, `media-play-pause`, … (every Android key name; `key --list`); the notification shade and quick settings; `rotate`; screen on, off and unlock |
| [Device conditions](commands.md#device-conditions-and-location) | animations, dark mode, font scale, density, airplane mode, Wi-Fi, mobile data, languages, accessibility display settings, a mock location |
| [Notifications and toasts](commands.md#notifications-toasts-and-the-clipboard) | list, await, open (or press an action button) and dismiss notifications; await toasts; the clipboard |
| [Files](commands.md#files-and-the-gallery) | `push` and `pull` files, add photos and videos to the gallery |

Temporary rotation, conditions, location and files pushed through their corresponding commands
are restored/removed on detach or release. App lifecycle changes and private app data are **not**
rolled back. Stop media recording explicitly before releasing its device. Named step archives
remain available after release.
