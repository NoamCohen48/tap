package io.github.noamcohen48.tap.daemon.cli

/** A usage error; handled at the executable boundary, never by exiting from the parser. */
internal class UsageException(
    message: String? = null,
    val command: String = "",
) : IllegalArgumentException(message)

internal data class CommandLine(
    val command: String,
    val options: Map<String, String> = emptyMap(),
    val arguments: List<String> = emptyList(),
    val help: Boolean = false,
)

internal data class OptionHelp(
    val value: String?,
    val description: String,
)

internal data class CommandHelp(
    val summary: String,
    val options: Set<String> = emptySet(),
    val argument: String? = null,
    val example: String = "",
)

internal val START_OPTIONS =
    setOf(
        "--port",
        "--state-dir",
        "--adb",
        "--scrcpy",
        "--scrcpy-server",
        "--driver-apk",
        "--driver-test-apk",
        "--driver-idle-timeout-ms",
    )
private val STATE = setOf("--state-dir")
private val INSPECT = STATE + "--json"
private val WATCH = INSPECT + setOf("--watch", "--interval")

internal val OPTION_HELP =
    linkedMapOf(
        "--state-dir" to OptionHelp("DIR", "State directory (default: TAP_STATE_DIR or ~/.tap). May appear before the command."),
        "--port" to OptionHelp("N", "Loopback port, 0..65535 (default: 0, choose a free port)."),
        "--adb" to OptionHelp("PATH", "ADB executable (default: TAP_ADB or adb on PATH)."),
        "--scrcpy" to OptionHelp("PATH", "Recording executable (default: TAP_SCRCPY or scrcpy on PATH)."),
        "--scrcpy-server" to OptionHelp("JAR", "scrcpy 4.1 server (default: TAP_SCRCPY_SERVER; otherwise no shared video)."),
        "--driver-apk" to OptionHelp("APK", "Override bundled driver; requires --driver-test-apk."),
        "--driver-test-apk" to OptionHelp("APK", "Override bundled driver tests; requires --driver-apk."),
        "--driver-idle-timeout-ms" to OptionHelp("N", "Experimental runtime reuse: 0 (default, off) or 1000..300000 ms."),
        "--json" to OptionHelp(null, "Print structured JSON; watch mode prints one JSON object per line."),
        "--watch" to OptionHelp(null, "Repeat read-only inspection until interrupted (does not renew sessions)."),
        "--interval" to OptionHelp("SECONDS", "Watch refresh interval, 0.1..3600 (default: 2; requires --watch)."),
        "--force" to OptionHelp(null, "Explicitly permit interrupting clients; normal cleanup and quarantine still apply."),
        "--reason" to OptionHelp("TEXT", "Administrative close reason, at most 497 characters without control characters."),
        "--fix" to OptionHelp(null, "Repair leftovers from ended sessions; live sessions are skipped."),
        "--serial" to OptionHelp("SERIAL", "Inspect or recover only this device."),
        "--tail" to OptionHelp("N", "Print the last N log lines, 0..10000 (default: 100)."),
        "--follow" to OptionHelp(null, "Follow appended log lines until interrupted; reopen after replacement/truncation."),
        "--out" to OptionHelp("PATH", "Create a new owner-only diagnostics ZIP; never overwrite."),
    )

internal val COMMAND_HELP =
    linkedMapOf(
        "start" to
            CommandHelp(
                "Start the daemon in the background; reuse it if already running.",
                START_OPTIONS,
                example = "tap start\ntap status\ntap devices",
            ),
        "serve" to CommandHelp("Run the daemon in the foreground.", START_OPTIONS, example = "tap serve --port 0"),
        "stop" to CommandHelp("Stop the daemon and clean up all sessions (interrupts active clients).", STATE, example = "tap stop"),
        "restart" to
            CommandHelp(
                "Restart with the running daemon's startup settings; refuse active sessions without --force.",
                START_OPTIONS + "--force",
                example = "tap restart\ntap restart --force --port 0",
            ),
        "status" to
            CommandHelp("Show authenticated daemon health, endpoint, versions and compatibility.", WATCH, example = "tap status --watch"),
        "devices" to
            CommandHelp(
                "List devices, availability and holding session IDs.",
                WATCH,
                example = "tap devices\ntap devices show SERIAL",
            ),
        "devices show" to
            CommandHelp(
                "Show one device's properties, owner and quarantine detail.",
                INSPECT,
                "SERIAL",
                "tap devices show emulator-5554 --json",
            ),
        "sessions" to
            CommandHelp(
                "List live client connections (sessions) and their devices.",
                WATCH,
                example = "tap sessions\ntap sessions show SESSION_ID",
            ),
        "sessions show" to
            CommandHelp(
                "Show a session's client name, activity, state and devices; does not renew it.",
                INSPECT,
                "SESSION_ID",
                "tap sessions show SESSION_ID --json",
            ),
        "sessions close" to
            CommandHelp(
                "Close a session, interrupt its client and clean up its devices. Requires --force.",
                INSPECT + setOf("--force", "--reason"),
                "SESSION_ID",
                "tap sessions close SESSION_ID --force --reason 'stuck test'",
            ),
        "doctor" to
            CommandHelp(
                "Check setup, daemon connectivity and device leftovers; repair only with --fix.",
                INSPECT + setOf("--adb", "--serial", "--fix"),
                example = "tap doctor\ntap doctor --serial SERIAL --fix",
            ),
        "unquarantine" to
            CommandHelp(
                "Retry cleanup and return a checked quarantined device to service.",
                STATE + setOf("--adb", "--serial"),
                example = "tap unquarantine --serial SERIAL",
            ),
        "logs" to
            CommandHelp(
                "Read daemon.log (foreground serve output is not captured here).",
                STATE + setOf("--tail", "--follow"),
                example = "tap logs --tail 200 --follow",
            ),
        "config show" to
            CommandHelp(
                "Show effective running configuration and local startup defaults with their sources; no credentials.",
                INSPECT + START_OPTIONS,
                example = "tap config show --json",
            ),
        "diagnostics" to
            CommandHelp(
                "Export allowlisted configuration, health, discovery and redacted log tail. Review before sharing.",
                STATE + "--out",
                example = "tap diagnostics --out tap-diagnostics.zip",
            ),
        "completion" to
            CommandHelp(
                "Print a shell completion script; does not install it.",
                argument = "SHELL",
                example = "tap completion bash > tap.bash\ntap completion zsh > _tap\ntap completion fish > tap.fish",
            ),
        "version" to CommandHelp("Show the installed CLI version (also --version).", example = "tap version"),
    )

/** Shared metadata drives validation, help and completion; no daemon or ADB work happens here. */
internal fun parseCommandLine(args: List<String>): CommandLine {
    if (args.isEmpty()) return CommandLine("", help = true)
    var wantsHelp = false
    val tokens = args
    val words = mutableListOf<String>()
    val options = linkedMapOf<String, String>()
    var i = 0
    var positionalOnly = false
    while (i < tokens.size) {
        val token = tokens[i++]
        if (token == "--" && !positionalOnly) {
            positionalOnly = true
        } else if ((token == "--help" || token == "-h") && !positionalOnly) {
            wantsHelp = true
        } else if (token == "--version" && words.isEmpty() && !positionalOnly) {
            words += "version"
        } else if (token.startsWith("-") && !positionalOnly) {
            val key = token.substringBefore('=')
            val spec = OPTION_HELP[key] ?: throw UsageException("unknown option '$key'", helpContext(words))
            val value =
                if (spec.value == null) {
                    if ('=' in token) throw UsageException("option $key takes no value", helpContext(words))
                    "true"
                } else if ('=' in token) {
                    token.substringAfter('=')
                } else {
                    tokens
                        .getOrNull(i)
                        ?.takeUnless { it.startsWith("--") || it == "-h" }
                        ?.also { i++ } ?: throw UsageException("option $key needs a value", helpContext(words))
                }
            if (spec.value != null && value.isEmpty()) throw UsageException("option $key needs a nonempty value", helpContext(words))
            if (options.put(key, value) != null) throw UsageException("option $key given twice", helpContext(words))
        } else {
            words += token
        }
    }
    if (words.firstOrNull() == "help") {
        words.removeAt(0)
        wantsHelp = true
    }
    val command = helpContext(words)
    if (words.isEmpty()) {
        if (options.keys.any { it != "--state-dir" }) throw UsageException("expected a command")
        return CommandLine("", options, help = true)
    }
    if (command.isEmpty()) {
        if (words.singleOrNull() == "config" && options.keys.all { it == "--state-dir" }) return CommandLine("config", help = true)
        throw UsageException("unknown command '${words.joinToString(" ")}'")
    }
    val spec = COMMAND_HELP.getValue(command)
    (options.keys - spec.options - "--state-dir").firstOrNull()?.let {
        throw UsageException("option $it is not valid for `tap $command`", command)
    }
    if (wantsHelp) return CommandLine(command, help = true)
    val arguments = words.drop(command.split(' ').size)
    if (arguments.size != if (spec.argument == null) 0 else 1) {
        throw UsageException(
            if (arguments.isEmpty()) "`tap $command` needs ${spec.argument}" else "unexpected argument '${arguments.last()}'",
            command,
        )
    }

    fun invalid(message: String): Nothing = throw UsageException(message, command)
    options["--port"]?.let {
        if (it.toIntOrNull()?.takeIf { n -> n in 0..65535 } ==
            null
        ) {
            invalid("--port must be an integer in 0..65535, got '$it'")
        }
    }
    options["--driver-idle-timeout-ms"]?.let {
        val n = it.toLongOrNull()
        if (n == null || (n != 0L && n !in 1000L..300000L)) invalid("--driver-idle-timeout-ms must be 0 (off) or 1000..300000")
    }
    options["--tail"]?.let { if (it.toIntOrNull()?.takeIf { n -> n in 0..10000 } == null) invalid("--tail must be an integer in 0..10000") }
    options["--interval"]?.let {
        if ("--watch" !in options) invalid("--interval requires --watch")
        if (it.toDoubleOrNull()?.takeIf { n -> n.isFinite() && n in 0.1..3600.0 } == null) invalid("--interval must be 0.1..3600 seconds")
    }
    if (("--driver-apk" in options) != ("--driver-test-apk" in options)) invalid("--driver-apk and --driver-test-apk go together")
    if (command == "unquarantine" && "--serial" !in options) invalid("`tap unquarantine` needs --serial SERIAL")
    if (command == "sessions close" && "--force" !in options) invalid("closing a session interrupts its client; pass --force to confirm")
    options["--reason"]?.let {
        if (it.isBlank() || it.length > 497 ||
            it.any(Char::isISOControl)
        ) {
            invalid("--reason must be nonblank, at most 497 characters and contain no control characters")
        }
    }
    if (command == "diagnostics" && options["--out"].isNullOrBlank()) invalid("`tap diagnostics` needs --out PATH")
    if (command == "completion" && arguments.single() !in setOf("bash", "zsh", "fish")) invalid("shell must be bash, zsh or fish")
    return CommandLine(command, options, arguments)
}

private fun helpContext(words: List<String>): String =
    COMMAND_HELP.keys
        .filter { words.take(it.split(' ').size).joinToString(" ") == it }
        .maxByOrNull { it.length }
        .orEmpty()

internal fun helpText(command: String = ""): String {
    if (command ==
        "config"
    ) {
        return "Usage: tap config show [options]\n\n${COMMAND_HELP.getValue(
            "config show",
        ).summary}\nRun tap config show --help for options."
    }
    if (command.isNotEmpty()) {
        val spec = COMMAND_HELP.getValue(command)
        return buildString {
            appendLine("Usage: tap $command${spec.argument?.let { " $it" }.orEmpty()} [options]")
            appendLine("\n${spec.summary}")
            val children = COMMAND_HELP.filterKeys { it.startsWith("$command ") }
            if (children.isNotEmpty()) {
                appendLine("\nSubcommands:")
                children.forEach { (key, value) -> appendLine("  ${key.removePrefix("$command ").padEnd(12)} ${value.summary}") }
            }
            appendLine("\nOptions:")
            OPTION_HELP.filterKeys { it in spec.options }.forEach { (key, value) ->
                appendLine("  ${(key + value.value?.let { " $it" }.orEmpty()).padEnd(32)} ${value.description}")
            }
            appendLine("  -h, --help                       Show this help and exit.")
            appendLine("\nExamples:\n${spec.example.prependIndent("  ")}")
            append("\nExit codes: 0 success, 1 operation/health failure, 2 usage error, 3 serve lock held.")
        }
    }
    return """
        Tap — local Android automation daemon administration

        Usage: tap [--state-dir DIR] COMMAND [options]
               tap help [COMMAND [SUBCOMMAND]]

        Commands:
        """.trimIndent() + "\n" +
        COMMAND_HELP.filterKeys { ' ' !in it }.entries.joinToString("\n") { (key, value) -> "  ${key.padEnd(14)} ${value.summary}" } +
        """

          config show    Explain effective configuration and its sources (no credentials).

        Start and explore:
          tap start
          tap status
          tap devices
          tap devices show SERIAL
          tap sessions
          tap doctor
          tap logs --tail 50

        Begin interacting (separate client; not needed for the commands above):
          Install tap-agent: uv tool install tap-agent
          tap-agent attach SERIAL
          tap-agent snapshot
          tap-agent release
          Kotlin/Python SDKs and Tap Studio are also available.

        tap administers the daemon; tap-agent is an independent interaction client.
        Inspection never starts a daemon or renews a session. Close interrupts clients:
          tap sessions close SESSION_ID --force --reason 'stuck test'

        Use tap COMMAND --help or tap help COMMAND for focused help.
        -h, --help: help; --version: installed version.
        State directory: --state-dir DIR, otherwise TAP_STATE_DIR or ~/.tap.
        Exit codes: 0 success/help, 1 operation/health failure, 2 usage error, 3 serve lock held.
        Guide: https://noamcohen48.github.io/tap/guide/server/
        """.trimIndent().let {
            "\n$it"
        }
}
