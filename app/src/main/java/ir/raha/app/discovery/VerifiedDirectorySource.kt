package ir.raha.app.discovery

/**
 * Security boundary between network/source-specific verification and Raha's parser.
 * Implementations MUST return the exact relay-directory body only after authenticating
 * its signature and freshness according to the upstream source contract.
 */
fun interface VerifiedDirectorySource {
    suspend fun fetchVerifiedRelayDirectory(): ByteArray
}
