// The page's view of the back end's activity stream: live connections and, per device, the
// actions every connection ran on it. Mutable with a version number, read through useStore.

import { create } from "@bufbuild/protobuf";
import { AttachedDeviceEntrySchema, ConnectionEntrySchema, type ConnectionEntry } from "./gen/client_connection_pb";
import type { LoggedEvent } from "./gen/event_log_pb";
import type { Activity, WatchResponse } from "./gen/watch_pb";
import { connectionLabel, describe, failed, summary, type Described } from "./describe";

/** Actions kept per device; older ones leave the page (the back end keeps its own history). */
export const MAX_ACTIONS = 2000;

/** The longest a view waits to hear that the store changed. */
export const NOTIFY_MS = 200;

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

/** A call that started and has not ended: what the device is doing now. */
export type Running = {
  connectionId: string;
  call: LoggedEvent;
  described: Described;
  summary: string;
  /** When it started (epoch ms), for "running 3.2 s". */
  sinceMs: number;
};

/** A connection attaching or detaching the device: where one test session ends and the next begins. */
export type SessionMark = {
  key: string;
  kind: "attached" | "detached";
  connectionId: string;
  atEpochMs: number;
  /** Detached: "detached", or why the connection ended. */
  reason: string;
};

export type DeviceActivity = { actions: Action[]; failures: number; running?: Running; marks: SessionMark[] };

/** Session marks kept per device. */
export const MAX_MARKS = 200;

/** Where the page keeps, per serial, the newest failure the viewer has looked at. */
const SEEN_KEY = "tap-watcher.seen";

const EMPTY: DeviceActivity = { actions: [], failures: 0, marks: [] };

function loadSeen(): Map<string, number> {
  try {
    const raw = globalThis.localStorage?.getItem(SEEN_KEY);
    return new Map(Object.entries(raw ? (JSON.parse(raw) as Record<string, number>) : {}));
  } catch {
    return new Map();
  }
}

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
  /** Per serial: failures at or before this epoch ms have been looked at (by this browser). */
  private readonly seen = loadSeen();
  private readonly listeners = new Set<() => void>();
  private notifying?: ReturnType<typeof setTimeout>;
  /** Per serial: the unseen failures, valid while the actions array and the seen mark are. */
  private readonly unseenCache = new Map<string, { actions: Action[]; since: number; unseen: Action[] }>();

  subscribe = (listener: () => void) => {
    this.listeners.add(listener);
    return () => this.listeners.delete(listener);
  };

  /** The version views last heard about: what useStore renders by, so a pending change does not re-render early. */
  getVersion = () => this.notified;
  private notified = 0;

  device(serial: string): DeviceActivity {
    return this.devices.get(serial) ?? EMPTY;
  }

  /**
   * Failures on [serial] the viewer has not looked at: newer than the last time the device was
   * opened. Kept in this browser only, so each viewer has their own.
   */
  unseen(serial: string): Action[] {
    const since = this.seen.get(serial) ?? -1;
    const actions = this.device(serial).actions;
    const cached = this.unseenCache.get(serial);
    if (cached && cached.actions === actions && cached.since === since) return cached.unseen;
    const unseen = actions.filter((action) => action.failed && Number(action.event.atEpochMs) > since);
    this.unseenCache.set(serial, { actions, since, unseen });
    return unseen;
  }

  /** The viewer looked at [serial]: every failure on it so far is seen. */
  markSeen(serial: string) {
    const newest = this.device(serial).actions.at(-1);
    const at = Math.max(this.seen.get(serial) ?? -1, newest ? Number(newest.event.atEpochMs) : -1);
    if (at === (this.seen.get(serial) ?? -1)) return;
    this.seen.set(serial, at);
    try {
      globalThis.localStorage?.setItem(SEEN_KEY, JSON.stringify(Object.fromEntries(this.seen)));
    } catch {
      // Storage refused (private window): the marks last until the page reloads.
    }
    this.changed();
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
    this.changed();
  }

  /**
   * The data changes at once; views hear about it at most every [NOTIFY_MS], so ten busy
   * devices re-render the page a few times a second, not once per call.
   */
  private changed() {
    this.version++;
    if (this.notifying !== undefined) return;
    this.notifying = setTimeout(() => {
      this.notifying = undefined;
      this.notified = this.version;
      this.listeners.forEach((listener) => listener());
    }, NOTIFY_MS);
  }

  private setRunning(serial: string, running: Running | undefined) {
    const device = this.device(serial);
    if (device.running === running) return;
    this.devices.set(serial, { ...device, running });
  }

  /** Ends what [connectionId] was running on [serial] (every serial when undefined). */
  private endRunning(connectionId: string, serial?: string) {
    for (const [key, device] of this.devices) {
      if (device.running?.connectionId === connectionId && (serial === undefined || key === serial)) this.setRunning(key, undefined);
    }
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
        this.endRunning(owner);
        break;
      case "deviceAttached": {
        const entry = this.connections.get(owner);
        if (entry && !entry.attachedDevices.some((d) => d.attachedDeviceId === kind.value.attachedDeviceId)) {
          const { attachedDeviceId, serial, generation } = kind.value;
          entry.attachedDevices = [...entry.attachedDevices, create(AttachedDeviceEntrySchema, { attachedDeviceId, serial, generation })];
        }
        this.mark(kind.value.serial, { key: `${activity.seq}`, kind: "attached", connectionId: owner, atEpochMs: Number(activity.atEpochMs), reason: "" });
        break;
      }
      case "deviceDetached": {
        const entry = this.connections.get(owner);
        if (entry) entry.attachedDevices = entry.attachedDevices.filter((d) => d.attachedDeviceId !== kind.value.attachedDeviceId);
        this.endRunning(owner, kind.value.serial);
        this.mark(kind.value.serial, { key: `${activity.seq}`, kind: "detached", connectionId: owner, atEpochMs: Number(activity.atEpochMs), reason: kind.value.reason });
        break;
      }
      case "callStarted": {
        const call = kind.value.call;
        if (!call) break;
        const described = describe(call);
        this.setRunning(call.serial, { connectionId: owner, call, described, summary: summary(described), sinceMs: Number(call.atEpochMs) });
        break;
      }
      case "event":
        this.add(owner, kind.value);
        break;
    }
  }

  private mark(serial: string, mark: SessionMark) {
    const device = this.device(serial);
    this.devices.set(serial, { ...device, marks: [...device.marks, mark].slice(-MAX_MARKS) });
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
    const device = this.device(event.serial);
    // A new array, so views memoized on it see the change.
    const actions = [...device.actions, action];
    let failures = device.failures + (action.failed ? 1 : 0);
    while (actions.length > MAX_ACTIONS) failures -= actions.shift()!.failed ? 1 : 0;
    // The call this event finishes stops running; a start from another call stays.
    const running = device.running;
    const ended = running && running.connectionId === connectionId && (running.call.startedMonotonicNs === undefined || running.call.startedMonotonicNs === event.startedMonotonicNs);
    this.devices.set(event.serial, { ...device, actions, failures, running: ended ? undefined : running });
  }
}
