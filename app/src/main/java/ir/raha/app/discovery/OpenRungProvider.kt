package ir.raha.app.discovery

/**
 * OpenRung source adapter.
 *
 * Intentionally not implemented with a hand-written JSON fetch. OpenRung's
 * relay directory is signed and its official clients verify the raw response.
 * Raha will integrate a pinned verifier/adapter before enabling this source.
 */
class OpenRungProvider : DiscoveryProvider {
    override val id: String = "openrung"

    override suspend fun discover(): List<RelayCandidate> {
        throw IllegalStateException(
            "OpenRung source disabled until signed-directory verification is wired"
        )
    }
}
