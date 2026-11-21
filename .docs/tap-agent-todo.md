# tap-agent todo

Approved work on the agent surface, not started yet. Remove an item when it lands, together with
the test or device check that proves it.


## Plan

All items land on the history branch, one commit per item, each building and passing its tests
on its own. Order, by dependency and risk (agent-only first, then the items that change the
driver and the proto):

1. Agent-only command and output changes, in this order: output (text by default, `--json` on
   every read); `keyboard` / `screen` without `state`; `swipe --finger`; waits; `history keep`;
   `type TEXT [TARGET]`; `device-info` with the whole state; `media-record --max`; MCP settle by
   default; `devices unquarantine`. Each commit updates the MCP tools, `SKILL.md`, the MCP
   instructions, `docs/agent/*.md`, the generated reference, `api-coverage.json` and
   `CHANGELOG.md` with it.
2. `capture` with the device's history, then capture management (agent only).
3. Snapshots: the driver dump reports `editable` and the node's actions; the daemon decides
   interactivity from them and `ScreenNode` carries them; the agent shows what each node accepts
   and collapses the keyboard window. The status/navigation-bar item starts with the device
   research it needs.
4. Keys: the full key table and `key --list`, then press modes (proto, driver, both SDKs, agent).
5. The soft-keyboard condition (host core, daemon, proto, driver, both SDKs, agent).

Decisions taken for this plan: `swipe TARGET --finger DIRECTION`; `wait-event` folds into
`watch --until-first`; recordings are read with `history --kept NAME`; MCP action tools settle by
default (each action then waits for the screen to be quiet, `stable_for` 0.5 s by default).

Checks: agent, Python, Studio, watcher and daemon unit tests per commit; `buf lint` and the
protocol tests for proto changes; the strict docs build. Items 3, 4 and 5 also need the API 29 /
API 34 device check, run only when the user agrees.

## Snapshot: decide interactivity from the node, not its class name

The daemon sets `ScreenNode.interactive` in
`host/daemon/src/main/kotlin/io/github/noamcohen48/tap/daemon/snapshot/ScreenSnapshots.kt` as
`clickable || longClickable || checkable || scrollable || editable`. Here `editable` means that
`className` ends with `EditText`, because the hierarchy dump (`HierarchyParser.kt`, the
UiAutomator XML) has no editable attribute and no action list. That rule is wrong both ways:

- A text field whose class is not `…EditText` (`AutoCompleteTextView`, custom views, some Compose
  and WebView inputs) is not editable. It is interactive only if it is also clickable.
- A node that only offers accessibility actions (custom actions, scroll forward/backward without
  `scrollable`, `setText`, `setProgress`) is left out of `snapshot -i`.
- A focusable-only input is left out of `snapshot -i`.

Do:

- Have the driver report, per node, what `AccessibilityNodeInfo` says: `isEditable` and the
  action list (standard and custom). The driver writes its own dump
  (`device/driver/core/.../HierarchyDump.kt`), which already adds attributes UiAutomator's dump
  lacks (`showing-hint`, `content-invalid`, `error`, `window-type`). Add `editable` and an
  `actions` attribute there (the action ids Tap can perform, as `inspect` names them), and parse
  them in `HierarchyParser.kt`. Snapshots stay diagnostic reads, never on the selector hot path.
  The daemon installs the driver it was built with, so there is no older-driver fallback to the
  class-name rule.
- In the daemon, compute `interactive` from those facts: editable, clickable, long-clickable,
  checkable, scrollable, or offering an action Tap can perform. Never infer from the class name.
- Remove the `editable` class-name check. Update `.docs/protocol-contract.md` if the wire changes,
  and `.docs/server-api.md` for the `ScreenNode` fields.
- Tests: daemon snapshot tests for a non-EditText editable node, an actions-only node and a
  focusable-only node. A device check on the fixture app (an `AutoCompleteTextView`, a Compose text
  field and a custom-action row) on the API 29 and API 34 matrix.

## Snapshot: say what each node accepts

`snapshot` lines show state (`focused`, `checked`, `selected`, `scrollable`, `disabled`) but not
what a node can do. To find out, you have to run `action @eN` or `inspect @eN`.

Do:

- Add short affordance words to each interactive node's line, from the node's facts (after the
  item above, from its editable flag and actions): for example `tap`, `long`, `check`, `edit`,
  `scroll`, plus `actions` when it offers other accessibility actions. Keep them in one fixed
  order and only for what the node really accepts.
- Show them in every view (`-i`, default, `--all`) and in the `--settle` diff, so a line is read
  the same way everywhere.
- Update `clients/agent/tap_agent/render.py` (`node_line`), the MCP snapshot text, `SKILL.md`,
  `docs/agent/session.md` and the tap-agent reference examples. Agent tests for each word.

## Snapshot: collapse the keyboard window by default

Today `snapshot` shows every window unless `--hide-keyboard` / `--hide-system-chrome` is given.
The keyboard's key nodes are noise for almost every task (agents type with `fill` and `type`), but
the fact that a keyboard is up matters: it covers the bottom of the screen, and a tap behind it is
refused as covered by another window. Hiding it by default would leave that refusal unexplained.

Do:

- By default, print an input-method window (`window_type` 2) as its header line only, with a
  count, for example `# keyboard · window 2 (24 nodes, --keyboard to show)`.
- Add `--keyboard` to show its nodes. Keep `--hide-keyboard` to drop even the header, or remove it
  if nothing needs that once the header is one line.
- Apply the same in the `--settle` diff and the MCP snapshot tool; update `SKILL.md`,
  `docs/agent/session.md` and the reference examples. Agent tests for the collapsed, expanded and
  hidden cases.

## Snapshot: hide only the status and navigation bars, not every system window

Keep showing system windows (`window_type` 3) by default: they include the notification shade
after `panel notifications`, quick settings, volume and (to confirm on a device) system dialogs
such as "app isn't responding". Hiding them by default would make an agent think the shade did not
open. Only the status bar and navigation bar are routine noise, and the window type alone cannot
tell them apart from the rest.

Do:

- Check on the API 29 and API 34 matrix which window package and title the status bar, navigation
  bar, shade, quick settings, volume panel and ANR dialog report.
- If the bars can be identified reliably from that, collapse just those two to their header line
  by default (as for the keyboard), and keep other system windows in full. If they cannot, leave
  the default as it is.
- Rework `--hide-system-chrome` to match: decide whether it still hides every system window or
  only the bars.

## Output: text by default everywhere, `--json` on every read

Status: done (`inspect`, `elements`, `server-info`, `device-info`, `exists`, the `app` reads,
`snapshot --json`, MCP `capabilities`).

Today the output form depends on the command. `inspect`, `elements` (also with `--texts`),
`device-info`, `server-info`, `app process` and `snapshot --candidates` print JSON with no text
form. `history`, `deaths`, `capabilities`, `toast --history` and `watch` print text and take
`--json`. Everything else prints text only. A reader cannot tell which form a command prints
without trying it.

One rule instead: every command prints readable text by default, and every command that reads
something takes `--json` for its complete structured form. Commands that act (`tap`, `fill`,
`wait`, …) stay text only: their one result line has nothing more to structure. Text suits the
usual reader, an agent or a person (shorter, and it can leave out what is empty), and `--json`
is always complete, so a script never parses text.

Do:

- `inspect`: text leads with the node's `snapshot` line, then only what is set (`package`,
  `bounds`, `state` words, `actions`, `range`); `--json` is today's full object.
- `elements`: one snapshot-style line per match, and with `--texts` one text per line; `--json`
  for the array.
- `device-info` / `server-info`: `key  value` lines, unset values left out; `--json` for the full
  object.
- `app process`: one line (`com.shop running, pid 4242`). `app installed`, `exists` and `count`
  already print one plain value and stay.
- `snapshot`: replace `--candidates` (which switches the output to JSON as a side effect) with
  `--json`, the complete structured snapshot including selector candidates.
- MCP: give each read tool the same `as_json` parameter, so the CLI and MCP stay one API.
- This changes output that scripts may read (mainly `inspect` and `device-info`): note it in
  `CHANGELOG.md`. Update `SKILL.md`, `docs/agent/session.md`, the MCP instructions, the tap-agent
  reference and the agent tests for each command's text and JSON forms.

## `keyboard` and `screen`: no `state` word; a soft-keyboard condition

Status: the `state` word is gone (done); the soft-keyboard condition is still to do.

`keyboard` takes `state|hide` and `screen` takes `state|on|off|unlock`, with `state` as the
default. The extra word adds nothing.

Do:

- `keyboard` with no word reads (`keyboard shown` / `keyboard hidden`); `keyboard hide` hides it
  (one Back key, only when a keyboard shows) and prints the state read back. Drop `state`.
- The same for `screen`: no word reads, `on`, `off` and `unlock` act. Drop `state`.
- There is no `keyboard open`: Android shows a soft keyboard only for a focused text field, at the
  app's request, and the driver runs outside the app. `tap` on the field brings it up.
- Add `condition soft-keyboard on|off`, Android's "Show virtual keyboard" with a hardware keyboard
  (`Settings.Secure` `show_ime_with_hard_keyboard`), restored on release like other conditions. On
  emulators with a hardware keyboard the soft keyboard otherwise never shows, even for a focused
  field. Report it in `condition` and `device-info`; check on the API 29 and API 34 matrix.
- Update the MCP tools, `SKILL.md`, `docs/agent/session.md`, the reference and the agent tests.

## `key`: every Android key name, and long, held, double and repeated presses

`key` knows 17 names (`clients/agent/tap_agent/targets.py`, `KEYS`) plus raw key codes, so
`key volume-up`, `key media-play-pause` or `key camera` fail. A `KEYCODE_` prefix is stripped, so
`KEYCODE_VOLUME_UP` looks supported but is not. The driver presses with UiAutomator's
`pressKeyCode` (`GestureCommands.kt`): down and up back to back, with no way to hold a key.

Do:

- Generate the key table once from Android's `KeyEvent` `KEYCODE_*` constants (about 300; codes
  never change, new ones are only added). Accept every name in lowercase-dash form (`volume-up`,
  `media-play-pause`, `dpad-center`), keep the aliases (`backspace`, `recents`) and raw codes.
- Add `key --list`: every name with its code, offline. `--help` keeps the common ones.
- Add press modes, mirroring `tap`:
  - `--long`: down; after the device's long-press timeout (`ViewConfiguration`), a repeat down
    with repeat count 1 and `FLAG_LONG_PRESS`; up. The sequence `adb shell input keyevent
    --longpress` and Appium UiAutomator2's `longPressKeyCode` send.
  - `--duration D`: hold for D, sending repeat downs at the device's key-repeat interval (as a held
    physical key does, for example volume ramping), then up.
  - `--double`: two presses within the double-tap timeout.
  - `--count N` with `--interval`: N presses, for example stepping volume.
- The driver injects the events itself (as `TextInputCommands.kt` already does for typing). It
  always sends the up, also on cancellation or timeout; a lost connection after the first down is
  `INDETERMINATE` and never replayed. Extend `PressKey` in the proto (hold time, press count and
  interval), update `.docs/protocol-contract.md` and `.docs/server-api.md`, and the Python and
  Kotlin SDKs.
- What a press does is the device's: system keys (power, volume) go to Android's own policy, and a
  long press of volume-down often does nothing. Document that the command reports what was sent,
  not its effect. Key chords (volume-up and volume-down together, the accessibility shortcut) need
  several keys down at once: later, separately.
- Tests: driver unit tests for each event sequence; a device check on the API 29 and API 34 matrix
  (power long press opens the power menu, a held volume key ramps the volume).

## `capture`: a complete bundle, with the device's history

Status: done (the snapshot-outline pairing is not done).

`capture` saves the device's state now (screenshot, hierarchy, device info, driver log, logcat,
crashes, activities, windows). Its logcat includes Tap's device-side action lines, but not what
`history` has: the agent's steps and their outcomes (including steps refused before input), app
calls, queries, Tap's crash and ANR records, gaps and markers. A capture should be the one thing
to grab when something goes wrong, and hand on.

Do:

- Add the device's history to every capture, as text (`<serial>.history.txt`, what
  `history --logs --details` prints) and as JSON (`<serial>.history.json`, one `tap-history/2`
  document). Range: everything kept of the session's connection on that device, oldest first,
  across every page (follow `next_cursor` and merge the pages into one document; carry each
  page's time slice so every agent step appears exactly once). Include the agent's steps from
  before the first server entry.
- Add a `--last DURATION` (or `--from`) to bound it for long sessions; default the whole kept
  range.
- Reading history must never fail the capture: a failed read is a `history failed: ErrorType`
  line, like any other part, and the rest is still saved.
- Index the new parts in history like the others. Consider the same text + JSON pairing for the
  other parts (a `snapshot` outline next to the hierarchy XML).
- Update `SKILL.md`, the MCP instructions, `docs/agent/export.md`, `docs/agent/session.md` and the
  reference. Agent tests: both files exist, they match `history` for the same range, multi-page
  merge, a failed read.
- Also say in `SKILL.md` and `docs/agent/export.md` when to use which: `capture` first when the
  current screen matters (it is perishable), `history --last 30s --logs --details` for what led
  up to it.

## `capture`: manage captures (name, list, show, clean)

Today `capture` only takes a new bundle. Its folder is indexed in the agent's records
(`records.py`, the `artifacts` table: time, serial, attachment, directory, parts, failures) and
shown in `history`, but there is no way to name a capture, list the captures, find one again or
delete them: old bundles pile up under `.tap/agent/out` and must be removed by hand.

Do, shaped like `record`:

- `capture [NAME]`: take a bundle; `NAME` (the same rules as recording names) labels it and is
  part of its folder name. Unnamed captures get an id. Refuse a name already used in the session.
- `capture list [-L]`: one line per capture, newest first, with its time, name or id, serial and
  size; `-L` adds the folder, the parts and failed parts. `--device`, `--from` / `--last` select.
  Text by default, `--json` for the structured list.
- `capture show NAME|ID`: the bundle's folder and its files, so a capture can be found again
  without searching the disk.
- `capture clean NAME|ID`, and `capture clean --older-than DURATION` / `--all`: delete the
  bundle's files and its index entry. Delete only files the agent saved itself (the indexed parts,
  in their own private folder); refuse when the folder holds anything else.
- History keeps showing each capture at its time, by name; a cleaned one is shown as cleaned, not
  dropped silently.
- MCP tools with the same shape. Update `SKILL.md`, `docs/agent/session.md`, the reference and the
  agent tests (name clash, list order and filters, show, clean with a stray file in the folder).

## `scroll` / `swipe`: one meaning per direction word

Status: done.

`scroll X down` and `fling X down` move the content (reveal what is below: the finger moves up);
`swipe X down` moves the finger down. The same word means opposite gestures, so an agent that
mixes the commands scrolls the wrong way without noticing.

Do: keep the content meaning for `scroll` and `fling`. Give `swipe` explicit finger words
(`swipe X --finger up`, or `swipe X finger-up`), and have every gesture print its effect
(`scrolled down (finger up)`). Update the MCP tools, `SKILL.md`, docs and tests.

## Waits: one shape

Status: done, except `app visible`: it stays an `app` action whose help says it waits (the
allowed alternative), since `wait app=PKG` would add an `app=` term to every target.

Waiting has four shapes: `wait TARGET`; `toast TEXT`, which waits by default while `toast
--history` reads; `notification await` (a sub-word); `wait-event KIND`, `deaths --wait` (a flag)
and `settle`.

Do: `wait` stays the wait for what is on screen. Every event command reads by default and waits
with `--wait`: `toast --wait TEXT`, `notification --wait --title …`, `deaths --wait`. Fold
`wait-event` into `watch --until-first KIND` (or keep it as the single event wait, but not both).
`app visible` is a wait too: make it `wait app=com.shop` (or say in its help that it waits).
Update the MCP tools, `SKILL.md`, docs and tests; note the renames in `CHANGELOG.md`.

## History recordings: `history keep`

Status: done (`history --kept NAME` reads a kept range).

`record` keeps a range of history, `media-record` records video and audio, and Tap Studio has
"recordings" too. Recordings are already read with `history --recording`.

Do: move `record start|stop|list|clean` to `history keep start|stop|list|clean NAME`, and rename
`history --recording NAME` to match (`history --kept NAME`, or keep `--recording`, decided with the
rename). Remove `record`. `media-record` keeps its name for now. Update the MCP tools (`record`
→ the history tool), `SKILL.md`, `docs/agent/export.md`, the reference and the tests; note it in
`CHANGELOG.md`.

## `type`: target as a positional

Status: done.

`fill TARGET TEXT` and `clear TARGET` take the target first; `type TEXT --target T` takes it as a
flag, because `type` usually goes to the focused field.

Do: `type TEXT [TARGET]`: the focused field without a target, else the target after its focus
wait. Remove `--target`. Update MCP, docs and tests.

## `device-info`: the whole device state

Status: done (the soft-keyboard setting joins it with that condition).

`activity`, `screen`, `keyboard`, `condition` and `device-info` each read part of the device
state.

Do: keep the short commands for quick checks, and make `device-info` contain all of it: the top
activity, screen and keyguard, keyboard, every condition (and the soft-keyboard setting), as text
by default and `--json` (see the output item above).

## `media-record --max-seconds`: a duration

Status: done.

It is the only time option that takes a bare number of seconds; every other one takes a duration.

Do: `--max DURATION` (`--max 20s`), same limits (30 s with video, 60 s audio only).

## MCP: settle after actions by default

Status: done, after a failed settle after input stopped failing the step.

After almost every action an agent's next call is `snapshot`. Make MCP action tools settle by
default and return the diff (`settle=false` to opt out), halving the calls. The CLI keeps its
default (`--settle` opt in). Document it in the MCP instructions; MCP tests for both.

## `unquarantine` under `devices`

Status: done on the CLI; MCP keeps a separate `unquarantine` tool so `devices` keeps its
read-only hint.

It is the only device-lifecycle command outside a group. Make it `devices unquarantine SERIAL`,
next to the listing that shows quarantined devices; remove the top-level verb. Update MCP, docs
and tests.
