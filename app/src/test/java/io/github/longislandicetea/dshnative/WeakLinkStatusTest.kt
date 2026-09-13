package io.github.longislandicetea.dshnative

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the app says about the network, in the one place a reader looks.
 *
 * The state a weak link needs most is the third one: connected, and holding
 * something that has not arrived. Calling that "connected" is the lie that made
 * a queued message look lost.
 */
class WeakLinkStatusTest {
    private val point = DshEndpoint("192.168.1.20", 3080)

    @Test
    fun nothing_configured_says_so() {
        assertEquals("not configured", connectionStatus(null, connected = false, waiting = 0))
    }

    @Test
    fun a_lost_socket_says_reconnecting() {
        assertEquals("reconnecting · 192.168.1.20", connectionStatus(point, connected = false, waiting = 0))
    }

    /** Both facts at once: the link is down, and this is what it is holding. */
    @Test
    fun a_lost_socket_still_says_what_is_waiting() {
        assertEquals("reconnecting · 2 waiting · 192.168.1.20", connectionStatus(point, connected = false, waiting = 2))
    }

    @Test
    fun a_held_message_is_not_reported_as_connected() {
        assertEquals(
            "1 waiting for the network · 192.168.1.20",
            connectionStatus(point, connected = true, waiting = 1),
        )
        assertEquals(
            "3 waiting for the network · 192.168.1.20",
            connectionStatus(point, connected = true, waiting = 3),
        )
    }

    @Test
    fun a_healthy_link_says_connected() {
        assertEquals("connected · 192.168.1.20", connectionStatus(point, connected = true, waiting = 0))
    }
}
