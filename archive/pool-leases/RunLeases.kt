// Archived from clients/kotlin/sdk/.../TapClient.kt and clients/kotlin/junit5/.../TapExtension.kt (commit e7d6004): serial lease calls.

    /**
     * Leases every one of [serials] for this run, all or none, waiting up to [timeout] for them
     * to be free at the same time. Returns their facts in request order. The pool only knows
     * serials; naming devices (roles) is up to the caller — see [freeSerials] to pick some.
     */
    fun acquire(serials: Collection<String>, timeout: Duration = 300.seconds): List<DeviceFacts> {
        val request = AcquireRequest.newBuilder().setRunId(id).setTimeoutMs(timeout.inWholeMilliseconds).addAllSerials(serials)
        return mapped {
            client.pool.withDeadlineAfter(timeout.inWholeSeconds + 30, TimeUnit.SECONDS).acquire(request.build()).devicesList
        }
    }

    /** Releases [serials] (empty = everything this run holds). Sessions on them are closed first. */
    fun release(serials: Collection<String> = emptyList()): Int = mapped {
        client.pool.withDeadlineAfter(60, TimeUnit.SECONDS)
            .release(ReleaseRequest.newBuilder().setRunId(id).addAllSerials(serials).build()).released
    }

    // TapExtension.beforeEach: TapRun.run.acquire(assignment.values, config.acquireTimeout) before openAll, release on failure and in afterEach.
