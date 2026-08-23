package com.omniagent.mobile

import com.omniagent.mobile.data.OpenCodeClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenCodeClientTest {

    @Test
    fun `parses plain url`() {
        val t = OpenCodeClient.fromUrl("http://192.168.1.20:4096")!!
        assertEquals("192.168.1.20", t.host)
        assertEquals(4096, t.port)
        assertNull(t.password)
    }

    @Test
    fun `parses url with user password`() {
        val t = OpenCodeClient.fromUrl("http://opencode:hunter2@192.168.1.20:4096/")!!
        assertEquals("opencode", t.username)
        assertEquals("hunter2", t.password)
        assertEquals(4096, t.port)
    }

    @Test
    fun `rejects non http input`() {
        assertNull(OpenCodeClient.fromUrl("not a url"))
        assertNull(OpenCodeClient.fromUrl("ftp://x"))
    }

    @Test
    fun `prompt body matches verified wire shape`() {
        val body = OpenCodeClient.promptBody("hi")
        val json = OpenCodeClient.defaultJson.encodeToString(
            kotlinx.serialization.json.JsonObject.serializer(),
            body,
        )
        assertTrue(json.contains("\"parts\""))
        assertTrue(json.contains("\"type\":\"text\""))
        assertTrue(json.contains("\"text\":\"hi\""))
    }
}
