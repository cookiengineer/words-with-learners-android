package engineer.cookie.wordswithlearners

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ServerService : Service() {
    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var manager: ServerProcessManager
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var foregroundStarted = false

    val state: StateFlow<ServerState>
        get() = manager.state

    inner class LocalBinder : Binder() {
        fun service(): ServerService = this@ServerService
    }

    override fun onCreate() {
        super.onCreate()
        manager = ServerProcessManager(this)
        createNotificationChannel()
        registerNetworkCallback()

        serviceScope.launch {
            manager.state.collect { current ->
                if (foregroundStarted) {
                    startForegroundCompat(buildNotification(current))
                }
                updateWifiLock(current)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServerAndSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat(buildNotification(manager.state.value))
        foregroundStarted = true
        manager.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopServerAndSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        networkCallback?.let { callback ->
            runCatching {
                getSystemService(ConnectivityManager::class.java)
                    .unregisterNetworkCallback(callback)
            }
        }
        networkCallback = null
        releaseWifiLock()
        manager.close()
        serviceScope.cancel()
        if (foregroundStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        super.onDestroy()
    }

    fun startServer() {
        if (!foregroundStarted) {
            startForegroundCompat(buildNotification(manager.state.value))
            foregroundStarted = true
        }
        manager.start()
    }

    private fun stopServerAndSelf() {
        manager.stopBlocking()
        if (foregroundStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
        stopSelf()
    }

    private fun startForegroundCompat(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.description = getString(R.string.notification_channel_description)
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(current: ServerState): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_wwl)
            .setContentTitle(getString(R.string.notification_title))
            .setContentIntent(contentPendingIntent())
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)

        when (current) {
            is ServerState.Running -> {
                val lan = NetworkAddresses.lanIPv4(this).firstOrNull()
                builder.setContentText(
                    if (lan != null) {
                        getString(R.string.notification_lan_url, lan, current.port)
                    } else {
                        getString(R.string.notification_local_url, current.port)
                    },
                )
                builder.addAction(
                    R.drawable.ic_stat_wwl,
                    getString(R.string.notification_action_stop),
                    stopPendingIntent(),
                )
            }

            is ServerState.Starting -> {
                builder.setContentText(getString(R.string.notification_starting))
                builder.addAction(
                    R.drawable.ic_stat_wwl,
                    getString(R.string.notification_action_stop),
                    stopPendingIntent(),
                )
            }

            is ServerState.Failed -> {
                builder.setContentText(getString(R.string.notification_failed))
                builder.addAction(
                    R.drawable.ic_stat_wwl,
                    getString(R.string.notification_action_stop),
                    stopPendingIntent(),
                )
            }

            is ServerState.Stopping -> {
                builder.setContentText(getString(R.string.server_stopping))
            }

            else -> {
                builder.setContentText(getString(R.string.notification_stopped))
            }
        }

        return builder.build()
    }

    private fun contentPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this,
            REQUEST_CONTENT,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun stopPendingIntent(): PendingIntent {
        val intent = Intent(this, ServerService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(
            this,
            REQUEST_STOP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun registerNetworkCallback() {
        val manager = getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                refreshNotification()
            }

            override fun onLost(network: Network) {
                refreshNotification()
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                refreshNotification()
            }
        }
        runCatching {
            manager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        }
    }

    private fun refreshNotification() {
        if (foregroundStarted) {
            startForegroundCompat(buildNotification(manager.state.value))
        }
    }

    private fun updateWifiLock(current: ServerState) {
        if (current is ServerState.Running) {
            acquireWifiLock()
        } else {
            releaseWifiLock()
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock == null) {
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = getSystemService(WifiManager::class.java)
                .createWifiLock(mode, WIFI_LOCK_TAG)
                .apply { setReferenceCounted(false) }
        }
        runCatching {
            if (wifiLock?.isHeld != true) {
                wifiLock?.acquire()
            }
        }
    }

    private fun releaseWifiLock() {
        runCatching {
            if (wifiLock?.isHeld == true) {
                wifiLock?.release()
            }
        }
        wifiLock = null
    }

    companion object {
        const val ACTION_STOP = "engineer.cookie.wordswithlearners.action.STOP"
        private const val CHANNEL_ID = "local_game_server"
        private const val NOTIFICATION_ID = 1001
        private const val WIFI_LOCK_TAG = "wwl:server"
        private const val REQUEST_CONTENT = 1
        private const val REQUEST_STOP = 3
    }
}
