package engineer.cookie.wordswithlearners

sealed interface ServerState {
    data object Idle : ServerState
    data object Starting : ServerState
    data class Running(val port: Int) : ServerState
    data class Failed(val message: String, val logs: String) : ServerState
    data object Stopping : ServerState
    data object Stopped : ServerState
}
