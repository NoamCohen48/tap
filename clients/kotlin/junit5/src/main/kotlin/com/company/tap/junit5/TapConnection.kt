package com.company.tap.junit5

import com.company.tap.sdk.Connection
import com.company.tap.sdk.TapClient

/**
 * One client and one service [Connection] per test JVM. The connection is closed on JVM exit;
 * if the JVM dies without that, the service notices the dropped liveness stream and closes
 * its sessions.
 */
internal object TapConnection {
    val client: TapClient by lazy { TapClient() }

    val connection: Connection by lazy {
        client.connect("junit ${ProcessHandle.current().pid()} ${System.getProperty("user.dir")}").also { connection ->
            Runtime.getRuntime().addShutdownHook(Thread {
                runCatching { connection.close() }
                runCatching { client.close() }
            })
        }
    }
}
