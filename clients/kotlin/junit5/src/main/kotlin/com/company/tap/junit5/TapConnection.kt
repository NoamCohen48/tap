package com.company.tap.junit5

import com.company.tap.sdk.Connection
import com.company.tap.sdk.TapClient
import com.company.tap.sdk.TapServiceProcess
import org.junit.platform.launcher.LauncherSession
import org.junit.platform.launcher.LauncherSessionListener
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One client and one service [Connection] per test JVM. With `tap.manageService=true` the
 * service is started (`tap start`) before the client connects and, if that start created it,
 * stopped (`tap stop`) when the JUnit launcher session ends — a service that was already
 * running is left alone. Everything is closed on JVM exit at the latest; if the JVM dies
 * without that, the service notices the dropped liveness stream and closes its sessions.
 */
internal object TapConnection {
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    val client: TapClient by lazy {
        if (TapConfig.current.manageService) started.set(TapServiceProcess.start().started)
        TapClient().also { Runtime.getRuntime().addShutdownHook(Thread(::shutdown)) }
    }

    val connection: Connection by lazy {
        client.connect("junit ${ProcessHandle.current().pid()} ${System.getProperty("user.dir")}")
    }

    /** Closes the connection and stops the service this JVM started. Idempotent. */
    fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { connection.close() }
        runCatching { client.close() }
        if (started.get()) runCatching { TapServiceProcess.stop() }
    }
}

/** Registered through `META-INF/services`; closes [TapConnection] once all tests have run. */
class TapLauncherSessionListener : LauncherSessionListener {
    override fun launcherSessionClosed(session: LauncherSession) {
        TapConnection.shutdown()
    }
}
