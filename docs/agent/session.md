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

## Read the screen

```console
$ tap-agent app cold-launch com.android.settings
cold-launched com.android.settings (pid 14853)
$ tap-agent snapshot -i
# com.android.settings
@e4  [ScrollView]  id=settings_homepage_container  scrollable
@e8  [ImageView]  desc="Profile picture, double tap to open Google Account"  id=account_avatar
@e12  [ViewGroup]  id=search_action_bar
@e15  [ScrollView]  id=main_content_scrollable_container  scrollable
@e21  [LinearLayout]  (by index)
…
```

A **snapshot** prints the visible screen, one node per line, grouped by window (`# package`).
`-i` keeps only the nodes an action can target. Each line starts with a **ref**: `@e12` names
a selector the server found to match exactly that node, and keeps naming it while the node stays
on screen. `(by index)` marks a node that only its position among similar nodes singles out:
it has no id, text or description of its own, an accessibility gap in the app.

An empty field showing its hint prints `empty  hint="Name"`, rather than presenting the hint
as a typed value. App validation state prints `invalid` and `error="Cannot be blank"`.

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
window covers (a dialog, the shade) fails as `NOT_INTERACTABLE` / `OBSCURED`. Exit codes: `0`
ok, `1` the step failed, `2` usage, `3` no server running.

`screenshot` and `capture` (screenshot, hierarchy, device info, driver log) save evidence
under `.tap/agent/` and print the paths.

## History, named recordings and release

The raw session log is now `history` (previously `export`):

```console
$ tap-agent history -o session.json
wrote 9 events (3 failed) to session.json
$ tap-agent release
released session 'agent' (detached emulator-5554)
```

For a durable named flow, use `record start NAME` before the actions, `record stop` afterwards,
then `export NAME [-o FILE]`—even after release. `record list` lists archives and `record clean NAME`
deletes a stopped one. Only actions and explicit waits are steps, not inspections/polls.
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
rendered lines; use the default view. `logs device`, `logs driver` and `logs hierarchy` save SDK
diagnostics to new mode-0600 files, not inline text. `-o PATH` chooses a new file, never overwrites.
Hierarchy remains diagnostic only. Accessibility data and logs can contain secrets; keep them local.
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

`history --after-seq N` retrieves raw events after a known sequence number. Named `export` reads
stored action-and-wait recordings instead; it does not need a live session.

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
