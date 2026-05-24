package com.marchsnow.midibridge.server

import java.net.InetAddress

/**
 * IP allowlist filter supporting three formats:
 *   1. Exact IP:        "192.168.1.100"
 *   2. IP range:        "192.168.1.1-192.168.2.254"
 *   3. CIDR subnet:     "172.16.0.0/16"
 *
 * IPv4-mapped IPv6 addresses (::ffff:x.x.x.x) are auto-converted to plain IPv4.
 * An empty allowlist means "allow all".
 *
 * Corresponds to Go ipfilter.go.
 */
object IpFilter {

    /**
     * Check whether [ip] is permitted by the comma-separated [allowlist].
     * Returns true if allowed (or allowlist is blank), false otherwise.
     */
    fun isAllowed(ip: String, allowlist: String): Boolean {
        if (allowlist.isBlank()) return true
        val normalized = normalizeIp(ip)
        return allowlist.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .any { entry -> matchEntry(normalized, entry) }
    }

    /** Strip leading "/" and convert IPv4-mapped IPv6 to plain IPv4. */
    private fun normalizeIp(ip: String): String {
        val stripped = ip.removePrefix("/")
        return if (stripped.startsWith("::ffff:")) {
            stripped.removePrefix("::ffff:")
        } else stripped
    }

    private fun matchEntry(ip: String, entry: String): Boolean = when {
        entry.contains("/")  -> matchCidr(ip, entry)
        entry.contains("-")  -> matchRange(ip, entry)
        else                 -> ip == entry
    }

    private fun matchCidr(ip: String, cidr: String): Boolean = runCatching {
        val (networkStr, prefixLenStr) = cidr.split("/")
        val prefixLen  = prefixLenStr.toInt()
        val network    = ipToLong(networkStr)
        val target     = ipToLong(ip)
        val mask       = if (prefixLen == 0) 0L else (-1L shl (32 - prefixLen)) and 0xFFFFFFFFL
        (target and mask) == (network and mask)
    }.getOrDefault(false)

    private fun matchRange(ip: String, range: String): Boolean = runCatching {
        val parts  = range.split("-")
        val start  = ipToLong(parts[0].trim())
        val end    = ipToLong(parts[1].trim())
        val target = ipToLong(ip)
        target in start..end
    }.getOrDefault(false)

    /** Convert an IPv4 string to a 32-bit unsigned integer for range/CIDR comparison. */
    private fun ipToLong(ip: String): Long {
        val bytes = InetAddress.getByName(ip).address
        var result = 0L
        for (b in bytes) result = (result shl 8) or (b.toLong() and 0xFF)
        return result
    }
}
