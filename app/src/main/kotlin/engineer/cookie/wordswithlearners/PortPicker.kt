package engineer.cookie.wordswithlearners

import java.net.InetSocketAddress
import java.net.ServerSocket

object PortPicker {
    fun pick(): Int = ServerSocket().use { socket ->
        socket.bind(InetSocketAddress("0.0.0.0", 0))
        socket.localPort
    }
}
