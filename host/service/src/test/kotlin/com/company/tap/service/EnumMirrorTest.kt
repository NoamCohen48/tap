package com.company.tap.service

import com.company.tap.api.v1.Direction as ProtoDirection
import com.company.tap.api.v1.StabilitySignal as ProtoStabilitySignal
import com.company.tap.api.v1.ErrorCode as ProtoErrorCode
import com.company.tap.api.v1.MatchLimit as ProtoMatchLimit
import com.company.tap.api.v1.MatchMode as ProtoMatchMode
import com.company.tap.api.v1.Command as ProtoCommand
import com.company.tap.api.v1.CommandResult as ProtoCommandResult
import com.company.tap.api.v1.TargetScope as ProtoTargetScope
import com.company.tap.protocol.Direction
import com.company.tap.protocol.StabilitySignal
import com.company.tap.protocol.ErrorCode
import com.company.tap.protocol.MatchLimit
import com.company.tap.protocol.MatchMode
import com.company.tap.protocol.Command
import com.company.tap.protocol.CommandResult
import com.company.tap.protocol.TargetScope
import kotlin.test.Test
import kotlin.test.assertEquals

/** `contracts/api/proto` enums must be exactly the protocol enums, prefixed, plus `*_UNSPECIFIED`; oneof cases must be the protocol names. */
class EnumMirrorTest {
    @Test
    fun `proto enums mirror protocol enums`() {
        assertMirror("ERR_", ProtoErrorCode.entries.map { it.name }, ErrorCode.entries.map { it.name })
        assertMirror("DIR_", ProtoDirection.entries.map { it.name }, Direction.entries.map { it.name })
        assertMirror("MATCH_", ProtoMatchMode.entries.map { it.name }, MatchMode.entries.map { it.name })
        assertMirror("LIMIT_", ProtoMatchLimit.entries.map { it.name }, MatchLimit.entries.map { it.name })
        assertMirror("SCOPE_", ProtoTargetScope.entries.map { it.name }, TargetScope.entries.map { it.name })
        assertMirror("STABILITY_", ProtoStabilitySignal.entries.map { it.name }, StabilitySignal.entries.map { it.name })
    }

    @Test
    fun `Command op cases are the protocol op names`() {
        val cases = ProtoCommand.getDescriptor().oneofs.single { it.name == "op" }.fields.map { it.name }
        assertEquals(Command.names, cases.sorted(), "Command.op cases drifted from the protocol's op names")
    }

    @Test
    fun `CommandResult outcome cases are the protocol result kinds plus error`() {
        val kinds = CommandResult.serializer().descriptor.getElementDescriptor(1)
        val expected = (0 until kinds.elementsCount).map(kinds::getElementName) + "error"
        val cases = ProtoCommandResult.getDescriptor().oneofs.single { it.name == "outcome" }.fields.map { it.name }
        assertEquals(expected.sorted(), cases.sorted(), "CommandResult.outcome cases drifted from the protocol's result kinds")
    }

    @Test
    fun `every protocol value converts both ways`() {
        ErrorCode.entries.forEach { assertEquals(it, Conversions.errorCode(Conversions.errorCode(it))) }
        Direction.entries.forEach { assertEquals(it, Conversions.direction(Conversions.direction(it))) }
        StabilitySignal.entries.forEach { assertEquals(it, Conversions.stabilitySignal(Conversions.stabilitySignal(it))) }
    }

    private fun assertMirror(prefix: String, proto: List<String>, kotlin: List<String>) {
        val mirrored = proto.filter { it != "UNRECOGNIZED" && !it.endsWith("UNSPECIFIED") }
        assertEquals(kotlin.map { prefix + it }, mirrored, "$prefix enum drifted between contracts/api/proto/tap.proto and the protocol")
    }
}
