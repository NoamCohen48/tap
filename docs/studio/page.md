# The page

You pick an element on the screen, then choose what to do with it. A click on the screen never
runs anything on the device.

- **Screen:** the latest frame with an overlay of the elements. The frame follows the device.
  While the screen is changing, the overlay turns dashed until it settles. Hovering shows the
  selector a step would use. A click selects the element under it; a right-click reaches every
  element, including the ones the overlay leaves out. A red hatched box marks an element with no
  unique selector, an accessibility gap in the app.
  Under the phone, its own buttons: **Back**, **Home**, **Recent apps**, **Notifications** and
  **Quick settings** (`openNotifications()` / `openQuickSettings()`). A replay sends steps back
  to back, and a system panel ignores a key sent while it is still sliding open. So after
  opening one, record a step that waits for it, such as a **Wait** for an element in it.
  **More keys** presses another key into whatever has focus: **Enter** to submit a search after
  setting its text, **Delete**, **Tab**, **Space**, **Escape**, **Search**, the volume keys, or
  any Android key code (`pressKey(66)`).
- **Composer:** the selected element (its class, its selector candidates, the first of which is
  what gets recorded, and how many elements the chosen one matches now) and, below it, the steps
  you can record on it, in three tabs, then a tab for the app and one for the device.
- **Inspector:** the selected element's properties, and the **Screen tree** of every element.
- **Steps:** the recording, each step with the wait it runs after and how it went the last time
  it ran.

Each button runs its step on the device and records it. The composer's tabs, with their keys:

| Tab | Key | What it records |
|---|---|---|
| **Act** | `1` | Something done to the element. |
| **Assert** | `2` | A check of the element as it is now. It fails at once if the check does not hold. |
| **Wait** | `3` | A wait for something to happen to the element, up to the device's wait timeout. |
| **App** | `4` | Something done to the app, or a wait on it, apart from any element. |
| **Device** | `5` | Something done to the device itself: rotation, conditions, dialogs, notifications. |

**Act** offers, for the selected element:

- **Tap**, **Double tap** and Android's default **Long press**. **Tap sequence** selects 1–100
  taps with a release-to-press interval of 40–1000 ms (at most 30 seconds total). **Hold for
  duration** injects a physical press for 1–30000 ms, which is not an accessibility long-click.
  Each resolves the target once, requires an unobscured touch point and never retries input.
- **Drag to…**: the element is outlined; click the element to drop it on, and one
  `dragTo(res("bin"))` step is recorded. Esc cancels.
- **Swipe** up, down, left or right on the element.
- **Pinch** open or closed (`pinchOpen()` / `pinchClose()`), across the **Distance**.
- **Scroll** up, down, left or right, on a scrollable element (a list or a page that scrolls).
  On an element inside one, such as a row of a list, **Select the … around it** selects the list.
- **Distance:** how far the fingers move in a swipe, a scroll or a pinch, in percent of the
  element (80 % unless you change it).
- **Fling** up, down, left or right, on a scrollable element or one inside a list.
- **Scroll until**, on a scrollable element: pick a direction and the studio scrolls once, without
  recording it. The list is outlined: click the element you were looking for inside it, or
  **Scroll again**. The click records one step, `scrollUntil(text("Settings"))`, that scrolls
  until that element is there; it allows 20 scrolls, or twice as many as it took you. Esc
  cancels, and the scrolls so far stay unrecorded.
- On a text field, **Set text** (Enter) replaces the field's text in one command, **Type keys**
  taps the field, waits for focus and types, for fields that react to key events, and **Clear**
  empties it. Turn off **Wait for focus** when the tap moves focus somewhere else, such as a
  child or a separate input view (`typeText(…, awaitFocus = false)`). **Secret** keeps the value out of the recording: the step names the secret, and a
  replay asks for its value. Both Set text and Type keys mark secret input sensitive, so the
  daemon's event history and watcher also omit the typed value. This does not mask the device's
  screen or make ordinary selectors and notes secret. Password fields default to secret. **Submit** presses the
  keyboard's action key for the field (Search, Go, Done: `imeAction()`).
- **Actions**: what the element offers through accessibility. Custom actions such as
  **Archive** on a list row (`performCustomAction("Archive")`) stand in for a swipe; standard
  ones such as **Expand** or **Copy** are `performAction(StandardAction.EXPAND)`.
- **Value**, on a slider, a seek bar or a rating bar: set it exactly, in its own units
  (`setProgress(7f)`), rather than dragging to a pixel.

**Assert** offers the checks that hold for the element now: it exists, is enabled (or
disabled), is checked (or unchecked), is focused; its text equals or contains a value; or the
selector matches a number of elements. An assertion that does not hold is reported and not
recorded.

**Wait** offers waits until the element is visible, exactly one, gone, enabled, disabled,
checked, unchecked or focused, has a text, or the selector matches a number of elements. The
waits on the app as a whole are in the **App** tab.

**App** offers the app's package, then **Cold launch** and **Launch** (of the launcher activity,
or of the **Activity** you name, such as `.ui.SettingsActivity`, with any **Extras** you add:
a key, a type (String, Int, Long, Float or Boolean) and a value), **Foreground** (back as it was
left, as from Recents) and **Background** (`pressHome()`), **Open** a deep **Link** (in this app,
or any app's), **Force stop** and **Clear data**, **Grant** or **Revoke** a permission (revoking
stops the app, as Android does), the app's own **Languages** (Android 13+; **Follow system**
undoes them), and waits until the app is in the foreground (`awaitVisible()`), its screen is
stable, settled (the elements stop changing) or its animation ended (the pixels stop changing).
For stability, choose **Whole screen** to omit the package requirement, and set **Quiet period
(ms)** (1–30000). Foreground visibility still requires a package. These waits do not use
synchronization or `awaitIdle`. Selecting an element on the screen switches back to **Act**.

**Defaults for new steps**, at the top of the composer, sets separate command/wait,
selector-precondition and typing-focus budgets where the SDK supports them. The step editor
can change these later; replay keeps the recorded values. See [Steps and export](steps.md).

**Device** starts with what the device is now: the screen, its rotation, the keyboard and the
activity in front, read again after every step. Each control below marks the value the device
has now with a dot. Choosing a value records the step, even when it is the current one, because
a test sets what it relies on.

- **Screen:** **Portrait** or **Landscape** (`setOrientation`), a fixed **Rotation** or **Auto**
  (`setDisplayRotation` / `unfreezeRotation()`), **Wake** and **Sleep** (`wake()` / `sleep()`),
  and **Unlock** for a keyguard with no PIN (`dismissKeyguard()`).
- **Keyboard and clipboard:** **Hide keyboard**, and set the **Clipboard**.
- **Permission dialog:** wait until the system's dialog is shown (`awaitPermissionPrompt()`),
  then **Choose** its answer (`choosePermission(PermissionChoice.ALLOW_FOREGROUND_ONLY)`), with
  precise or approximate first on a location request.
- **Notifications and toasts:** the notifications shown now, each with **Wait for**, **Open**
  (as a tap on it), its own action buttons (**Mark as read**) and **Dismiss**; they are matched by
  title (or text) and package. **Toast** waits for one, any or by its text; record it right after
  the step that shows it.
- **Conditions:** animations, dark mode, stay awake, font size, display density, airplane mode,
  Wi-Fi, mobile data, high contrast text, inverted colors, bold text, the system languages and a
  mock **Location**. Each is held until the device is released, which puts the device's own
  value back.
- **Assert:** the activity in front (prefilled with the one there now), the keyboard shown or
  hidden, or the clipboard's text.

**Assert** and **Wait** also take a typed selector, such as `res("login")`, so a check can be about
an element that is not on the screen or has gone. They offer every check then, including **None
match** (a count of zero). Tick **Add to the recording without running it now** to write a step
for a screen that is not here yet: it shows no result until it runs.

Every value of a step can be edited after it is recorded, and Studio checks it when you save: a
refused save keeps what you typed. **Duplicate** copies a step, which then shows no result until it
runs. **Undo** and **Redo** (Ctrl+Z, Ctrl+Shift+Z) change the recording only. What already ran on
the device stays done, and a restored step keeps its result only if it has not changed since.

Studio asks before you leave a step with unsaved edits, release the device with them, or close the
page. **Clear data** also asks first, because it runs on the device and undo cannot bring it back.
Dialogs keep the keyboard until they close, and the screen tree picks elements the same way the
screen does, including where a drag drops and what a scroll until looks for.

**Parameters** are test data a step can name instead of holding, such as a user name or an expected
title. Declare them above the steps, then choose one in a step's editor for the text it types or
expects. A run looks up the value first and refuses before any input if it is missing. Changing a
value clears the results of the steps that use it. Parameters are written to the recording file, so
keep anything private in a secret, whose value never is. A parameter stands only for that text: it
is never part of a selector, and `${x}` typed into a step is just those characters.

The **Library** keeps named recordings privately on this machine, in `~/.tap/studio/recordings`. It
works with no device attached and no Tap server running, so you can open, rename, delete, read and
export a recording; attach a device to edit or replay it. Saved recordings hold definitions only:
never a secret's value, and no results. If another Studio changed an entry since you opened it,
saving is refused rather than overwriting it.

**Autosave** is off until you turn it on for a saved recording, and turns itself off and says why if
a write fails. A file recovered after a crash is the recording as it was last saved. It records
your edits, not what ran on the device, so it cannot tell you whether input was sent before the
crash.

**Pause** stops recording, but steps still run on the device. This is useful for getting the
app into a state you do not want in the flow. Wait for the current operation before changing
the recording mode.

The server serializes actions, tools, recording edits and attachment changes. Closing or
canceling a browser request does not interrupt accepted input: the operation finishes and
reconciles its result before Studio allows a conflicting change. A stopped replay runs no
further steps. Shutdown also drains the accepted operation before releasing the device;
process termination or a machine failure is not recoverable execution journaling.

One tab controls a Studio. Any other tab open on it watches: it follows the screen and the
recording but cannot change anything, and the server refuses it if it tries. **Take control**
moves control to the tab you are in, once nothing is running, and what you have typed there
stays. When the controlling tab closes or reloads, control passes to the tab opened last.

While Studio is finishing something a cancelled request left behind, the page stays locked and
says so. The result is not known until it finishes.

Changing a named secret's value marks every affected step as needing verification. Notes alone
do not invalidate a step's result.

A tap on a row that only an index could single out (a preference or list row with no id or
text of its own) is recorded as a tap on its title, `text("Apps")`, which lands on the row and
still finds it after the list scrolls.

Each action is recorded after the wait that proved it could run: its element was on screen and
matched exactly once. A selector that picks among several matches (`.first()`, `.at(i)`) waits
for at least one match, and the action then picks.
