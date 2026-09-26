package io.github.noamcohen48.tap.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

/**
 * The one JSON configuration for `REQUEST`/`RESPONSE` and blob metadata payloads, shared by the
 * host and the driver. Defaults are always encoded, so a field's value on the wire is the
 * sender's and never the receiver's own default (a host and a driver built with different
 * defaults cannot silently disagree); null optionals are still omitted. Handshake payloads use
 * [CanonicalJson] instead.
 */
@OptIn(ExperimentalSerializationApi::class)
object ProtocolJson {
    val codec: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
}
