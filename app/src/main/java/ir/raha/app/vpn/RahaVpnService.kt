package ir.raha.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import ir.raha.app.MainActivity
import ir.raha.app.R
import ir.raha.app.discovery.NativeDirectorySource
import ir.raha.app.discovery.OpenRungProvider
import ir.raha.app.discovery.RelayCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Owns the Android TUN lifecycle. The TUN is established only after a relay has
 * passed a real handshake, so a failed connect never leaves a black-hole VPN.
 */
class RahaVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var connectJob: Job? = null
    private var tun: ParcelFileDescriptor? = null
    private val manager = ConnectionManager(listOf(OpenRungProvider(NativeDirectorySource())))
    private var current: SelectedRelay? = null
    private val backups = ArrayDeque<SelectedRelay>()
    private var sessionStart = 0L
    private val networkKick = Channel<Unit>(Channel.CONFLATED)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                disconnect(ConnectionState.Idle)
                return START_NOT_STICKY
            }
            else -> connect()
        }
        return START_NOT_STICKY
    }

    private fun connect() {
        if (connectJob?.isActive == true) return
        enterForeground(getString(R.string.notif_connecting))
        connectJob = scope.launch {
            try {
                RahaState.set(ConnectionState.Discovering)
                val candidates = manager.discover()
                val tested = manager.select(candidates, preferredId = lastGoodRelayId()) {
                    RahaState.set(ConnectionState.Testing(it))
                }

                val pfd = establishTun() ?: throw IllegalStateException("VPN permission was revoked")
                tun = pfd
                val best = tested.first()
                manager.startEngine(best.relay, pfd.fd, MTU)
                ensureActive()

                sessionStart = SystemClock.elapsedRealtime()
                current = best
                backups.clear()
                backups.addAll(tested.drop(1))
                announceConnected(best)
                registerNetworkWatch()
                monitor(pfd.fd)
            } catch (e: CancellationException) {
                // A disconnect can land while the native engine is mid-start.
                manager.stopEngine()
                throw e
            } catch (t: Throwable) {
                disconnect(ConnectionState.Failed(t.message ?: t.javaClass.simpleName))
            }
        }
    }

    /**
     * Health loop: a page is fetched through the tunnel every 20 s, or right
     * away when the phone changes network. Two misses in a row trigger a relay
     * switch on the same TUN, so apps see a short stall instead of a dead VPN.
     */
    private suspend fun monitor(fd: Int) {
        var misses = 0
        while (true) {
            // After a miss, re-check quickly instead of waiting a full interval.
            val wait = if (misses == 0) HEALTH_INTERVAL_MS else RECHECK_MS
            withTimeoutOrNull(wait) { networkKick.receive() }
            if (manager.healthy()) {
                misses = 0
                continue
            }
            misses++
            if (misses >= 2) {
                misses = 0
                failover(fd)
            }
        }
    }

    private suspend fun failover(fd: Int) {
        RahaState.set(ConnectionState.Reconnecting("Relay stopped answering, switching…", sessionStart))
        updateNotification(getString(R.string.notif_switching))

        val tried = mutableSetOf<String>()
        // The current relay first: after a network change it is usually fine
        // and only needs fresh connections.
        val queue = ArrayDeque<RelayCandidate>()
        current?.let { queue.add(it.relay) }
        queue.addAll(backups.map { it.relay })
        backups.clear()

        while (queue.isNotEmpty()) {
            val relay = queue.removeFirst()
            if (!tried.add(relay.id)) continue
            val ok = manager.handshake(relay) ?: continue
            if (switchTo(ok, fd)) return
        }

        // Every known relay failed: fetch a fresh signed list and try again.
        val fresh = runCatching {
            manager.select(manager.discover(), exclude = tried)
        }.getOrNull()
        if (fresh != null) {
            for ((i, candidate) in fresh.withIndex()) {
                if (switchTo(candidate, fd)) {
                    backups.addAll(fresh.drop(i + 1))
                    return
                }
            }
        }
        disconnect(ConnectionState.Failed("Connection lost and no other relay is answering"))
        throw CancellationException("no relay left")
    }

    private suspend fun switchTo(relay: SelectedRelay, fd: Int): Boolean {
        val started = runCatching { manager.startEngine(relay.relay, fd, MTU) }.isSuccess
        if (!started) return false
        current = relay
        announceConnected(relay)
        return true
    }

    private fun announceConnected(selected: SelectedRelay) {
        rememberGoodRelay(selected.relay.id)
        RahaState.set(
            ConnectionState.Connected(
                relayName = selected.relay.displayName,
                countryCode = selected.relay.country,
                city = selected.relay.metadata["city"]?.takeIf { it.isNotBlank() },
                latencyMs = selected.latencyMs,
                sinceElapsed = sessionStart
            )
        )
        updateNotification(getString(R.string.notif_connected, selected.relay.displayName))
    }

    // Phone switched Wi-Fi <-> mobile: check the tunnel now rather than in 20 s.
    private fun registerNetworkWatch() {
        if (networkCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var first = true
            override fun onAvailable(network: Network) {
                // The first callback just reports the network we're already on.
                if (first) { first = false; return }
                networkKick.trySend(Unit)
            }
        }
        runCatching { cm.registerNetworkCallback(request, callback) }.onSuccess { networkCallback = callback }
    }

    private fun unregisterNetworkWatch() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback) }
    }

    private fun lastGoodRelayId(): String? =
        getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_LAST_RELAY, null)

    private fun rememberGoodRelay(id: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LAST_RELAY, id).apply()
    }

    private fun establishTun(): ParcelFileDescriptor? {
        val builder = Builder()
            .setSession("Raha")
            .setMtu(MTU)
            .addAddress("172.19.0.1", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            // Raha's own sockets (directory fetch, relay dial) must bypass the
            // tunnel, or the engine would route into itself.
            .addDisallowedApplication(packageName)
        // Capture IPv6 too, so it cannot leak around the tunnel.
        runCatching { builder.addAddress("fdfe:dcba:9876::1", 126).addRoute("::", 0) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)
        return builder.establish()
    }

    private fun disconnect(finalState: ConnectionState) {
        RahaState.set(ConnectionState.Stopping)
        unregisterNetworkWatch()
        connectJob?.cancel()
        connectJob = null
        current = null
        backups.clear()
        manager.stopEngine()
        runCatching { tun?.close() }
        tun = null
        RahaState.set(finalState)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        disconnect(ConnectionState.Idle)
        super.onRevoke()
    }

    override fun onDestroy() {
        if (tun != null || connectJob?.isActive == true) disconnect(ConnectionState.Idle)
        scope.cancel()
        super.onDestroy()
    }

    private fun enterForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RahaVpnService::class.java).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_raha)
            .setContentTitle("Raha")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, getString(R.string.disconnect), stop).build())
            .build()
    }

    companion object {
        const val ACTION_CONNECT = "ir.raha.app.CONNECT"
        const val ACTION_DISCONNECT = "ir.raha.app.DISCONNECT"
        private const val CHANNEL_ID = "raha_vpn"
        private const val NOTIF_ID = 7
        private const val MTU = 1500
        private const val HEALTH_INTERVAL_MS = 20_000L
        private const val RECHECK_MS = 3_000L
        private const val PREFS = "raha"
        private const val KEY_LAST_RELAY = "last_good_relay"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RahaVpnService::class.java).setAction(ACTION_CONNECT))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RahaVpnService::class.java).setAction(ACTION_DISCONNECT))
        }
    }
}
