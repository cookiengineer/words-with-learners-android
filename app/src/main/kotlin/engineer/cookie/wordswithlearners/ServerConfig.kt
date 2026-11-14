package engineer.cookie.wordswithlearners

object ServerConfig {
    fun arguments(port: Int, dataDir: String): List<String> = listOf(
        "-addr",
        "0.0.0.0:$port",
        "-data",
        dataDir,
    )
}
