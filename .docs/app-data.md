# Debuggable app data

Status: implemented; Kotlin and Python API 34 file/WAL round trips passed. API 29 remains
unverified because the Samsung phone is disconnected.

## Decision

Private app data is accessed by host core through serial-specific ADB `run-as`, never by a
client, root, a new app service, or arbitrary user-supplied shell. An installed app being
labelled a debug build is not sufficient: probe whether `run-as` actually works on this device.
Report unavailable access explicitly; do not attempt to bypass Android's debuggability checks.
The driver and its instrumentation packages remain protected.

The first surface covers capability detection, private file list/read/write/delete, and
SQLite database export/import bundles, not SQL execution. Paths are relative to the app's
credential-encrypted data directory. Reject absolute paths, traversal and symlink components.
Bound byte counts. Never include file contents in the connection event log or errors.

The caller decides when to stop and relaunch the app. Data access requires it to be stopped;
Tap does not silently stop, relaunch, clear, or restore app data. Stop verification includes
secondary processes. External actors can still start an app; this is not an OS filesystem
transaction or a substitute for cooperating with the app.

SQLite bundles must preserve the database and any `-wal` and rollback `-journal` files. A
force-stopped app can retain committed WAL transactions: copying only `.db` is not a backup.
`-shm` is rebuildable coordination state and is not portable database content. Never assume a
`sqlite3` executable exists on the device. Database contents may still require the app's own
schema, encryption keys and migration logic; SQLCipher/Keystore-backed data is not decrypted.

## Implementation and limits

`AppPrivateData` in host core is the one implementation. `AppDataService` (`app_data.proto`)
checks connection ownership and exposes distinct RPC envelopes around a shared header/chunk
body. Kotlin/Python `app.data` and agent `app-data` / MCP `app_data` use it; no client runs ADB.
The additive service and `APP_DATA` failure reason need no device-wire/compatibility epoch bump.
Older daemons reject unsupported RPCs, never silently substitute another operation.

Files/bundles are at most 64 MiB, chunks 1 MiB, paths 1024 UTF-8 bytes, listings 1024 immediate
children. Check actual run-as UID for the current Android user and scan all processes under that
UID, not only package-named processes. Explicit lifecycle mutations share the device data mutex;
UI/notifications/outside actors must not concurrently drive the app. Post-check errors can
follow changed bytes; they do not make mutations safe to replay.

Binary transfers never enter the text ADB collector/argv: use shell-only mode-0600 files and
owner-only host spools. Journal only those shell files as initially absent, before creation.
Copy uploads to mode-0600 host files before adb push because push transmits source permissions.
Verify size/SHA-256 before use. Paths and contents are omitted from event records. Host crashes
can leave owner-only host spools/private staging; app changes are never journal-restored.

The API 34 production-style image refused a run-as hard link (`ln: Permission denied`). Use
`mv -n -T` and verify that it consumed the temporary file for no-clobber writes, not an assumed
hard-link permission. This still does not exclude an external actor racing the filesystem.

Database import verifies/stages the complete bundle before replacing the old files. The commit
script keeps old base/WAL/journal/SHM in a reserved private backup and attempts rollback on a
definite error. Required staged files must exist before any old file is moved and their moves
must succeed: lost staging must never turn replacement into deletion. Multi-file replacement
is not atomic/durable across process/device loss. Preserve an interrupted backup and block
another import (`INCOMPLETE_IMPORT`); never silently discard/replay/relaunch. Repair explicitly,
or explicitly clear fixture data before importing a known-good bundle.

The backup's `main`, `main-wal`, `main-journal`, `main-shm` hold old files already moved;
`absentmain` / `absentmain-<suffix>` mark originally absent files. Missing backup entries can
also mean an old file was not moved yet. These are recovery artifacts, not a portable bundle;
use debugging tools for repair, not a guessed sequence of blind renames.

## Verification

Offline tests execute the actual POSIX scripts in disposable host sandboxes: binary/Unicode/
quoted paths, overwrite, symlinks, stopped UID, WAL/journal/SHM handling, rollback and lifecycle
serialization. gRPC tests cover ownership/driver protection, shape/size rejection and cleanup;
SDK/agent tests cover streamed chunks, malformed responses, explicit policy and owner-only local
exports. The sandbox is not evidence of an OEM SELinux policy: real-device checks are separate.

API 34 (`emulator-5554`): Kotlin SDK file/bundle round trip passed. Python also opened the
exported base+WAL with host SQLite and observed committed value 41, changed it to 99 offline,
then imported and observed 99 in the app. Import of the original snapshot after explicit clear
recovered 41. Both paths proved no automatic restart, symlink refusal and binary byte fidelity.
The native image built successfully, and the same Python API 34 private-data round trip also
passed against native `tap`, exercising the new RPC envelopes and reflection metadata.
No SQL ran through a Tap endpoint. Mutations were fixture-only; no reboot. API 29 (`85e49002`)
was disconnected and was not run.

## Evidence

- Android's Device Explorer documentation: private directories of non-debuggable apps cannot
  be expanded: https://developer.android.com/studio/debug/device-file-explorer
- AOSP Android 14 `run-as`: verifies shell/root caller, app UID, debuggability and data-directory
  ownership, then switches to the app identity and SELinux context:
  https://android.googlesource.com/platform/system/core/+/android-14.0.0_r1/run-as/run-as.cpp
- SQLite's WAL documentation: WAL is persistent database state and must remain with a database
  copied after an unclean shutdown: https://sqlite.org/wal.html

## Related action audit

Launch, cold launch, force-stop, clear-data, install/uninstall, foreground/background, deep
links, process identity and running state already exist in the Kotlin/Python APIs. Existing
host validation and client lifecycle tests exercise stop/clear/relaunch; strengthen these rather
than introduce duplicate lifecycle calls. `clearData` means Android's `pm clear` (storage,
cache and runtime permissions), not uninstall. `launch` reports Android's `am start -W` result,
not UI readiness: explicit visibility/stability waits remain the caller's choice.

Missing touch actions are arbitrary-count taps and a caller-selected press duration. Add these
as new command operations so an older daemon/driver rejects them instead of silently ignoring
new fields on an old operation. Preserve existing tap/double-tap/default long-tap behaviour.
All taps in a counted sequence target the same initially resolved, unobscured point; do not
re-resolve or retry after input. Bound the sequence duration and check the remaining command
budget before touching the screen. Multi-tap timing does not promise any app-specific
triple-click recognition. Exact-duration presses are physical input, not an accessibility
long-click action.
