package com.omniagent.mobile.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.BasicAuthCredentials
import io.ktor.client.plugins.auth.providers.basic
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.net.URLEncoder

class OmniApiException(val status: HttpStatusCode, message: String) : Exception(message)

data class ServerTarget(
    val host: String,
    val port: Int,
    val password: String? = null,
    val username: String = "opencode",
) {
    val baseUrl: String get() = "http://$host:$port"
}

/**
 * HTTP client for a headless `opencode serve` endpoint.
 * Wire shapes verified against opencode 1.18.21 (see mobile/README.md).
 */
class OpenCodeClient(
    private val target: ServerTarget,
    private val json: Json = defaultJson,
) {
    private val client = HttpClient {
        expectSuccess = false
        install(UserAgent) { agent = "OmniAgentMobile/0.1" }
        install(ContentNegotiation) { json(json) }
        if (!target.password.isNullOrBlank()) {
            install(Auth) {
                basic {
                    credentials { BasicAuthCredentials(target.username, target.password) }
                    sendWithoutRequest { true }
                }
            }
        }
    }

    companion object {
        val defaultJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
            encodeDefaults = false
            coerceInputValues = true
        }

        fun fromUrl(raw: String): ServerTarget? {
            var rest = raw.trim().trimEnd('/')
            if (!rest.startsWith("http://")) return null
            rest = rest.removePrefix("http://")
            if (rest.isBlank()) return null
            var user = "opencode"
            var pass: String? = null
            val atIndex = rest.indexOf('@')
            if (atIndex >= 0) {
                val userInfo = rest.substring(0, atIndex)
                rest = rest.substring(atIndex + 1)
                val colon = userInfo.indexOf(':')
                if (colon >= 0) {
                    user = userInfo.substring(0, colon).ifBlank { "opencode" }
                    pass = userInfo.substring(colon + 1)
                }
            }
            var token: String? = null
            Regex("^([^/@]+)/([A-Za-z0-9+/=_-]{6,})$").find(rest)?.let { m ->
                token = m.groupValues[2]
                rest = m.groupValues[1]
            }
            val slash = rest.indexOf('/')
            if (slash >= 0) rest = rest.substring(0, slash)
            val colon = rest.lastIndexOf(':')
            val host: String
            val port: Int
            if (colon >= 0) {
                host = rest.substring(0, colon)
                port = rest.substring(colon + 1).toIntOrNull() ?: return null
            } else {
                host = rest
                port = 4096
            }
            if (host.isBlank()) return null
            return ServerTarget(host = host, port = port, password = pass ?: token, username = user)
        }

        fun promptBody(text: String): JsonObject = buildJsonObject {
            putJsonArray("parts") {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            }
        }
    }

    private suspend fun io(block: suspend () -> HttpResponse): HttpResponse =
        withContext(Dispatchers.IO) { block() }

    private suspend fun get(path: String): HttpResponse = io { client.get("${target.baseUrl}$path") }

    private suspend fun postJson(path: String, body: JsonObject): HttpResponse = io {
        client.post("${target.baseUrl}$path") {
            header("Content-Type", "application/json")
            setBody(json.encodeToString(JsonObject.serializer(), body))
        }
    }

    suspend fun health(): HealthDto = get("/global/health").ok().body()

    suspend fun projects(): List<ProjectDto> = get("/project").ok().body()

    suspend fun sessions(directory: String?): List<SessionDto> {
        val path = if (directory.isNullOrBlank()) "/session"
        else "/session?directory=${urlEncode(directory)}"
        return get(path).ok().body()
    }

    suspend fun session(id: String): SessionDto = get("/session/$id").ok().body()

    suspend fun createSession(title: String?, directory: String?): SessionDto {
        val body = buildJsonObject {
            title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
            directory?.takeIf { it.isNotBlank() }?.let { put("directory", it) }
        }
        return postJson("/session", body).ok().body()
    }

    suspend fun renameSession(id: String, title: String) {
        io {
            client.patch("${target.baseUrl}/session/$id") {
                header("Content-Type", "application/json")
                setBody(json.encodeToString(JsonObject.serializer(), buildJsonObject { put("title", title) }))
            }
        }.ok()
    }

    suspend fun deleteSession(id: String) {
        io { client.delete("${target.baseUrl}/session/$id") }.ok()
    }

    suspend fun messages(sessionId: String): List<MessageWithPartsDto> =
        get("/session/$sessionId/message").ok().body()

    suspend fun sendMessage(sessionId: String, text: String): HttpResponse =
        postJson("/session/$sessionId/message", Companion.promptBody(text))

    suspend fun abortSession(sessionId: String) {
        postJson("/session/$sessionId/abort", buildJsonObject { }).ok()
    }

    suspend fun agents(): List<AgentDto> = runCatching {
        get("/agent").ok().body<List<AgentDto>>()
    }.getOrDefault(emptyList())

    suspend fun providers(): ProviderListDto? = runCatching {
        get("/provider").ok().body<ProviderListDto>()
    }.getOrNull()

    suspend fun projectDirectories(projectId: String): List<String> = runCatching {
        val dirs = get("/project/$projectId/directories")
            .ok()
            .body<List<Map<String, String>>>()
            .mapNotNull { it["directory"] }
        dirs
    }.getOrDefault(emptyList())

    suspend fun setSessionModel(sessionId: String, providerId: String, modelId: String) {
        postJson(
            "/api/session/$sessionId/model",
            buildJsonObject {
                put("model", buildJsonObject {
                    put("id", modelId)
                    put("providerID", providerId)
                })
            },
        ).ok()
    }

    suspend fun sessionStatus(): JsonObject = runCatching {
        get("/session/status").ok().body<JsonObject>()
    }.getOrDefault(JsonObject(emptyMap()))

    suspend fun permissions(): List<PermissionRequestDto> = runCatching {
        get("/permission").ok().body<List<PermissionRequestDto>>()
    }.getOrDefault(emptyList())

    suspend fun replyPermission(requestId: String, reply: String) {
        postJson("/permission/$requestId/reply", buildJsonObject { put("reply", reply) }).ok()
    }

    fun eventStream(): SSEStream = SSEStream(client, target.baseUrl, json)

    fun close() {
        client.close()
    }

    private suspend fun HttpResponse.ok(): HttpResponse {
        if (status.value !in 200..299) {
            val text = runCatching { body<String>() }.getOrDefault("")
            throw OmniApiException(status, "HTTP ${status.value} ${text.take(280)}")
        }
        return this
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
