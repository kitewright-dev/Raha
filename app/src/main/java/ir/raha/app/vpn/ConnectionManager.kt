package ir.raha.app.vpn

import ir.raha.app.discovery.DiscoveryProvider
import ir.raha.app.discovery.RelayCandidate
import ir.raha.app.discovery.RelayProbe
import ir.raha.app.discovery.RelayRanker
import ir.raha.nativebridge.rahacore.Rahacore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

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
    private val handshakeTimeoutMs: Long = 9000
) {
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

    suspend fun select(candidates: List<RelayCandidate>, onTesting: (Int) -> Unit): SelectedRelay = coroutineScope {
        val tcp = candidates.map { async { tcpProbe.probe(it) } }.awaitAll()
        val shortlist = RelayRanker.rank(tcp).filter { it.reachable }.take(handshakeCandidates)
        if (shortlist.isEmpty()) throw NoWorkingRelayException("No relay answered on this network")
        onTesting(shortlist.size)

        val tested = shortlist.map { probed ->
            async(Dispatchers.IO) {
                runCatching {
                    SelectedRelay(probed.relay, Rahacore.probeRelay(probed.relay.toEngineJson(), handshakeTimeoutMs))
                }.getOrNull()
            }
        }.awaitAll().filterNotNull()

        tested.minByOrNull { it.latencyMs }
            ?: throw NoWorkingRelayException("Relays found, but none completed a secure handshake")
    }

    suspend fun startEngine(relay: RelayCandidate, tunFd: Int, mtu: Int) = withContext(Dispatchers.IO) {
        Rahacore.startTunnel(relay.toEngineJson(), tunFd.toLong(), mtu.toLong())
    }

    fun stopEngine() {
        runCatching { Rahacore.stopTunnel() }
    }
}
