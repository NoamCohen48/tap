# Tap Studio: inspect and record

!!! warning "Experimental"
    `tap-studio`, its page and the `tap-recording/1` format are experimental: they may change in
    any release, including after 1.0, until this notice goes away.

Tap Studio shows a device's screen in your browser with its elements overlaid. It records what
you do there as steps you can edit, replay and export. Every step targets an element through a
selector, the same one you would write in a test (`res("search")`, `text("Log in")`), and never
a screen coordinate. It runs through the same `tap` server your tests use.

## Install

It needs the `tap` server ([Getting started](getting-started.md#1-the-server)), Python 3.10+ and
a browser. Install the Python client first, then the studio:

```bash
pip install https://github.com/NoamCohen48/tap/releases/download/client-python/v0.0.2/tap_e2e-0.0.2-py3-none-any.whl
pip install https://github.com/NoamCohen48/tap/releases/download/client-studio/v0.0.1/tap_studio-0.0.1-py3-none-any.whl
```

Or install it from a checkout of the repository. The page is built with [Bun](https://bun.sh).
Run these from the repository root:

```bash
(cd clients/studio/web && bun install && bun run build)
pip install -e clients/python -e clients/studio
```

## Run

```bash
tap start                    # once; the studio never starts the server
tap-studio                   # serves on a free loopback port and opens the page
```

| Option | Effect |
|---|---|
| `--no-open` | Print the link instead of opening a browser (open it yourself). |
| `--serial S` | Attach device `S` at start instead of picking it in the page. |
| `--port N` | Serve on loopback port `N` instead of a free one. |

The printed link carries a one-time token: the page signs in with it and the studio refuses
anything else. It listens on `127.0.0.1` only. Ctrl-C stops it and frees the device.

Without `--serial`, the page lists the server's devices: pick one and attach it. Attaching names
no app. Enter the package of the app you work on in the **App** panel (it suggests the packages
on screen): it is what the panel launches, stops, clears and waits for, and the app a replay
offers to cold launch first (the page remembers it). Selectors are not limited to it, so a
recording can go through a system dialog or a second app.
The studio holds the device exclusively, as a test does, so another client cannot attach it
until the studio releases it.

## The page

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
- **Composer:** the selected element (its class, its selector candidates, the first of which is
  what gets recorded, and how many elements the chosen one matches now) and, below it, the steps
  you can record on it, in three tabs.
- **App:** the app's package, then **Cold launch** and **Launch**, **Force stop** and **Clear
  data**, **Grant** a permission, and waits until the app is in the foreground
  (`awaitVisible()`), its screen is stable, settled (the elements stop changing) or its
  animation ended (the pixels stop changing).
- **Inspector:** the selected element's properties, and the **Screen tree** of every element.
- **Steps:** the recording, each step with the wait it runs after and how it went the last time
  it ran.

Each button runs its step on the device and records it. The composer's tabs, with their keys:

| Tab | Key | What it records |
|---|---|---|
| **Act** | `1` | Something done to the element. |
| **Assert** | `2` | A check of the element as it is now. It fails at once if the check does not hold. |
| **Wait** | `3` | A wait for something to happen to the element, up to the device's wait timeout. |

**Act** offers, for the selected element:

- **Tap** and **Long press**.
- **Swipe** up, down, left or right on the element.
- **Scroll** up, down, left or right, on a scrollable element (a list or a page that scrolls).
  On an element inside one, such as a row of a list, **Select the … around it** selects the list.
- **Distance:** how far the finger moves in a swipe or a scroll, in percent of the element
  (80 % unless you change it).
- **Scroll until**, on a scrollable element: pick a direction and the studio scrolls once, without
  recording it. The list is outlined: click the element you were looking for inside it, or
  **Scroll again**. The click records one step, `scrollUntil(text("Settings"))`, that scrolls
  until that element is there; it allows 20 scrolls, or twice as many as it took you. Esc
  cancels, and the scrolls so far stay unrecorded.
- On a text field, **Set text** (Enter) replaces the field's text in one command, **Type keys**
  taps the field, waits for focus and types, for fields that react to key events, and **Clear**
  empties it. **Secret** keeps the value out of the recording: the step names the secret, and a
  replay asks for its value. Password fields default to secret.

**Assert** offers the checks that hold for the element now: it exists, is enabled (or
disabled), is checked (or unchecked), is focused; its text equals or contains a value; or the
selector matches a number of elements. An assertion that does not hold is reported and not
recorded.

**Wait** offers waits until the element is visible, exactly one, gone, enabled, disabled,
checked, unchecked or focused, has a text, or the selector matches a number of elements. The
waits on the app as a whole are in the **App** panel.

**Pause** stops recording, but steps still run on the device. This is useful for getting the
app into a state you do not want in the flow.

A tap on a row that only an index could single out (a preference or list row with no id or
text of its own) is recorded as a tap on its title, `text("Apps")`, which lands on the row and
still finds it after the list scrolls.

Each action is recorded after the wait that proved it could run: its element was on screen and
matched exactly once. A selector that picks among several matches (`.first()`, `.at(i)`) waits
for at least one match, and the action then picks.

## Editing steps

Click a step to open it:

- **Selector:** choose another candidate, or type a selector in the Kotlin DSL
  (`res("search").hasAncestor(res("toolbar"))`, `text("Add").at(1)`). A recorded selector
  usually ends in `.andPackageName("com.example.basket")`: the package of the element's app,
  which a test writes as `app("com.example.basket").element(…)`. Remove it to match on the
  whole screen. The line below shows how many elements the selector matches on the current
  screen.
- **Value and secret:** change the text of a set text or type step, or make it secret, and
  the expected text of a text wait or assertion.
- **Scroll until:** change the selector of the element a scroll until brings into view.
- **Note:** a free-text note, kept in the file.
- **Run** runs the step alone. **Run from here** runs it and every step after it. Move up, move
  down and delete are there too.

New steps go after the selected step, or at the end when none is selected.

## Replay

**Replay** runs every step from the first and stops at the first failure. The failed step shows
the driver's error. If the recording does not start with an app step, the page offers to cold
launch the app first, because a replay from an unknown screen is not reproducible. Steps with
secrets ask for the values before the run. The values are kept only while the studio runs.

## Export and open

**Export** shows the recording as `tap-recording/1` JSON, with **Copy JSON** and **Download**.
**Open** loads such a file to continue or replay it. **New** starts an empty recording. Both ask
before discarding recorded steps.

```json
{
  "format": "tap-recording/1",
  "recorded_at": "2026-09-30T09:12:44Z",
  "recorder": "tap-studio 0.0.1",
  "device": {"serial": "emulator-5554", "api_level": 34, "manufacturer": "Google", "model": "sdk_gphone64_x86_64"},
  "steps": [
    {"id": "s1", "app": {"operation": "cold_launch", "package_name": "com.example.basket"}},
    {"id": "s2", "action": {
      "command": {"set_text": {"selector": {"node": {"all_of": {"nodes": [{"resource": {"name": "search"}},
        {"match": {"property": "PROPERTY_PACKAGE_NAME", "value": "com.example.basket", "mode": "MATCH_EXACT"}}]}}}, "text": "wool"}},
      "wait": {"wait_visible": {"selector": {"node": {"all_of": {"nodes": [{"resource": {"name": "search"}},
        {"match": {"property": "PROPERTY_PACKAGE_NAME", "value": "com.example.basket", "mode": "MATCH_EXACT"}}]}}}, "exactly_one": true}},
      "selector_origin": "SELECTOR_ORIGIN_SYNTHESIZED"}},
    {"id": "s3", "wait": {"selector": {"node": {"match": {"property": "PROPERTY_TEXT", "value": "3 results"}}},
      "condition": "CONDITION_VISIBLE"}},
    {"id": "s4", "assertion": {"selector": {"node": {"resource": {"name": "checkout"}}},
      "check": "CHECK_ENABLED"}}
  ]
}
```

Steps carry `tap.v1` messages in proto3 JSON, the same encoding as the
[gRPC API](../reference/grpc.md). Any language can read them, and a step replays by sending
its messages unchanged: an action's `wait`, then its `command`. The file records the device it
was made on, but it replays on any device. It never contains a secret's value, only its name
(`"secret": "password"`, listed in `secrets` at the top).

## Turning a recording into a test

The studio does not generate code. A recording maps one to one onto the client APIs, so you,
or a coding agent ([tap-agent](agents.md)), write the test from it:

| Step | Kotlin | Python |
|---|---|---|
| `app` `cold_launch` | `app.coldLaunch()` | `app.cold_launch()` |
| `action` `set_text` on `res("search")` in `com.example.basket` | `app.await(res("search")).one().setText("wool")` | `app.wait(res("search")).one().set_text("wool")` |
| `wait` `CONDITION_VISIBLE`, no package | `device.screen.await(text("3 results")).visible()` | `device.screen.wait(text("3 results")).visible()` |
| `assertion` `CHECK_ENABLED` | `assertTrue(device.screen.element(res("checkout")).isEnabled())` | `assert device.screen.element(res("checkout")).is_enabled()` |
| `assertion` `CHECK_TEXT_EQUALS` `"Wool"` | `assertEquals("Wool", ….text())` | `assert ….text() == "Wool"` |
| `scroll_until` on `res("list")`, target `text("Row 40")` | `….element(res("list")).scrollUntil(text("Row 40"))` | `….element(res("list")).scroll_until(text("Row 40"))` |
| `app_wait` `wait_app_visible` / `wait_screen_stable` | `app.awaitVisible()` / `app.awaitScreenStable()` | `app.await_visible()` / `app.await_screen_stable()` |

A selector whose top-level conjunction has a `PROPERTY_PACKAGE_NAME` match is that app's:
`app = device.app("com.example.basket")`, and the rest of the selector goes to
`app.element` / `app.await`. A selector without one is `device.screen`'s. The step list shows
each step that way.

Keep the selectors the studio chose: they matched exactly one element when the step was
recorded, and replay proved them.
