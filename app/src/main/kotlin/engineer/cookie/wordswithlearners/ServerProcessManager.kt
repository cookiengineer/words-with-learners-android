package engineer.cookie.wordswithlearners

import android.content.Context
import android.util.Log
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ServerProcessManager(context: Context) {
    private val appContext = context.applicationContext
    private val binary = File(appContext.applicationInfo.nativeLibraryDir, "libwwlserver.so")
    private val dataDir = File(appContext.noBackupFilesDir, "wwl-data")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()
    private val processLock = Any()
    private val stateLock = Any()
    private val logLock = Any()
    private val logs = ArrayDeque<String>()

    @Volatile
    private var process: Process? = null

    @Volatile
    private var stopping = false

    @Volatile
    private var restartAttempts = 0

    @Volatile
    private var generation = 0L

    private var logJob: Job? = null
    private var monitorJob: Job? = null

    private val _state = MutableStateFlow<ServerState>(ServerState.Idle)
    val state: StateFlow<ServerState> = _state.asStateFlow()

    fun dataDirectory(): File = dataDir

    fun start() {
        scope.launch {
            startInternal()
        }
    }

    fun stopBlocking() {
        synchronized(stateLock) {
            stopping = true
            generation += 1
        }
        monitorJob?.cancel()
        monitorJob = null
        logJob?.cancel()
        logJob = null

        val started = synchronized(processLock) {
            val current = process
            process = null
            current
        }

        if (started != null) {
            _state.value = ServerState.Stopping
            started.destroy()
            try {
                if (!started.waitFor(2, TimeUnit.SECONDS)) {
                    started.destroyForcibly().waitFor(1, TimeUnit.SECONDS)
                }
            } catch (_: InterruptedException) {
                started.destroyForcibly()
                Thread.currentThread().interrupt()
            }
        }

        synchronized(stateLock) {
            _state.value = ServerState.Stopped
        }
    }

    fun logs(): String = synchronized(logLock) {
        logs.joinToString("\n")
    }

    fun close() {
        stopBlocking()
        scope.cancel()
    }

    private suspend fun startInternal() {
        startMutex.withLock {
            val attemptGeneration = synchronized(stateLock) {
                stopping = false
                generation += 1
                generation
            }
            if (process?.isAlive == true) {
                return
            }

            if (!binary.exists()) {
                _state.value = ServerState.Failed(
                    "Server binary not found in ${binary.parentFile?.absolutePath ?: "nativeLibraryDir"}.",
                    logs(),
                )
                return
            }

            if (!binary.canExecute()) {
                binary.setExecutable(true)
            }
            if (!binary.canExecute()) {
                _state.value = ServerState.Failed(
                    "Server binary is not executable: ${binary.absolutePath}",
                    logs(),
                )
                return
            }

            if (!dataDir.exists() && !dataDir.mkdirs()) {
                _state.value = ServerState.Failed(
                    "Could not create data directory: ${dataDir.absolutePath}",
                    logs(),
                )
                return
            }

            _state.value = ServerState.Starting
            var lastError = "The local server did not start."

            repeat(5) {
                if (stopping || generation != attemptGeneration) {
                    return
                }
                val port = try {
                    PortPicker.pick()
                } catch (error: Exception) {
                    lastError = "Could not reserve a local port: ${error.message}"
                    return@repeat
                }

                val started = tryStart(port)
                if (started != null) {
                    val accepted = synchronized(stateLock) {
                        if (stopping || generation != attemptGeneration) {
                            false
                        } else {
                            restartAttempts = 0
                            _state.value = ServerState.Running(port)
                            true
                        }
                    }
                    if (accepted) {
                        startMonitor(started, attemptGeneration)
                    } else {
                        stopProcess()
                    }
                    return
                }

                lastError = "Local server did not become ready on port $port."
                stopProcess()
                delay(250)
            }

            synchronized(stateLock) {
                if (!stopping && generation == attemptGeneration) {
                    _state.value = ServerState.Failed(lastError, logs())
                }
            }
        }
    }

    private suspend fun tryStart(port: Int): Process? {
        appendLog("starting server on 0.0.0.0:$port")

        val command = buildList {
            add(binary.absolutePath)
            addAll(ServerConfig.arguments(port, dataDir.absolutePath))
        }

        val builder = ProcessBuilder(command)
            .directory(dataDir)
            .redirectErrorStream(true)
        builder.environment()["HOME"] = appContext.filesDir.absolutePath
        builder.environment()["TMPDIR"] = appContext.cacheDir.absolutePath

        val started = try {
            builder.start()
        } catch (error: Exception) {
            appendLog("could not start server: ${error.message}")
            return null
        }

        synchronized(processLock) {
            process = started
        }

        if (stopping) {
            started.destroy()
            return null
        }

        logJob?.cancel()
        logJob = scope.launch {
            try {
                started.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line -> appendLog(line) }
                }
            } catch (_: Exception) {
            }
        }

        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (stopping) {
                return null
            }
            if (!started.isAlive) {
                appendLog("server exited during startup with code ${started.exitValue()}")
                return null
            }
            if (canConnect(port)) {
                appendLog("server is ready on port $port")
                return started
            }
            delay(150)
        }

        appendLog("timed out waiting for server on port $port")
        return null
    }

    private fun canConnect(port: Int): Boolean = try {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("127.0.0.1", port), 300)
        }
        true
    } catch (_: Exception) {
        false
    }

    private fun startMonitor(started: Process, attemptGeneration: Long) {
        monitorJob?.cancel()
        val self = scope.launch {
            val code = started.waitFor()
            synchronized(processLock) {
                if (process === started) {
                    process = null
                }
            }

            if (stopping || generation != attemptGeneration) {
                return@launch
            }

            appendLog("server exited unexpectedly with code $code")
            if (restartAttempts < 3) {
                restartAttempts += 1
                _state.value = ServerState.Starting
                delay(1_000L * restartAttempts)
                if (!stopping && generation == attemptGeneration) {
                    if (monitorJob === coroutineContext[Job]) {
                        monitorJob = null
                    }
                    startInternal()
                }
            } else {
                _state.value = ServerState.Failed(
                    "The local server exited unexpectedly.",
                    logs(),
                )
            }
        }
        monitorJob = self
    }

    private fun stopProcess() {
        logJob?.cancel()
        logJob = null

        val started = synchronized(processLock) {
            val current = process
            process = null
            current
        }

        if (started != null) {
            started.destroy()
            try {
                if (!started.waitFor(3, TimeUnit.SECONDS)) {
                    started.destroyForcibly()
                }
            } catch (_: InterruptedException) {
                started.destroyForcibly()
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun appendLog(line: String) {
        Log.d(TAG, line)
        synchronized(logLock) {
            logs.addLast(line)
            while (logs.size > 300) {
                logs.removeFirst()
            }
        }
    }

    companion object {
        private const val TAG = "wwl-server"
    }
}
