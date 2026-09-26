# Daemon, client, and device terminology

Status: accepted design.

## Purpose

Tap has one long-running host process between test clients and Android devices. This document gives every role one name, separates the client-facing server from the device-facing driver client, and keeps ownership relationships non-cyclic.

## System shape

```text
Kotlin / Python SDK client
          |
          | gRPC
          v
+----------------------------- TapDaemon -----------------------------+
|                                                                    |
|  TapServer                                                         |
|      |                                                             |
|      v                                                             |
|  ClientConnection      AttachedDevice registry                     |
|                               |                                    |
|                               | owns cleanup of                     |
|                               v                                    |
|                          DeviceSession                              |
|                               |                                    |
|                               v                                    |
|                          DriverClient                               |
|                               |                                    |
|                               v                                    |
|                          DriverTransport                            |
+-------------------------------|------------------------------------+
                                | TAP1 over an ADB-forwarded socket
                                v
                         on-device Driver
                                |
                                v
                         AUT / Android system
```

The daemon has two roles:

- Toward SDK clients it is a **server**: it listens for gRPC requests.
- Toward the on-device driver it is a **client**: it opens an authenticated TAP1 connection and sends commands.

Tap-owned architecture uses daemon and server rather than service. The `service` keyword remains only where protobuf or gRPC requires it.

## Terms and ownership

### `TapDaemon`

The long-running host process started by `tap start`.

It owns:

- the `TapServer` lifecycle;
- the `ClientConnection` registry;
- the `AttachedDevice` registry;
- daemon-wide device discovery and bundled-driver installation state;
- shutdown coordination and detached cleanup work.

The daemon creates both client connections and attached devices. It is the only object that resolves their public IDs.

### `TapServer`

The client-facing gRPC listener and request adapters inside `TapDaemon`.

It:

- binds to loopback;
- receives requests from SDK clients;
- validates and converts protobuf messages;
- delegates state and lifecycle operations to `TapDaemon`;
- maps results and failures back to gRPC.

Its public RPC areas are client connections, devices, and apps. It does not own ADB, driver instrumentation, or TAP1 transport state.

### `ClientConnection`

One logical connection from one SDK process to the daemon.

It contains:

- a connection ID;
- a diagnostic client name;
- Observe-stream ownership and close callbacks;
- closed/open state.

The long-lived Observe stream is the liveness signal for the SDK process. Disconnecting it detaches every device whose `ownerConnectionId` matches the connection.

A `ClientConnection` does not contain attached-device objects or IDs. The authoritative ownership relationship exists only on `AttachedDevice.ownerConnectionId`.

### `DeviceEntry`

A device discovered through ADB. It is inventory, not ownership.

A device entry may be free, leased, quarantined, or offline. Listing a device does not attach it and creates no driver process.

### `AttachedDevice`

The daemon/server-facing record for one device currently attached to one client connection.

It contains:

- the public attached-device ID;
- the owning connection ID;
- one `DeviceSession` reference through the daemon's testable device-session seam;
- the client-selected default command timeout;
- the captured driver log.

It answers: “Which device has this client attached, and how does the server address it?” SDK requests use `attached_device_id`.

One client connection may own zero, one, or many attached devices. One attached device has exactly one owner and one device session.

### `DeviceSession`

The host-core implementation of an attachment. It owns exclusive use and lifecycle of one Android device.

It contains or controls:

- the device serial and AUT package;
- the per-serial device lease;
- the persistent session journal;
- the TAP1 session ID and generation;
- the on-device driver instrumentation process;
- the exact ADB port forward;
- one `DriverClient`;
- per-package `AppLifecycle` objects;
- in-flight ADB-operation tracking;
- cleanup and quarantine state.

Attaching a device acquires the lease, recovers prior state, starts the driver, creates the forward, authenticates the driver client, verifies health, and records READY. Detaching reverses those resources and records CLOSED or QUARANTINED before releasing the lease.

A device session is never transferred between clients. Another client can attach the serial only after the old device session releases its lease, and receives a fresh session ID, secret, driver run, and generation.

A `DeviceSession` knows nothing about `TapDaemon`, `TapServer`, `ClientConnection`, `AttachedDevice`, gRPC, or SDK clients. Host validation can use it without running the daemon.

### `DriverClient`

The daemon-side authenticated client of the on-device driver.

It owns:

- the host socket endpoint;
- TAP1 authentication and version/capability negotiation;
- command validation and request construction;
- typed execution, raw send, screenshot, ping, heartbeat, and close operations;
- pending-command state, cancellation, deadlines, artifact verification, and transport-loss classification;
- one `DriverTransport`.

`DriverClient` is named from the daemon's device-facing perspective: the daemon is the client and the Android instrumentation process is the driver server.

### `DriverTransport`

The private wire implementation beneath `DriverClient`.

It owns ordered request admission, request IDs, serialized frame writes, pending-command routing, blob assembly, PING/PONG state, and terminal close/poison behavior.

### `Driver`

Tap's instrumentation process running on Android. It is not the device.

```text
Android device
├── Tap driver
├── application under test (AUT)
└── Android system and permitted system packages
```

## Actions

The vocabulary distinguishes process connectivity, device ownership, and UI state:

- **Connect / disconnect** an SDK client process.
- **Observe** a client connection for liveness.
- **Attach / detach** a device.
- **Wake / unlock** a device screen.
- **Launch / stop** an app.

“Open device” is not used because open could mean waking or opening the screen.

## Non-cyclic registry model

The daemon owns two independent registries. The only ownership edge is an ID on the attached device.

```kotlin
class ClientConnection(
    val id: String,
    val name: String,
)

class AttachedDevice(
    val id: String,
    val ownerConnectionId: String,
    val deviceSession: DaemonDeviceSession,
    val defaultTimeoutMs: Long,
    val driverLog: DriverLogBuffer,
)

class TapDaemon {
    val clientConnectionsById: MutableMap<String, ClientConnection>
    val attachedDevicesById: MutableMap<String, AttachedDevice>
}
```

There is no object-reference cycle and no duplicate per-connection device-ID index that can disagree with the authoritative registry.

The ownership direction is:

```text
TapDaemon
├── registry owns ClientConnection
└── registry owns AttachedDevice
        └── owns cleanup responsibility for DeviceSession
                └── owns DriverClient
                        └── owns DriverTransport
```

## Connection lifecycle

```text
SDK client sends Connect
→ TapServer validates the request
→ TapDaemon creates ClientConnection
→ register it under the lifecycle lock
→ return client_connection_id
→ SDK starts Observe stream
```

If Observe ends unexpectedly, the daemon disconnects the client connection and detaches every attached device whose `ownerConnectionId` matches.

## Device attachment

```text
SDK client sends AttachDevice
→ TapServer validates serial and AUT package
→ TapDaemon verifies ownerConnectionId
→ open DeviceSession outside the lifecycle lock
→ create AttachedDevice
→ under the lifecycle lock, revalidate the owner and register AttachedDevice
→ return attached_device_id and device information
```

If disconnect wins while device startup is suspended, the newly opened `DeviceSession` is closed before exposure.

## Detach and reuse

Detaching one device:

```text
under lifecycle lock:
  remove AttachedDevice from attachedDevicesById
outside lifecycle lock:
  close its DeviceSession
```

Disconnecting a client:

```text
under lifecycle lock:
  remove and mark ClientConnection closed
  scan attachedDevicesById for matching ownerConnectionId
  remove all matching AttachedDevices
outside lifecycle lock:
  end Observe
  close every detached DeviceSession
```

Registry lifetime and cleanup lifetime intentionally differ. An attached device becomes unreachable before slow device cleanup starts. During cleanup the device lease remains held, so another attach either fails busy or waits according to its lease timeout. The old attachment is never adopted or transferred.

Transport loss does not repair a device session in place. The attached device remains available only for cleanup; callers detach it and attach a fresh device.

## Server API shape

```text
ClientConnectionService
├── Connect
├── Observe
├── Disconnect
└── Info

DeviceService
├── ListDevices
├── Attach
├── Detach
├── Execute
├── Screenshot
└── DriverLog

AppService
├── Install / Uninstall
├── Launch / ForceStop / ClearData
├── Process / IsRunning / IsInstalled
├── GrantPermission
├── ColdLaunch
└── AwaitIdle
```

The SDK-facing `Device` class is the client proxy for an `AttachedDevice`. The daemon type remains `AttachedDevice` so it cannot be confused with an inventory `DeviceEntry`.

## Naming map

| Old name | Current name |
| --- | --- |
| Tap host service/process | Tap daemon |
| `TapService` | `TapDaemon` |
| `ServiceConfig` | `DaemonConfig` |
| daemon `Connection` | `ClientConnection` |
| daemon `Session` / `ClientSession` | `AttachedDevice` |
| `client_session_id` | `attached_device_id` |
| `OpenClientSession` | `AttachDevice` |
| `CloseClientSession` | `DetachDevice` |
| connection `Open` | `Connect` |
| connection `Attach` liveness stream | `Observe` |
| connection `Close` | `Disconnect` |
| `DeviceSession` | unchanged |
| `DriverClient` | unchanged |
| `DriverTransport` | unchanged |

## Invariants

- One client connection may own multiple attached devices.
- One attached device owns cleanup responsibility for exactly one device session.
- One device serial has at most one live device session, enforced by its journal lock.
- Disconnecting or losing a client connection detaches all devices it owns.
- A device session is never transferred or reused by another client.
- A fresh attachment advances the generation and uses a new TAP1 session ID and secret.
- Slow cleanup happens outside the daemon lifecycle lock.
- Attachment registration and detachment are atomic under that lock.
- `AttachedDevice.ownerConnectionId` is the single ownership index.
- Host modules never depend on SDK client modules.
- `DeviceSession` never depends on daemon/server classes.
- No command bypasses `DriverClient` and `DriverTransport`.
