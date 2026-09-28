# Daemon startup: who starts `tap`, and how the starter knows it is ready

Decision record. Supersedes the auto-start behaviour the clients had until September 2026.

## Decision

1. **Starting the daemon is explicit.** `tap start` is the only code path that spawns a daemon.
   Clients (`TapClient`, `TapClient.create()` (Python)) discover a running one — `TAP_SERVER`, then a live
   `<state-dir>/daemon.json` — and otherwise fail with *no running tap daemon; run `tap start`*.
   Convenience lives one layer up: `TapDaemonProcess.start()/stop()` and
   `tap.start_daemon()/stop_daemon()` shell out to `tap start` / `tap stop`, and the test
   runners run them around a whole run when asked (`tap.manageDaemon`, `tap_manage_daemon`),
   stopping only a daemon they started.
2. **Readiness is the health RPC on a port the starter chose.** `tap start` reserves a free
   port, launches `serve --port N` detached with its output in `daemon.log`, and polls
   `ClientConnectionService.Info` (authenticated with the new instance's token) until it answers, the child exits, or 30 s pass. Nothing is parsed
   from the child's output; the `TAP_SERVER_READY port=N` stdout line is gone.

## Why not auto-start

The previous design copied the ADB server: the first client to find no daemon spawned one.
That is convenient for a single developer but it hid the daemon's lifetime — users asked
*"wait, the client starts the daemon?"* — and it put process-spawning code into two clients
in two languages (each with its own readiness parser, log redirection and detach quirks), and
any future client would need a third copy. Explicit start keeps one spawner, in the binary
that knows how to run itself, and makes the lifetime visible: a CI step, a runner option, or a
one-liner in a script.

## Why not parse stdout

The old line worked, but it was one channel doing two jobs: a log stream and a control signal.
The parent had to scan free text for a magic prefix, keep the pipe drained forever (or the
child blocks on a full pipe), and treat EOF as "the child died". Those are the classic
failure modes of stdout-as-protocol. Alternatives that established tools use:

| # | Pattern | Used by | Assessment for Tap |
|---|---|---|---|
| 1 | **Caller-chosen port + health poll** — parent picks a free port, passes it, polls a health RPC until it answers or the child exits | Selenium `DriverService` (picks the port, polls `/status`), most test-harness "wait for server" loops | **Chosen.** The health API (`Info`) *is* the readiness signal, so nothing extra is invented; identical for the JVM dist and the native image; any language can do it. The only weakness is a tiny TOCTOU on the port: another process may grab it between reservation and bind — then `serve` fails to bind, exits, and `start` reports the exit. |
| 2 | **Notify socket** — parent creates a private datagram/stream Unix socket, passes its path in `NOTIFY_SOCKET`, child sends `READY=1` (plus arbitrary `KEY=VALUE`) when it is listening | systemd `sd_notify(3)`; `start-stop-daemon --notify-await`; many Go/Rust daemons | The most rigorous: structured, private to this launch, no polling, the child can report its port. Costs: a Unix-socket server in the parent (JDK ≥ 16 has `UnixDomainSocketAddress`; Windows needs AF_UNIX on Win10+), datagram semantics on JDK are awkward (stream sockets only), and it is a second protocol to document. Over-engineered for a loopback development daemon whose health RPC already exists. |
| 3 | **Ready file at a caller-provided unique path** — `--ready-file PATH`; child writes port (atomically) when listening; parent polls the file and the child's liveness | Chrome `DevToolsActivePort` in the profile dir | Private per launch and atomic, but still file polling, and Chromium itself is moving away from it (issue 40645090) towards pipes because of races between profile reuse and readiness. We already keep `daemon.json` for later discovery; a second file adds nothing the health poll does not give. |
| 4 | **Inherited pipe / file descriptor** — `--ready-fd 3`; child writes to an fd the parent created and closes it when ready; EOF = child gone | Chrome `--remote-debugging-pipe`, gVisor, many C daemons (`daemon(3)`-style readiness pipes) | The cleanest in C: no polling, no filesystem, no port. Not available to us: a JVM parent cannot pass arbitrary fds through `ProcessBuilder` (only stdin/stdout/stderr), and the native image runs the same code. |
| 5 | **Fixed well-known port** — no signal at all; clients just connect | ADB server on 5037 | No protocol to design, but port collisions, one daemon per host, and still a poll on the client side. Our `--state-dir` isolation (two daemons on one machine, as CI does) rules it out. |

Option 1 wins because it uses an RPC we already have, needs no new wire format, and behaves
the same from Kotlin, Python, a shell script and the native image. The `daemon.json`
descriptor remains for what it was always for: later discovery by clients, `tap status`, and
`tap stop`.

## Details worth knowing

- `tap start` re-executes *itself*: the native executable via `ProcessHandle.current().info().command()`
  (detected by `org.graalvm.nativeimage.imagecode`), the JVM dist via `java.home/bin/java -cp
  java.class.path io.github.noamcohen48.tap.daemon.TapDaemonMainKt`. The launcher script is not re-entered.
- The child gets `/dev/null` as stdin and `daemon.log` (append) as stdout+stderr, so it survives
  the starter's exit and a closed terminal. Java children are not in a new session; on Linux the
  starter exiting does not signal them.
- `--port 0` (dynamic) is still the `serve` default for foreground use; `start` always passes an
  explicit port because that is what it polls.
- `--adb` is forwarded from `start` to `serve`; `--state-dir` always is.
- JUnit: `TapLauncherSessionListener` (registered in `META-INF/servers`) calls
  `SharedConnection.shutdown()` when the launcher session closes, so `tap stop` runs after the last
  test rather than in a JVM shutdown hook; the hook remains as a fallback and the shutdown is
  idempotent. The listener needs `junit-platform-launcher` on the test runtime, which Gradle's
  and every IDE's JUnit Platform runner already provides (`compileOnly` in `tap-junit5`).
- pytest: the session-scoped `tap_server` fixture starts and, in its teardown, stops.

## Sources

- systemd `sd_notify(3)` — <https://man7.org/linux/man-pages/man3/sd_notify.3.html>
- `start-stop-daemon(8)` `--notify-await` — <https://manpages.debian.org/testing/dpkg/start-stop-daemon.8.en.html>
- "systemd and sd_notify" — <https://dxuuu.xyz/systemd-sdnotify.html>
- Chromium: stop relying on `DevToolsActivePort` — <https://issues.chromium.org/issues/40645090>
- Chrome remote-debugging switches (`--remote-debugging-pipe`) — <https://developer.chrome.com/blog/remote-debugging-port>
