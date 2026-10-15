package ir.raha.app.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

data class ProbedRelay(
    val relay: RelayCandidate,
    val reachable: Boolean,
    val latencyMs: Long?,
    val error: String? = null
)

/** Cheap TCP pre-filter. It only drops dead endpoints; the real test is the native handshake probe. */
class RelayProbe(
    private val timeoutMs: Int = 2500
) {
    suspend fun probe(relay: RelayCandidate): ProbedRelay = withContext(Dispatchers.IO) {
        val socket = Socket()
        try {
            val started = System.nanoTime()
            socket.connect(InetSocketAddress(relay.host, relay.port), timeoutMs)
            val elapsed = (System.nanoTime() - started) / 1_000_000
            ProbedRelay(relay, reachable = true, latencyMs = elapsed)
        } catch (t: Throwable) {
            ProbedRelay(relay, reachable = false, latencyMs = null, error = t.javaClass.simpleName)
        } finally {
            runCatching { socket.close() }
        }
    }
}
