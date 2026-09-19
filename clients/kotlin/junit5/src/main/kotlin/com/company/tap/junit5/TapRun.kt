package com.company.tap.junit5

import com.company.tap.sdk.Run
import com.company.tap.sdk.TapClient

/**
 * One service connection and one run per test JVM. The run is closed on JVM exit; if the JVM
 * dies without that, the service notices the dropped liveness stream and releases everything.
 */
internal object TapRun {
    val client: TapClient by lazy { TapClient() }

    val run: Run by lazy {
        client.openRun("junit ${ProcessHandle.current().pid()} ${System.getProperty("user.dir")}").also { run ->
            Runtime.getRuntime().addShutdownHook(Thread {
                runCatching { run.close() }
                runCatching { client.close() }
            })
        }
    }
}
