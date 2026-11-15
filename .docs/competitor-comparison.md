# Tap vs. agent-device, Maestro and Appium: a competitive comparison

Date: 2026-11-13. Research note, not a decision record: nothing here changes a decision until the
owner approves it and the decision record it touches is updated. Any recommendation that crosses
the "do not copy" rows in `upstream-reference-audit.md` or the invariants in `CLAUDE.md` is
marked **needs owner decision**.

## Sources

Read at the current heads, newer than the pins in `upstream-reference-audit.md`:

| Project | Commit | Date | Version |
|---|---|---|---|
| callstack/agent-device | `ddbfc855661fa9d29b8c836057b7f5b099bcad44` | 2026-11-13 | 0.21.38-dev |
| mobile-dev-inc/Maestro | `0ccf5a9c5b1c48d4cb6211d9060d537e04745107` | 2026-11-13 | 2.12.1 |
| appium/appium-uiautomator2-driver | `911af30ae7b490ac93da36ccb576993791522cc1` | 2026-11-13 | 9.0.3 |
| appium/appium-uiautomator2-server | `56bf27a222b9aa242e6a40cf87825b78b443b7be` | 2026-10-06 | — |
| appium/appium-android-driver (UiA2's base class) | `ce35384b86ed2e4c496212743581ff53155b16b6` | 2026-11-01 | 15.x |
| Tap | `1a3be893` (main) | 2026-11-13 | engine 0.0.2 line, protocol 5.1 |

agent-device has moved a long way since the pin (`0ebd2540`, 2026-09-20). It is now about 352k
lines of production TypeScript in roughly 40 packages, with about 2,000 test files and 39 ADRs.
Tap is about 76k lines across Kotlin, Python and TypeScript. If anything is adopted from these
heads, re-pin the audit to these commits.

Method: I read the source, ADRs and user docs of each project, plus Tap's code and `.docs/`. I ran
nothing against a device. Statements about competitor behaviour come from their code and docs at
the commits above.

---

## 1. Summary

- **Each product is built for a different job.**
  - *agent-device* is a device layer for AI agents. An agent explores the app at run time, collects
    evidence, and optionally saves a replay script. It covers many platforms (iOS, Android,
    HarmonyOS, TV, web, macOS, Linux, device clouds) and is tuned for token efficiency.
  - *Maestro* is a YAML flow runner. It optimises for the shortest route from install to a first
    test and for tolerating flakiness: implicit waits, settling and retries everywhere. It now ships
    an MCP server, Studio, a cloud service and AI assertions.
  - *Appium* is the W3C WebDriver standard for mobile. It has the most breadth (iOS, Android, web
    views, many client languages, a plugin ecosystem, Grid, Inspector) and the most accumulated
    workarounds for Android OEMs.
  - *Tap* is a deterministic, host-driven Android E2E engine. You write tests in Kotlin/JUnit 5 or
    Python/pytest against a typed gRPC server. It also has an agent surface (`tap-agent`), a
    recorder (Studio) and a live viewer (`tap-watcher`) on the same engine.
- **Tap's lead is correctness.** It resolves the element on the device and requires exactly one
  match before input, in the same call that injects the input. It never replays a mutation. It
  fences request IDs and session generations, keeps crash-resumable journals, and reads back and
  restores every device setting it changes. It supports several devices in one test with
  structured concurrency. No competitor has all of this. agent-device comes closest in spirit:
  `dispatched: no|unknown`, its ref-frame expiry, its interaction guarantee matrix. It reached that
  with a host-side snapshot and coordinate taps, not on-device resolution.
- **Tap's gaps are breadth and evidence.** It supports one platform. It has no web views or hybrid
  contexts, no crash/ANR codes, no performance capture, no streamed app logs, no visual regression,
  no replay runner, no remote or cloud devices, no emulator boot, no telephony or biometrics, and
  no sharding.
- **agent-device is ahead on agent ergonomics.** Its snapshots summarise off-screen content and
  acknowledge an unchanged screen briefly. Every failure carries a typed `reason`, a hint and a
  `dispatched` field. Screenshots can be scaled and overlaid with refs. Scrolls report what moved.
  Text fills report `unconfirmed` with `before`/`after`. A `capabilities` command lists what each
  device supports. MCP clients get instructions at `initialize`, and an `llms-full.txt` is
  published. Most of this is cheap to adopt in `tap-agent` without touching the engine's invariants.

---

## 2. Product at a glance

| | **Tap** | **agent-device** | **Maestro** | **Appium (UiAutomator2)** |
|---|---|---|---|---|
| Primary user | Test engineers (Kotlin/Python), then agents | AI coding agents, then CI replay | QA and mobile devs writing YAML | Test engineers in any WebDriver language |
| Platforms | Android only | iOS, Android, HarmonyOS, Android TV/Fire TV, tvOS, Vega OS, web, macOS, Linux | Android, iOS simulator, web | Android (iOS etc. through other drivers) |
| Test authoring | Kotlin + JUnit 5, Python + pytest, typed gRPC | CLI verbs, `.ad` scripts, Node client, MCP; runs and exports Maestro YAML | YAML flows plus GraalJS scripts | Any WebDriver client (Java, Python, JS, Ruby, C#…) |
| Host process | `tap` daemon (native image, loopback gRPC, token), started explicitly | Per-user Node daemon, auto-started, loopback HTTP/socket with token; remote daemons with tenants and leases | CLI process per run (`maestro test`) | Appium server (Node, HTTP) |
| On-device component | Driver APK pair (instrumentation, separate from the AUT), framed protobuf over adb forward, authenticated | Snapshot/automation helper APK (instrumentation, persistent socket session), optional test IME | `dev.mobile.maestro` instrumentation with a gRPC server | `io.appium.uiautomator2.server` instrumentation with an HTTP JSON server |
| Where selectors resolve | **On the device** (UiAutomator `BySelector` plan, exactly-one check before input) | On the host, over a fresh accessibility capture; input goes to rect-centre coordinates | On the host, over a full hierarchy dump; input goes to coordinates | On the device (UiAutomator / UiSelector / XPath over a dump) |
| Waiting | Explicit only, one deadline per call | Explicit `wait`; opt-in `--settle`; post-gesture stabilisation by default | Implicit: lookups wait up to 17 s (7 s if optional), settle after every command | Implicit wait setting plus WebDriverWait in clients |
| Retry on failure | Never replays a mutation; `INDETERMINATE` after a lost transport | Never resends a possibly-dispatched mutation; reports `dispatched: unknown` | `retryIfNoChange` re-taps; `retry` blocks; flakiness tolerance by design | Client-side and Grid retries are common |
| Multi-device | Several devices in one test, structured concurrency, `DeviceBarrier` | Separate sessions in parallel, no in-script coordination | Parallel shards (`--shard-split`/`--shard-all`) | One session per device; coordination is up to you |
| GUI tooling | Tap Studio (recorder, selector-based), tap-watcher (live view of all connections, video) | None (`takeover` hold, `record contact-sheet`) | Maestro Studio, MCP viewer | Appium Inspector |
| Agent surface | `tap-agent` CLI + MCP (Python), experimental | The product itself: CLI, MCP, Node API, AI SDK/Eve integrations, skills | `maestro mcp` (10 coarse tools), `maestro chat` | Separate community MCP servers |
| Licence | Apache-2.0 | MIT | Apache-2.0 | Apache-2.0 |

---

## 3. API comparison

### 3.1 Shape of the API

| | **Tap** | **agent-device** | **Maestro** | **Appium** |
|---|---|---|---|---|
| Wire contract | One protobuf schema (`tap.v1`), gRPC; clients generated or hand-wrapped; `buf breaking` in CI | Daemon RPC JSON over loopback HTTP/socket, integer protocol version; local client and daemon must match exactly | Internal gRPC between CLI and on-device server; no public API (the YAML is the API) | W3C WebDriver HTTP + JSON, `mobile:` extensions through `executeScript`, Appium "Settings" API |
| Primary programmatic API | `Device` → `App` → `Element` → `ElementWait` (Kotlin `suspend`, Python sync) | `client.<group>.<verb>({…options})`, 15 groups (`devices`, `sessions`, `apps`, `capture`, `interactions`, `replay`, `batch`, `observability`, `recording`, `settings`, `leases`, `metro`, …) | None for tests; YAML commands (48 command types) | `driver.findElement(By…)` → `WebElement` handle; `driver.executeScript("mobile: …", args)` |
| Element model | **Lazy selector**: every call resolves again, nothing persists | Positional **refs** (`@e12`, optionally generation-pinned `@e12~s42`), valid until the next mutation; or selector strings | Selector maps in YAML, resolved per command | **Persistent element handles** (`elementId`), `StaleElementReferenceException` |
| Selector language | Typed AST built from functions: `res`, `text`/`text_contains`/`text_starts_with`/`text_matches` (RE2), `desc`, `hint`, `class_name`, `clickable`, `scrollable`, `any_of`/`all_of`, relations (`child`, `descendant`, `has_descendant`, `has_parent`, `ancestor`), `first()`/`at(n)`, `PROPERTY_PACKAGE_NAME` | String DSL: `key=value` terms joined by spaces, `\|\|` alternatives; keys `id`, `label`, `value`, `text`, `role`, booleans (`visible`, `editable`, `selected`, `focused`, `enabled`, `hittable`); exact-but-case-insensitive; `find` for partial matching | YAML: `text` (regex), `id`, `index`, `childOf`, `above`/`below`/`leftOf`/`rightOf`, `containsChild`, `containsDescendants`, traits, `enabled`/`checked`/`focused`/`selected`, `point` | `id`, accessibility id, class, `-android uiautomator` (UiSelector strings), XPath, image (plugin) |
| Ambiguity | Mutations need exactly one match (`AMBIGUOUS` before input); reads say what they do | Mutations refuse `AMBIGUOUS_MATCH` and list up to five candidate refs; reads take the first match; `--first`/`--last` | First match (with optional `index`) | First match (`findElement`) |
| Waits | `app.wait(sel).visible()/one()/gone()/enabled()/checked()/focused()/text_equals()/text_contains()/count()`, `await_until`, `await_screen_stable(TREE/PIXELS/ALL)`, `await_settled`, `await_animation_end`, `await_toast`, `await_notification`, `await_permission_prompt`, `await_idle` | `wait <ms>\|text\|@ref\|selector`, `wait absent` (strict), `is visible/hidden/exists/absent/editable/selected/focused/text`, `alert wait`; failures carry the history of every poll | Implicit in every command; `extendedWaitUntil`, `waitForAnimationToEnd`, `assertVisible/NotVisible/True` | Implicit wait; `WebDriverWait` + expected conditions (client side) |
| Errors | Closed taxonomy: 28 `ErrorCode`s with typed details (`OBSCURED`, `UNSUPPORTED_CHARACTERS`, `match_count`, wait-timeout details), RPC `Failure`, typed client exceptions (`CommandError`, `WaitTimeoutError`, `DeviceBusyError`, `DeviceQuarantinedError`, …) | `error.code` + `error.details.reason` (hundreds of typed reasons) + `hint` + `dispatched` + `retriable`; documented as "branch on the reason, never the message" | Exceptions and messages; the report shows the failing step | W3C error codes (`no such element`, `stale element reference`, …) + message |
| Lifecycle | Explicit `tap start`; attach/detach; observed or held connections; per-serial lock | Daemon auto-starts and is replaced on a version mismatch only when idle; sessions per worktree or name; device claims | Per-run | Session per `new session` capabilities |
| Test-framework integration | JUnit 5 extension (`@TapTest`, parameter injection, roles, artifacts on failure), pytest plugin (`tap_device`, `tap_devices` with device criteria) | None for tests (`test` runs `.ad` suites; a separate product, e2e by tester.army, is the framework) | Its own runner; JUnit/HTML reports | Any test framework through client libraries |
| Agent API | `tap-agent` CLI + MCP, about 40 tools | CLI = MCP = Node, one command registry; `help` tool; `--structured` | `maestro mcp`: `list_devices`, `inspect_screen`, `run` (YAML), `take_screenshot`, cheat sheet, cloud tools | None built in |

### 3.2 The same task in each API

**Tap (Python)**
```python
shop = tap_device.app("com.example.shop")
shop.cold_launch()
shop.element(res("email")).set_text("a@b.c")
shop.element(text("Log in")).tap()          # AMBIGUOUS / NOT_FOUND before any input
shop.wait(text("Welcome")).visible()         # explicit, one deadline
```

**agent-device (Node)**
```ts
const c = createAgentDeviceClient({ session: 'qa' });
await c.apps.open({ app: 'com.example.shop', platform: 'android', relaunch: true });
await c.interactions.fill({ selector: 'id="email"', text: 'a@b.c' });
await c.interactions.press({ selector: 'label="Log in"', settle: true }); // returns a diff
await c.command.wait({ text: 'Welcome', timeoutMs: 5000 });
```

**Maestro (YAML)**
```yaml
appId: com.example.shop
---
- launchApp: { clearState: true }
- tapOn: { id: "email" }
- inputText: "a@b.c"
- tapOn: "Log in"          # waits up to 17 s, settles, taps the first match at its centre
- assertVisible: "Welcome"
```

**Appium (Java)**
```java
driver.activateApp("com.example.shop");
driver.findElement(AppiumBy.id("email")).sendKeys("a@b.c");
driver.findElement(AppiumBy.androidUIAutomator("new UiSelector().text(\"Log in\")")).click();
new WebDriverWait(driver, Duration.ofSeconds(10))
    .until(ExpectedConditions.visibilityOfElementLocated(AppiumBy.xpath("//*[@text='Welcome']")));
```

### 3.3 Where each API is stronger

- **Tap**
  - It is the only one whose element API has no stale state: no handles, and no refs on the test
    side.
  - Its selectors are typed values. A typo fails at compile time in Kotlin and at call time in
    Python, never on the device.
  - Waits are named predicates with typed timeouts, and a `WaitTimeoutError` carries the last
    observation.
  - Kotlin and Python expose the same surface (camelCase vs. snake_case).
  - The gRPC contract is public, versioned and lint-checked, so a third language costs only the
    stub generation.
  - The JUnit/pytest integration is the most complete of the four (roles, criteria, ordered opens,
    artifacts, teardown that survives cancellation).
- **agent-device**
  - One command registry drives the CLI, MCP, Node and batch, so the four can never drift.
  - Every response shares one shape and every error has a typed reason, a hint and a `dispatched`
    flag.
  - `settle` and `verify` are options on any mutation rather than separate calls.
  - `capabilities` returns per-device command support.
  - `batch` runs N steps in one request.
  - The selector string language is compact enough to type in a shell.
- **Maestro**
  - The YAML is readable and diffable, and needs no programming.
  - `runFlow`, `repeat`, `retry` and `evalScript` give composition without a language.
  - `env`/`${VAR}` interpolation and tags are built in.
- **Appium**
  - It is a standard API with clients in every major language, so existing WebDriver knowledge
    carries over.
  - The `mobile:` extensions expose almost any adb or Android capability (about 100 methods).
  - The Settings API tunes server behaviour per session (`waitForIdleTimeout` and others).

### 3.4 API lessons for Tap

1. **Typed `reason` plus `hint` on every refusal, and say whether input was sent.**
   - Tap already has `ErrorCode` + details and `INDETERMINATE`. A client-side
     `dispatched: no|unknown` derived from the code would help: every code returned before input
     maps to `no`, and `INDETERMINATE`/`TRANSPORT_LOST` after acceptance map to `unknown`.
   - Exposed in `tap-agent` output, it tells an agent whether a retry is safe. This is cheap and
     needs no protocol change.
2. **A `capabilities()` call per device:**
   - which commands this device's API level supports (`ime_action` needs API 30+, per-app locales
     API 33+, page scroll actions API 29+);
   - what `clipboard()` needs.
   Today a test learns this from `UNSUPPORTED`.
3. **Kotlin parity gaps.** Held connections and snapshots exist only in Python (the proto has
   them). JUnit roles have no device criteria (pytest has them). Both are known gaps in
   `framework-gaps.md`.
4. **Don't copy:** the string selector DSL (Tap's typed AST is a deliberate advantage; `tap-agent`
   already has a small target syntax for the shell), Appium-style element handles, and
   Maestro-style implicit waits.

---

## 4. Inner workings and methodology

### 4.1 How an action reaches the screen

| Step | Tap | agent-device (Android) | Maestro (Android) | Appium UiA2 |
|---|---|---|---|---|
| 1. Find target | Driver compiles the selector to a native UiAutomator plan (or a traversal plan for relations) over every window | Helper captures every interactive window's accessibility tree (persistent socket session, about 6–17 ms p50); the host resolves the selector or ref | CLI asks the device server for the full view hierarchy (with optional web view augmentation over CDP); the host matches the selector | Server finds the element through UiAutomator (or XPath over a dump) and caches a handle |
| 2. Gate | Exactly one match; occlusion check of the touch point against the window list (`OBSCURED`); stale node before input → `STALE_BEFORE_INPUT` | Ref-frame admission (epoch/scope); visibility, parent-owned touch point, covered annotation from drawing order; moving-target gate (100 ms still within 1.5 s) if the previous action animated | Waits until visible (implicit); `waitForAppToSettle` (hierarchy equality loop) | None beyond the handle lookup |
| 3. Input | Injected `MotionEvent` at the node's visible centre (default display) or `UiObject2.click()`; text via `ACTION_SET_TEXT` or key events | Helper `UiAutomation` injection at rect-centre coordinates, falling back to `adb shell input tap`; text via a test IME or `input text` | gRPC `tap(x, y)`; text one character at a time with a 75 ms gap | `UiObject2.click()` / accessibility actions / W3C actions |
| 4. After | Returns Android's acceptance; the test asserts the effect | Optional `--settle` diff and `--verify`; post-gesture stabilisation on the next read; fill verifies text | Settles again; `retryIfNoChange` re-taps when the hierarchy did not change | Returns |

The main structural difference: **Tap resolves and acts in one on-device call**. agent-device and
Maestro read the tree on the host, then send coordinates, so the screen can change between the
read and the tap. agent-device spends a lot of machinery closing that gap (ADR 0011, 0014, 0039:
moving-target gate, ref frames, admission loop). Tap avoids most of the problem by construction.
What remains for Tap is an animation that moves the node between its lookup and the injection,
which takes microseconds on the device.

### 4.2 Reliability model

| Concern | Tap | agent-device | Maestro | Appium |
|---|---|---|---|---|
| Duplicate or late mutations | Strictly increasing request IDs per generation, old generations refused, `INDETERMINATE`, quarantine of late work | Apple runner never resends; command IDs; `COMMAND_ALREADY_FINISHED`; `dispatched` ledger | Retries by design | No fencing |
| Device ownership | Per-serial journal lock (machine-wide, dropped by the OS when the daemon dies), held connections resumable by name, `tap doctor` | Host-global device claims with owner PID, stale detection and `device release --stale`; sessions scoped to the git worktree | None (one CLI run) | `SessionClaimHandler` IPC between sessions in one server |
| Crash recovery | Journal replays restoration on the next attach (settings, rotation, files, media) | Durable claim resources settled on the next `open`; IME restore record | — | — |
| State restoration | Every changed setting is read back and restored on detach (rotation, animations, dark mode, font scale, density, locales, network, location, stay awake, a11y display) | Mostly manual: the orientation lock "persists after the session ends", `settings animations on` afterwards; the IME is restored | Manual | Manual |
| Guarantee consistency across code paths | One path per command on the device | Machine-checked path × guarantee matrix with owned waivers (ADR 0011) | — | — |

### 4.3 Engineering practice

- **agent-device** has an ADR per cross-cutting rule, each with "rules at a glance" and rejected
  alternatives. It uses a domain-language file (`CONTEXT.md`), mutation testing (Stryker),
  layering-graph checks, parity tables shared between TypeScript and Swift, and cites PR evidence
  for timings in the docs. Its docs read like contracts: every refusal reason is listed.
- **Maestro** keeps a changelog-driven release cadence, an e2e suite on real apps, and a
  community-scale bug intake.
- **Appium** works through many small packages with semantic releases, and carries years of
  OEM-specific fixes.
- **Tap** has decision records, a device matrix (API 29 Samsung + API 34 emulator), fault-injection
  validation, golden wire bytes, `buf breaking`, and honest progress and gap docs. Its device
  coverage is narrower: two devices, and no API 26–28 or 35+ in the matrix.

---

## 5. Gaps: what they have that Tap lacks

Fit legend: **easy** (client or daemon only, small), **medium**, **large**; **conflicts** means it
runs into an invariant or a do-not-copy row; **owner** means it needs an owner decision. Tier
is the recommendation tier in §9 (A first, E needs a decision, — not recommended).

### 5.0 Gap list

Every gap found in this review, in one list. §5.1–5.4 give the details for G1–G35. G36–G44
come from the API comparison (§3) and the methodology lessons (§7).

| # | Gap | Who has it | Fit | Tier |
|---|---|---|---|---|
| **Platform and context** |||||
| G1 | iOS / tvOS / web / desktop / HarmonyOS | agent-device, Maestro, Appium | out of scope | — |
| G2 | Web views / hybrid app contexts | Appium, Maestro | large | E |
| G3 | Remote / cloud devices, remote daemon | agent-device, Maestro, Appium | large | E |
| G4 | Boot and shut down emulators | agent-device, Maestro | easy–medium | D |
| G5 | Foldable postures | agent-device | medium | — |
| **Evidence and diagnostics** |||||
| G6 | Crash / ANR reported as error codes | all three | medium | B |
| G7 | Performance capture (frames, memory, CPU profiles, Perfetto, hprof) | agent-device, Appium | medium | D |
| G8 | Streamed app logs with markers and redaction; network dump from logs | agent-device, Appium | easy–medium | B |
| G9 | Event timeline with cursors and paging for agents | agent-device | easy | A |
| G10 | Visual regression: screenshot vs. baseline, changed regions | Maestro, agent-device | medium | D |
| G11 | Screenshot `--scale`, `--overlay-refs`, `--crop-on` | agent-device | easy | A |
| G12 | Video contact sheet | agent-device | easy | — |
| G13 | Failure video attached to reports | Maestro, agent-device | medium | — |
| G14 | HTML report, insights, per-step timing file | Maestro, agent-device | medium | — |
| G15 | AI assertions | Maestro | owner | E |
| G16 | React Native tooling (devtools, Metro reload, overlays) | agent-device | out of scope | — |
| **Device and app control** |||||
| G17 | Non-ASCII `type_text` (test IME) | agent-device | medium | D |
| G18 | Telephony and SMS (emulator) | agent-device, Appium | easy | C |
| G19 | Biometrics: fingerprint match / no match | agent-device, Appium | easy | C |
| G20 | Bluetooth, NFC, GPS, battery and power, sensors, camera image, network speed | Appium, agent-device | easy each | C |
| G21 | Install `.aab`, split APKs, from a URL; `reinstall` | agent-device, Appium | easy–medium | C |
| G22 | Push simulation by broadcast; app-event deep-link template | agent-device | easy | C |
| G23 | Arbitrary intents and services, app strings, app list, trim memory, doze | Appium | easy–medium | C |
| G24 | Multi-touch: 2–5 finger taps, rotate, transform | agent-device | medium | — |
| G25 | Coordinate taps and swipes | all three | conflicts | — |
| G26 | Status-bar demo mode for stable screenshots | agent-device | easy | C |
| G27 | Scroll progress report (moved / at edge / no progress), keyboard-avoiding swipes | agent-device | easy–medium | A |
| G28 | Text read-back after fill (`unconfirmed`, before/after) | agent-device | conflicts in the driver; easy on the client | — |
| **Workflow** |||||
| G29 | Replay runner for recordings (suites, retries, resume from step N) | agent-device, Maestro | owner | E |
| G30 | Run or export Maestro YAML | agent-device | conflicts | — |
| G31 | `batch`: N steps in one request | agent-device | easy | A |
| G32 | Sharding and tags across devices | Maestro | medium | — |
| G33 | Human takeover hold | agent-device | owner | E |
| G34 | Detect a newer server than the client | agent-device | easy | — |
| G35 | MCP `instructions`, a `help` tool, `llms-full.txt` | agent-device, Maestro | easy | A |
| **API and engine** |||||
| G36 | `dispatched: no/unknown` and a hint on every failure | agent-device | easy | A |
| G37 | `capabilities()`: which commands this device supports | agent-device | easy | A |
| G38 | Kotlin SDK: held connections and screen snapshots (Python only today) | — (Tap's own parity gap) | medium | — |
| G39 | JUnit device criteria (pytest has them) | — (Tap's own parity gap) | easy–medium | — |
| G40 | Covered views inside the same window (drawing order); occlusion check for `set_text` | agent-device | medium | B |
| G41 | Fast attach: driver instrumentation kept alive (4–6 s per device per test today) | agent-device (persistent helper) | large | B |
| G42 | Snapshot off-screen summaries; short answer when nothing changed | agent-device | easy | A |
| G43 | A checked table of which guarantees each gesture path has | agent-device | easy | — |
| G44 | Install without a GitHub token (Maven Central / PyPI) | Maestro, agent-device, Appium | medium | — |

### 5.1 Platform and context coverage

| Feature | Who | Fit for Tap |
|---|---|---|
| iOS / tvOS / web / desktop / HarmonyOS | agent-device, Maestro (iOS sim, web), Appium (other drivers) | Out of scope (Android-first by design) |
| **Web views / hybrid apps**: Chrome DevTools contexts (Appium `getContexts` + Chromedriver), web view hierarchy merged through CDP (Maestro 2.9) | Appium, Maestro | **large**. The gaps doc says "WebView: nothing". Merging the CDP DOM into snapshots is diagnostic-only and could be a first step |
| Remote / cloud devices (BrowserStack, AWS Device Farm, TestMu, Limrun; remote proxy with tenants and leases; Maestro Cloud) | agent-device, Maestro, Appium (Grid) | large; the daemon is loopback-only by design (`daemon-startup.md`) |
| Emulator boot/shutdown (`boot --headless`, `maestro start-device`) | agent-device, Maestro | **easy–medium** (host core: `emulator -avd`, `sys.boot_completed`) |
| Foldable postures (`fold open/half-open/closed`) | agent-device | medium (emulator console) |

### 5.2 Evidence and diagnostics

| Feature | Who | Fit |
|---|---|---|
| **Crash/ANR as first-class results** (Maestro reports crashes from how the process ended; agent-device crash details; Appium logcat) | all three | **medium**. Already a top gap (`AUT_CRASHED`/`AUT_ANR` exist in the enum but are never sent). Tap saves crash evidence with failures since #33 |
| **Performance**: `perf frames` (dropped frames, worst windows from gfxinfo), `perf memory sample/snapshot` (hprof), simpleperf CPU profiles, Perfetto traces; Appium `getPerformanceData` (cpu, memory, network, battery) | agent-device, Appium | **medium**; fits the daemon (adb-only, read-only) |
| **Streamed app logs** with markers (`logs start/mark/clear`, rotation, redaction regexes) and `network dump` parsed from logs | agent-device (Appium `startLogsBroadcast`) | **easy–medium**. Tap reads the logcat ring buffer at failure only |
| Session event timeline readable by agents (`events`, NDJSON with cursors) | agent-device | Tap has `Events` (bounded, in memory) and `Watch`; cursors/paging are **easy** |
| **Visual regression** (`assertScreenshot` with threshold; `diff screenshot` ranking changed regions; `--normalize-status-bar` demo mode) | Maestro, agent-device | **medium**; a host-side pixel diff already exists for `STABILITY_PIXELS` |
| Screenshot conveniences: `--scale` for agent context, `--overlay-refs` (refs burned into the PNG), `--crop-on <selector>` | agent-device | **easy** in `tap-agent` |
| Video contact sheet (a grid of frames where the screen changed) | agent-device | easy (host side, over scrcpy recordings) |
| Rotating failure video / video attached to reports | Maestro (`maestro record` with step overlay), agent-device (touch telemetry) | medium; listed in the gaps doc |
| HTML report, insights (`--analyze`), timing NDJSON per step | Maestro, agent-device | medium; plan §19 |
| AI assertions: `assertWithAI`, `extractTextWithAI`, `assertNoDefectsWithAI` | Maestro | client-side only, if at all (**needs owner decision**; it is non-deterministic) |
| React Native tooling (devtools tree/profile, Metro reload, dismiss overlays) | agent-device | out of scope unless RN apps become a target |

### 5.3 Device and app control

| Feature | Who | Fit |
|---|---|---|
| **Non-ASCII text** through a test IME (`type`/`fill` of CJK, emoji) | agent-device (Maestro `inputText` per character through `setText`) | **medium**. Tap's `type_text` uses `KeyCharacterMap` and refuses with `UNSUPPORTED_CHARACTERS`; `set_text` (`ACTION_SET_TEXT`) already handles Unicode |
| Telephony and SMS (`adb emu gsm call/sms`), GSM signal/voice | agent-device, Appium | **easy** (emulator only) |
| Biometrics (`fingerprint match/nonmatch`) | agent-device, Appium | **easy** (`adb emu finger touch`, `cmd fingerprint`) |
| Bluetooth / NFC / GPS toggles, battery and power (`powerCapacity`, `powerAc`), sensors (`sensorSet`), camera image injection, network speed | Appium, agent-device (Bluetooth) | easy each (emulator console / `svc`), journaled like the network switches |
| Install from URL / `.aab` through bundletool / split APKs (`installMultipleApks`), `reinstall` | agent-device, Appium | easy–medium (bundletool is a host dependency) |
| Push simulation (broadcast with extras), app-event deep-link template | agent-device | easy (an `am broadcast` call with typed extras, reusing `IntentExtra`) |
| `startActivity`/`startService`/`broadcast` with arbitrary intents, `getAppStrings`, `listApps`, `queryAppState`, `sendTrimMemory`, `deviceidle` | Appium | easy–medium per call; `sendTrimMemory` and doze are useful for lifecycle tests |
| Multi-touch: 2–5 finger taps, rotate, combined transform, timed pan with two pointers | agent-device | medium (driver gesture planner) |
| Coordinate taps and swipes, percentage points | all three | **conflicts** (no coordinates by design) |
| Status-bar demo mode for stable screenshots | agent-device | easy (`am broadcast … demo`), journaled |
| Scroll with progress detection (`moved`/`at-edge`/`unchanged`; `scroll_until_no_progress`, `scroll_until_edge_reached`; keyboard-avoiding swipes) | agent-device | **easy–medium**. Tap's `scroll_until` gives up with `WaitTimeoutError` and cannot say "end of list" |
| Fill read-back (`verification: unconfirmed`, `requested/before/after`) | agent-device | conflicts in the driver ("driver assumes nothing"); fine as a **client-side** helper or a `tap-agent` report |

### 5.4 Workflow

| Feature | Who | Fit |
|---|---|---|
| **Replay runner** for recorded sessions (`.ad` replay, `test` suites with retries, JUnit reporter, resume from step N with a plan digest, divergence reports) | agent-device (Maestro flows natively) | **needs owner decision**. Tap exports `tap-events/1` and `tap-recording/1` but deliberately does not render or run them; Studio can replay in the page |
| Run or export Maestro YAML | agent-device | large; conflicts with the "no YAML interpreter" row |
| `batch` (N steps in one request) | agent-device | easy in `tap-agent` (loop over the core, stop at the first failure) |
| Sharding / tags (`--shard-split`, `--include-tags`) | Maestro | medium; JUnit-platform sharding is in the gaps doc; pytest-xdist plus the device locks may be enough for Python |
| Human takeover hold (agent mutations refused while a person drives) | agent-device | **easy–medium**; the daemon already knows which connection holds a device, and Studio and the watcher are natural callers |
| Version-skew handling (replace a daemon from another build only when idle) | agent-device | Tap refuses an older server; a newer server is not detected (gaps doc) |
| Skills and agent docs: `help workflow`, MCP `instructions` on `initialize`, `llms-full.txt`, an installable skills directory | agent-device, Maestro (cheat sheet tool) | **easy**. Tap has `tap-agent skill` and Markdown docs for LLMs; adding MCP `instructions` is a one-liner |

---

## 6. What Tap has that they don't

| Tap feature | Nearest competitor equivalent |
|---|---|
| On-device selector resolution, exactly-one, before input, in the same call as the injection | Appium resolves on the device but takes the first match; agent-device/Maestro resolve on the host and tap coordinates |
| No dump on the hot path (native UiAutomator plans; snapshots are diagnostic only) | agent-device captures the tree for every resolved action (fast, but a full capture); Maestro dumps and settles for every command |
| Request-ID / generation fencing, `INDETERMINATE`, quarantine of late mutations, journaled sessions | agent-device's `dispatched` ledger is the closest; no generation fencing anywhere |
| Read-back + restore of every device change on detach, and after a crash on the next attach | None; agent-device leaves orientation locked, Maestro and Appium leave settings changed |
| Several devices in one test: structured concurrency, sibling cancellation without replay, `DeviceBarrier`, roles opened in serial order | None (parallel sessions only) |
| Real test-framework integrations: JUnit 5 extension and pytest plugin, same API in both languages, role criteria (pytest) | Appium has clients but no device-aware fixtures; agent-device points to a separate product |
| Public typed contract (`tap.v1` proto, `buf breaking`) | Appium (W3C, untyped JSON); agent-device RPC is internal and version-locked |
| Permission dialogs by the controller's resource ids, 10 typed choices including partial media and location accuracy, API 29 and 34 | agent-device ranks by grant ids and English labels; Appium `acceptAlert` |
| Notifications as data (listener in the driver app: await, list, open, dismiss) | Appium `getNotifications` (similar); others none |
| Accessibility actions (23 standard, custom by label) and slider `set_progress` | agent-device can *list* custom actions on iOS but not trigger them |
| `ime_action` (the field's own editor action), with a check that the node offers it | Appium `performEditorAction` (sends without that check) |
| Clipboard that works on API 29+ through the shell's `READ_CLIPBOARD_IN_BACKGROUND` | agent-device refuses on API 36 (`cmd clipboard` missing); Maestro has set/paste only |
| Device conditions with read-back: font scale, density, system and per-app locales, a11y display settings, stay awake, mock location through a test provider | Partial in each (agent-device text-size/appearance/animations; Maestro dark mode and locale) |
| Explicit stability waits: `TREE` / `PIXELS` / `ALL` signals, driven by accessibility events | Maestro settles implicitly; agent-device `--settle` is a best-effort diff |
| Stable refs across snapshots (LCS alignment; an old ref is the same node or `UNKNOWN_REF`, never another node) and every ref is a synthesised **selector** that becomes test code | agent-device refs are positional and expire at every mutation by design |
| Tap Studio: select-then-act recorder with selectors only, exporting `tap-recording/1` | Maestro Studio (YAML), Appium Inspector (no recording into code by default) |
| tap-watcher: a read-only live view of what every connection does on every device, with shared scrcpy video | None |
| Device audio + video recording through a daemon-owned scrcpy | agent-device `screenrecord` (180 s chunks, no audio); Maestro `startRecording` |
| `sensitive` text kept out of logs, the watcher and exports | agent-device `--text-stdin` / `--record-as` (comparable) |
| Native-image daemon (fast start, no JVM on users' machines) | Maestro needs Java 17; agent-device needs Node 22+ |

---

## 7. What they do better (lessons, by competitor)

### 7.1 agent-device

1. **The agent output contract.**
   - Snapshots list visible elements first.
   - Off-screen content collapses to summaries such as
     `[off-screen below] 3 interactive items: "Privacy", "Battery", "About"`, shown inside their
     container.
   - A repeated unchanged snapshot answers with a short acknowledgement (`--force-full` to
     override).
   - Elements without area are marked `[unmeasured]`.
   - A `truncated: true` flag means the capture was cut off.
   - Screenshots take `--scale 0.3`.

   Each of these saves agent tokens or prevents a wrong action. `tap-agent` has `-i`/`--all` and
   diffs, but none of these.
2. **Every failure tells you what to do next.** There is a typed `reason`, a `hint`, `dispatched`
   and `retriable`. Wait timeouts include per-poll `outcome`/`durationMs` and the first and last
   polls.
3. **Gestures report what they observed.** A scroll returns `movement: moved | at-edge | unchanged
   | unobserved`. It refuses `scroll_no_progress` when the container still has content but nothing
   moved, and it keeps the swipe above the keyboard. This is still "report, don't judge": it
   reports an observation rather than deciding the test failed.
4. **Ref frames (ADR 0014).** Refs are an authorisation namespace that expires at the moment of the
   side effect. Tap's design (a ref names a selector, and the driver re-checks exactly-one) already
   prevents a ref silently hitting another node. Their typed rejection order
   (`ref_frame_expired` → `generation_mismatch` → `not_issued`) is still a good model for clear
   `tap-agent` messages.
5. **Covered-target detection within one window from drawing order**
   (`AccessibilityNodeInfo.getDrawingOrder`, API 24+). Controls under a sheet are marked
   `interactionBlocked: "covered"` with `coveredBy`. This is exactly Tap's open gap: `OBSCURED`
   reads only the window list.
6. **Guarantee matrix (ADR 0011).** Every dispatch path declares where it stands on every guarantee,
   and the declarations are machine-checked. Tap has fewer paths (tap, long tap, double tap, drag,
   pinch, fling, secondary display), but its known divergences (secondary-display pointer gestures,
   `set_text` not checked for occlusion) are exactly the kind of unfilled cell this catches. A
   small table in `protocol-contract.md` would do.
7. **Persistent helper with measured gains.** Snapshot p50 dropped from 227 ms to 5.8 ms when the
   helper stayed alive. Tap's driver is already persistent per session. The comparable Tap cost is
   *attach*: 1.2 s on the emulator, 3.8 s on the Samsung, 4–6 s per device per test. That is the
   "keep the driver instrumentation alive" TODO in `agent-surface.md`.
8. **Ownership tools.** `device status` reads claims without a daemon. `device release --stale`
   proves the owner is gone (PID reused, state directory deleted) before releasing. Sessions are
   scoped to the git worktree by default, which suits parallel agents in worktrees, a pattern this
   repo uses heavily.
9. **Docs as contracts, with evidence.** Timings cite PR comments and limitations name the device.

### 7.2 Maestro

1. **Time to first test.** One install command, YAML, no SDK, a 5-minute start. Tap needs a
   running server, an SDK and a GitHub Packages token for Kotlin. The one-zip bundle helps, but
   Maven Central / PyPI publishing would remove the token step.
2. **Studio and the MCP viewer** let a person watch an agent's run live, which tap-watcher can
   already do. Maestro ships the viewer *inside* `maestro mcp` and prints its URL. `tap-agent mcp`
   could offer to start the watcher the same way.
3. **Reports and sharding out of the box** (`--format junit|html`, `--shard-split`, tags).
4. **Web view hierarchy merged into the normal tree** over CDP, so `tapOn: "text"` works inside
   web views.
5. **A crash is a result**, detected from how the process ended even without a crash report.

### 7.3 Appium

1. **Breadth of `mobile:` extensions**: about 100 device capabilities behind one call. Breadth here
   comes from many thin, independent adb wrappers, the same pattern as Tap's device-actions groups.
2. **Contexts** for web views (Chromedriver auto-download, matching Chrome versions).
3. **The Settings API**: per-session tuning (`waitForIdleTimeout`, `actionAcknowledgmentTimeout`,
   `ignoreUnimportantViews`, …), useful on OEMs that stall.
4. **Ecosystem**: Inspector, plugins (images, gestures), Grid, every language.

---

## 8. Deliberate differences (keep)

These competitor behaviours are rejected for Tap, and the reasons still hold after this review:

- **Coordinate input and percentage points.** Every competitor has them. Tap keeps element-relative
  input only. agent-device's own docs show the cost: moving-target gates, `target_still_moving`,
  ref expiry, and an admission loop.
- **Implicit waits and settling** (Maestro 17 s lookups, settle after every command; uiautomator2
  `implicitly_wait`). These hide timing and slow every step.
- **Retry by default / `retryIfNoChange`.** This replays a mutation.
- **XPath / persistent handles** (Appium).
- **YAML flows as the test language** (Maestro; agent-device's Maestro engine).
- **Watchers that auto-dismiss popups** (uiautomator2). Agent-device's iOS "foreground repair"
  is similar in spirit: a read can silently re-activate the app ("silence is not proof").

---

## 9. Recommendations (prioritised)

Ordered by value per effort. None of them breaks an invariant unless marked.

**A. Agent surface (client only, about a week in total)**
1. `tap-agent` output (plain-text and JSON):
   - add `dispatched: no|unknown` and a hint line to every failure;
   - collapse off-screen content into summaries;
   - answer an unchanged snapshot briefly;
   - add `screenshot --scale` and `--overlay-refs`.
2. Add MCP `instructions` on `initialize`, a `help` tool, and an `llms-full.txt` built from
   `docs/agent/`.
3. Add a `capabilities` tool computed from `DeviceInfo` and the API level.
4. Add `batch`, which runs steps in order and stops at the first failure.
5. `scroll_until` should report *why* it stopped. If the client loop compares snapshots between
   passes, it can tell "end of list" from "timeout".

**B. Engine gaps already on the gaps list, which competitors show are table stakes**
1. Send `AUT_CRASHED` / `AUT_ANR` (Maestro and agent-device both do).
2. Detect covering views inside the same window from drawing order (agent-device's approach), and
   check `set_text`/`clear_text` too.
3. Make attach faster (keep the driver instrumentation alive between tests or connections). This
   is the biggest gap in per-test time.
4. Stream logcat into the artifacts (continuously, with markers) instead of reading the ring buffer
   at failure.

**C. New, cheap device actions (daemon/host core, journaled with read-back like group 3)**
- emulator console: telephony/SMS, fingerprint, battery/power, sensors;
- `svc bluetooth`;
- status-bar demo mode;
- `am broadcast` with typed extras;
- `am send-trim-memory`;
- install from `.aab` (bundletool) and split APKs.

**D. Medium**
- Performance evidence: frames from `dumpsys gfxinfo`, `dumpsys meminfo`, an optional Perfetto
  trace.
- Pixel diff of screenshots against a baseline, reusing the `STABILITY_PIXELS` code.
- Unicode `type_text` through a Tap test IME. (`set_text` already covers most cases; document that
  first.)
- Boot and stop emulators from `tap`.

**E. Needs owner decision**
- A replay runner for `tap-recording/1` / `tap-events/1` (agent-device's strongest workflow
  feature; today Tap deliberately leaves conversion to the user).
- Web view support (Maestro-style CDP hierarchy merge as a first, diagnostic step).
- A human-takeover hold.
- AI assertions (client-side only).
- Remote/cloud daemons.

---

## 10. Caveats

- Competitor behaviour is taken from source and docs at the commits above, not from running them.
  agent-device's docs are unusually precise. Maestro's and Appium's are less so, so some Maestro
  and Appium rows are inferred from code (for example, Maestro's 17 s / 7 s lookup timeouts in
  `Orchestra.kt`).
- agent-device changes daily (about 4,500 PRs). Re-check before relying on any detail here.
- The line counts compare different scopes: agent-device covers nine platform families, Tap covers
  one.
