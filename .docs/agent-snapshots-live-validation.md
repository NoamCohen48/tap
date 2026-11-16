# Contextual agent snapshots: live validation

Validated 2026-11-16 against the committed contextual-snapshot daemon, bundled product driver
and Python agent. This was targeted interactive validation, not the full device regression
suite and not a timed usability study.

## Method

- Physical Samsung SM-J810G, Android 10 / API 29, serial `85e49002`.
- Medium Phone emulator, Android 14 / API 34, serial `emulator-5554`.
- Built `:host:daemon:installDist`; started an isolated JVM daemon explicitly with `tap start`.
  Both attachments used `--driver-start cold`, installing the matching driver.
- Captured one live SDK snapshot at each comparison point, then rendered that **same snapshot**
  with main's previous renderer and the new renderer. This isolates presentation differences
  from screen movement and ref alignment. Also exercised actual CLI snapshots and mutations.
- Saved private snapshots, paired rendered text, metrics and screenshots under
  `/tmp/tap-context-live-evidence/`. These local artifacts may disappear and are not committed;
  the non-personal excerpts and results below are the durable evidence.

## Results

| Screen | Previous interactive output | New output and observed result |
|---|---|---|
| Samsung Settings home | Five fully visible category rows were anonymous `LinearLayout (by index)` lines | All five gained names and summaries: Connections, Sounds and vibration, Notifications, Display, Wallpaper. Selected Display from its contextual line and tapped its ref; the destination snapshot showed Display controls |
| API 34 Settings home | Seven fully visible category rows were anonymous; search container had only its resource id | All seven gained category names/summaries; search gained `Search settings`. Tapped the Network & internet row ref and verified the destination heading plus Internet, SIMs, VPN and Private DNS rows |
| Samsung Display | Rows and `Off` switches were flat and detached from their meaning | Eight rows gained context; indentation placed switches under Outdoor mode, Blue light filter and Dark mode. No switches were toggled |
| API 34 Settings search, keyboard open | 45 interactive-output lines, mostly Gboard controls | `--hide-keyboard --hide-system-chrome` reduced output to 3 lines: window header, Back and focused search field. The field kept its ref and accepted `fill @ref Display` |
| API 34 search results | 54 lines with keyboard; six result rows were anonymous | Filtered output had 12 lines, including six named results with breadcrumb context. Selected the `Display` result by its row ref and verified the Display destination in both snapshot and screenshot |
| Samsung Settings search | 29 lines including keyboard and navigation controls; five history rows anonymous | Filtered output had 15 lines; all five history rows gained labels and their delete buttons remained nested beneath them. History was neither changed nor cleared |

Without any suppression, the Settings home output stayed the same length: Samsung 16 lines
before/after, API 34 13 lines before/after. The improvement is information per control and
ancestry, not simply emitting more standalone text nodes. Samsung home suppression additionally
removed the system navigation window, reducing 16 lines to 12 while retaining Settings.

### Exact paired example: Samsung Settings home

Previous renderer:

```text
@e126  [LinearLayout]  (by index)
@e133  [LinearLayout]  (by index)
@e139  [LinearLayout]  (by index)
@e146  [LinearLayout]  (by index)
@e152  [LinearLayout]  (by index)
```

New renderer (under the scrollable ancestor):

```text
  @e126  [LinearLayout]  context="Connections · Wi-Fi, Bluetooth, Flight mode, Data usage"  (by index)
  @e133  [LinearLayout]  context="Sounds and vibration · Sound mode, Ringtone, Volume"  (by index)
  @e139  [LinearLayout]  context="Notifications · App notifications, Status bar, Do not disturb"  (by index)
  @e146  [LinearLayout]  context="Display · Brightness, Blue light filter, Home screen"  (by index)
  @e152  [LinearLayout]  context="Wallpaper"  (by index)
```

`tap @e146 --settle` opened Display; no inspection of separate text nodes was needed to choose
the row. Context did not turn into a `text=` predicate: the ordinary server-resolved row ref
was used.

### Package/window filtering

Actual CLI calls with `--package com.android.settings --window 1` retained Settings, and calls
with each search app's actual package retained its field/results. Suppression classified
Samsung Keyboard and Gboard as type 2, system navigation as type 3, and Settings/search as
type 1. The search packages differ: Samsung uses `com.android.settings.intelligence`, the
emulator `com.google.android.settings.intelligence`. A mismatched package correctly returned
`(no visible nodes)`; the header supplies the package to use.

## Limits observed

- An API 34 bottom-edge row with no visible accessibility label remained anonymous. The renderer
  must not invent a label for clipped/invisible content; scroll before selecting it.
- Structural containers and scroll views can still have no descriptive context deliberately.
- Positional selectors still say `(by index)`: richer labels do not make those selectors stable.
- Sibling section headings such as Display's `Appearance` are not promoted to ancestor groups;
  existing ancestor grouping and nested controls are preserved, not fabricated.
- Noise suppression is opt-in for snapshots; default action/settle diffs can still include the
  keyboard and conservatively repeat unchanged controls when labels are removed.
- One initial Samsung `app foreground` CLI call exceeded the harness's 120-second deadline.
  A fresh snapshot and ADB activity inspection confirmed the launcher remained foreground;
  a separately budgeted `app launch --timeout 20s` then succeeded. No foreground success or
  latency improvement is claimed, and the uncertain mutation was not blindly replayed.

## Cleanup

Returned both devices to Home, released both held connections, stopped the isolated daemon
and shut down only the emulator started for this test. Confirmed Samsung's launcher was resumed
and its driver process was stopped. No device setting was toggled, no app data was cleared,
and no device was rebooted. The emulator search query was the only entered text.

**Conclusion:** the acceptance scenario works on both API families. Named contextual rows can
be selected directly from interactive-only output and acted on using their own refs. Optional
window suppression substantially reduces search-screen noise. This is demonstrated functional
usability improvement, not a measured human task-time claim.
