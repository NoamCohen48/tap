package io.github.noamcohen48.tap.daemon.grpc

import io.github.noamcohen48.tap.api.v1.CommandResult
import io.github.noamcohen48.tap.api.v1.ElementSnapshot
import io.github.noamcohen48.tap.api.v1.ElementSnapshotList
import io.github.noamcohen48.tap.host.Adb
import io.github.noamcohen48.tap.protocol.Commands
import io.github.noamcohen48.tap.protocol.Selectors
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BulkReadServiceTest {
    @Test fun `bulk values survive driver transport and Execute without losing order or text presence`() =
        runBlocking {
            AttachedHarness.with(Adb("must-not-be-invoked")) {
                val values =
                    ElementSnapshotList
                        .newBuilder()
                        .addElements(
                            ElementSnapshot
                                .newBuilder()
                                .setText("Email")
                                .setCheckable(true)
                                .setChecked(true)
                                .setEnabled(true),
                        ).addElements(ElementSnapshot.newBuilder().setText(""))
                        .addElements(ElementSnapshot.getDefaultInstance())
                        .build()
                val command = Commands.snapshots(Selectors.resource("option"))
                val read = execute(command, CommandResult.newBuilder().setSnapshots(values))
                assertEquals(CommandResult.OutcomeCase.SNAPSHOTS, read.outcomeCase)
                assertEquals(values, read.snapshots)
                assertFalse(read.snapshots.elementsList[2].hasText())

                val empty = execute(command, CommandResult.newBuilder().setSnapshots(ElementSnapshotList.getDefaultInstance()))
                assertEquals(CommandResult.OutcomeCase.SNAPSHOTS, empty.outcomeCase)
                assertEquals(0, empty.snapshots.elementsCount)
            }
        }
}
