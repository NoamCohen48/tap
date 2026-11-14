---
name: tap-android
description: Drive an Android device or emulator to explore an app, reproduce a bug or check a UI change, using the tap-agent CLI over a running Tap daemon. Use when asked to look at, click through or verify something in an Android app on a connected device.
---

# Driving Android with tap-agent

`tap-agent` runs one step per call against the Tap daemon, which must already be running
(`tap start`; exit code 3 means it is not). The session (the devices you attached) stays
in the daemon between calls and ends after 15 idle minutes or on `release`.

## Flow

```sh
tap-agent devices                                   # serials and whether they are free
tap-agent attach emulator-5554
tap-agent app cold-launch com.example.app            # force-stop, then start the app
tap-agent snapshot                                  # read the screen
tap-agent tap @e12 --settle                         # act; --settle prints what changed
tap-agent fill id=email me@example.com
tap-agent wait text=Welcome
tap-agent release                                   # when done: frees the device
```

## Discovering support

`tap-agent capabilities` explains SDK operation support on the attached device; `--json` gives
status, typed reasons, API requirements and remaining prerequisites. The read-only MCP
`capabilities` tool returns the same JSON. It changes no settings, grants no permissions and
starts no operations. Unsupported is a known blocker; conditional and unknown are not a promise
of success. Check a selected element's actions separately. Recording needs scrcpy on the daemon
host, and a present executable alone does not prove capture will work.

## Reading the screen

`snapshot` prints one line per node, grouped by window:

```
# com.example.app
@e3  [Button]  "Log in"  id=login_button
@e5  [EditText]  hint="Email"  id=email  focused
@e9  [TextView]  "Welcome"
```

`-i` shows only nodes you can act on; `--all` adds layout containers. `(by index)` marks a
node told apart only by its position (fragile), `(no selector)` one that cannot be targeted.
Nodes under `# com.android.systemui` or another package are other windows (status bar,
dialogs).

## Targets

- A ref from the latest snapshot: `@e3`. A ref keeps naming the same node while it stays on
  screen; once the node is gone the ref fails with "not on the screen any more".
- Or a selector, key=value terms joined by commas, all of which must match: `id=login_button`
  (that id in any package; `id=com.example.app:id/login_button` names the package),
  `text=Log in`, `text~=Log` (contains), `desc=Close`, `desc~=…`, `hint=Email`, `class=Button`,
  `pkg=com.android.permissioncontroller` (only that app's nodes), `index=1` (the second of
  several matches). Write `\,` for a comma inside a value. Without `pkg` a selector searches
  the whole screen, other apps' windows included; add `pkg=` when another window has a match too.

Also supported: boolean flags (`enabled=true`, `checked=false`, `focused=true`, checkable,
clickable, focusable, long_clickable, scrollable, selected), and `text:starts_with=Order` /
`text:regex=Order \\d+` (modes exact/contains/starts_with/ends_with/regex on text/desc/hint/class/pkg).
Nested JSON selector strings support all/any groups and parent/ancestor/child/descendant predicates,
e.g. `'{"text":"Save","enabled":true,"ancestor":{"id":"dialog"}}'`. Only the outer selector may
pick index or first. Bounded at 64 KiB / 32 levels / 256 nodes; no XPath.

`inspect TARGET` reads full SDK element state as JSON (may include sensitive text); `exists`
and `count` read immediate matches. `wait TARGET --state enabled|disabled|checked|unchecked|focused`
waits on state, `--state text|text-contains --value TEXT` on text, `--state count --value N` on count,
and `--until enabled=true,text=Pay` (target grammar, no ref/index/pkg) until the node satisfies all parts at once.
All command/wait budgets are 1ms..120s; MCP settle now also accepts
`timeout_seconds`. Do not treat a successful input as proof its expected result appeared.

Every action needs exactly one matching node; with none or several it fails before touching
the device, and says so.

## Acting

`tap`, `tap --long`, `tap --double`, `tap <target> --count 3 [--interval 100ms]`,
`tap <target> --duration 2500ms [--timeout 5s]` (physical hold; implies long press), `fill <target> <text>` (replaces the text), `type <text>`
(key events into the focused field; add `--sensitive` to either for a password or token, which keeps it out of the log and export), `clear`, `scroll <target> up|down|left|right` (`down`
reveals content below), `swipe <target> <direction>`, `fling <target> <direction>` (as
`scroll`, fast; the content may keep moving), `drag <target> <destination>` (long-press, move
onto the destination node, drop), `pinch <target> open|close [--percent 80]`,
`key back|home|recents|enter|tab|delete|…`,
`panel notifications|quick-settings` (the status bar's panels: their nodes are in
`pkg=com.android.systemui`; `key back` closes them, twice from quick settings on newer
Android; use `--settle` before opening another), and
`app launch|cold-launch|foreground|background|open-link|stop|clear|install|uninstall|grant|revoke|granted|running|locale <package> [activity|URI|APK|PERMISSION|TAGS]`
(`foreground` returns to the app as it was left; `app open-link <package> myapp://x` opens a
deep link in the app, `--any-app` lets Android pick the handler; `--extra KEY[:TYPE]=VALUE`,
repeatable, puts an extra on a launch or cold-launch intent, TYPE string (default), int, long,
float or bool).
A node that another window covers completely is not found; an action on a node it covers
partly fails as not interactable (OBSCURED) instead of tapping whatever is on top. Either way,
close the covering window (often `key back`) first.

Device state: `rotate portrait|landscape|natural|left|upside-down|right|auto` (prints the
resulting orientation; `auto` hands rotation back to the sensor; `release` restores the
device's own setting), `screen` (on/off and lock state), `screen on|off|unlock` (`unlock`
wakes and dismisses a lock screen without a PIN; a PIN is never entered).
`condition` prints animations, dark mode, font scale, density, the network switches and the
device languages; `condition animations
on|off`, `condition dark-mode on|off` (Android 10+), `condition font-scale 0.5..2.0` and
`condition density <dpi>|reset` change one and print the value read back;
`condition airplane-mode|wifi|mobile-data on|off` flip the real switches and `condition locale
fr-FR,en` sets the device languages. `location <lat> <lon> [--accuracy M] [--altitude M]` mocks the device
location (call again to move it). `app locale <package>`
prints the app's own languages, `app locale <package> fr-FR,en` sets them and `app locale
<package> system` makes the app follow the system again (Android 13+). `condition stay-awake
on` keeps the screen on while plugged in; `condition high-contrast-text|color-inversion|bold-text
on|off` set the accessibility display settings (bold text Android 12+). All of these, like
rotation, are put back on `release`.

Notifications: `notification` lists them as data (the shade stays closed; Tap's driver gets
notification access until `release`), `notification await --title T [--text X] [--contains]
[--package PKG]` waits for one, `notification open --title T` opens it as a tap does
(`--button "Mark as read"` presses an action button instead), `notification dismiss --title T`
swipes it away (an ongoing one is refused). Open and dismiss need exactly one match: add
`--package` or `--text` when several match. `activity` prints the activity on top
(`package/class`), e.g. to see where a notification or link landed.

Keyboard and clipboard: `keyboard` prints whether a soft keyboard shows, `keyboard hide`
hides it (nothing is pressed when none shows); `submit <target>` runs the field's keyboard
action key (Search, Go, Send, Done; Android 11+), and the field must have input focus, so tap
it first. `clipboard` prints the device clipboard, `clipboard <text>` sets it.
`toast [text] [--contains] [--package PKG]` waits for a toast (one shown in the last 3.5 s
counts; any app's unless `--package`) and prints its text and app.

Permission dialogs: `permission` waits for one and lists its buttons
(`allow`, `allow-foreground-only`, `deny`, …); `permission <choice>` presses one. Buttons are
found by id, so this works in any language. `--accuracy precise|approximate` picks the location
dialog's accuracy first (Android 12+).

Accessibility actions: `action <target>` lists what the node offers (standard actions such as
`expand`, `dismiss`, `scroll-forward`, and custom labels); `action <target> <name>` performs a
standard one, `action <target> <label> --custom` a custom one, without touching the screen.
`progress <target> <value>` sets a slider (SeekBar, Slider, RatingBar) in its own units.

Files: `push <local> <device-path>` (absolute; the directory must exist; never overwrites a file
Tap did not create), `pull <device-path> [-o FILE]` (prints the local path), `media <local>
[--name NAME]` adds a photo or video to the gallery so pickers list it. Release removes what
Tap pushed or added.

Debuggable private data: `app-data capabilities <package>` probes actual run-as. Stop the app
explicitly (`app stop`) before `app-data list <package> [relative-path]`,
`app-data read|write <package> <relative-path> <local-file>`, `app-data delete <package> <path>`,
or `app-data export-db|import-db <package> <database-name> <local-directory>`. No root, SQL,
printed contents, implicit restart or restore on release. Transfers are ≤ 64 MiB. Downloads
need new owner-only outputs; export requires a new directory. Keep database plus WAL/journal
files together, never SHM. Remote replacement requires `--overwrite`. After an interrupted
import, inspect/repair or explicitly clear fixture data before launching; do not retry blindly.

Add `--settle` to an action to wait until the screen stops changing and print the difference:
`+` added nodes, `-` removed ones. That is usually enough to decide the next step without a
new `snapshot`. `settle` alone does the same without acting.

`wait <target>` waits for a node (`--gone`: until it disappears; `--timeout 30s`).

`scroll`/`swipe --percent 40 --timeout 3s` control distance/budget. Use
`scroll-until CONTAINER DESTINATION --max-scrolls 12 --percent 50 --timeout 15s` for the bounded
SDK descendant loop; this is not end-of-list detection or a replay policy. All element inputs
accept `--timeout` (up to 120s). `type TEXT --target id=input --focus-timeout 2s --timeout 3s`
uses SDK tap/focus-wait/type: input budgets are per step, focus has its separate wait (default
10s); `--no-await-focus` explicitly skips it. Focus alone does not prove IME readiness; observe
the app/keyboard explicitly when needed. Keep `--sensitive` for secrets.

`app installed PACKAGE` queries installation; `app process PACKAGE --timeout 3s` returns the
SDK process identity; `app visible PACKAGE --timeout 5s` waits without launching/foregrounding.
`settle --package PACKAGE --signal tree|pixels|all --stable-for 300ms --timeout 5s` selects its
package/quiet period/signal. Package must be focused; timeout is still a changing-screen note,
not an app-state assertion. None of this uses synchronization/awaitIdle.

## Evidence

`screenshot` saves a PNG and prints its path (look at it only when the text is not enough);
`capture` saves screenshot, hierarchy, device info and driver log together. Files go under
`.tap/agent/` unless `-o` says otherwise. `log` prints the device log since the attach: the app's logcat lines with Tap's own actions
(`── tap  text="Pay"`) and markers between them, secrets redacted, at most 200 entries (`--limit`
up to 5000): from a start the first ones, then `(N more entries …; read on with --after C)`;
without a start the newest. Times are the host's. A phone logs hundreds of lines a second: add
`--package PKG`. After an action, `log --last-action --package PKG --priority warn` shows what
the app logged because of it; `log mark NAME` then `log --since NAME` brackets a longer stretch
(a name means its newest marker; `mark` prints the cursor, and `--since @CURSOR` names exactly
that marker); the last line (`cursor N`) feeds `log --after N`; `--until OTHER` (or `@CURSOR`)
ends the read at a second marker instead of now, and
`--json` returns the same entries as data (line / action / marker per entry) when you need to
filter or compute on them rather than read them. Your own steps are named in it (`── step 3 fill  [id]`) and `log --step 3`
prints just that step's part. `log driver` and `log evidence` save the Tap driver's log and the
device's failure evidence (log, crashes/ANRs, activity and window state) to
new private files (0600), never inline or overwritten. Treat accessibility data and logs as
potentially sensitive. `snapshot --candidates` returns structured SDK data instead of rendered
lines, including selector candidates; use the default view.

`media-record start --max-seconds 10` explicitly starts silent video through the daemon's scrcpy;
`media-record stop [-o NEW_FILE]` verifies and saves it privately. Default limits: video 30s/16 MiB,
audio-only 60s/3 MiB. Start can select `--audio-source output` (API 30+, mutes speakers), playback
(API 33+, apps can opt out), or mic (captures microphone); `--no-video` selects audio only.
Start acknowledgement is not first-frame readiness. Stop before detach/release; unfinished
capture is discarded. Failed start/stop is never replayed. Stop retains a private canonical copy
under `.tap/agent/` before an optional custom copy: if that copy fails, use the artifact named
in the error, do not repeat stop. No automatic app stop, launch or restore.

## Step history and export

Nothing to start: every action and explicit wait of a session is recorded as a step from the
first one, and the history stops at `release`. `export [-o NEW_FILE]` returns it as
`tap-agent-recording/2` on stdout or in a new 0600 file, also after release/offline; it does not
stop anything. `log mark NAME` puts a marker in the device log and in the history, so
`export --since NAME [--until OTHER]` (or `@CURSOR`) exports only the steps between markers.
While the session is live, `export --logs` adds each device's log for that range (narrow it with
`--package` / `--priority` / `--tag` / `--grep`: unfiltered it is mostly other processes) and
`export --calls` the server's record of every device call; each log action and call carries the
`id` of the step that caused it. A failed step's `outcome.error` is its Tap code (`NOT_FOUND`,
`WAIT_TIMEOUT`, `SESSION_UNUSABLE`). `history` lists saved histories with their state, stop
reason, times and markers (the newest 20 stopped ones are kept), `export NAME` exports an older
one, `history clean NAME` / `history clean --all` delete stopped ones.

Storage is private under `$TAP_AGENT_DIR/records` (default `.tap/agent/records`); CLI/MCP must
share that directory. A history belongs to one held connection: a new connection of the same
session name starts the next history. Steps retain actual device/resolved SDK selectors,
not transient refs or internal polls/snapshots. Sensitive text is redacted before storage;
clipboard writes always are. Do not put secrets in later waits/selectors/extras or unmarked inputs.
Output/exception messages are not saved. A failed action's status says what the device may have
seen: `rejected_before_input` (nothing sent), `failed` (one command sent, and its error code proves
it changed nothing) or `unknown` (the input may have happened: never repeat it without checking the
screen). Later steps are still recorded after an `unknown` or interrupted one.

Limits: 10,000 steps, 1 MiB/step, 32 MiB/history; a full history is stopped and the next one
starts. A post-input storage error preserves the action result with a warning not to repeat it.
`complete: true` means a stopped history with known outcomes, not a passing app/test assertion.
The export is language-neutral data, not automatic replay or generated test code.

## Several devices or sessions

`attach` another serial to the same session and pass `--device <serial>` to device steps.
`--session <name>` (or `TAP_AGENT_SESSION`) keeps separate sessions apart; `sessions` lists
them. `devices` includes API/model/emulator properties when available; `server-info` and
`device-info` return complete SDK metadata. `detach --device SERIAL` releases only that device,
including temporary settings/files, and keeps the held session and its other devices alive.

## Quarantined devices

A `QUARANTINED` device (see `devices`) is one Tap could not prove clean after a session. Never
run tests on it. To look at it and fix it: `attach SERIAL --quarantined` (MCP:
`inspect_quarantined`), then screenshot, snapshot and act as usual; Tap still puts back what
the sessions changed when you `detach`, and the device stays quarantined. When it is in a good
state, `unquarantine SERIAL` puts it back in service and says what is still off. Ask the user
before unquarantining a device you did not check. One marked `retried on attach` needs none of
this: the next attach retries its restore.
Synchronization, observed test-client liveness and passive watcher streams are not agent tools.
