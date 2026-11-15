package io.github.noamcohen48.tap.host

import io.github.noamcohen48.tap.protocol.BlobFrames
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InstalledApkBatchTest {
    private class BatchAdb(
        private val output: String,
    ) : Adb() {
        val commands = mutableListOf<Pair<String, List<String>>>()
        val pulled = mutableListOf<String>()

        override suspend fun execResult(
            serial: String,
            vararg arguments: String,
            timeoutMs: Long,
        ): Result {
            commands += serial to arguments.toList()
            return Result(0, output)
        }

        override suspend fun pull(
            serial: String,
            devicePath: String,
            local: Path,
            timeoutMs: Long,
        ) {
            pulled += devicePath
            Files.write(local, devicePath.encodeToByteArray())
        }
    }

    private val digest = "a".repeat(64)

    private fun block(
        index: Int,
        path: String,
        hash: String = digest,
        exit: Int = 0,
        pm: Int = 0,
    ) = "tap-apk-start:$index\n${if (path.isEmpty()) "" else "package:$path"}\ntap-pm-status:$pm\n$hash\ntap-apk-end:$index:$exit\n"

    @Test
    fun `both packages and split digests use one named-serial shell command`() =
        runTest {
            val output =
                block(0, "/base.apk", "$digest  /base.apk") +
                    "tap-apk-start:1\npackage:/test.apk\npackage:/split.apk\ntap-pm-status:0\n$digest  /test.apk\n$digest  /split.apk\ntap-apk-end:1:0\n"
            val adb = BatchAdb(output)
            assertEquals(
                mapOf("driver" to listOf(digest), "test" to listOf(digest, digest)),
                adb.installedApksSha256("phone", listOf("driver", "test")),
            )
            assertEquals(1, adb.commands.size)
            assertEquals("phone", adb.commands.single().first)
            val command = adb.commands.single().second
            assertEquals("shell", command.first())
            assertTrue("pm path driver" in command[1] && "pm path test" in command[1])
            assertTrue("echo 'tap-apk-start:0'" in command[1])
            assertTrue("echo \"tap-apk-end:0:" in command[1])
            assertTrue("echo \"tap-pm-status:" in command[1])
            assertTrue("printf '%s\\n'" in command[1]) // Keep arbitrary path data lossless, unlike echo.
            assertTrue("sha256sum" in command[1]) // The per-attach byte checks are not cached or omitted.
            assertTrue(adb.pulled.isEmpty())
        }

    @Test
    fun `a missing package stays missing and a hash failure falls back independently`() =
        runTest {
            val adb = BatchAdb(block(0, "", "", pm = 1) + block(1, "/test.apk", "sha256sum: not found", exit = 127))
            assertEquals(
                mapOf("driver" to emptyList(), "test" to listOf(BlobFrames.sha256Hex("/test.apk".encodeToByteArray()))),
                adb.installedApksSha256("phone", listOf("driver", "test")),
            )
            assertEquals(listOf("/test.apk"), adb.pulled)
        }

    @Test
    fun `truncated output or missing status cannot look like a verified install`() =
        runTest {
            for (output in listOf(
                block(0, "/base.apk", "$digest  /base.apk"),
                "tap-apk-start:0\npackage:/base.apk\ntap-apk-end:0:0\n" + block(1, "/test.apk", "$digest  /test.apk"),
            )) {
                assertFailsWith<AdbCommandException> { BatchAdb(output).installedApksSha256("phone", listOf("driver", "test")) }
            }
        }
}
