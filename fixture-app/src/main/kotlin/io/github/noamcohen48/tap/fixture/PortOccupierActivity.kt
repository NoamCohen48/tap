package io.github.noamcohen48.tap.fixture

import android.app.Activity
import android.os.Bundle
import java.net.InetAddress
import java.net.ServerSocket

class PortOccupierActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val port = intent.getIntExtra("port", -1)
        require(port in 1..65535)
        occupiedSocket?.close()
        occupiedSocket = ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))
    }

    private companion object {
        var occupiedSocket: ServerSocket? = null
    }
}
