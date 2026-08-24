package com.orbit.mobile

import com.orbit.mobile.data.ApiFlavor
import com.orbit.mobile.data.MessageWithPartsDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V2MessageShapeTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    @Test
    fun `v2 assistant message with content array normalizes`() {
        val raw = """
        {"id":"msg_1","type":"assistant","agent":"build",
         "model":{"id":"x-preview-f-free","providerID":"opencode","variant":"max"},
         "time":{"created":1787557967245,"completed":1787557969235},
         "content":[
           {"type":"reasoning","text":"thinking..."},
           {"type":"tool","name":"shell","state":{"status":"completed","input":{"command":"ls"}}},
           {"type":"text","text":"Done — iteration 2 is live."}
         ]}
        """.trimIndent()
        val element = json.parseToJsonElement(raw).jsonObject
        val msg = MessageWithPartsDto.fromJson(element, ApiFlavor.V2)
        assertEquals("assistant", msg.info.role)
        assertEquals("msg_1", msg.info.id)
        assertEquals(1787557967245L, msg.info.time?.created)
        assertEquals(1787557969235L, msg.info.time?.completed)
        assertTrue(msg.parts.any { it.type == "text" && it.text == "Done — iteration 2 is live." })
        assertTrue(msg.parts.any { it.type == "tool" && it.tool == "shell" })
    }

    @Test
    fun `v2 user message with top-level text normalizes`() {
        val raw = """{"id":"msg_2","type":"user","text":"Continue.","time":{"created":1}}"""
        val element = json.parseToJsonElement(raw).jsonObject
        val msg = MessageWithPartsDto.fromJson(element, ApiFlavor.V2)
        assertEquals("user", msg.info.role)
        assertEquals(1, msg.parts.size)
        assertEquals("Continue.", msg.parts[0].text)
    }
}
