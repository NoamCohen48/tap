package com.company.tap.service

import com.company.tap.api.v1.Direction as ProtoDirection
import com.company.tap.api.v1.StabilitySignal as ProtoStabilitySignal
import com.company.tap.api.v1.ErrorCode as ProtoErrorCode
import com.company.tap.api.v1.Node as ProtoNode
import com.company.tap.api.v1.NodeFlag as ProtoNodeFlag
import com.company.tap.api.v1.Relation as ProtoRelation
import com.company.tap.api.v1.Selector as ProtoSelector
import com.company.tap.api.v1.TextProperty as ProtoTextProperty
import com.company.tap.api.v1.MatchMode as ProtoMatchMode
import com.company.tap.api.v1.Command as ProtoCommand
import com.company.tap.api.v1.CommandResult as ProtoCommandResult
import com.company.tap.protocol.Direction
import com.company.tap.protocol.StabilitySignal
import com.company.tap.protocol.ErrorCode
import com.company.tap.protocol.Node
import com.company.tap.protocol.NodeFlag
import com.company.tap.protocol.Pick
import com.company.tap.protocol.Relation
import com.company.tap.protocol.Scope
import com.company.tap.protocol.TextProperty
import com.company.tap.protocol.MatchMode
import com.company.tap.protocol.Command
import com.company.tap.protocol.CommandResult
import kotlin.test.Test
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlin.test.assertEquals

/** `contracts/api/proto` enums must be exactly the protocol enums, prefixed, plus `*_UNSPECIFIED`; oneof cases must be the protocol names. */
class EnumMirrorTest {
    @Test
    fun `proto enums mirror protocol enums`() {
        assertMirror("ERR_", ProtoErrorCode.entries.map { it.name }, ErrorCode.entries.map { it.name })
        assertMirror("DIR_", ProtoDirection.entries.map { it.name }, Direction.entries.map { it.name })
        assertMirror("MATCH_", ProtoMatchMode.entries.map { it.name }, MatchMode.entries.map { it.name })
        assertMirror("PROPERTY_", ProtoTextProperty.entries.map { it.name }, TextProperty.entries.map { it.name })
        assertMirror("FLAG_", ProtoNodeFlag.entries.map { it.name }, NodeFlag.entries.map { it.name })
        assertMirror("RELATION_", ProtoRelation.entries.map { it.name }, Relation.entries.map { it.name })
        assertMirror("STABILITY_", ProtoStabilitySignal.entries.map { it.name }, StabilitySignal.entries.map { it.name })
    }

    @Test
    fun `Command op cases are the protocol op names`() {
        val cases = ProtoCommand.getDescriptor().oneofs.single { it.name == "op" }.fields.map { it.name }
        assertEquals(Command.names, cases.sorted(), "Command.op cases drifted from the protocol's op names")
    }

    @Test
    fun `selector sum types are oneofs with the protocol kind names`() {
        assertOneof(ProtoNode.getDescriptor(), "kind", Node.serializer().descriptor)
        assertOneof(ProtoSelector.getDescriptor(), "scope", Scope.serializer().descriptor)
        assertOneof(ProtoSelector.getDescriptor(), "pick", Pick.serializer().descriptor)
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

    /** A sealed interface's subclass serial names must be exactly the proto `oneof`'s case names. */
    private fun assertOneof(message: com.google.protobuf.Descriptors.Descriptor, oneof: String, sealed: SerialDescriptor) {
        val kinds = sealed.getElementDescriptor(1)
        val expected = (0 until kinds.elementsCount).map(kinds::getElementName)
        val cases = message.oneofs.single { it.name == oneof }.fields.map { it.name }
        assertEquals(expected.sorted(), cases.sorted(), "${message.name}.$oneof cases drifted from the protocol's kinds")
    }

    private fun assertMirror(prefix: String, proto: List<String>, kotlin: List<String>) {
        val mirrored = proto.filter { it != "UNRECOGNIZED" && !it.endsWith("UNSPECIFIED") }
        assertEquals(kotlin.map { prefix + it }, mirrored, "$prefix enum drifted between contracts/api/proto/tap.proto and the protocol")
    }
}
