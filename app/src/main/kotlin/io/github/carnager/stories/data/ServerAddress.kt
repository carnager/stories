package io.github.carnager.stories.data

import java.net.InetAddress
import java.net.URI

/**
 * Where a server may be, and how it may be spoken to: plain http only to
 * addresses inside a home network or VPN, https anywhere else, so a password
 * or token never crosses the internet in the clear.
 */
object ServerAddress {
    const val DEFAULT_PORT = 8743

    /** What was typed, as a base URL; null if it can't be one. Without a scheme, a bare name or address is taken for the LAN. */
    fun normalize(input: String): String? {
        val text = input.trim().trimEnd('/')
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        val withScheme = if ("://" in text) text else "http://$text"
        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host ?: return null
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        val port = when {
            uri.port > 0 -> uri.port
            scheme == "http" -> DEFAULT_PORT
            else -> -1
        }
        val path = uri.rawPath.orEmpty().trimEnd('/')
        return "$scheme://$host" + (if (port > 0) ":$port" else "") + path
    }

    fun isPrivate(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress) return true
        val b = address.address
        return when (b.size) {
            4 -> b[0].toInt() and 0xff == 100 && b[1].toInt() and 0xc0 == 64 // Tailscale's range, 100.64.0.0/10
            16 -> b[0].toInt() and 0xfe == 0xfc // Unique local addresses, fc00::/7
            else -> false
        }
    }

    /** May a token be sent here? Resolves the name, so call it off the main thread. */
    fun allowed(base: String): Boolean {
        val uri = runCatching { URI(base) }.getOrNull() ?: return false
        if (uri.scheme.equals("https", ignoreCase = true)) return true
        if (!uri.scheme.equals("http", ignoreCase = true)) return false
        val host = uri.host ?: return false
        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: return false
        return addresses.isNotEmpty() && addresses.all(::isPrivate)
    }
}
