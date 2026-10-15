package ir.raha.app.discovery

import org.json.JSONObject

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
    val flow: String,
    val expiresAtEpochSeconds: Long? = null,
    val metadata: Map<String, String> = emptyMap()
) {
    /** The only shape that crosses into the native engine. Never logged. */
    fun toEngineJson(): String = JSONObject()
        .put("host", host)
        .put("port", port)
        .put("uuid", clientId)
        .put("flow", flow)
        .put("publicKey", realityPublicKey)
        .put("shortId", shortId)
        .put("serverName", serverName)
        .toString()

    val displayName: String
        get() = metadata["label"]?.takeIf { it.isNotBlank() } ?: id.take(14)
}
