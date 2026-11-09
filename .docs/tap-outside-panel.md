# Tap outside a panel

Date: 2026-11-09. Status: **implemented** (`tap_outside_panel`). Decided with the user after two
design reviews and a device proof of concept on both local devices.

## Problem

A test of "a touch outside closes it" (a dialog, popup, menu, drawer or bottom sheet) had no way
to touch outside. Selectors match accessibility nodes, and the outside of a modal panel has none:
while a window that takes every touch (no `FLAG_NOT_TOUCH_MODAL`) is open, Android leaves every
window below it out of the accessibility window list, so a node outside the panel is
`NOT_FOUND`. Gestures are element-only by design, so a point cannot be given either.

## Decision

One command, `tap_outside_panel(selector, allow_reach_below)`: a single tap outside the panel
that holds the one node the selector matches. The test names any node inside the panel (its
title, a row); the node need not be tappable. Clients: Kotlin `Element.tapOutsidePanel()`,
Python `Element.tap_outside_panel()`, agent `tap --outside-panel [--allow-reach-below]` and MCP
`tap(outside_panel, allow_reach_below)`. It returns `OutsideTouch` (the point, the panel and the
side): Python returns it, the agent prints it, and the watcher shows the step's target; the
Kotlin method returns nothing, so a JUnit test ending in one cannot become a non-`Unit` method
that JUnit skips.

The driver (`OutsidePanel`) finds the panel, innermost first:

1. **In-window panel**: the nearest node from the match up that offers `ACTION_DISMISS` or has a
   pane title, and is smaller than its window. The touch goes in the middle of the free space
   beside it, inside its nearest ancestor that contains it and is larger (where the scrim lies;
   measuring to the window frame put the point on the gesture bar), with at least 24 dp free.
   These marks are not only on panels (a Snackbar and a swipe-to-dismiss item offer dismiss, a
   persistent bottom sheet has no scrim, a screen container may have a pane title), so a point
   is accepted only when the deepest node there that is important for accessibility and
   actionable is none, or a scrim: clickable, covering the whole free strip, not an ancestor of
   the panel. A `DrawerLayout` marks its content not important while a drawer is open (the
   signal the drawer occlusion refusal uses), so the content beside an open drawer passes.
2. **Window**: otherwise, or when no side of the in-window panel is accepted, the match's window
   by its real frame (`WindowFrames`). The touch goes `scaledWindowTouchSlop` + 8 dp past the
   edge, centred along it.

Sides are tried below, above, right, left. A point off the display, on the system bars' insets
or the bottom gesture area (API 30+ window insets: status and navigation bars, tappable
elements, mandatory system gestures; before, the platform's bar dimensions), on a
non-application window (bars, keyboard, accessibility overlays) or on an app window above the
panel's is skipped. The gesture area is not an accessibility window, so without the insets a
point below a plain screen's frame passed the window check whenever the gesture area is taller
than the margin. A free side wins over one that reaches a window below, even with the opt-in. A
point on an app window *below* the panel's (the panel lets touches through) is used only with
`allow_reach_below`; without it, and with no free side, the command is refused. All refusals
come before input: `NOT_INTERACTABLE` / `NO_OUTSIDE_POINT` (no side; a plain screen, a
full-screen window), `NOT_INTERACTABLE` / `TOUCH_REACHES_BELOW`. Whether the panel closed is not
checked ("the driver assumes nothing"): the test asserts it.

A new operation rather than a field on `Tap`: an older driver rejects an unknown operation as
unsupported before input, while it would ignore an unknown field and tap the node's centre,
which on a dialog is likely one of its buttons. Default display only.

## Evidence

`OutsidePanelTest` (device test) on both devices: a dialog, a popup menu over a dialog (only the
menu closes), a popup menu, an expanded Views bottom sheet, a Views drawer (its point lies over a
button the drawer hides), a Compose drawer, sheet and `AlertDialog` close, with a control under
every panel counting stray clicks (none); a pass-through popup is refused, then sent with the
opt-in (and reaches the button under it); a bottom sheet that cannot be cancelled and a node on
a plain screen are refused as `NO_OUTSIDE_POINT`; in landscape a dialog and a drawer on the end
side close. Through the server, the samples' Kotlin `OutsidePanelTest` and the Python
`test_outside_panel` call the client APIs on the same panels. Before it, the evidence below:

`OutsideTouchProbe` (raw touches around 19 panels) and the proof of concept that computed this
rule's point from the hierarchy and sent it with `input tap`, on emulator-5554 (API 34, gesture
navigation) and 85e49002 (SM-J810G, API 29, 3-button navigation), 23 cases each:

| Panel | Found by | Both devices |
|---|---|---|
| Platform, Material, AppCompat, `DialogFragment` dialogs, a dialog with the keyboard up | window | closed |
| Popup over a dialog, focusable popup, `PopupMenu`, context menu, `Spinner`, Compose `DropdownMenu` | window | closed |
| Compose `AlertDialog`, full-width Compose `Dialog` | window | closed |
| A dialog with `setCanceledOnTouchOutside(false)` | window | touch sent, stayed open |
| Non-focusable `PopupWindow`, `AutoCompleteTextView` dropdown, default `PopupWindow` | window | refused `TOUCH_REACHES_BELOW`; with the opt-in sent (a default popup never closes) |
| Views `DrawerLayout` drawer | `ACTION_DISMISS` | closed |
| Compose `ModalNavigationDrawer` | `ACTION_DISMISS`, pane "Navigation menu" | closed |
| Views `BottomSheetDialog`, also fully expanded over its scrim's centre | `ACTION_DISMISS` | closed |
| Compose `ModalBottomSheet` | pane "Bottom Sheet" (no dismiss action) | closed |
| A node on a plain screen | none | refused `NO_OUTSIDE_POINT` |

Other findings that shaped it:

- Platform dialogs ignore touches up to the window touch slop past their frame (no dismissal at
  8 dp, dismissal at 24 dp); Material, Compose and popups dismiss at 8 dp. Hence slop + 8 dp.
- Before the window-frame fix, the SM-J810G's moved window bounds put "outside" points inside
  menus and dialogs; with `WindowFrames` the same points close them.
- A plain `tap` on a scrim node is not a safe substitute: on the expanded sheet, `tap` on
  `id("touch_outside")` pressed a row and reported OK on both devices (the scrim's centre is
  under the sheet). That is a separate gap in `tap` (a sibling drawn above the node in the same
  window), not fixed here.
- Pane titles are localized ("Dialog" on the emulator, "Dialogue" on the SM-J810G): only their
  presence is read.

## Alternatives

- **A placement on every gesture** (`at = outside(...)`, `avoiding(...)`), the first proposal.
  Rejected: `avoiding` needed a second node and its geometry, scroll and fling have no use for
  it, it widened `Tap` with a field older drivers would ignore, and it conflicted with the
  drawer occlusion refusal.
- **Detecting a scrim as "a clickable sibling drawn behind the panel"**. Rejected: the Compose
  scrims sit beside or above their sheet rather than behind it, and the loose form of the rule
  picks the row above in any list of buttons.
- **Tapping scrim nodes** (`desc("Close sheet")`): localized labels, no node for a Views drawer,
  and the covered-centre problem above.
- **A synthetic "outside" node** in lookups, **host `dumpsys window`** frames, **coordinates**:
  rejected as before (they break "selectors match nodes", are slow and OEM-formatted, or are not
  portable).

## Limits

- Which outside touches close a panel is the app's choice; Tap only places the touch.
- A modal panel hides the windows below it, so the point cannot be proven empty: the panel's
  window takes the touch, which is the behaviour under test.
- A custom overlay that sets neither a dismiss action nor a pane title (a dimmed `Box`, an older
  Material 2 Compose sheet) is not found as an in-window panel; in a full-screen window that is
  `NO_OUTSIDE_POINT`, never a touch on the panel.
- The "not a control" check reads the accessibility tree: a view that handles touches without
  exposing itself as actionable (a custom `onTouchEvent`) beside a marked non-panel would still
  be touched. A point on empty space beside a non-panel (a Snackbar over plain text) is sent and
  closes nothing; the test's assertion fails, not a stray click.
- The point is placed from the panel's bounds once, with no settle: a panel still animating in
  is touched where it is at that moment. Tests wait for the screen to be stable first.
- Split screen, freeform windows, display cutouts, an RTL layout and WebView modals are untested
  (landscape and an end-side drawer are tested). The point avoids listed windows only.
- The margin's evidence is two devices; other OEMs may use a larger touch slop, which
  `scaledWindowTouchSlop` follows.
