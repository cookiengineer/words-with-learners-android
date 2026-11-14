package engineer.cookie.wordswithlearners

import org.junit.Assert.assertEquals
import org.junit.Test

class ServerConfigTest {
    @Test
    fun buildsServerArguments() {
        assertEquals(
            listOf("-addr", "0.0.0.0:12345", "-data", "/tmp/wwl"),
            ServerConfig.arguments(12345, "/tmp/wwl"),
        )
    }
}
