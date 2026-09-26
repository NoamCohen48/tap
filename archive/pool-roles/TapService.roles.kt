// Archived from host/service/src/main/kotlin/io/github/noamcohen48/tap/service/TapService.kt (commit 3afba99).
// Role-based, constraint-matched acquisition. Not compiled; kept for a future client-side or separate scheduler.

import io.github.noamcohen48.tap.api.v1.DeviceConstraints

    /**
     * All-or-none assignment of [roles] (role → constraints) for [run]. Waits until every role
     * can be satisfied simultaneously or the deadline passes.
     */
    fun acquire(run: Run, roles: List<Pair<String, DeviceConstraints>>, timeoutMs: Long): Map<String, Facts> {
        require(roles.isNotEmpty()) { "at least one role is required" }
        require(roles.map { it.first }.distinct().size == roles.size) { "roles must be unique: ${roles.map { it.first }}" }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.coerceAtLeast(0))
        var lastReason = ""
        while (true) {
            val candidates = inventory().filter { it.status == DeviceStatus.Free }.map { it.facts }
            poolLock.withLock {
                if (run.closed) throw UnknownRunException(run.id)
                val assignment = assign(roles, candidates.filter { it.serial !in leases })
                if (assignment != null) {
                    assignment.forEach { (role, device) ->
                        val lease = DeviceStatus.Leased(run.id, role)
                        leases[device.serial] = lease
                        run.leases[device.serial] = role
                    }
                    config.log("run ${run.id} acquired ${assignment.mapValues { it.value.serial }}")
                    return assignment
                }
                lastReason = "free=${candidates.map { it.serial }} leased=${leases.keys}"
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) {
                    val wanted = roles.joinToString { (role, c) -> "$role=${TextFormat.shortDebugString(c).ifEmpty { "any" }}" }
                    config.log("run ${run.id} acquire timed out after ${timeoutMs}ms: roles {$wanted} $lastReason")
                    throw AcquireTimeoutException("Timed out after ${timeoutMs}ms acquiring roles ${roles.map { it.first }} ($lastReason)")
                }
                poolChanged.await(minOf(remaining, TimeUnit.SECONDS.toNanos(2)), TimeUnit.NANOSECONDS)
            }
        }
    }

    private fun assign(roles: List<Pair<String, DeviceConstraints>>, free: List<Facts>): Map<String, Facts>? {
        // Pinned and most-constrained roles first so a generic role cannot steal the only match.
        val ordered = roles.sortedByDescending { (_, c) -> constraintWeight(c) }
        val remaining = free.toMutableList()
        val result = linkedMapOf<String, Facts>()
        for ((role, constraints) in ordered) {
            val pick = remaining.firstOrNull { satisfies(it, constraints) } ?: return null
            remaining.remove(pick)
            result[role] = pick
        }
        return roles.associate { (role, _) -> role to result.getValue(role) }
    }

    private fun constraintWeight(c: DeviceConstraints): Int =
        (if (c.hasSerial()) 100 else 0) + listOf(c.hasMinApi(), c.hasMaxApi(), c.hasEmulator(), c.hasModelContains()).count { it }

    private fun satisfies(facts: Facts, c: DeviceConstraints): Boolean =
        (!c.hasSerial() || c.serial == facts.serial) &&
            (!c.hasMinApi() || facts.apiLevel >= c.minApi) &&
            (!c.hasMaxApi() || facts.apiLevel <= c.maxApi) &&
            (!c.hasEmulator() || facts.emulator == c.emulator) &&
            (!c.hasModelContains() || facts.model.contains(c.modelContains, ignoreCase = true))

