package ir.raha.app.discovery

data class RelayCandidate(
    val source: String,
    val id: String,
    val host: String,
    val port: Int,
    val country: String?,
    val transport: String,
    val clientId: String,
    val realityPublicKey: String,
    val shortId: String,
    val serverName: String,
    val expiresAtEpochSeconds: Long? = null,
    val metadata: Map<String, String> = emptyMap()
)
