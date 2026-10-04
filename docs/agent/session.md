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

`--settle` waits for the screen to stop changing after the action and prints the difference:
`+` added and `-` removed nodes. That is usually enough to pick the next step without another
snapshot. A screen that never goes quiet (a blinking cursor, a spinner) prints
`(screen still changing after 10s)` with the difference so far.

## Check the result

```console
$ tap-agent wait "text=Dark theme"
text=Dark theme is visible
```

`wait` polls on the device until the target is visible (`--gone`, `--one`), up to `--timeout`
(10 s by default). Use it instead of a sleep, and to confirm what an action was supposed to do.

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

## Export and release

```console
$ tap-agent export -o session.json
wrote 9 events (3 failed) to session.json
$ tap-agent release
released session 'agent' (detached emulator-5554)
```

[Sessions to tests](export.md) explains the export and turns it into a test.

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

Whatever the agent changes on the device (rotation, conditions, location, files it pushed) is
restored or removed when the session is released.
