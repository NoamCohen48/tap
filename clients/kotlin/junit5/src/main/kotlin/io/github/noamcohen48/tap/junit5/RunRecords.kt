package io.github.noamcohen48.tap.junit5

import io.github.noamcohen48.tap.api.v1.TAP_API_COMPATIBILITY
import io.github.noamcohen48.tap.api.v1.TAP_API_VERSION
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.UUID

/** Client-owned tap-run/1 records, shared by parallel tests of one launcher execution. */
internal class RunRecords(
    root: Path,
) {
    val runId = UUID.randomUUID().toString()
    val directory: Path = root.resolve(runId)
    private val origin = System.nanoTime()
    private var seq = 0L
    private val attempts = mutableMapOf<String, Int>()
    private val tests = linkedMapOf<String, MutableMap<String, Any?>>()
    private val artifacts = mutableListOf<Map<String, Any?>>()
    private val errors = mutableListOf<Map<String, Any?>>()
    private val metadata =
        linkedMapOf<String, Any?>(
            "schema" to "tap-run/1",
            "run_id" to runId,
            "runner" to "junit5",
            "engine_version" to TAP_API_VERSION,
            "api_compatibility" to TAP_API_COMPATIBILITY,
            "started_at_epoch_ms" to System.currentTimeMillis(),
            "finished_at_epoch_ms" to null,
            "status" to "running",
            "recording_status" to "recording",
            "pid" to ProcessHandle.current().pid(),
        )

    init {
        Files.createDirectories(root)
        Files.createDirectory(directory)
        event("run_started")
        publish()
    }

    @Synchronized
    fun event(
        type: String,
        fields: Map<String, Any?> = emptyMap(),
    ) {
        val record =
            linkedMapOf<String, Any?>(
                "schema" to "tap-events/1",
                "run_id" to runId,
                "seq" to ++seq,
                "at_epoch_ms" to System.currentTimeMillis(),
                "elapsed_ns" to System.nanoTime() - origin,
                "type" to type,
            ) + fields
        FileChannel.open(directory.resolve("events.jsonl"), CREATE, WRITE, APPEND).use { channel ->
            val bytes = ByteBuffer.wrap((json(record).toString() + "\n").encodeToByteArray())
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
    }

    @Synchronized
    fun startTest(identity: String): String {
        val id = sha256(identity.encodeToByteArray())
        val attempt = attempts.getOrDefault(id, 0) + 1
        attempts[id] = attempt
        val key = "$id/$attempt"
        val test =
            linkedMapOf<String, Any?>(
                "test_id" to id,
                "identity" to identity,
                "attempt" to attempt,
                "status" to "running",
                "started_at_epoch_ms" to System.currentTimeMillis(),
                "finished_at_epoch_ms" to null,
            )
        tests[key] = test
        event("test_started", test)
        publish()
        return key
    }

    @Synchronized
    fun finishTest(
        key: String,
        status: String,
        errorType: String? = null,
    ) {
        val test = tests.getValue(key)
        test["status"] = status
        test["finished_at_epoch_ms"] = System.currentTimeMillis()
        test["error_type"] = errorType
        event("test_finished", test)
        publish()
    }

    @Synchronized
    fun artifact(
        key: String,
        part: String,
        bytes: ByteArray,
        extension: String,
        mediaType: String,
        role: String? = null,
        serial: String? = null,
        generation: Long? = null,
    ): Path {
        var dir = directory.resolve("tests").resolve(key)
        if (role != null) {
            val deviceId = sha256(json(listOf(role, serial)).toString().encodeToByteArray())
            dir = dir.resolve(deviceId).resolve(generation.toString())
        }
        Files.createDirectories(dir)
        val path = dir.resolve("$part.$extension")
        Files.write(path, bytes)
        val entry =
            linkedMapOf<String, Any?>(
                "test" to key,
                "part" to part,
                "path" to directory.relativize(path).joinToString("/"),
                "size_bytes" to bytes.size,
                "sha256" to sha256(bytes),
                "media_type" to mediaType,
                "role" to role,
                "serial" to serial,
                "generation" to generation,
            )
        artifacts.add(entry)
        event("artifact_saved", entry)
        publish()
        return path
    }

    @Synchronized
    fun error(
        key: String,
        part: String,
        error: Throwable,
        identity: Map<String, Any?> = emptyMap(),
    ) {
        val entry = mapOf("test" to key, "part" to part, "error_type" to error.javaClass.simpleName) + identity
        errors.add(entry)
        event("collection_error", entry)
        publish()
    }

    @Synchronized
    fun markIncomplete() {
        metadata["recording_status"] = "incomplete"
    }

    @Synchronized
    fun finish(status: String) {
        tests.values.filter { it["status"] == "running" }.forEach { it["status"] = "incomplete" }
        metadata["status"] = status
        if (metadata["recording_status"] != "incomplete") metadata["recording_status"] = "complete"
        metadata["finished_at_epoch_ms"] = System.currentTimeMillis()
        event("run_finished", mapOf("status" to status))
        publish()
    }

    private fun publish() {
        atomic("manifest.json", mapOf("schema" to "tap-artifacts/1", "run_id" to runId, "artifacts" to artifacts, "errors" to errors))
        atomic("run.json", metadata + mapOf("tests" to tests.values.toList()))
    }

    private fun atomic(
        name: String,
        value: Any?,
    ) {
        val temporary = directory.resolve(".$name.tmp")
        Files.writeString(temporary, json(value).toString() + "\n")
        FileChannel.open(temporary, WRITE).use { it.force(true) }
        Files.move(temporary, directory.resolve(name), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    private fun json(value: Any?): JsonElement =
        when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to json(it.value) })
            is Iterable<*> -> JsonArray(value.map(::json))
            else -> error("unsupported JSON value: ${value.javaClass.name}")
        }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
