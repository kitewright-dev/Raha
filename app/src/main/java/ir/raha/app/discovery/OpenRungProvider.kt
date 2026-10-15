package ir.raha.app.discovery

/**
 * OpenRung source adapter.
 *
 * The network implementation is deliberately injected: it must use the pinned
 * OpenRung brokerapi binding (or an equivalent independently audited verifier)
 * and return only exact, signature-verified directory bytes.
 */
class OpenRungProvider(
    private val directorySource: VerifiedDirectorySource
) : DiscoveryProvider {
    override val id: String = "openrung"

    override suspend fun discover(): List<RelayCandidate> {
        val verified = directorySource.fetchVerifiedRelayDirectory()
        return VerifiedRelayDirectory.parseOpenRung(verified)
    }
}
