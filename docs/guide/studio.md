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
| `--serial S --package P` | Attach device `S` for app `P` at start, instead of picking them in the page. |
| `--port N` | Serve on loopback port `N` instead of a free one. |

The printed link carries a one-time token: the page signs in with it and the studio refuses
anything else. It listens on `127.0.0.1` only. Ctrl-C stops it and frees the device.

Without `--serial`, the page lists the server's devices: pick one and enter the app package.
The studio holds the device exclusively, as a test does, so another client cannot attach it
until the studio releases it.

## The page

- **Screen:** the latest frame with an overlay of the elements. The frame follows the device.
  While the screen is changing, the overlay turns dashed until it settles. Hovering shows the
  selector a step would use and how many elements it matches. A red hatched box marks an
  element with no unique selector, an accessibility gap in the app. The rail on the screen's
  left, like the emulator's side toolbar: **Back**, **Home**, **Recent apps**,
  **Notifications**, **Quick settings** (`openNotifications()` / `openQuickSettings()`) and the
  **App** menu (cold launch, launch, force stop, clear data, grant permission). Each button
  runs on the device and records its step. A replay sends steps back to back, and a system
  panel ignores a key sent while it is still sliding open. So after opening one, record a step
  that waits for it, such as an action on an element in it, or an **Assert** that an element
  of the app is gone.
- **Inspector:** the selected element's class, its selector candidates (the first is what gets
  recorded), the actions and checks that fit it, and its properties. **Screen tree** lists every
  element.
- **Steps:** the recording, each step with the wait it runs after and how it went the last time
  it ran.

Clicks do what the mode in the top bar says:

| Mode | Key | A click on an element |
|---|---|---|
| **Act** | `1` | Runs the action on the device and records it. |
| **Assert** | `2` | Records a check: visible, gone, exactly one, text equals / contains, enabled, checked, count. |
| **Inspect** | `3` | Selects the element only. A right-click always inspects. |

In Act mode:

- A click is a `tap`, and an Alt-click is a `long_tap`.
- The mouse wheel over a scrollable element records a `scroll` on it.
- A drag across an element records a `swipe` on it, in the drag's direction.
- A click on a text field opens a small editor.
    - **Set text** (Enter) replaces the field's text in one command.
    - **Type keys** taps the field, waits for focus and types, for fields that react to key
      events.
    - **Secret** keeps the value out of the recording. The step names the secret, and a replay
      asks for its value. Password fields default to secret.

**Pause** stops recording, but actions still run on the device. This is useful for getting the
app into a state you do not want in the flow.

Each action is recorded after the wait that proved it could run: its element was on screen and
matched exactly once. A selector that picks among several matches (`.first()`, `.at(i)`) waits
for at least one match, and the action then picks.

## Editing steps

Click a step to open it:

- **Selector:** choose another candidate, or type a selector in the Kotlin DSL
  (`res("search").hasAncestor(res("toolbar"))`, `text("Allow").inPackage("android")`,
  `text("Add").at(1)`). The line below it shows how many elements the
  selector matches on the current screen.
- **Value and secret:** change the text of a set text or type step, or make it secret.
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
  "aut_package": "com.example.basket",
  "steps": [
    {"id": "s1", "app": {"operation": "cold_launch", "package_name": "com.example.basket"}},
    {"id": "s2", "action": {
      "command": {"set_text": {"selector": {"node": {"resource": {"name": "search", "aut_package": true}}}, "text": "wool"}},
      "wait": {"wait_visible": {"selector": {"node": {"resource": {"name": "search", "aut_package": true}}}, "exactly_one": true}},
      "selector_origin": "SELECTOR_ORIGIN_SYNTHESIZED"}},
    {"id": "s3", "assertion": {"selector": {"node": {"match": {"property": "PROPERTY_TEXT", "value": "3 results"}}},
      "condition": "CONDITION_VISIBLE"}}
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
| `action` `set_text` on `res("search")` | `device.await(res("search")).one().setText("wool")` | `device.wait(res("search")).one().set_text("wool")` |
| `assertion` `CONDITION_VISIBLE` | `device.await(text("3 results")).visible()` | `device.wait(text("3 results")).visible()` |

Keep the selectors the studio chose: they matched exactly one element when the step was
recorded, and replay proved them.
