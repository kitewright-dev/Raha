package ir.raha.app.discovery

interface DiscoveryProvider {
    val id: String

    /**
     * Returns only candidates whose source authenticity has been verified by
     * the provider. Implementations must fail closed on invalid signatures,
     * expired envelopes, unsupported transports, or malformed credentials.
     */
    suspend fun discover(): List<RelayCandidate>
}
