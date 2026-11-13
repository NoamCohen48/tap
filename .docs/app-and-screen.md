# App and screen: package predicates instead of scopes, and the occlusion check

Date: 2026-10-01. Status: accepted (user, 2026-09-30); implemented in protocol 5.0.

## Decision

1. **An attached device names no app.** `Attach` takes a serial; the host and the driver keep
   no session AUT (`AttachRequest.aut_package`, `sync_authority`, the driver's
   `tapExpectedAut` / `tapSyncAuthority` arguments are gone). App calls carry their
   `package_name`, as they already did.
2. **Selectors have no scope.** The `Selector.scope` oneof (`aut` / `system` / `any_window`) is
   removed. Every lookup searches all visible windows (`UiDevice.findObjects`, or the roots of
   `findObjects(By.depth(0))` on the traversal plan).
3. **Package ownership is a node predicate**, `Match{PROPERTY_PACKAGE_NAME, value, mode}`
   (natively `By.pkg`). It composes like any predicate: inside `any_of`, on an ancestor, with a
   `CONTAINS` mode.
4. **`app(pkg)` and `screen` exist only in the clients.** `device.app(pkg).element(s)` /
   `.await(s)` send `all_of(s, package == pkg)`; `device.screen.element(s)` sends `s` alone.
   Both SDKs, tap-agent (`pkg=`), Tap Studio codegen and the daemon's selector synthesis follow
   this. No client attaches for an app either: `tap-agent attach <serial>`, Tap Studio's
   `Attach{serial}` (its App menu holds the package it acts on, as page state). App waits (`awaitVisible`, `awaitSettled`, `awaitScreenStable`,
   `awaitAnimationEnd`) live on `App`.
5. **`ResourceId{name, optional package_name}`.** With a package: exactly `pkg:id/name`.
   Without: `name` in any package (`<any>:id/name`, natively a quoted `By.res(Pattern)`) or a
   bare Compose testTag. `aut_package` is gone; a `name` containing `:id/` is rejected
   (`QUALIFIED_RESOURCE_NAME`), so one id has one spelling. `App` never rewrites ids.
6. **Occlusion check before every gesture** (driver behaviour change, approved as "(b)"):
   `tap`, `long_tap`, `swipe` and `scroll` compute the touch point the way `UiObject2` will
   (`TouchPoints`: a click's visible centre; a swipe's or scroll's start on the 10%-margin
   edge), and `TouchReachability` fails the command `NOT_INTERACTABLE` / `OBSCURED` when the
   topmost touchable window at that point (accessibility window list, by layer) is not the
   target's. It runs after resolving the target and before the mutation gate, so it promises
   no input. Only a partly covered target reaches it: Android marks a node that windows above
   cover completely as not visible (`isVisibleToUser` uses the window's interactive region), so
   UiAutomator never returns it and the command is `NOT_FOUND`.
7. Removed fields and values are deleted outright, not `reserved` (0.x;
   `contracts/proto/BREAKING_BASELINE` moved). The protocol major went to 5.0 because a 4.0
   driver would read a scope-less selector as the AUT scope.

## Why

- A test often spans more than one app: a permission dialog, a share sheet, a system panel, a
  second app. The session AUT made the common case implicit and everything else an opt-in
  (`inPackage`, `inAnyWindow`, `SCOPE_DENIED`), and the attach-time app leaked into the event
  log, recordings and the agent's attach.
- A scope is a second, separate way of saying "belongs to package X". As a predicate it needs no
  scope policy in the driver (`SearchScope`, focused-window lookup, AUT resolution of
  resources) and can express what a scope could not (a node in either of two packages).
- The old AUT scope searched the app's *focused* window only. Searching every window finds
  nodes a scope missed (a `PopupWindow` or dropdown that does not take focus) — and also nodes
  partly under another window: a dialog, the keyboard or an overlay over the app leaves the
  app's window in the accessibility tree, a node it covers only partly is still visible with
  its own bounds, and `UiObject2` clicks its visible centre, which can be under the other
  window. With the driver not verifying effects, that tap would report success after pressing
  something else. (A node covered completely is safe: Android reports it not visible.) The
  focused-window scope hid this for the AUT in the common case only; `system` / `any_window`
  selectors were already exposed.

## Alternatives

- **(a) Keep "focused window of the package" as an app-selector predicate.** Cheap and exactly
  the old AUT behaviour, but protects only `app(…)`, not `screen`, keeps missing non-focusable
  popups, and couples a selector to focus state. Rejected for (b).
- **Per-call scopes set by `App`** (scope stays on the wire, the session loses the AUT). Keeps
  two ways to say one thing; rejected with the scope itself.
- **Pack the package into `ResourceId.name` (`pkg:id/name`) with an `exact` flag.** Retyped a
  field (buf `WIRE_JSON` break) and needed `:id/` parsing in six places; rejected for
  `package_name` plus the `QUALIFIED_RESOURCE_NAME` check.

## Limits

- The occlusion check reads the window list, plus one cover inside the target's own window: an
  open `DrawerLayout` drawer. Its scrim is drawn by the layout (no view, no node) and a touch on
  the content closes the drawer; the layout hides that content from accessibility while a drawer
  is open, but UiAutomator still reports it visible with its own bounds. A target that is not
  important for accessibility under a `DrawerLayout` with another child on screen is `OBSCURED`
  (`DrawerScrimTest`, 2026-11-09; both signals are needed: apps mark views not important
  themselves, and many wrap their whole content in a `DrawerLayout`). Any other control drawn
  over the touch point in the same window is found by a hit test from the window's root (the
  visible child containing the point that is drawn last, by drawing order, ties to the later
  child, down to the deepest node): when the deepest node there that is important for
  accessibility and takes touches is not the target, an ancestor or a descendant of it, the
  gesture is `OBSCURED` (`InWindowCoverTest`, Views and Compose, 2026-11-13). A view on top that
  takes no touches (a label, a badge) lets the touch through and passes; a view that handles
  touches without exposing itself as actionable (a custom `onTouchEvent`, a custom scrim) is not
  seen. A Compose `ModalNavigationDrawer` leaves the content out of the tree while open, so
  it is `NOT_FOUND`.
- Only the default display is checked. A momentarily stale window list, or no window at the
  point, is not refused.
- Below API 33 a window's touch area is its bounds. Some devices report a window with surface
  insets (a dialog, an elevated popup) moved up and left by that margin (SM-J810G, API 29: 56 px;
  the API 34 emulator is exact), so the driver takes the frame from the window's root node when
  it has the window's size but another origin (`WindowFrames`). The same frame clips the visible
  bounds of snapshots, the hierarchy dump and the touch point of tap, long tap, double tap,
  multi-tap, hold and drag. Swipe, scroll, fling and pinch are injected by `UiObject2` from its
  own visible bounds, so their check keeps those points (`InsetWindowTest`).
- `set_text` / `clear_text` use accessibility actions, not touches, so they are not checked.
- Snapshot refs always carry the node's package predicate; codegen turns it into
  `app("pkg").element(…)`.

## Evidence

2026-10-01, local matrix (emulator-5554 API 34, 85e49002 Samsung SM-J810G API 29):
`OcclusionTest` on the fixture's `OcclusionActivity` — a tap on a button whose middle half an
in-app popup window covers is `OBSCURED` and neither the button nor the popup counts a click; a
button fully under the keyboard (`adjustNothing`) is `NOT_FOUND` on both devices, and no key
reaches the focused field; uncovered, both taps go through. The first version of the test, a
keyboard over the main screen's Compose list, could not occlude anything: that window resizes
for the keyboard, so every item it found was above it.

After rebasing onto the scrcpy recordings (`423ef76`), on emulator-5554: the validation
`deviceTest` suite, the Kotlin fixture tests (JVM daemon), the Python suite, the tap-agent smoke
and the Studio smoke (native daemon) all passed; `buf lint` passes, and `buf breaking` against
`main` lists only the deliberate deletions above.
