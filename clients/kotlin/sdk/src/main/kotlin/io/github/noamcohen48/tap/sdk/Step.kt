package io.github.noamcohen48.tap.sdk

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ClientInterceptors
import io.grpc.ForwardingClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import java.util.UUID

private val CURRENT_STEP = ThreadLocal<String?>()
private val STEP_HEADER: Metadata.Key<String> = Metadata.Key.of("tap-step", Metadata.ASCII_STRING_MARSHALLER)
private val STEP_ID = Regex("[A-Za-z0-9._-]{1,64}")

/**
 * Runs [block] as one step and passes it the step's id: a caller's own unit of work (a line of
 * a test, a command of a tool), named so that what it did on the device can be found afterwards.
 *
 * Every call [block] makes to the server carries the id. The server copies it to the actions
 * the calls cause in the device log ([LogAction.step]), so a caller that keeps its own record of
 * steps can match the two:
 *
 * ```
 * val checkout = withStep { id -> cart.tap(); pay.tap(); id }
 * val log = device.logs.read(filter = LogFilter(step = checkout)) // its first action to its last
 * ```
 *
 * [id] is 1 to 64 of `A-Z a-z 0-9 . _ -`; without one a new id is made. A step inside a step
 * replaces it until the inner block ends.
 */
suspend fun <T> withStep(
    id: String? = null,
    block: suspend (id: String) -> T,
): T {
    val step = id ?: UUID.randomUUID().toString().replace("-", "").take(16)
    require(STEP_ID.matches(step)) { "a step id is 1 to 64 of A-Z a-z 0-9 . _ -" }
    return withContext(CURRENT_STEP.asContextElement(step)) { block(step) }
}

/** [channel] with `tap-step: <id>` on every call started inside [withStep]. */
internal fun withStepHeader(channel: Channel): Channel =
    ClientInterceptors.intercept(
        channel,
        object : ClientInterceptor {
            override fun <ReqT, RespT> interceptCall(
                method: MethodDescriptor<ReqT, RespT>,
                options: CallOptions,
                next: Channel,
            ): ClientCall<ReqT, RespT> {
                val call = next.newCall(method, options)
                val step = CURRENT_STEP.get() ?: return call
                return object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(call) {
                    override fun start(
                        responseListener: Listener<RespT>,
                        headers: Metadata,
                    ) {
                        headers.put(STEP_HEADER, step)
                        super.start(responseListener, headers)
                    }
                }
            }
        },
    )
