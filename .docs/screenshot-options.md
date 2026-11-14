# Screenshot options: scale, element crop, ref overlay

Status: **proposed** (2026-11-14). Nothing here is implemented. The decisions in
[Open decisions](#open-decisions) are the owner's to make before work starts.

This closes gap G11 of `.docs/competitor-comparison.md` ("Screenshot `--scale`, `--overlay-refs`,
`--crop-on`", agent-device, tier A). The gap list rates it as a `tap-agent`-only change. This
record proposes a different split: scale and crop in the driver, the ref overlay in `tap-agent`.

## What exists today

- `tap.wire.v1.CaptureScreenshot {}` has no options. The driver takes
  `UiAutomation.takeScreenshot()` and encodes a full-resolution PNG
  (`device/driver/core/.../ArtifactCommands.kt`).
- `DeviceService.Screenshot` returns `png`, `sha256`, `width`, `height`.
- `Device.screenshot()` exists in the Python client and the Kotlin SDK. Elements have no
  screenshot.
- `tap-agent screenshot [-o FILE]` saves that PNG. The MCP `screenshot` tool returns it at full
  size as image content.
- There is no image library on the host or in the Python clients.
- `ScreenNode` already carries `ref`, `bounds` and `interactive`, so an overlay needs no new
  server data.

## Upstream reference

**callstack/agent-device** at `ddbfc855661fa9d29b8c836057b7f5b099bcad44` (the commit the
comparison used; `website/docs/docs/commands.md`, `packages/capture-kit/src/screenshot-overlay*.ts`,
`packages/daemon/src/execution/screenshot-crop.ts`):

| Option | Behaviour |
|---|---|
| `--scale <factor>` | 0.01 to 1, applied on the host after capture. `--max-size` is refused with guidance to use `--scale`. A default can be set by environment variable. |
| `--overlay-refs` | Takes a fresh snapshot, then draws a red border and a yellow `eN` badge per target, with a built-in 5×7 bitmap font. At most 24 refs, ranked by a score; marks contained in other marks are dropped. Drawn at the final scaled size. |
| `--crop-on <selector>` | Crops to the frame the selector resolves on the same screen. Needs exactly one framed node, warns when the frame is clipped, and cannot be combined with `--overlay-refs`. |

**appium/appium-uiautomator2-server** at the pinned `56bf27a222b9aa242e6a40cf87825b78b443b7be`:
`GetElementScreenshot` crops the bitmap on the device before encoding
(`ScreenshotHelper.takeScreenshot(cropArea)`).

Decision matrix: ADAPT. Nothing is copied; the overlay drawing is written for Tap.

## Proposed split

1. **Scale and crop happen in the driver.** It already holds the `Bitmap`, so it crops and
   scales before PNG encoding. Every client gets the options (Python, Kotlin, Tap Studio's frame
   loop in `tap_studio/screen.py`), and less data crosses adb. Not measured yet: the saving in
   device encode time and transfer size is expected, not proven.
2. **Crop takes a selector, resolved inside the same driver command.** This keeps the
   exactly-one rule (`AMBIGUOUS` / `NOT_FOUND` before any work) and leaves no gap between reading
   bounds and capturing pixels.
3. **The overlay is drawn in `tap-agent`.** Which nodes to mark and how to label them is agent
   presentation. Tap Studio and the watcher draw their own overlays in the browser, so no other
   client needs burned-in marks. This needs Pillow as a `tap-agent` dependency (not `tap-e2e`).

The driver still assumes nothing about the app: it scales and crops pixels it was asked for and
judges no result.

## API

### Server and wire

```proto
// tap.v1
message ScreenshotRequest {
  string client_connection_id = 1;
  string attached_device_id = 2;
  optional int64 timeout_ms = 3;
  optional float scale = 4;        // 0.01..1; absent = 1
  optional Selector element = 5;   // crop to this element's visible bounds; exactly one match
}

message ScreenshotResponse {
  bytes png = 1;
  string sha256 = 2;
  optional int32 width = 3;
  optional int32 height = 4;
  optional Bounds region = 5;      // the screen pixels the image covers
}

// tap.wire.v1 (protocol 5.1 -> 5.2)
message CaptureScreenshot {
  optional float scale = 1;
  optional Selector element = 2;
}
```

- `width` and `height` are the size of the returned image, after crop and scale.
- `region` is in screen pixels, before scaling. A client maps a screen point into the image as
  `(p - region.origin) * scale`.
- Both changes only add fields, so `buf breaking` passes without a new `BREAKING_BASELINE`.

### Python client and Kotlin SDK

```python
device.screenshot(scale=0.3)
device.element(text("Save")).screenshot()
device.element(text("Save")).screenshot(scale=0.5)

shot.region   # Bounds: the screen pixels the image covers
shot.scale    # 1.0 when not scaled
```

```kotlin
device.screenshot(scale = 0.3f)
device.element(text("Save")).screenshot()
```

### tap-agent

```
tap-agent screenshot [-o FILE] [--scale F] [--overlay-refs] [--crop-on TARGET]
```

MCP: `screenshot(scale=1.0, overlay_refs=False, crop_on="", path="")`.

- `TARGET` is a ref (`@e12`) or a selector, the same as the other verbs.
- `--overlay-refs` takes a screen snapshot, then the screenshot, and marks the on-screen
  `interactive` nodes: the same set `snapshot -i` lists. It prints those ref lines next to the
  image, so the picture and the text agree. That snapshot becomes the current one, so
  `tap @e12` works straight away.
- Marks are drawn at the final size, after scaling, so the badge text keeps a fixed pixel size.
- `--crop-on` with `--overlay-refs` is refused in the first version.

### Failure rules

| Case | Result |
|---|---|
| `scale` outside 0.01..1 | Invalid argument, before any capture |
| `element` matches nothing / several nodes | `NOT_FOUND` / `AMBIGUOUS`, no image |
| Element partly off screen | Cropped to the visible part; the response `region` shows what was kept, and `tap-agent` prints a warning |
| Element with no visible area | A typed failure, never an empty image |

## Use cases

1. **Cheaper agent looks.** A 1080×2400 capture costs about 1,500 image tokens and about 300 at
   scale 0.3. This is an estimate from width × height / 750 after the model's own downscaling,
   not a measurement.
2. **Unlabelled controls.** Icon buttons, canvases and games have no useful text in the
   snapshot. The agent sees `e12` drawn on the image and taps the ref. This matters more for Tap
   than for the other tools, because Tap has no coordinate taps.
3. **Checking one widget.** "Does this chart or avatar look right?" needs only the cropped
   element, at full resolution.
4. **Tests.** `element.screenshot()` is failure evidence for one widget, and the input a visual
   regression check (gap G10) would need.
5. **Tap Studio and watcher frames.** Scaled frames make the paired snapshot-plus-screenshot
   loop lighter.

## Work plan

One PR, three commits, each building and passing its tests on its own:

1. **Protocol, driver, daemon: scale and element crop.** `CaptureScreenshot` options, the driver
   crop and scale, `ScreenshotRequest` / `ScreenshotResponse` fields, wire golden bytes,
   `.docs/protocol-contract.md` (5.2), `.docs/server-api.md`, regenerated Python stubs, driver
   and daemon unit tests.
2. **Python client and Kotlin SDK.** `screenshot(scale=)`, `Element.screenshot()`, the
   `Screenshot.region` and `.scale` fields, docstrings and KDoc, the public guide.
3. **tap-agent.** The three options on the CLI and the MCP tool, the overlay drawing,
   `SKILL.md`, `api-coverage.json`, tests on the in-process fake daemon.

Verification:

- Unit: `:contracts:protocol:test`, `:host:daemon:test`, `:device:driver:command-engine:test`,
  `:host:core:test`, the Python client suite and `pytest clients/agent/tests`.
- Device (only when the owner asks): a screenshot at scale 0.3 and an element crop on API 29 and
  API 34, checking the returned size, `region`, and the clipped-element case.
- The G11 row of `.docs/competitor-comparison.md` is marked done in the same change as the
  device check.

## Open decisions

1. **Driver-side scale and crop, or everything in `tap-agent` with Pillow?** The second is about
   half the work and touches no protocol, but only agents get it and the crop is not atomic.
   Recommended: driver-side.
2. **Pillow as a `tap-agent` dependency?** The alternative is a hand-written PNG codec and
   bitmap font, as agent-device does; in pure Python that is slow at full resolution.
   Recommended: Pillow.
3. **Default scale.** Recommended: 1 everywhere, with a `TAP_AGENT_SCREENSHOT_SCALE` override,
   so the MCP tool does not change what it returns today.
4. **The screen changing between snapshot and screenshot.** agent-device accepts this race. A
   second snapshot would detect it at the cost of one more hierarchy dump per overlay.
   Recommended: accept the race.

## Not in scope

- Other image formats (JPEG, WebP). `ImageFormat` has room for them; they are a separate change.
- Visual regression against a baseline (G10) and status-bar demo mode (G26).
- A `--max-size` option. A factor keeps the screen-to-image mapping simple.
