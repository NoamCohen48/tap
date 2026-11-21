package io.github.noamcohen48.tap.daemon.cli

import io.github.noamcohen48.tap.api.v1.AttachedDeviceEntry
import io.github.noamcohen48.tap.api.v1.ClientConnectionServiceGrpc
import io.github.noamcohen48.tap.api.v1.ConnectionEntry
import io.github.noamcohen48.tap.api.v1.DeviceServiceGrpc
import io.github.noamcohen48.tap.api.v1.DriverLogRequest
import io.github.noamcohen48.tap.api.v1.DriverLogResponse
import io.github.noamcohen48.tap.api.v1.InfoRequest
import io.github.noamcohen48.tap.api.v1.InfoResponse
import io.github.noamcohen48.tap.api.v1.ListConnectionsRequest
import io.github.noamcohen48.tap.api.v1.ListConnectionsResponse
import io.github.noamcohen48.tap.api.v1.TAP_API_COMPATIBILITY
import io.github.noamcohen48.tap.daemon.grpc.TokenAuthInterceptor
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import io.grpc.stub.StreamObserver
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DriverLogAdministrationTest {
    @Test
    fun `driver diagnostics use original authenticated attachment without acquiring devices`() {
        val state = Files.createTempDirectory("tap-driver-cli")
        val token = DaemonDescriptor.newToken()
        val reads = mutableListOf<DriverLogRequest>()
        val entries =
            mutableListOf(
                ConnectionEntry
                    .newBuilder()
                    .setClientConnectionId("original-owner")
                    .addAttachedDevices(
                        AttachedDeviceEntry.newBuilder().setSerial("phone").setAttachedDeviceId("original-attachment"),
                    ).build(),
            )
        // Connect and Attach are deliberately unimplemented: either call would fail this test.
        val server =
            NettyServerBuilder
                .forAddress(InetSocketAddress("127.0.0.1", 0))
                .addService(
                    object : ClientConnectionServiceGrpc.ClientConnectionServiceImplBase() {
                        override fun info(
                            request: InfoRequest,
                            observer: StreamObserver<InfoResponse>,
                        ) {
                            observer.onNext(
                                InfoResponse
                                    .newBuilder()
                                    .setPid(ProcessHandle.current().pid())
                                    .setApiCompatibility(TAP_API_COMPATIBILITY)
                                    .build(),
                            )
                            observer.onCompleted()
                        }

                        override fun listConnections(
                            request: ListConnectionsRequest,
                            observer: StreamObserver<ListConnectionsResponse>,
                        ) {
                            observer.onNext(ListConnectionsResponse.newBuilder().addAllConnections(entries).build())
                            observer.onCompleted()
                        }
                    },
                ).addService(
                    object : DeviceServiceGrpc.DeviceServiceImplBase() {
                        override fun driverLog(
                            request: DriverLogRequest,
                            observer: StreamObserver<DriverLogResponse>,
                        ) {
                            reads += request
                            observer.onNext(DriverLogResponse.newBuilder().addLines("diagnostic $token\u001b[31m").build())
                            observer.onCompleted()
                        }
                    },
                ).intercept(TokenAuthInterceptor(token))
                .build()
                .start()
        val descriptor = DaemonDescriptor(server.port, ProcessHandle.current().pid(), token, "test", "fake-adb")
        descriptor.write(state.resolve(DaemonDescriptor.FILE_NAME))

        fun run(vararg words: String): Pair<Int, String> {
            val output = mutableListOf<String>()
            val error = mutableListOf<String>()
            val code = runCli(listOf("--state-dir", state.toString()) + words, output::add, error::add)
            return code to (output + error).joinToString("\n")
        }
        try {
            val text = run("logs", "driver", "--serial", "phone")
            assertEquals(0, text.first)
            assertTrue(text.second.contains("diagnostic"))
            assertFalse(text.second.contains(token))
            assertFalse(text.second.contains('\u001b'))
            assertEquals("original-owner", reads.single().clientConnectionId)
            assertEquals("original-attachment", reads.single().attachedDeviceId)
            val json = run("logs", "driver", "--serial", "phone", "--json")
            assertEquals(0, json.first)
            assertTrue(json.second.contains("current_attachment_driver_buffer"))
            assertFalse(json.second.contains(token))
            assertEquals(2, run("logs", "driver").first)
            assertEquals(1, run("logs", "driver", "--serial", "missing").first)
            assertEquals(2, reads.size)
            entries +=
                entries
                    .single()
                    .toBuilder()
                    .setClientConnectionId("other-owner")
                    .build()
            assertEquals(1, run("logs", "driver", "--serial", "phone").first)
            assertEquals(2, reads.size)
            descriptor.copy(token = DaemonDescriptor.newToken()).write(state.resolve(DaemonDescriptor.FILE_NAME))
            assertEquals(1, run("logs", "driver", "--serial", "phone").first)
        } finally {
            server.shutdownNow()
            server.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
