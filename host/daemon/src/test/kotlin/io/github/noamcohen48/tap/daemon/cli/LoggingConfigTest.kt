package io.github.noamcohen48.tap.daemon.cli

import io.github.noamcohen48.tap.daemon.core.LoggingPolicy
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoggingConfigTest {
    @TempDir lateinit var root: Path

    @Test fun `config works with a stopped daemon and writes private atomic settings`() {
        assertEquals(
            0,
            runCli(
                listOf(
                    "--state-dir",
                    root.toString(),
                    "config",
                    "logging-policy",
                    "rolling",
                    "--max-age",
                    "10m",
                    "--max-size",
                    "2MiB",
                    "--hard-size",
                    "3MiB",
                    "--min-free",
                    "1024B",
                ),
            ),
        )
        assertEquals(LoggingPolicy(600_000, 2_097_152, 3_145_728, 1024), LoggingPolicy.read(root))
        assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(root.resolve(LoggingPolicy.FILE)))
        assertEquals(0, runCli(listOf("config", "logging-policy", "show", "--state-dir", root.toString())))
    }

    @Test fun `bad limits are usage failures and do not partially change previous policy`() {
        val old = LoggingPolicy()
        LoggingPolicy.write(root, old)
        for (limits in listOf(
            listOf("--max-age", "0h"),
            listOf("--hard-size", "1MiB"),
            listOf("--max-age", "-1h"),
            listOf("--max-size", "999999999999999999999999GiB"),
        )) {
            val errors = mutableListOf<String>()
            assertEquals(
                2,
                runCli(listOf("config", "logging-policy", "rolling", "--state-dir", root.toString()) + limits, err = errors::add),
            )
            assertTrue(errors.single().contains("Usage: tap config logging-policy rolling"))
            assertEquals(old, LoggingPolicy.read(root))
        }
    }

    @Test fun `help is discoverable and leaves state untouched`() {
        val missing = root.resolve("not-created")
        for (path in listOf(emptyList(), listOf("show"), listOf("rolling"))) {
            val output = mutableListOf<String>()
            assertEquals(
                0,
                runCli(listOf("--state-dir", missing.toString(), "config", "logging-policy") + path + "--help", out = output::add),
            )
            assertTrue(output.single().contains("logging-policy"))
            assertFalse(Files.exists(missing))
        }
        assertTrue(helpText("config").contains("logging-policy"))
        assertTrue(helpText().contains("config logging-policy"))
    }

    @Test fun `completion handles third level policy commands`() {
        for (shell in listOf("bash", "zsh", "fish")) {
            val script = completionScript(shell)
            assertTrue(script.contains("logging-policy"))
            assertTrue(script.contains("--max-age") || script.contains("-l max-age"))
        }
        assertTrue(completionScript("bash").contains("'config') choices='show logging-policy --state-dir --help'"))
        assertTrue(completionScript("bash").contains("'config logging-policy') choices='show rolling"))
        assertTrue(completionScript("fish").contains("__fish_seen_subcommand_from config; and __fish_seen_subcommand_from logging-policy"))
    }

    @Test fun `units are explicit and overflowing limits are rejected`() {
        assertEquals(3_600_000, ageLimit("1h"))
        assertEquals(1_073_741_824, byteLimit("1GiB"))
        assertEquals(0, byteLimit("0"))
        assertFailsWith<UsageException> { byteLimit("1GB") }
        assertFailsWith<UsageException> { ageLimit("-1h") }
        assertFailsWith<IllegalArgumentException> { byteLimit("999999999999999999999999GiB") }
    }
}
