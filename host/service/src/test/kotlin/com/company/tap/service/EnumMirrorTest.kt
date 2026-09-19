package com.company.tap.service

import com.company.tap.api.v1.Direction as ProtoDirection
import com.company.tap.api.v1.ErrorCode as ProtoErrorCode
import com.company.tap.api.v1.MatchLimit as ProtoMatchLimit
import com.company.tap.api.v1.MatchMode as ProtoMatchMode
import com.company.tap.api.v1.Operation as ProtoOperation
import com.company.tap.api.v1.TargetScope as ProtoTargetScope
import com.company.tap.protocol.Direction
import com.company.tap.protocol.ErrorCode
import com.company.tap.protocol.MatchLimit
import com.company.tap.protocol.MatchMode
import com.company.tap.protocol.Operation
import com.company.tap.protocol.TargetScope
import kotlin.test.Test
import kotlin.test.assertEquals

/** `api/tap.proto` enums must be exactly the protocol enums, prefixed, plus `*_UNSPECIFIED`. */
class EnumMirrorTest {
    @Test
    fun `proto enums mirror protocol enums`() {
        assertMirror("OP_", ProtoOperation.entries.map { it.name }, Operation.entries.map { it.name })
        assertMirror("ERR_", ProtoErrorCode.entries.map { it.name }, ErrorCode.entries.map { it.name })
        assertMirror("DIR_", ProtoDirection.entries.map { it.name }, Direction.entries.map { it.name })
        assertMirror("MATCH_", ProtoMatchMode.entries.map { it.name }, MatchMode.entries.map { it.name })
        assertMirror("LIMIT_", ProtoMatchLimit.entries.map { it.name }, MatchLimit.entries.map { it.name })
        assertMirror("SCOPE_", ProtoTargetScope.entries.map { it.name }, TargetScope.entries.map { it.name })
    }

    @Test
    fun `every protocol value converts both ways`() {
        Operation.entries.forEach { assertEquals(it, Conversions.operation(Conversions.operation(it))) }
        ErrorCode.entries.forEach { assertEquals(it, Conversions.errorCode(Conversions.errorCode(it))) }
        Direction.entries.forEach { assertEquals(it, Conversions.direction(Conversions.direction(it))) }
    }

    private fun assertMirror(prefix: String, proto: List<String>, kotlin: List<String>) {
        val mirrored = proto.filter { it != "UNRECOGNIZED" && !it.endsWith("UNSPECIFIED") }
        assertEquals(kotlin.map { prefix + it }, mirrored, "$prefix enum drifted between api/tap.proto and the protocol")
    }
}
