package ir.raha.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import ir.raha.app.MainActivity
import ir.raha.app.R
import ir.raha.app.discovery.NativeDirectorySource
import ir.raha.app.discovery.OpenRungProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
                val selected = manager.select(candidates) { RahaState.set(ConnectionState.Testing(it)) }

                val pfd = establishTun() ?: throw IllegalStateException("VPN permission was revoked")
                tun = pfd
                manager.startEngine(selected.relay, pfd.fd, MTU)
                ensureActive()

                RahaState.set(
                    ConnectionState.Connected(
                        relayName = selected.relay.displayName,
                        countryCode = selected.relay.country,
                        city = selected.relay.metadata["city"]?.takeIf { it.isNotBlank() },
                        latencyMs = selected.latencyMs,
                        sinceElapsed = SystemClock.elapsedRealtime()
                    )
                )
                updateNotification(getString(R.string.notif_connected, selected.relay.displayName))
            } catch (e: CancellationException) {
                // A disconnect can land while the native engine is mid-start.
                manager.stopEngine()
                throw e
            } catch (t: Throwable) {
                disconnect(ConnectionState.Failed(t.message ?: t.javaClass.simpleName))
            }
        }
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
        connectJob?.cancel()
        connectJob = null
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

        fun start(context: Context) {
            context.startForegroundService(Intent(context, RahaVpnService::class.java).setAction(ACTION_CONNECT))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RahaVpnService::class.java).setAction(ACTION_DISCONNECT))
        }
    }
}
