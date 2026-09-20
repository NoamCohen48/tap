// Archived from host/service/src/main/kotlin/com/company/tap/service/Servicers.kt (commit 3afba99): the role-shaped PoolServicer.acquire and leased_role.

    override fun acquire(request: AcquireRequest, observer: StreamObserver<AcquireResponse>) = reply(observer) {
        val run = service.run(request.runId)
        val roles = request.rolesList.map { it.role to it.constraints }
        val assignment = service.acquire(run, roles, if (request.timeoutMs > 0) request.timeoutMs else 300_000)
        AcquireResponse.newBuilder().addAllAssignments(
            assignment.map { (role, facts) -> Assignment.newBuilder().setRole(role).setDevice(facts(facts)).build() },
        ).build()
    }

    // in poolDevice(): DeviceStatus.Leased carried the role
    //     leasedRole = status.role
