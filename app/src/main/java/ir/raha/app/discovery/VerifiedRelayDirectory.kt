package ir.raha.app.discovery

import org.json.JSONObject
import java.time.Instant

/** Parses ONLY bytes already authenticated by the source adapter. */
object VerifiedRelayDirectory {
    fun parseOpenRung(verifiedJson: ByteArray, now: Instant = Instant.now()): List<RelayCandidate> {
        val root = JSONObject(verifiedJson.toString(Charsets.UTF_8))
        require(root.optString("channel") == "api") { "unexpected relay directory channel" }

        val notAfter = Instant.parse(root.getString("not_after"))
        require(now.isBefore(notAfter)) { "relay directory expired" }

        val relays = root.getJSONArray("relays")
        return buildList {
            for (i in 0 until relays.length()) {
                val r = relays.getJSONObject(i)
                if (r.getString("protocol") != "vless-reality-vision") continue
                if (r.getString("flow") != "xtls-rprx-vision") continue
                if (r.getString("exit_mode") != "direct") continue

                val expires = Instant.parse(r.getString("expires_at"))
                if (!now.isBefore(expires)) continue

                val host = r.getString("public_host").trim().trim('[', ']')
                val port = r.getInt("public_port")
                if (host.isBlank() || port !in 1..65535) continue

                add(
                    RelayCandidate(
                        source = "openrung",
                        id = r.getString("id"),
                        host = host,
                        port = port,
                        country = r.optString("country_code").ifBlank { r.optString("country").ifBlank { null } },
                        transport = r.optString("transport", "direct"),
                        clientId = r.getString("client_id"),
                        realityPublicKey = r.getString("reality_public_key"),
                        shortId = r.getString("short_id"),
                        serverName = r.getString("server_name"),
                        expiresAtEpochSeconds = expires.epochSecond,
                        metadata = mapOf(
                            "nodeClass" to r.optString("node_class", "volunteer"),
                            "flow" to r.getString("flow"),
                            "protocol" to r.getString("protocol"),
                            "label" to r.optString("label")
                        )
                    )
                )
            }
        }
    }
}
