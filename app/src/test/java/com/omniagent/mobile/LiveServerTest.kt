package com.omniagent.mobile

import com.omniagent.mobile.data.OpenCodeClient
import com.omniagent.mobile.data.ServerTarget
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs against a real `opencode serve` instance when one is reachable
 * (OMNI_LIVE_URL, default http://127.0.0.1:14100); skips otherwise.
 * Wire shapes verified here match opencode 1.18.21.
 */
class LiveServerTest {

    private fun client(): OpenCodeClient {
        val url = System.getenv("OMNI_LIVE_URL") ?: "http://127.0.0.1:14100"
        val target = OpenCodeClient.fromUrl(url) ?: ServerTarget("127.0.0.1", 14100)
        return OpenCodeClient(target)
    }

    private fun lastConnectError(): String? = runBlocking {
        val r = runCatching { client().health() }
        r.exceptionOrNull()?.let { "${it::class.simpleName}: ${it.message}" }
    }

    @Test
    fun `diagnostic connection check`() {
        val err = lastConnectError()
        assumeTrue("live server unreachable: $err", err == null)
        assertTrue(true)
    }

    @Test
    fun `health reports healthy`() = runBlocking {
        assumeTrue("live server unreachable: " + (lastConnectError() ?: "ok"), lastConnectError() == null)
        val c = client()
        val health = c.health()
        assertTrue(health.healthy)
        assertTrue(!health.version.isNullOrBlank())
        c.close()
    }

    @Test
    fun `session create rename message delete roundtrip`() = runBlocking {
        assumeTrue("live server unreachable: " + (lastConnectError() ?: "ok"), lastConnectError() == null)
        val c = client()
        val created = c.createSession(title = "mobile-live-check", directory = null)
        assertTrue(created.id.startsWith("ses_"))
        assertEquals("mobile-live-check", created.title)

        c.renameSession(created.id, "mobile-live-renamed")
        assertEquals("mobile-live-renamed", c.session(created.id).title)

        val msgs = c.messages(created.id)
        assertEquals(0, msgs.size)

        val list = c.sessions(null)
        assertTrue(list.any { it.id == created.id })

        c.deleteSession(created.id)
        assertTrue(c.sessions(null).none { it.id == created.id })
        c.close()
    }

    @Test
    fun `event stream delivers frames`() = runBlocking {
        assumeTrue("live server unreachable: " + (lastConnectError() ?: "ok"), lastConnectError() == null)
        val c = client()
        var gotEvent = false
        val job = c.eventStream().connect(
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO),
            password = null,
            onEvent = { _, _ -> gotEvent = true },
            onState = {},
        )
        val deadline = System.currentTimeMillis() + 8_000
        while (!gotEvent && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(100)
        }
        job.cancel()
        assertTrue("no SSE frame received", gotEvent)
        c.close()
    }
}
