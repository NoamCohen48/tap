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

Every action needs exactly one matching node; with none or several it fails before touching
the device, and says so.

## Acting

`tap`, `tap --long`, `tap --double`, `fill <target> <text>` (replaces the text), `type <text>`
(key events into the focused field), `clear`, `scroll <target> up|down|left|right` (`down`
reveals content below), `swipe <target> <direction>`, `fling <target> <direction>` (as
`scroll`, fast; the content may keep moving), `drag <target> <destination>` (long-press, move
onto the destination node, drop), `pinch <target> open|close [--percent 80]`,
`key back|home|recents|enter|tab|delete|…`,
`panel notifications|quick-settings` (the status bar's panels: their nodes are in
`pkg=com.android.systemui`; `key back` closes them, twice from quick settings on newer
Android; use `--settle` before opening another), and
`app launch|cold-launch|foreground|background|open-link|stop|clear|install|uninstall|grant|revoke|granted|running|locale <package> [activity|URI|APK|PERMISSION|TAGS]`
(`foreground` returns to the app as it was left; `app open-link <package> myapp://x` opens a
deep link in the app, `--any-app` lets Android pick the handler).
A node that another window covers completely is not found; an action on a node it covers
partly fails as not interactable (OBSCURED) instead of tapping whatever is on top. Either way,
close the covering window (often `key back`) first.

Device state: `rotate portrait|landscape|natural|left|upside-down|right|auto` (prints the
resulting orientation; `auto` hands rotation back to the sensor; `release` restores the
device's own setting), `screen` (on/off and lock state), `screen on|off|unlock` (`unlock`
wakes and dismisses a lock screen without a PIN; a PIN is never entered).
`condition` prints animations, dark mode, font scale and density; `condition animations
on|off`, `condition dark-mode on|off` (Android 10+), `condition font-scale 0.5..2.0` and
`condition density <dpi>|reset` change one and print the value read back. `app locale <package>`
prints the app's own languages, `app locale <package> fr-FR,en` sets them and `app locale
<package> system` makes the app follow the system again (Android 13+). All of these, like rotation, are put back on `release`.

Keyboard and clipboard: `keyboard` prints whether a soft keyboard shows, `keyboard hide`
hides it (nothing is pressed when none shows); `submit <target>` runs the field's keyboard
action key (Search, Go, Send, Done; Android 11+), and the field must have input focus, so tap
it first. `clipboard` prints the device clipboard, `clipboard <text>` sets it.
`toast [text] [--contains] [--package PKG]` waits for a toast (one shown in the last 3.5 s
counts; any app's unless `--package`) and prints its text and app.

Permission dialogs: `permission` waits for one and lists its buttons
(`allow`, `allow-foreground-only`, `deny`, …); `permission <choice>` presses one. Buttons are
found by id, so this works in any language.

Add `--settle` to an action to wait until the screen stops changing and print the difference:
`+` added nodes, `-` removed ones. That is usually enough to decide the next step without a
new `snapshot`. `settle` alone does the same without acting.

`wait <target>` waits for a node (`--gone`: until it disappears; `--timeout 30s`).

## Evidence

`screenshot` saves a PNG and prints its path (look at it only when the text is not enough);
`capture` saves screenshot, hierarchy, device info and driver log together. Files go under
`.tap/agent/` unless `-o` says otherwise.

## Exporting the session

`export` prints the session's event log as JSON (`-o FILE` writes it): every device call the
session made, in order, with its outcome — commands with the selector they used (a ref appears
as the selector it stood for) and app changes such as launch or clear. Snapshots, screenshots
and `devices` are not in it. Export before `release`: the log ends with the session. The JSON
is language-neutral; turn it into a test yourself if asked to.

## Several devices or sessions

`attach` another serial to the same session and pass `--device <serial>` to device steps.
`--session <name>` (or `TAP_AGENT_SESSION`) keeps separate sessions apart; `sessions` lists
them.
