package ir.raha.app.vpn

import android.os.SystemClock
import ir.raha.app.discovery.DiscoveryProvider
import ir.raha.app.discovery.RelayCandidate
import ir.raha.app.discovery.RelayProbe
import ir.raha.app.discovery.RelayRanker
import ir.raha.nativebridge.rahacore.Rahacore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

data class SelectedRelay(val relay: RelayCandidate, val latencyMs: Long)

class NoWorkingRelayException(message: String) : Exception(message)

/**
 * Raha's connection ladder:
 * discover (verified) -> TCP pre-filter -> real REALITY handshake test -> best wins.
 */
class ConnectionManager(
    private val providers: List<DiscoveryProvider>,
    private val tcpProbe: RelayProbe = RelayProbe(),
    private val handshakeCandidates: Int = 6,
    private val handshakeTimeoutMs: Long = 9000,
    /** After the first relay passes, wait this long for faster ones, then decide. */
    private val graceMs: Long = 1500
) {
    // Native handshake calls can't be interrupted, so they run outside the
    // caller's scope: selection can return early without waiting on stragglers.
    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun discover(): List<RelayCandidate> {
        val errors = mutableListOf<String>()
        val all = providers.flatMap { provider ->
            runCatching { provider.discover() }
                .onFailure { errors += "${provider.id}: ${it.message ?: it.javaClass.simpleName}" }
                .getOrDefault(emptyList())
        }.distinctBy { it.source to it.id }
        if (all.isEmpty()) {
            throw NoWorkingRelayException(
                errors.firstOrNull()?.let { "Relay directory unreachable ($it)" } ?: "Directory returned no usable relays"
            )
        }
        return all
    }

    /**
     * Returns every relay that passed a real handshake, fastest first. The
     * previously working relay, when still listed and reachable, is always in
     * the handshake shortlist.
     */
    suspend fun select(
        candidates: List<RelayCandidate>,
        preferredId: String? = null,
        exclude: Set<String> = emptySet(),
        onTesting: (Int) -> Unit = {}
    ): List<SelectedRelay> {
        val pool = candidates.filter { it.id !in exclude }
        val tcp = coroutineScope { pool.map { async { tcpProbe.probe(it) } }.awaitAll() }
        val reachable = RelayRanker.rank(tcp).filter { it.reachable }
        val shortlist = (reachable.filter { it.relay.id == preferredId } +
            reachable.filter { it.relay.id != preferredId }).take(handshakeCandidates)
        if (shortlist.isEmpty()) throw NoWorkingRelayException("No relay answered on this network")
        onTesting(shortlist.size)

        val results = Channel<SelectedRelay?>(shortlist.size)
        for (probed in shortlist) {
            probeScope.launch {
                results.send(handshake(probed.relay))
            }
        }

        val passed = mutableListOf<SelectedRelay>()
        var received = 0
        var deadline = Long.MAX_VALUE
        while (received < shortlist.size) {
            val wait = deadline - SystemClock.elapsedRealtime()
            if (wait <= 0) break
            val next = withTimeoutOrNull(wait) { results.receive() } ?: break
            received++
            next?.let {
                passed += it
                if (deadline == Long.MAX_VALUE) deadline = SystemClock.elapsedRealtime() + graceMs
            }
        }
        if (passed.isEmpty()) throw NoWorkingRelayException("Relays found, but none completed a secure handshake")
        return passed.sortedBy { it.latencyMs }
    }

    suspend fun handshake(relay: RelayCandidate): SelectedRelay? = withContext(Dispatchers.IO) {
        runCatching { SelectedRelay(relay, Rahacore.probeRelay(relay.toEngineJson(), handshakeTimeoutMs)) }.getOrNull()
    }

    /** Starts the engine, or switches it to [relay] when it is already running on [tunFd]. */
    suspend fun startEngine(relay: RelayCandidate, tunFd: Int, mtu: Int) = withContext(Dispatchers.IO) {
        Rahacore.startTunnel(relay.toEngineJson(), tunFd.toLong(), mtu.toLong())
    }

    /** True when a page loads through the running tunnel. */
    suspend fun healthy(timeoutMs: Long = 8000): Boolean = withContext(Dispatchers.IO) {
        runCatching { Rahacore.healthCheck(timeoutMs) }.isSuccess
    }

    fun stopEngine() {
        runCatching { Rahacore.stopTunnel() }
    }
}
