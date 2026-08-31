package com.marchsnow.midibridge.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the IP allowlist filter (AND-SEC-08 / AND-新N13).
 *
 * Logger calls android.util.Log, which is mocked to return defaults in
 * unit tests (testOptions.unitTests.isReturnDefaultValues = true).
 */
class IpFilterTest {

    // ─── Blank allowlist ───

    @Test
    fun `empty allowlist allows all`() {
        assertTrue(IpFilter.isAllowed("1.2.3.4", ""))
        assertTrue(IpFilter.isAllowed("1.2.3.4", "   "))
    }

    // ─── Exact match ───

    @Test
    fun `exact ip match`() {
        assertTrue(IpFilter.isAllowed("192.168.1.100", "192.168.1.100"))
        assertFalse(IpFilter.isAllowed("192.168.1.101", "192.168.1.100"))
    }

    @Test
    fun `multiple entries any-match semantics`() {
        val list = "10.0.0.5, 192.168.1.100"
        assertTrue(IpFilter.isAllowed("10.0.0.5", list))
        assertTrue(IpFilter.isAllowed("192.168.1.100", list))
        assertFalse(IpFilter.isAllowed("10.0.0.6", list))
    }

    @Test
    fun `ipv4-mapped ipv6 is normalized to ipv4`() {
        assertTrue(IpFilter.isAllowed("::ffff:192.168.1.5", "192.168.1.5"))
        assertTrue(IpFilter.isAllowed("::ffff:192.168.1.5", "192.168.1.0/24"))
    }

    @Test
    fun `ipv6 exact entry matches by string equality`() {
        assertTrue(IpFilter.isAllowed("::1", "::1"))
        assertFalse(IpFilter.isAllowed("::2", "::1"))
    }

    // ─── CIDR (AND-SEC-08: prefix length must be 0..32) ───

    @Test
    fun `cidr 24 matches subnet`() {
        assertTrue(IpFilter.isAllowed("192.168.1.55", "192.168.1.0/24"))
        assertFalse(IpFilter.isAllowed("192.168.2.1", "192.168.1.0/24"))
    }

    @Test
    fun `cidr 16 matches subnet`() {
        assertTrue(IpFilter.isAllowed("172.16.200.1", "172.16.0.0/16"))
        assertFalse(IpFilter.isAllowed("172.17.0.1", "172.16.0.0/16"))
    }

    @Test
    fun `cidr 0 matches everything`() {
        assertTrue(IpFilter.isAllowed("8.8.8.8", "0.0.0.0/0"))
        assertTrue(IpFilter.isAllowed("203.0.113.9", "10.0.0.0/0"))
    }

    @Test
    fun `cidr 32 is exact match`() {
        assertTrue(IpFilter.isAllowed("10.1.2.3", "10.1.2.3/32"))
        assertFalse(IpFilter.isAllowed("10.1.2.4", "10.1.2.3/32"))
    }

    @Test
    fun `cidr prefix length above 32 is rejected`() {
        assertFalse(IpFilter.isAllowed("10.0.0.1", "10.0.0.0/33"))
        assertFalse(IpFilter.isAllowed("10.0.0.1", "10.0.0.0/128"))
    }

    @Test
    fun `cidr negative prefix length is rejected`() {
        assertFalse(IpFilter.isAllowed("10.0.0.1", "10.0.0.0/-1"))
    }

    @Test
    fun `cidr non-numeric prefix length is rejected`() {
        assertFalse(IpFilter.isAllowed("10.0.0.1", "10.0.0.0/abc"))
    }

    @Test
    fun `cidr missing prefix is rejected`() {
        assertFalse(IpFilter.isAllowed("10.0.0.1", "10.0.0.0/"))
        assertFalse(IpFilter.isAllowed("10.0.0.1", "10.0.0.0/24/24"))
    }

    @Test
    fun `cidr with invalid network address is rejected`() {
        assertFalse(IpFilter.isAllowed("10.0.0.1", "999.0.0.0/24"))
        assertFalse(IpFilter.isAllowed("10.0.0.1", "10.0.0/24"))
    }

    // ─── Hostnames are rejected (AND-新N13: no DNS on the accept path) ───

    @Test
    fun `hostname entry never allows an ip`() {
        // Old code resolved "example.com" via InetAddress.getByName() →
        // blocking DNS lookup on every connection. Now: rejected outright.
        assertFalse(IpFilter.isAllowed("93.184.216.34", "example.com"))
    }

    @Test
    fun `hostname in cidr entry is rejected`() {
        assertFalse(IpFilter.isAllowed("93.184.216.34", "example.com/24"))
    }

    @Test
    fun `hostname in range entry is rejected`() {
        assertFalse(IpFilter.isAllowed("93.184.216.34", "example.com-test.com"))
    }

    @Test
    fun `mixed valid and invalid entries still work`() {
        val list = "bad.host.name, 192.168.0.0/16"
        assertTrue(IpFilter.isAllowed("192.168.5.5", list))
        assertFalse(IpFilter.isAllowed("10.0.0.1", list))
    }

    // ─── Malformed literals ───

    @Test
    fun `invalid octet values are rejected`() {
        assertFalse(IpFilter.isAllowed("999.1.1.1", "999.1.1.1"))
        assertFalse(IpFilter.isAllowed("1.2.3.4", "256.0.0.1"))
    }

    @Test
    fun `non-numeric ip is rejected`() {
        assertFalse(IpFilter.isAllowed("1.2.3.4", "1.2.3.x"))
    }

    // ─── Ranges ───

    @Test
    fun `range matches inside bounds`() {
        assertTrue(IpFilter.isAllowed("192.168.1.10", "192.168.1.1-192.168.1.254"))
        assertTrue(IpFilter.isAllowed("192.168.1.1", "192.168.1.1-192.168.1.254"))
        assertTrue(IpFilter.isAllowed("192.168.1.254", "192.168.1.1-192.168.1.254"))
        assertFalse(IpFilter.isAllowed("192.168.1.255", "192.168.1.1-192.168.1.254"))
        assertFalse(IpFilter.isAllowed("192.168.2.1", "192.168.1.1-192.168.1.254"))
    }

    @Test
    fun `range across subnets matches`() {
        assertTrue(IpFilter.isAllowed("192.168.2.100", "192.168.1.1-192.168.2.254"))
        assertFalse(IpFilter.isAllowed("192.168.3.1", "192.168.1.1-192.168.2.254"))
    }

    @Test
    fun `reversed range is rejected`() {
        assertFalse(IpFilter.isAllowed("192.168.1.5", "192.168.1.254-192.168.1.1"))
    }

    @Test
    fun `malformed range with three parts is rejected`() {
        assertFalse(IpFilter.isAllowed("192.168.1.5", "192.168.1.1-192.168.1.2-192.168.1.3"))
    }
}
