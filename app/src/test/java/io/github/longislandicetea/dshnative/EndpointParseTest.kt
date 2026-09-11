package io.github.longislandicetea.dshnative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Endpoint parsing, including the full-width punctuation a CJK keyboard emits.
 *
 * The case that matters is real: typing `192.168.255.5:3080` on a phone with a
 * Chinese IME stores `192。168.255.5：3080`, and the app reported "not configured"
 * with the text visibly present in the box.
 */
class EndpointParseTest {
    @Test
    fun `host and port parse`() {
        val endpoint = DshEndpoint.parse("192.168.1.20:3080")
        assertEquals("192.168.1.20", endpoint?.host)
        assertEquals(3080, endpoint?.port)
    }

    @Test
    fun `a bare host uses the default port`() {
        val endpoint = DshEndpoint.parse("192.168.1.20")
        assertEquals("192.168.1.20", endpoint?.host)
        assertEquals(3080, endpoint?.port)
    }

    @Test
    fun `a pasted url parses`() {
        val endpoint = DshEndpoint.parse("http://192.168.1.20:3080/")
        assertEquals("192.168.1.20", endpoint?.host)
        assertEquals(3080, endpoint?.port)
    }

    @Test
    fun `full-width punctuation parses`() {
        // What a Chinese IME actually produces.
        val endpoint = DshEndpoint.parse("192。168。255。5：3080")
        assertEquals("192.168.255.5", endpoint?.host)
        assertEquals(3080, endpoint?.port)
    }

    @Test
    fun `full-width punctuation is normalised before use`() {
        assertEquals("a.b:c/d-e", DshEndpoint.normalizePunctuation("a。b：c／d－e"))
        assertEquals("192.168.1.20:3080", DshEndpoint.normalizePunctuation("192.168.1.20:3080"))
    }

    @Test
    fun `surrounding whitespace and a trailing slash are tolerated`() {
        val endpoint = DshEndpoint.parse("  192.168.1.20:3080/  ")
        assertEquals("192.168.1.20", endpoint?.host)
    }

    @Test
    fun `a typed port always wins over the default`() {
        assertEquals(9000, DshEndpoint.parse("192.168.1.20:9000")?.port)
        assertEquals(80, DshEndpoint.parse("http://192.168.1.20:80")?.port)
    }

    @Test
    fun `an explicit url without a port falls back to the default`() {
        // Not OkHttp's 80: this client only ever talks to the harness's port.
        assertEquals(3080, DshEndpoint.parse("http://192.168.1.20")?.port)
    }

    @Test
    fun `nonsense is rejected`() {
        assertNull(DshEndpoint.parse(""))
        assertNull(DshEndpoint.parse("   "))
        // A non-http scheme is refused: this client only speaks plain LAN HTTP.
        assertNull(DshEndpoint.parse("https://192.168.1.20:3080"))
    }
}
