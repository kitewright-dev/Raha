package ir.raha.app.discovery

import org.json.JSONObject
import java.time.Instant

/** Parses ONLY bytes already authenticated by the source adapter. */
object VerifiedRelayDirectory {
    private const val PROTOCOL = "vless-reality-vision"
    private const val FLOW = "xtls-rprx-vision"

    /**
     * Freshness of the directory itself is enforced by brokerapi (with its own
     * clock-skew allowance). Relay leases are judged against the broker's signed
     * `server_time`, not the phone clock: phones with a wrong clock are common,
     * and a ~3 minute lease compared against a skewed clock drops every relay.
     */
    fun parseOpenRung(verifiedJson: ByteArray): List<RelayCandidate> {
        val root = JSONObject(verifiedJson.toString(Charsets.UTF_8))
        require(root.optString("channel") == "api") { "unexpected relay directory channel" }

        val reference: Instant = root.optString("server_time").takeIf { it.isNotBlank() }
            ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            ?: Instant.now()

        val relays = root.optJSONArray("relays") ?: return emptyList()
        return buildList {
            for (i in 0 until relays.length()) {
                val r = relays.optJSONObject(i) ?: continue
                runCatching { parseRelay(r, reference) }.getOrNull()?.let(::add)
            }
        }
    }

    private fun parseRelay(r: JSONObject, reference: Instant): RelayCandidate? {
        if (r.getString("protocol") != PROTOCOL) return null
        if (r.getString("flow") != FLOW) return null
        if (r.getString("exit_mode") != "direct") return null

        val expires = Instant.parse(r.getString("expires_at"))
        if (!reference.isBefore(expires)) return null

        val host = r.getString("public_host").trim().trim('[', ']')
        val port = r.getInt("public_port")
        if (host.isBlank() || port !in 1..65535) return null

        val clientId = r.getString("client_id")
        val publicKey = r.getString("reality_public_key")
        val shortId = r.getString("short_id")
        val serverName = r.getString("server_name")
        if (clientId.isBlank() || publicKey.isBlank() || shortId.isBlank() || serverName.isBlank()) return null

        return RelayCandidate(
            source = "openrung",
            id = r.getString("id"),
            host = host,
            port = port,
            country = r.optString("country_code").ifBlank { r.optString("country").ifBlank { null } },
            transport = r.optString("transport", "direct").ifBlank { "direct" },
            clientId = clientId,
            realityPublicKey = publicKey,
            shortId = shortId,
            serverName = serverName,
            flow = FLOW,
            expiresAtEpochSeconds = expires.epochSecond,
            metadata = mapOf(
                "nodeClass" to r.optString("node_class", "volunteer"),
                "label" to r.optString("label"),
                "city" to r.optString("city")
            )
        )
    }
}
