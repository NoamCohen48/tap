# Private app data

`app.data` accesses a **debuggable app's** private files through the Tap server. The server
uses serial-specific ADB `run-as`, not root or a shell command supplied by the client. It probes
actual access: a debug-build label alone does not guarantee that `run-as` works on an OEM device.
Tap's driver and instrumentation packages are protected.

## Stop and restart explicitly

```python
app = device.app("com.example.shop")
access = app.data.capabilities()  # safe while the app runs
if not access.available:
    raise RuntimeError(access.reason)

app.force_stop()                 # your decision, never hidden inside a data call
entries = app.data.list("files")
app.data.write("files/test-state.bin", b"\x00\xfffixture")
assert app.data.read("files/test-state.bin") == b"\x00\xfffixture"
app.data.delete("files/test-state.bin")
app.launch()                    # also your decision
```

Except the capability probe, every operation requires all processes under the app UID to be
stopped, including secondary, native and shared-UID processes. Tap checks before and after
access and serializes explicit app lifecycle changes with data operations on that device.
Do not concurrently start or drive the app through UI, notifications, another ADB client or
another external actor. This is not an OS filesystem lock; a post-operation running-app error
can arrive after bytes have changed. Inspect the result rather than repeating a mutation blindly.

These changes **persist after release/detach**. Unlike pushed gallery/device files, app data is
not silently restored or deleted. No SQL is executed, encryption bypassed, schema migrated,
root fallback attempted, or app stopped/restarted implicitly.

## Files and limits

| Python | Kotlin | Effect |
|---|---|---|
| `capabilities()` | `capabilities()` | `AppDataAccess(available, reason)` for the current Android user |
| `list(path="")` | `list(path = "")` | Immediate children; empty path lists the private root |
| `read(path)` | `read(path)` | Binary bytes, with server-side size and SHA-256 verification |
| `write(path, data, overwrite=False)` | `write(path, data, overwrite = false)` | Create parents and write; replacement needs explicit consent |
| `delete(path)` | `delete(path)` | Delete one regular file, never a directory or the data root |

Paths are normalized relative UTF-8 paths under the app's credential-encrypted private data
root, such as `files/state.bin`, `shared_prefs/preferences.xml` or `databases/shop.db`. Absolute
paths, empty components, `.`/`..`, backslashes, control/replacement characters and reserved
`.tap-private-` components are rejected. Paths are at most 1024 UTF-8 bytes. Symlink entries
can be listed (`is_symlink` / `isSymlink`) but are never followed, even in a parent component.
Device-encrypted/external data and arbitrary binary filenames are outside this surface.

A file or complete database bundle is at most **64 MiB**. Directory listings have at most
1024 immediate entries. Transfers use chunks of at most 1 MiB; clients validate stream shape,
paths and declared sizes. The SDK methods return bytes in memory. `read`, `write` and database
transfers accept an optional timeout; otherwise they use the device's lifecycle timeout.
Private file paths and contents are omitted from connection event records; local exports are
still sensitive and must be stored accordingly.

Refusals raise Python `AppDataError` or Kotlin `AppDataException`. Their `detail` is a stable
token such as `APP_RUNNING`, `RUN_AS_UNAVAILABLE`, `SYMLINK`, `EXISTS`, `NOT_FOUND`,
`NOT_REGULAR`, `LIMIT`, `INCONSISTENT_SNAPSHOT` or `INCOMPLETE_IMPORT`. Invalid arguments and
transport failures retain the usual client error types. An unavailable process-table scan fails
closed (`UNSUPPORTED_PROCESS_SCAN`), rather than assuming the app is stopped.

## SQLite bundles

A force-stopped SQLite app can leave **committed transactions in WAL**. Copying only the base
file can lose those transactions or even the schema. `export_database` / `exportDatabase`
returns a `DatabaseBundle`: `name`, base `database` bytes, optional `wal`, optional rollback
`journal`. It never includes `-shm`, which is rebuildable coordination state.

```python
app.force_stop()
backup = app.data.export_database("shop.db")
# Keep database, wal and journal together. Import needs the app's compatible schema and keys.
app.data.import_database(backup, overwrite=True)
assert not app.is_running()
app.launch()
```

```kotlin
app.forceStop()
val backup = app.data.exportDatabase("shop.db")
app.data.importDatabase(backup, overwrite = true)
check(!app.isRunning())
app.launch()
```

Database names are basenames in `databases/`, not paths or sidecar names. An import can choose
another `name`. It stages and verifies all bytes before replacing files, refuses existing
files by default, and removes old sidecars (including SHM) that do not belong to the new bundle.
It needs no device `sqlite3` executable. SQLCipher/Keystore-backed databases remain encrypted;
app-specific keys, schema and migrations are your responsibility.

Replacement spans several files, **not one atomic OS transaction**. Normal commit-script
failure attempts to restore the old files. Device/process/transport loss during commit can
leave a partial import: do not launch or repeat blindly. A retained
`databases/.tap-private-backup-*` directory blocks another import (`INCOMPLETE_IMPORT`). Inspect
and repair it with the app's debugging tools, or explicitly clear fixture data and import a
known-good bundle. Tap never discards a retained backup automatically.

## Agent CLI and MCP

```sh
tap-agent app stop com.example.shop
tap-agent app-data capabilities com.example.shop
tap-agent app-data list com.example.shop files
tap-agent app-data read com.example.shop files/state.bin ./state.bin
tap-agent app-data write com.example.shop files/state.bin ./state.bin --overwrite
tap-agent app-data export-db com.example.shop shop.db ./shop-backup
tap-agent app-data import-db com.example.shop shop.db ./shop-backup --overwrite
tap-agent app launch com.example.shop
```

`read` requires a new local file (mode 0600); `export-db` requires a new directory (mode 0700)
and writes the base database and existing `-wal`/`-journal` files together. Do not reuse a folder
with stale sidecars. Import reads those files, not SHM. Contents are never printed. MCP's
`app_data` tool takes the same action, package, private path/database name, local path and
explicit overwrite flag. These are host-local paths on the machine running the agent, not
paths the daemon can access directly.
