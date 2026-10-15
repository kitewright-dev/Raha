package ir.raha.app.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.system.measureTimeMillis

data class ProbedRelay(
    val relay: RelayCandidate,
    val reachable: Boolean,
    val latencyMs: Long?,
    val error: String? = null
)

/** Lightweight reachability probe used before handing a relay to the tunnel engine. */
class RelayProbe(
    private val timeoutMs: Int = 2500
) {
    suspend fun probe(relay: RelayCandidate): ProbedRelay = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            var elapsed = 0L
            socket = Socket()
            elapsed = measureTimeMillis {
                socket.connect(InetSocketAddress(relay.host, relay.port), timeoutMs)
            }
            ProbedRelay(relay, reachable = true, latencyMs = elapsed)
        } catch (t: Throwable) {
            ProbedRelay(relay, reachable = false, latencyMs = null, error = t.javaClass.simpleName)
        } finally {
            runCatching { socket?.close() }
        }
    }
}
