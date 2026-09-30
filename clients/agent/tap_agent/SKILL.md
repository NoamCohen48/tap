---
name: tap-android
description: Drive an Android device or emulator to explore an app, reproduce a bug or check a UI change, using the tap-agent CLI over a running Tap daemon. Use when asked to look at, click through or verify something in an Android app on a connected device.
---

# Driving Android with tap-agent

`tap-agent` runs one step per call against the Tap daemon, which must already be running
(`tap start`; exit code 3 means it is not). The session (a device attached to your app) stays
in the daemon between calls and ends after 15 idle minutes or on `release`.

## Flow

```sh
tap-agent devices                                   # serials and whether they are free
tap-agent attach emulator-5554 com.example.app --cold
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
- Or a selector, key=value terms joined by commas, all of which must match: `id=login_button`,
  `text=Log in`, `text~=Log` (contains), `desc=Close`, `desc~=…`, `hint=Email`, `class=Button`,
  `pkg=com.android.permissioncontroller` (search that app's window), `index=1` (the second of
  several matches). Write `\,` for a comma inside a value.

Every action needs exactly one matching node; with none or several it fails before touching
the device, and says so.

## Acting

`tap`, `tap --long`, `fill <target> <text>` (replaces the text), `type <text>` (key events into
the focused field), `clear`, `scroll <target> up|down|left|right` (`down` reveals content
below), `swipe <target> <direction>`, `key back|home|recents|enter|tab|delete|…`,
`panel notifications|quick-settings` (the status bar's panels: their nodes are in
`pkg=com.android.systemui`; `key back` closes them, twice from quick settings on newer
Android; use `--settle` before opening another), and
`app launch|cold-launch|stop|clear|install APK|grant PERMISSION|running`.

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
