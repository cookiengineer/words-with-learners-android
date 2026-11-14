package engineer.cookie.wordswithlearners

import java.net.ServerSocket
import org.junit.Assert.assertTrue
import org.junit.Test

class PortPickerTest {
    @Test
    fun picksAPortThatCanBeBound() {
        val port = PortPicker.pick()
        assertTrue(port in 1..65535)
        ServerSocket(port).use {
            assertTrue(it.isBound)
        }
    }
}
