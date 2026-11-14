# Device capability discovery

Status: implemented; locally verified, including the physical API 29 device. Origin: competitor comparison G37 (PR #61).

## Decision

Expose a read-only `capabilities()` API per attached device through `DeviceService`, the Kotlin
and Python SDKs, and tap-agent CLI/MCP. The daemon owns discovery; clients do not maintain
independent Android-version tables. Discovery describes whether an operation is supported and
which prerequisites remain. It does not promise that a particular execution will succeed.

This is distinct from the driver's wire-negotiation capabilities and from
`app.data.capabilities()` (package-specific private-data access). No implicit skips, fallback
mutations, retries or execution preflight are introduced. Existing execution guards remain
authoritative. Synchronization is out of scope and must not be changed.

The upstream reference is callstack/agent-device at the audit pin
`0ebd2540a33c674194d59357a48287629b8377a9`, especially
`src/daemon/session-lifecycle/internal/__tests__/session-capabilities.test.ts`: derive support
from read-only runtime facts, exclude unsupported operations, and never start an operation to
probe its availability. Adapt that boundary, not its provider/multi-platform architecture.
No upstream code is copied.

## Semantics

Each operation has a typed ID and one of:

- `SUPPORTED`: known device/host prerequisites are met.
- `CONDITIONAL`: supported with a specific unresolved prerequisite, such as a target offering
  an accessibility action.
- `UNSUPPORTED`: a known blocker exists, such as the Android API level or missing host tool.
- `UNKNOWN`: discovery cannot establish support; not equivalent to unsupported.

Return all catalog entries, including unsupported operations. Each entry carries typed reasons,
an optional minimum API, typed requirements and a human-readable explanation. Unknown IDs
from a newer server must not break older clients. The catalog covers public device/app
operations and meaningful sub-operations; control-plane and package/element-specific discovery
are explicitly classified separately.

Discovery uses attachment facts, negotiated support where relevant, and narrow read-only host
dependency checks. It must not grant permissions, change settings, inspect private contents,
launch recording, perform test mutations or dump the UI hierarchy. It does not invoke the driver
once per operation. Changeable host facts are refreshed per discovery call rather than cached
indefinitely. Normal attachment ownership checks apply. Pre-attach selection continues using
device inventory properties and API criteria.

`SUPPORTED` does not bypass exactly-one matching, occlusion, target-state checks, Android
acceptance or transport fencing. A conditional element operation does not claim that a node
currently offers its action. Package-private-data discovery stays on its existing API.

## Restrictions verified in current code

| Operation | Known prerequisite |
|---|---|
| Keyboard IME action | API 30+ |
| Accessibility page actions | API 29+; target offers the action |
| Accessibility press-and-hold | API 30+; target offers the action |
| Per-app languages | API 33+ |
| Dark mode / network switches | API 29+ |
| Bold text | API 31+ |
| Audio output / microphone recording | API 30+ and scrcpy on the daemon host |
| Audio playback duplication | API 33+ and scrcpy; apps may exclude their audio |
| Clipboard read | Tap adopts shell background-read permission on API 29+; no manual IME/focus setup normally needed |
| Network changes | Values that would cut network ADB are refused |

Evidence: driver `KeyboardCommands`, `AccessibilityActionCommands`, `ClipboardCommands`; host
`DeviceConditions`, `AppLifecycle`, `ScrcpyRecorder`; daemon `DeviceService.startRecording`.
API eligibility is not a guarantee of OEM support, encoder availability, or audible capture.

## API shape

Add an additive RPC to `DeviceService`:

```protobuf
rpc GetDeviceCapabilities(GetDeviceCapabilitiesRequest)
    returns (GetDeviceCapabilitiesResponse);

message GetDeviceCapabilitiesRequest {
  string client_connection_id = 1;
  string attached_device_id = 2;
}

message GetDeviceCapabilitiesResponse {
  repeated DeviceCapability capabilities = 1;
}
```

`DeviceCapability` contains a typed capability ID, typed status, typed reasons, optional
`required_api`, typed `requirements`, and human-readable `detail`. IDs are operation-level,
with separate variants where support differs (for example `KEYBOARD_IME_ACTION`,
`ACCESSIBILITY_PAGE_DOWN`, `APP_SET_LOCALES`, `NETWORK_DISABLE_WIFI`, `CLIPBOARD_READ`,
`RECORD_VIDEO`, `RECORD_AUDIO_OUTPUT`, `RECORD_AUDIO_PLAYBACK`, `RECORD_AUDIO_MIC`). Do not
collapse partial support to `recording=true` or `accessibility=true`.

SDKs expose `device.capabilities()` returning a typed collection indexable by capability ID.
No boolean helper erases conditional or unknown support. Examples:

```kotlin
val support = device.capabilities()[Capability.APP_SET_LOCALES]
assumeTrue(support.status == CapabilityStatus.SUPPORTED, support.detail)
```

```python
support = device.capabilities()[Capability.APP_SET_LOCALES]
if support.status is CapabilityStatus.UNSUPPORTED:
    pytest.skip(support.detail)
```

`tap-agent capabilities` (including `--json`) and the MCP `capabilities` tool use the selected
held attachment and the same Python API. SDK-native discovery is separate from adapter-only
commands: a listed capability does not invent a CLI/MCP command.

## Use cases

1. Explain or skip unsupported tests on mixed Android-version matrices before mutation.
2. Let agents plan operations without discovering support by trying and retrying actions.
3. Let Studio display disabled controls with explanations; selected-node actions still use
   element-specific action discovery. Studio integration itself is not required in this change.
4. Distinguish Android API restrictions from missing host scrcpy during recording setup.
5. Explain transport-protection restrictions for network-ADB devices.

## Implementation plan

1. Audit public operations and meaningful variants; establish the capability catalog and
   explicit exclusions. Share API thresholds between discovery and execution rather than
   adding drifting version tables. Driver-level rules belong in shared contract/protocol code
   where necessary; host-only rules remain in host core.
2. Implement read-only daemon discovery with normal ownership checks and bounded host-tool
   inspection. Reuse attachment facts and negotiated support when relevant.
3. Add typed Kotlin/Python models, SDK methods, and tap-agent CLI/MCP parity. Regenerate
   committed Python protobuf stubs and update native-image metadata as needed.
4. Add unit tests at API 28/29, 29/30, 30/31 and 32/33 boundaries; missing scrcpy, network ADB,
   conditional target operations, unknown facts, ownership, and absence of mutation. Add a
   catalog-coverage test for represented or explicitly excluded public operations.
5. Update `.docs/server-api.md`, public guide pages and generated-reference source docstrings /
   KDoc. Keep the gap marked open until the implementation and regression checks prove it.
6. Run protocol, daemon, host and client unit checks plus API-contract validation as applicable.
   Device runs require explicit user authorization. The physical API 29 validation below was
   requested; no reboot or broader local matrix run was requested.
7. Rebase on `origin/main`, rerun relevant checks, and publish one PR for this task. Keep
   distinct building changes in distinct commits; docs/tests/generated files travel with the
   implementation they describe.

## Verification and progress

- Worktree: `.worktrees/feat/device-capabilities`, branch `feat/device-capabilities`.
- Implemented: 89 operation IDs in `capabilities.proto`; host-core canonical catalog; owned
  daemon RPC using authenticated handshake facts (no driver/ADB call); SDK-native Kotlin and
  Python models with future-ID preservation and UNKNOWN for missing entries; CLI text/JSON and
  read-only MCP parity; explicit exclusion from named action recordings.
- Recording discovery checks the recorder's configured executable on every call, searching at
  most 128 PATH entries without launching a process. A present executable leaves capture
  CONDITIONAL rather than claiming that codecs, flags or capture access have been verified.
- Shared execution/discovery thresholds cover IME, page actions, press-and-hold and recording;
  host-only thresholds reuse the existing host-core constants. No wire semantics or execution
  guards changed. No Studio control integration or pre-attach discovery was added.
- JVM checks: `:host:core:test :host:daemon:test :contracts:protocol:test
  :clients:kotlin:sdk:test :device:driver:command-engine:test` passed; Android
  `:device:driver:core:lint` passed (also compiles the changed driver sources).
- Python + agent: 430 passed, 4 skipped (offline suites, including the opt-in device tests).
- Frontend generated-contract checks: Studio 181 tests and build passed; Watcher 30 tests and
  build passed. Both committed TypeScript outputs were regenerated along with Python stubs.
- `buf lint` / `buf breaking` against origin/main and all three stub-drift checks passed.
- MkDocs configuration loading, Python reference rendering and agent reference generation
  passed. A full documentation-site build and native-image build were not run.
- Device matrix: targeted physical-device capability validation passed on `85e49002`, Samsung
  SM-J810G / Android API 29, after the user explicitly requested it. No local API 34 run or
  reboot. See the reproducible test and evidence below.
- Publishing/rebase verification is recorded in the PR; G37's implementation is covered by the
  boundary, ownership, no-driver-command, no-child-process, unknown-enum and catalog tests.

### Physical-device capability validation

`clients/agent/tests/device/test_capabilities.py` is an opt-in integration test requiring an
explicitly started capability-enabled daemon and `TAP_AGENT_DEVICE_SERIAL`. It needs no AUT
or fixture APK and sends no input or settings mutations. Reproduce with:

```sh
TAP_AGENT_DEVICE_SERIAL=85e49002 \
  .venv/bin/pytest clients/agent/tests/device/test_capabilities.py -v -s
```

For this validation the daemon's JVM distribution was built from a clean copy of the published
implementation, with bundled product driver APKs. Its separate daemon state directory linked
`sessions` to `~/.tap/sessions`, so normal machine-wide locking/journaling protected the physical
device; it did not create a private competing lock namespace. The test daemon was stopped after
validation, with no remaining client connections or device attachments.

Result: **1 passed** on the physical Samsung (two successful runs, the final one explicitly
asserting clean detach). All 89 entries were present and SDK, CLI and MCP JSON agreed. Repeated
discovery left configuration values and the event log unchanged. Driver request IDs around all
discovery calls were **3 → 4**: only the two explicit `device_info` reads consumed IDs, proving
that discovery and adapter resumption sent no hidden driver commands. The generation was stable.

API 29 reported IME action, per-app locales, bold text and all audio-recording sources as
UNSUPPORTED; page-down was CONDITIONAL on the target offering it. A read-only per-app locale
query returned the expected `UNSUPPORTED_API` refusal, and IME action returned
`UNSUPPORTED / REQUIRES_API_30` before resolving a deliberately absent target or sending input.
Configuration values still matched afterwards, and Detach explicitly returned clean. The run
neither launched an AUT nor recorded media, changed a setting, sent input or rebooted the phone.
