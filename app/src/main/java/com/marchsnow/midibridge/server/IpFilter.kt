package com.marchsnow.midibridge.server

import com.marchsnow.midibridge.util.Logger

/**
 * IP allowlist filter supporting three formats:
 *   1. Exact IP:        "192.168.1.100"
 *   2. IP range:        "192.168.1.1-192.168.2.254"
 *   3. CIDR subnet:     "172.16.0.0/16"
 *
 * IPv4-mapped IPv6 addresses (::ffff:x.x.x.x) are auto-converted to plain IPv4.
 * An empty allowlist means "allow all".
 *
 * Only LITERAL IPs are accepted. Hostnames are rejected with a warning:
 * the old implementation resolved entries via InetAddress.getByName(),
 * which performs a blocking DNS lookup on the connection-accept path
 * (AND-新N13). CIDR prefix lengths are validated to 0..32 (AND-SEC-08).
 *
 * Corresponds to Go ipfilter.go (net.ParseIP + net.CIDR semantics).
 */
object IpFilter {

    private const val TAG = "IpFilter"

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
        entry.contains("/") -> matchCidr(ip, entry)
        entry.contains("-") -> matchRange(ip, entry)
        else                -> matchExact(ip, entry)
    }

    private fun matchExact(ip: String, entry: String): Boolean {
        // Entries containing ':' are treated as IPv6 literals (exact string
        // match only — no range/CIDR support for IPv6, matching the Go port).
        if (entry.contains(":")) return ip == entry
        if (ipToLong(entry) == null) {
            Logger.w(TAG, "Ignoring allowlist entry that is not a literal IP address: '$entry'")
            return false
        }
        return ip == entry
    }

    private fun matchCidr(ip: String, cidr: String): Boolean {
        val parts = cidr.split("/")
        if (parts.size != 2) {
            Logger.w(TAG, "Ignoring malformed CIDR entry (expected 'a.b.c.d/len'): '$cidr'")
            return false
        }
        val prefixLen = parts[1].trim().toIntOrNull()
        if (prefixLen == null || prefixLen !in 0..32) {
            Logger.w(TAG, "Ignoring CIDR entry with out-of-range prefix length (must be 0..32): '$cidr'")
            return false
        }
        val network = ipToLong(parts[0].trim())
        if (network == null) {
            Logger.w(TAG, "Ignoring CIDR entry with non-literal network address: '$cidr'")
            return false
        }
        val target = ipToLong(ip) ?: return false
        val mask = if (prefixLen == 0) 0L else (-1L shl (32 - prefixLen)) and 0xFFFFFFFFL
        return (target and mask) == (network and mask)
    }

    private fun matchRange(ip: String, range: String): Boolean {
        val parts = range.split("-")
        if (parts.size != 2) {
            Logger.w(TAG, "Ignoring malformed range entry (expected 'a.b.c.d-a.b.c.d'): '$range'")
            return false
        }
        val start = ipToLong(parts[0].trim())
        if (start == null) {
            Logger.w(TAG, "Ignoring range entry with non-literal start address: '$range'")
            return false
        }
        val end = ipToLong(parts[1].trim())
        if (end == null) {
            Logger.w(TAG, "Ignoring range entry with non-literal end address: '$range'")
            return false
        }
        if (start > end) {
            Logger.w(TAG, "Ignoring reversed IP range (start > end): '$range'")
            return false
        }
        val target = ipToLong(ip) ?: return false
        return target in start..end
    }

    /**
     * Strict IPv4 literal parser: dotted quad of decimal octets 0-255.
     * Returns null for anything else — hostnames included.
     *
     * NEVER use InetAddress.getByName here: for hostnames it performs a
     * blocking DNS lookup, stalling the connection-accept path (AND-新N13).
     * Mirrors Go's net.ParseIP semantics.
     */
    private fun ipToLong(ip: String): Long? {
        val parts = ip.split(".")
        if (parts.size != 4) return null
        var result = 0L
        for (part in parts) {
            if (part.isEmpty() || part.length > 3) return null
            for (ch in part) if (ch !in '0'..'9') return null
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            result = (result shl 8) or value.toLong()
        }
        return result
    }
}
