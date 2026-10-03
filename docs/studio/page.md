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

- **Tap**, **Double tap** and **Long press**.
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
  replay asks for its value. Password fields default to secret. **Submit** presses the
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
Selecting an element on the screen switches back to **Act**.

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

**Pause** stops recording, but steps still run on the device. This is useful for getting the
app into a state you do not want in the flow.

A tap on a row that only an index could single out (a preference or list row with no id or
text of its own) is recorded as a tap on its title, `text("Apps")`, which lands on the row and
still finds it after the list scrolls.

Each action is recorded after the wait that proved it could run: its element was on screen and
matched exactly once. A selector that picks among several matches (`.first()`, `.at(i)`) waits
for at least one match, and the action then picks.
