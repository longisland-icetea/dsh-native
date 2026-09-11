package io.github.longislandicetea.dshnative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Endpoint parsing.
 *
 * The address is typed by hand, so what matters is that a well-formed one lands on
 * the harness's port: a bare host used to resolve to 80 because OkHttp reports its
 * scheme default and the fallback never applied.
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
