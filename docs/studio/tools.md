# App data and diagnostic tools

Open **Tools (not recorded)** at the top of the composer while Studio has a device attached.
These are explicit SDK operations, not flow steps: recording, replay and generated selectors do
not include them. Studio acts only through its own connection and attached device.

## Read app state

**Tool package** shares the App tab's package field. Choose **Installed**, **Running**, **Process
identity**, **App languages**, or **Permission granted**, then **Read app state**.

Process identity uses the SDK's process lookup, which can wait briefly; its optional budget is
1–120000 whole milliseconds. The other queries use their SDK budgets. Running means a process
exists, not that a screen is ready. Use the App tab's foreground wait or an element wait for
visibility/readiness. Reading state never launches or stops the app and never records a step.

## Private files

This is the [debuggable private-data API](../guide/app-data.md), not arbitrary ADB or shell access.
It needs actual run-as capability for the current Android user. **Capabilities** is safe while
the app runs. For every other operation, explicitly use **App → Force stop** first: the entire
UID, including secondary/native/shared-UID processes, must be stopped. The daemon checks this;
a checkbox does not bypass it. Do not drive the app concurrently through UI, notifications,
another client or an external ADB process. This is not an OS filesystem lock.

Choose a private-data operation:

- **List files:** a normalized relative directory; empty lists the private root. The result is
  immediate-child metadata, not a recursive dump. Symlinks may be listed but are never followed.
- **Download file:** a relative regular-file path, such as `files/state.bin`. Bytes are downloaded
  without converting binary data into text or putting it in a recording.
- **Write uploaded file:** choose a local browser file and a relative destination. Empty files
  are valid. Existing files are refused unless **Overwrite existing data** is selected.
- **Delete file:** deletes one regular file, never a directory or recursive tree.

Writes, deletes and imports need **I explicitly stopped the app and confirm this private-data
mutation**. Consent is for one attempt and the current package/parameters. Changing the target,
file, operation or overwrite choice clears it. Studio never force-stops, restarts or restores
private data for you. A failed or uncertain mutation must not be repeated blindly.

Reads/writes and database transfers accept a separate optional transfer budget (1–120000 ms).
Capabilities, listing and deletion retain their SDK budgets.

## SQLite bundles

**Export database bundle** takes a database basename, such as `shop.db`, and downloads one
`.tap-database.json` file. Keep it private. It is `tap-studio-database/1`, proto3 JSON containing
`format`, `name`, base `database` bytes, and optional `wal` and rollback `journal` bytes (base64).
Empty-but-present sidecars remain present. Rebuildable SHM is never exported.

To restore, choose **Import database bundle**, upload that Studio bundle and optionally enter a
different destination basename. Empty keeps the bundle's name. Replacement requires the explicit
overwrite option and mutation confirmation. This is an opaque file transfer, not SQL execution,
schema migration, decryption or an atomic multi-file transaction. Compatible app schema/keys are
your responsibility. Interrupted imports can leave recovery data: do not relaunch or repeat;
inspect/repair explicitly as described in the private-data guide. Release does not undo changes.

This bundle is separate from both Studio recording files and agent directory exports; use the
private-data upload, not **Open recording**.

## Diagnostic downloads

Choose **Screenshot**, **Diagnostic hierarchy**, **Device logs**, **Driver log**, **Raw event
history**, **Daemon metadata**, **Device metadata**, or **Device inventory**, then **Download
diagnostic**. These use SDK diagnostics; hierarchy is never used to select or target an action.

Raw history is this Studio connection's `tap-events/1`, not an editable `tap-recording/2` flow.
**After event sequence** accepts a nonnegative 64-bit sequence, including values larger than a
JavaScript safe integer. The output reports whether older ring events were dropped. Secret
Set text/Type keys values are withheld by the daemon; ordinary selectors, notes, clipboard,
logs, screenshots and app data can still be sensitive.

## Limits and remaining boundaries

Browser file transfers, whole database bundles and diagnostic downloads are capped at **1 MiB
of bytes**. Oversized results fail rather than being truncated. Use the SDK's larger/streamed
surface for larger artifacts. Bundle JSON uploads are also bounded before parsing.

Downloads are requested through the browser, not written to arbitrary paths on the Studio
server. Browser download permissions and retention apply; Studio cannot guarantee a download
completed or assign POSIX permissions to it. Save sensitive artifacts securely.

The toolkit currently requires an attachment. Studio remains single-device, with one editable
recording at a time; saved recordings keep named copies on this computer. It does not yet provide
agent archive conversion, shared-storage/APK tools, or owned audio/video capture controls.
