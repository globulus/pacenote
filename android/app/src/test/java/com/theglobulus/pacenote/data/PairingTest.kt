package com.theglobulus.pacenote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingTest {
    private val token = "ab".repeat(32)

    @Test
    fun `parses the link setup prints`() {
        val p = Pairing.parse("pacenote://pair?url=http%3A%2F%2Fmac.tail123.ts.net%3A8788&token=$token")
        assertEquals(Pairing("http://mac.tail123.ts.net:8788", token), p)
        assertEquals("mac.tail123.ts.net", p!!.host)
    }

    @Test
    fun `accepts tailnet IPs, refuses everything else`() {
        assertEquals("http://100.101.102.103:8788", Pairing.parse("pacenote://pair?url=http%3A%2F%2F100.101.102.103%3A8788&token=$token")?.url)
        // Not a tailnet: a web page could otherwise redirect your messages.
        assertNull(Pairing.parse("pacenote://pair?url=http%3A%2F%2Fevil.example.com&token=$token"))
        assertNull(Pairing.parse("pacenote://pair?url=http%3A%2F%2F192.168.1.5%3A8788&token=$token"))
        assertNull(Pairing.parse("pacenote://pair?url=https%3A%2F%2Fmac.tail123.ts.net&token=$token"))
        // Malformed.
        assertNull(Pairing.parse("pacenote://pair?url=http%3A%2F%2F100.64.0.1&token=short"))
        assertNull(Pairing.parse("pacenote://other?url=http%3A%2F%2F100.64.0.1&token=$token"))
        assertNull(Pairing.parse("https://example.com"))
        assertNull(Pairing.parse(null))
    }

    @Test
    fun `carries the Mac's name for display`() {
        val p = Pairing.parse("pacenote://pair?url=http%3A%2F%2F100.101.102.103%3A8788&token=$token&name=gordans-mac")
        assertEquals(Pairing("http://100.101.102.103:8788", token, "gordans-mac"), p)
    }

    @Test
    fun `service addresses the app may use`() {
        assertTrue(Pairing.allowedServiceUrl("http://100.101.102.103:8788"))
        assertTrue(Pairing.allowedServiceUrl("http://mac.tail1.ts.net:8788"))
        assertTrue(Pairing.allowedServiceUrl("https://example.com"))
        assertFalse(Pairing.allowedServiceUrl("http://example.com:8788"))
        assertFalse(Pairing.allowedServiceUrl("http://127.0.0.1:8790"))
        assertTrue(Pairing.allowedServiceUrl("http://127.0.0.1:8790", allowLocal = true))
        assertFalse(Pairing.allowedServiceUrl(""))
    }

    @Test
    fun `tailnet hosts`() {
        assertTrue(Pairing.isTailnetHost("100.64.0.1"))
        assertTrue(Pairing.isTailnetHost("100.127.255.254"))
        assertFalse(Pairing.isTailnetHost("100.128.0.1"))
        assertFalse(Pairing.isTailnetHost("100.63.0.1"))
        assertFalse(Pairing.isTailnetHost("ts.net.evil.com"))
        // Another app on the phone could be listening there.
        assertFalse(Pairing.isTailnetHost("localhost"))
        assertFalse(Pairing.isTailnetHost("127.0.0.1"))
    }
}
