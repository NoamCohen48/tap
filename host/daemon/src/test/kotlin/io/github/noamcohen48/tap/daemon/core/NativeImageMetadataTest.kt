package io.github.noamcohen48.tap.daemon.core

import com.google.protobuf.GeneratedMessageLite
import io.github.noamcohen48.tap.api.v1.Failure
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * protobuf-lite reads message fields reflectively, so the native image must register every
 * generated message (reflect-config.json). A message added to contracts/proto without an entry
 * works on the JVM and fails only in the native binary; this catches it on the JVM.
 */
class NativeImageMetadataTest {
    private val registered: Set<String> =
        Json
            .parseToJsonElement(
                File("src/main/resources/META-INF/native-image/io.github.noamcohen48.tap/daemon/reflect-config.json").readText(),
            ).jsonArray
            .map { it.jsonObject.getValue("name").jsonPrimitive.content }
            .toSet()

    @Test
    fun `every generated schema message is registered for reflection`() {
        val schema = File(Failure::class.java.protectionDomain.codeSource.location.toURI())
        val classNames =
            if (schema.isDirectory) {
                schema.walkTopDown().filter { it.extension == "class" }.map { it.relativeTo(schema).path }.toList()
            } else {
                ZipFile(schema).use { zip -> zip.entries().toList().map { it.name }.filter { it.endsWith(".class") } }
            }
        val messages =
            classNames
                .map { it.removeSuffix(".class").replace('/', '.').replace(File.separatorChar, '.') }
                .filter { it.startsWith("io.github.noamcohen48.tap.") }
                .map { Class.forName(it, false, javaClass.classLoader) }
                .filter { GeneratedMessageLite::class.java.isAssignableFrom(it) && it != GeneratedMessageLite::class.java }
                .map { it.name }
                .toSet()
        assertEquals(emptySet(), messages - registered, "add these to reflect-config.json with allDeclaredFields")
    }
}
