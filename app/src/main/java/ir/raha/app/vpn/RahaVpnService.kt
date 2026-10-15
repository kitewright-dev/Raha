package ir.raha.app.vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor

/**
 * Owns the Android TUN lifecycle only.
 * The transport/engine is deliberately kept behind TunnelEngine so discovery,
 * ranking and protocol code never own the VPN file descriptor directly.
 */
class RahaVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // v0.1 bootstrap: do not establish a TUN until a verified relay and
        // packet engine are ready. This prevents a black-hole VPN session.
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    private fun stopTunnel() {
        runCatching { tun?.close() }
        tun = null
    }
}
