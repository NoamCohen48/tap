package com.company.tap.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

object CanonicalJson {
    val codec = Json { ignoreUnknownKeys = true }

    inline fun <reified T> encode(value: T): ByteArray {
        val element = codec.parseToJsonElement(codec.encodeToString(value))
        return canonicalize(element).toString().encodeToByteArray()
    }

    inline fun <reified T> decodeCanonical(payload: ByteArray): T {
        val text = payload.decodeToString()
        val element = codec.parseToJsonElement(text)
        val canonical = canonicalize(element).toString()
        if (text != canonical) throw SerializationException("Handshake JSON is not canonical")
        return codec.decodeFromString(canonical)
    }

    fun canonicalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries.sortedBy { it.key }.associate { it.key to canonicalize(it.value) }
        )
        is JsonArray -> JsonArray(element.map(::canonicalize))
        else -> element
    }
}
