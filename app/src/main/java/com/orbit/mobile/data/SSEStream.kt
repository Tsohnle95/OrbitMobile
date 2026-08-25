package com.orbit.mobile.data

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Long-lived Server-Sent Events reader.
 *
 * v1 frames: {"type": ..., "properties": {...}}
 * v2 frames: {"type": "session.reasoning.delta", "data": {...}, ...}
 *
 * Both shapes are normalized here: the handler receives (type, payload) where
 * payload is "properties" when present, otherwise the top-level "data" object,
 * falling back to the whole frame.
 */
class SSEStream(
    private val client: HttpClient,
    private val baseUrl: String,
    private val json: Json,
    private val eventPath: String = "/event",
) {
    fun connect(
        scope: CoroutineScope,
        password: String?,
        onEvent: suspend (type: String, payload: JsonObject) -> Unit,
        onState: (connected: Boolean) -> Unit,
    ): Job = scope.launch(Dispatchers.IO) {
        var attempt = 0
        while (isActive) {
            try {
                client.prepareGet("$baseUrl$eventPath") {
                    header(HttpHeaders.Accept, "text/event-stream")
                    header(HttpHeaders.CacheControl, "no-cache")
                    if (!password.isNullOrBlank()) {
                        header(HttpHeaders.Authorization, "Basic " + java.util.Base64.getEncoder().encodeToString("opencode:$password".toByteArray(Charsets.UTF_8)))
                    }
                }.execute { response ->
                    val channel: ByteReadChannel = response.bodyAsChannel()
                    onState(true)
                    attempt = 0
                    val data = StringBuilder()
                    while (!channel.isClosedForRead && isActive) {
                        val line = channel.readUTF8Line() ?: break
                        when {
                            line.startsWith("data:") -> data.append(line.removePrefix("data:").trimStart())
                            line.isEmpty() && data.isNotEmpty() -> {
                                dispatch(data.toString(), onEvent)
                                data.setLength(0)
                            }
                        }
                    }
                    onState(false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                attempt = (attempt + 1).coerceAtMost(6)
                onState(false)
            }
            delay(backoffMillis(attempt))
        }
    }

    private suspend fun dispatch(raw: String, onEvent: suspend (String, JsonObject) -> Unit) {
        runCatching {
            val root = json.parseToJsonElement(raw).jsonObject
            val type = root["type"]?.toString()?.trim('"') ?: return
            val payload = (root["properties"] as? JsonObject)
                ?: (root["data"] as? JsonObject)
                ?: JsonObject(emptyMap())
            onEvent(type, payload)
        }
    }

    private fun backoffMillis(attempt: Int): Long =
        if (attempt <= 1) 800L else minOf(500L * (1L shl attempt), 15_000L)
}
