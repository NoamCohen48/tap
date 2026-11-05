package io.github.noamcohen48.tap.junit5

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.platform.engine.EngineDiscoveryRequest
import org.junit.platform.engine.ExecutionRequest
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestEngine
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.descriptor.EngineDescriptor
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.core.LauncherConfig
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.ServiceLoader
import java.util.concurrent.Executors
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RunRecordsTest {
    @TempDir
    lateinit var root: Path

    @Test
    fun `attempts keep distinct files and metadata is readable before finalization`() {
        val run = RunRecords(root)
        val first = run.startTest("test[../../name]")
        val second = run.startTest("test[../../name]")
        assertNotEquals(first, second)
        for (key in listOf(first, second)) {
            run.artifact(key, "screenshot", byteArrayOf(1, 2), "png", "image/png", "../left", "host:5555", 7)
            run.finishTest(key, "failed")
        }
        assertEquals("running", read(run.directory.resolve("run.json"))["status"]!!.jsonPrimitive.content)
        val artifacts = read(run.directory.resolve("manifest.json"))["artifacts"]!!.jsonArray
        assertEquals(2, artifacts.map { it.jsonObject["path"] }.toSet().size)
        for (artifact in artifacts) {
            val entry = artifact.jsonObject
            val bytes = Files.readAllBytes(run.directory.resolve(entry.getValue("path").jsonPrimitive.content))
            assertEquals(2, bytes.size)
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(digest, entry.getValue("sha256").jsonPrimitive.content)
        }
        run.finish("failed")
        val events = Files.readAllLines(run.directory.resolve("events.jsonl")).map { Json.parseToJsonElement(it).jsonObject }
        assertEquals((1..events.size).map(Int::toString), events.map { it.getValue("seq").jsonPrimitive.content })
        assertEquals(
            "run_finished",
            events
                .last()
                .getValue("type")
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `parallel tests cannot interleave JSONL records or overwrite artifacts`() {
        val run = RunRecords(root)
        val pool = Executors.newFixedThreadPool(4)
        try {
            (0 until 20)
                .map { index ->
                    pool.submit {
                        val key = run.startTest("test_$index")
                        run.artifact(key, "logcat", "log".encodeToByteArray(), "txt", "text/plain")
                        run.finishTest(key, "passed")
                    }
                }.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
        run.error(run.startTest("incomplete"), "screenshot", IllegalStateException("private error text"))
        run.markIncomplete()
        run.finish("failed")
        assertEquals("incomplete", read(run.directory.resolve("run.json")).getValue("recording_status").jsonPrimitive.content)
        assertEquals(20, read(run.directory.resolve("manifest.json"))["artifacts"]!!.jsonArray.size)
        val text = Files.readString(run.directory.resolve("events.jsonl"))
        assertFalse("private error text" in text)
        text.lines().filter(String::isNotBlank).forEach { Json.parseToJsonElement(it) }
        assertNotEquals(run.runId, RunRecords(root).runId)
    }

    @Test
    fun `launcher records final outcomes including aborted tests and container failures`() {
        execute(report = true)
        val runFile = Files.list(root).use { it.findFirst().orElseThrow().resolve("run.json") }
        val run = read(runFile)
        assertEquals("failed", run.getValue("status").jsonPrimitive.content)
        val outcomes =
            run
                .getValue("tests")
                .jsonArray
                .map {
                    it.jsonObject
                        .getValue("status")
                        .jsonPrimitive.content
                }.sorted()
        assertEquals(listOf("failed", "passed", "skipped", "skipped"), outcomes)
        val failure = run.getValue("tests").jsonArray.map { it.jsonObject }.first { it.getValue("status").jsonPrimitive.content == "failed" }
        assertEquals("AssertionError", failure.getValue("error_type").jsonPrimitive.content)
        assertTrue(Files.readString(runFile.parent.resolve("events.jsonl")).contains("container_finished"))
        assertEquals(null, RunReports.current("not-a-test"))
    }

    @Test
    fun `disabled reporting writes no directory and IO errors do not escape the launcher`() {
        execute(report = false)
        assertEquals(0L, Files.list(root).use { it.count() })
        val occupied = root.resolve("occupied")
        Files.writeString(occupied, "not a directory")
        execute(report = true, output = occupied)
        assertTrue(Files.isRegularFile(occupied))
    }

    @Test
    fun `run listener is registered for ordinary JUnit launchers`() {
        assertTrue(ServiceLoader.load(TestExecutionListener::class.java).any { it is TapRunListener })
    }

    private fun execute(
        report: Boolean,
        output: Path = root,
    ) {
        val listener = TapRunListener(TapConfig(emptyList(), output, 1.seconds, emptyMap(), report = report))
        val launcher =
            LauncherFactory.create(
                LauncherConfig
                    .builder()
                    .enableTestEngineAutoRegistration(false)
                    .enableTestExecutionListenerAutoRegistration(false)
                    .addTestEngines(SyntheticEngine())
                    .build(),
            )
        launcher.registerTestExecutionListeners(listener)
        launcher.execute(LauncherDiscoveryRequestBuilder.request().build())
    }

    private fun read(path: Path) = Json.parseToJsonElement(Files.readString(path)).jsonObject

    @TapTest
    private class Marked

    /** Exercises the real launcher lifecycle without starting the Tap extension or a device. */
    private class SyntheticEngine : TestEngine {
        override fun getId(): String = "synthetic-tap"

        override fun discover(
            request: EngineDiscoveryRequest,
            uniqueId: UniqueId,
        ): TestDescriptor =
            EngineDescriptor(uniqueId, "Synthetic Tap").apply {
                listOf("pass", "fail", "abort", "skip").forEach { name ->
                    addChild(
                        object : AbstractTestDescriptor(uniqueId.append("test", name), name, ClassSource.from(Marked::class.java)) {
                            override fun getType(): TestDescriptor.Type = TestDescriptor.Type.TEST
                        },
                    )
                }
            }

        override fun execute(request: ExecutionRequest) {
            val listener = request.engineExecutionListener
            val root = request.rootTestDescriptor
            listener.executionStarted(root)
            root.children.forEach { test ->
                if (test.displayName == "skip") {
                    listener.executionSkipped(test, "private skip reason")
                } else {
                    listener.executionStarted(test)
                    val outcome =
                        when (test.displayName) {
                            "pass" -> TestExecutionResult.successful()
                            "abort" -> TestExecutionResult.aborted(IllegalStateException("private abort"))
                            else -> TestExecutionResult.failed(AssertionError("private failure"))
                        }
                    listener.executionFinished(test, outcome)
                }
            }
            listener.executionFinished(root, TestExecutionResult.failed(IllegalStateException("container failed")))
        }
    }
}
