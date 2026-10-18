package com.theglobulus.pacenote.data

import java.net.URI
import java.net.URLDecoder

/** What the pairing QR code (scripts/pair.sh) carries: where the Mac is, its token and its name. */
data class Pairing(val url: String, val token: String, val name: String? = null) {
    val host: String get() = URI(url).host

    companion object {
        private val TOKEN = Regex("^[0-9a-f]{64}$")

        /**
         * Parses `pacenote://pair?url=…&token=…`. Returns null for anything else,
         * including addresses outside a tailnet: a link on some web page must not
         * be able to point the app at someone else's server.
         */
        fun parse(link: String?): Pairing? {
            if (link == null) return null
            val uri = runCatching { URI(link.trim()) }.getOrNull() ?: return null
            if (uri.scheme != "pacenote" || uri.host != "pair") return null
            val params = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null else part.substring(0, i) to URLDecoder.decode(part.substring(i + 1), "UTF-8")
            }.toMap()
            val url = params["url"]?.trimEnd('/') ?: return null
            val token = params["token"] ?: return null
            if (!TOKEN.matches(token)) return null
            val target = runCatching { URI(url) }.getOrNull() ?: return null
            if (target.scheme != "http" || target.host == null || !isTailnetHost(target.host)) return null
            return Pairing(url, token, params["name"]?.takeIf { it.isNotBlank() }?.take(64))
        }

        /**
         * Whether the app may talk to this service address. Plain HTTP only inside
         * the tailnet, where Tailscale encrypts it. Android's network security config
         * can't name a whole IP range, so the app checks this itself.
         */
        fun allowedServiceUrl(url: String, allowLocal: Boolean = false): Boolean {
            val u = runCatching { URI(url) }.getOrNull() ?: return false
            val host = u.host ?: return false
            return when (u.scheme) {
                "https" -> true
                "http" -> isTailnetHost(host) || (allowLocal && (host == "127.0.0.1" || host == "localhost"))
                else -> false
            }
        }

        /** MagicDNS names and Tailscale's 100.64.0.0/10 addresses. */
        fun isTailnetHost(host: String): Boolean {
            if (host.endsWith(".ts.net")) return true
            val parts = host.split('.').map { it.toIntOrNull() ?: return false }
            return parts.size == 4 && parts.all { it in 0..255 } && parts[0] == 100 && parts[1] in 64..127
        }
    }
}
