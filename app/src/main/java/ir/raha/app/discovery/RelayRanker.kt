package ir.raha.app.discovery

/**
 * Raha-owned local ranking. Provider ordering is only a hint; measurements made
 * on the user's current network win.
 */
object RelayRanker {
    fun rank(results: List<ProbedRelay>): List<ProbedRelay> =
        results.sortedWith(
            compareByDescending<ProbedRelay> { it.reachable }
                .thenBy { it.latencyMs ?: Long.MAX_VALUE }
                .thenBy { it.relay.source }
                .thenBy { it.relay.id }
        )
}
