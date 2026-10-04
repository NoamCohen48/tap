// The page's view of the back end's activity stream: live connections and, per device, the
// actions every connection ran on it. Mutable with a version number, read through useStore.

import { create } from "@bufbuild/protobuf";
import { AttachedDeviceEntrySchema, ConnectionEntrySchema, type ConnectionEntry } from "./gen/client_connection_pb";
import type { LoggedEvent } from "./gen/event_log_pb";
import type { Activity, WatchResponse } from "./gen/watch_pb";
import { connectionLabel, describe, failed, summary, type Described } from "./describe";

/** Actions kept per device; older ones leave the page (the back end keeps its own history). */
export const MAX_ACTIONS = 2000;

export type Action = {
  /** Unique on the page: the connection and its own event seq. */
  key: string;
  connectionId: string;
  event: LoggedEvent;
  described: Described;
  summary: string;
  failed: boolean;
  /** Lower-cased text the search box matches, computed once. */
  search: string;
};

export type DeviceActivity = { actions: Action[]; failures: number };

export class ActivityStore {
  version = 0;
  /** The last back-end seq received; the stream resumes after it. */
  lastSeq = 0n;
  /** Activities the back end evicted before this page saw them. */
  dropped = 0n;
  readonly connections = new Map<string, ConnectionEntry>();
  /** Every connection name seen, so closed connections keep theirs. */
  readonly names = new Map<string, string>();
  private readonly devices = new Map<string, DeviceActivity>();
  private readonly listeners = new Set<() => void>();

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  getVersion = () => this.version;

  device(serial: string): DeviceActivity {
    return this.devices.get(serial) ?? { actions: [], failures: 0 };
  }

  /** The connection label for [id]: "JUnit · fixture-tests". */
  label(id: string): string {
    return connectionLabel(this.names.get(id) ?? "");
  }

  /** The connection attached to [serial], if any. */
  owner(serial: string): ConnectionEntry | undefined {
    for (const entry of this.connections.values()) {
      if (entry.attachedDevices.some((device) => device.serial === serial)) return entry;
    }
    return undefined;
  }

  /** Folds one response in. The first response of a stream replaces the live connections. */
  receive(response: WatchResponse, first: boolean) {
    if (first) {
      this.connections.clear();
      for (const entry of response.connections) {
        this.connections.set(entry.clientConnectionId, entry);
        this.names.set(entry.clientConnectionId, entry.name);
      }
      this.dropped = response.dropped;
    }
    for (const activity of response.activities) {
      if (activity.seq <= this.lastSeq) continue;
      this.lastSeq = activity.seq;
      this.apply(activity);
    }
    this.version++;
    this.listeners.forEach((listener) => listener());
  }

  private apply(activity: Activity) {
    const owner = activity.clientConnectionId;
    const kind = activity.kind;
    switch (kind.case) {
      case "connectionOpened":
        this.names.set(owner, kind.value.name);
        if (!this.connections.has(owner)) {
          this.connections.set(owner, create(ConnectionEntrySchema, { clientConnectionId: owner, name: kind.value.name }));
        }
        break;
      case "connectionClosed":
        this.connections.delete(owner);
        break;
      case "deviceAttached": {
        const entry = this.connections.get(owner);
        if (entry && !entry.attachedDevices.some((d) => d.attachedDeviceId === kind.value.attachedDeviceId)) {
          const { attachedDeviceId, serial, generation } = kind.value;
          entry.attachedDevices = [...entry.attachedDevices, create(AttachedDeviceEntrySchema, { attachedDeviceId, serial, generation })];
        }
        break;
      }
      case "deviceDetached": {
        const entry = this.connections.get(owner);
        if (entry) entry.attachedDevices = entry.attachedDevices.filter((d) => d.attachedDeviceId !== kind.value.attachedDeviceId);
        break;
      }
      case "event":
        this.add(owner, kind.value);
        break;
    }
  }

  private add(connectionId: string, event: LoggedEvent) {
    const described = describe(event);
    const line = summary(described);
    const action: Action = {
      key: `${connectionId}:${event.seq}`,
      connectionId,
      event,
      described,
      summary: line,
      failed: failed(event),
      search: `${line} ${described.detail} ${this.label(connectionId)}`.toLowerCase(),
    };
    let device = this.devices.get(event.serial);
    if (!device) {
      device = { actions: [], failures: 0 };
      this.devices.set(event.serial, device);
    }
    // A new array, so views memoized on it see the change.
    const actions = [...device.actions, action];
    let failures = device.failures + (action.failed ? 1 : 0);
    while (actions.length > MAX_ACTIONS) failures -= actions.shift()!.failed ? 1 : 0;
    this.devices.set(event.serial, { actions, failures });
  }
}
