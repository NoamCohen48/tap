package io.github.noamcohen48.tap.daemon

import io.github.noamcohen48.tap.protocol.Command
import io.github.noamcohen48.tap.protocol.CommandResult
import io.github.noamcohen48.tap.protocol.Direction
import io.github.noamcohen48.tap.protocol.ErrorCode
import io.github.noamcohen48.tap.protocol.MatchMode
import io.github.noamcohen48.tap.protocol.Node
import io.github.noamcohen48.tap.protocol.NodeFlag
import io.github.noamcohen48.tap.protocol.Pick
import io.github.noamcohen48.tap.protocol.Relation
import io.github.noamcohen48.tap.protocol.Scope
import io.github.noamcohen48.tap.protocol.StabilitySignal
import io.github.noamcohen48.tap.protocol.TextProperty
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import io.github.noamcohen48.tap.api.v1.Command as ProtoCommand
import io.github.noamcohen48.tap.api.v1.CommandResult as ProtoCommandResult
import io.github.noamcohen48.tap.api.v1.Direction as ProtoDirection
import io.github.noamcohen48.tap.api.v1.ErrorCode as ProtoErrorCode
import io.github.noamcohen48.tap.api.v1.MatchMode as ProtoMatchMode
import io.github.noamcohen48.tap.api.v1.Node as ProtoNode
import io.github.noamcohen48.tap.api.v1.NodeFlag as ProtoNodeFlag
import io.github.noamcohen48.tap.api.v1.Relation as ProtoRelation
import io.github.noamcohen48.tap.api.v1.Selector as ProtoSelector
import io.github.noamcohen48.tap.api.v1.StabilitySignal as ProtoStabilitySignal
import io.github.noamcohen48.tap.api.v1.TextProperty as ProtoTextProperty

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
    fun `Command op cases are the public protocol op names`() {
        val cases =
            ProtoCommand
                .getDescriptor()
                .oneofs
                .single { it.name == "op" }
                .fields
                .map { it.name }
        assertEquals(Command.names - HOST_INTERNAL_OPS, cases.sorted(), "Command.op cases drifted from the protocol's public op names")
    }

    @Test
    fun `selector sum types are oneofs with the protocol kind names`() {
        assertOneof(ProtoNode.getDescriptor(), "kind", Node.serializer().descriptor)
        assertOneof(ProtoSelector.getDescriptor(), "scope", Scope.serializer().descriptor)
        assertOneof(ProtoSelector.getDescriptor(), "pick", Pick.serializer().descriptor)
    }

    @Test
    fun `CommandResult outcome cases are the public protocol result kinds plus error`() {
        val kinds = CommandResult.serializer().descriptor.getElementDescriptor(1)
        val expected = (0 until kinds.elementsCount).map(kinds::getElementName) - HOST_INTERNAL_RESULTS + "error"
        val cases =
            ProtoCommandResult
                .getDescriptor()
                .oneofs
                .single { it.name == "outcome" }
                .fields
                .map { it.name }
        assertEquals(expected.sorted(), cases.sorted(), "CommandResult.outcome cases drifted from the protocol's result kinds")
    }

    @Test
    fun `every protocol value converts both ways`() {
        ErrorCode.entries.forEach { assertEquals(it, it.toProto().toErrorCode()) }
        Direction.entries.forEach { assertEquals(it, it.toProto().toDirection()) }
        StabilitySignal.entries.forEach { assertEquals(it, it.toProto().toStabilitySignal()) }
    }

    /** A sealed interface's subclass serial names must be exactly the proto `oneof`'s case names. */
    private fun assertOneof(
        message: com.google.protobuf.Descriptors.Descriptor,
        oneof: String,
        sealed: SerialDescriptor,
    ) {
        val kinds = sealed.getElementDescriptor(1)
        val expected = (0 until kinds.elementsCount).map(kinds::getElementName)
        val cases =
            message.oneofs
                .single { it.name == oneof }
                .fields
                .map { it.name }
        assertEquals(expected.sorted(), cases.sorted(), "${message.name}.$oneof cases drifted from the protocol's kinds")
    }

    private fun assertMirror(
        prefix: String,
        proto: List<String>,
        kotlin: List<String>,
    ) {
        val mirrored = proto.filter { it != "UNRECOGNIZED" && !it.endsWith("UNSPECIFIED") }
        assertEquals(kotlin.map { prefix + it }, mirrored, "$prefix enum drifted between contracts/api/proto and the protocol")
    }
}
