// Archived from clients/kotlin/sdk/src/main/kotlin/io/github/noamcohen48/tap/sdk/TapClient.kt (commit 3afba99).

/** Per-role pool constraints for [Run.acquire]. Null means unconstrained. */
data class DeviceConstraints(
    val serial: String? = null,
    val minApi: Int? = null,
    val maxApi: Int? = null,
    val emulator: Boolean? = null,
    val modelContains: String? = null,
) {
    internal fun toProto(): DeviceConstraintsProto {
        // Not `apply`: inside the builder scope the unqualified field names resolve to the
        // builder's getters, which would set every optional field to its default.
        val b = DeviceConstraintsProto.newBuilder()
        serial?.let(b::setSerial)
        minApi?.let(b::setMinApi)
        maxApi?.let(b::setMaxApi)
        emulator?.let(b::setEmulator)
        modelContains?.let(b::setModelContains)
        return b.build()
    }

    companion object {
        val ANY = DeviceConstraints()
        fun serial(serial: String) = DeviceConstraints(serial = serial)
    }
}

    /** All-or-none lease of one device per role, queued until [timeout]. */
    fun acquire(roles: Map<String, DeviceConstraints>, timeout: Duration = 300.seconds): Map<String, DeviceFacts> {
        val request = AcquireRequest.newBuilder().setRunId(id).setTimeoutMs(timeout.inWholeMilliseconds)
        roles.forEach { (role, constraints) ->
            request.addRoles(RoleRequest.newBuilder().setRole(role).setConstraints(constraints.toProto()))
        }
        return mapped {
            client.pool.withDeadlineAfter(timeout.inWholeSeconds + 30, TimeUnit.SECONDS).acquire(request.build())
                .assignmentsList.associate { it.role to it.device }
        }
    }
