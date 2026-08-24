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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
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
 * Speaks both wire flavors:
 *  - V1 (opencode 1.x): /session, /agent, parts[] prompts
 *  - V2 (opencode 2.x beta): /api/ paths, "data" envelopes, text-field prompts
 * Flavor auto-detected from the served OpenAPI document.
 */
class OpenCodeClient(
    private val target: ServerTarget,
    private val json: Json = defaultJson,
) {
    var flavor: ApiFlavor = ApiFlavor.V1
        private set

    private val client = HttpClient {
        expectSuccess = false
        install(UserAgent) { agent = "OmniAgentMobile/0.3" }
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
                val sep = userInfo.indexOf(':')
                if (sep >= 0) {
                    user = userInfo.substring(0, sep)
                    pass = userInfo.substring(sep + 1)
                } else {
                    pass = userInfo
                }
                rest = rest.substring(atIndex + 1)
            }
            var token: String? = null
            val slash = rest.indexOf('/')
            if (slash >= 0) {
                token = rest.substring(slash + 1).takeIf { it.isNotBlank() }
                rest = rest.substring(0, slash)
            }
            val colon = rest.lastIndexOf(':')
            val host: String
            val port: Int
            if (colon > 0) {
                host = rest.substring(0, colon)
                port = rest.substring(colon + 1).toIntOrNull() ?: return null
            } else {
                host = rest
                port = 4096
            }
            if (host.isBlank()) return null
            return ServerTarget(host = host, port = port, password = pass ?: token, username = user)
        }

        fun promptBody(text: String, agent: String? = null): JsonObject = buildJsonObject {
            agent?.takeIf { it.isNotBlank() }?.let { put("agent", it) }
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

    suspend fun detectFlavor(): ApiFlavor = withContext(Dispatchers.IO) {
        val v1doc = runCatching {
            val r = client.get("${target.baseUrl}/doc")
            if (r.status.value == 200) r.body<String>() else ""
        }.getOrDefault("")
        flavor = when {
            v1doc.trimStart().startsWith("{") && v1doc.contains("\"/api/session") -> ApiFlavor.V2
            v1doc.trimStart().startsWith("{") -> ApiFlavor.V1
            else -> {
                val v2doc = runCatching {
                    val r = client.get("${target.baseUrl}/openapi.json")
                    if (r.status.value == 200) r.body<String>() else ""
                }.getOrDefault("")
                if (v2doc.trimStart().startsWith("{")) ApiFlavor.V2 else ApiFlavor.V1
            }
        }
        flavor
    }

    suspend fun health(): HealthDto = when (flavor) {
        ApiFlavor.V1 -> get("/global/health").ok().body()
        ApiFlavor.V2 -> {
            get("/api/project").ok()
            HealthDto(healthy = true, version = "2.x")
        }
    }

    suspend fun projects(): List<ProjectDto> {
        val text = get(Wire.projectsPath(flavor)).ok().body<String>()
        return json.decodeFromString(
            ListSerializer(ProjectDto.serializer()),
            Wire.unwrapList(text, flavor),
        )
    }

    suspend fun sessions(directory: String?): List<SessionDto> {
        val text = get(Wire.sessionsPath(flavor, directory)).ok().body<String>()
        return json.decodeFromString(
            ListSerializer(SessionDto.serializer()),
            Wire.unwrapList(text, flavor),
        )
    }

    suspend fun session(id: String): SessionDto {
        val text = get(Wire.sessionPath(flavor, id)).ok().body<String>()
        return json.decodeFromString(SessionDto.serializer(), Wire.unwrap(text, flavor))
    }

    suspend fun createSession(title: String?, directory: String?): SessionDto {
        val body = buildJsonObject {
            title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
            if (flavor == ApiFlavor.V1) {
                directory?.takeIf { it.isNotBlank() }?.let { put("directory", it) }
            }
        }
        val text = postJson(Wire.sessionsPath(flavor, directory), body).ok().body<String>()
        return json.decodeFromString(SessionDto.serializer(), Wire.unwrap(text, flavor))
    }

    suspend fun renameSession(id: String, title: String) {
        io {
            client.patch("${target.baseUrl}${Wire.sessionPath(flavor, id)}") {
                header("Content-Type", "application/json")
                setBody(json.encodeToString(JsonObject.serializer(), buildJsonObject { put("title", title) }))
            }
        }.ok()
    }

    suspend fun deleteSession(id: String) {
        io { client.delete("${target.baseUrl}${Wire.sessionPath(flavor, id)}") }.ok()
    }

    suspend fun messages(sessionId: String): List<MessageWithPartsDto> {
        val text = get(Wire.messagesPath(flavor, sessionId)).ok().body<String>()
        val normalized = Wire.unwrapList(text, flavor)
        val element = json.parseToJsonElement(normalized)
        return element.jsonArray.map { obj ->
            MessageWithPartsDto.fromJson(obj.jsonObject, flavor)
        }
    }

    suspend fun sendMessage(sessionId: String, text: String, agent: String? = null): HttpResponse =
        postJson(Wire.promptPath(flavor, sessionId), Wire.promptBody(flavor, text, agent))

    suspend fun abortSession(sessionId: String) {
        runCatching {
            postJson(Wire.abortPath(flavor, sessionId), buildJsonObject { }).ok()
        }
    }

    suspend fun agents(): List<AgentDto> = runCatching {
        val text = get(Wire.agentsPath(flavor)).ok().body<String>()
        val element = json.parseToJsonElement(Wire.unwrap(text, flavor))
        val arrayElement = when {
            element is JsonObject && element.containsKey("data") -> element["data"]!!.jsonArray
            else -> element.jsonArray
        }
        json.decodeFromString(ListSerializer(AgentDto.serializer()), arrayElement.toString())
    }.getOrDefault(emptyList())

    suspend fun providers(): ProviderListDto? = runCatching {
        val text = get(Wire.providersPath(flavor)).ok().body<String>()
        json.decodeFromString(ProviderListDto.serializer(), Wire.unwrap(text, flavor))
    }.getOrNull()

    suspend fun projectDirectories(projectId: String): List<String> = runCatching {
        val dirs: List<Map<String, String>> =
            get(Wire.worktreesPath(flavor, projectId)).ok().body()
        dirs.mapNotNull { it["directory"] }
    }.getOrDefault(emptyList())

    suspend fun setSessionModel(sessionId: String, providerId: String, modelId: String) {
        postJson(
            Wire.modelPath(flavor, sessionId),
            buildJsonObject {
                put("model", buildJsonObject {
                    put("id", modelId)
                    put("providerID", providerId)
                })
            },
        ).ok()
    }

    suspend fun sessionStatus(): JsonObject = runCatching {
        get(Wire.statusPath(flavor)).ok().body<JsonObject>()
    }.getOrDefault(JsonObject(emptyMap()))

    suspend fun vcsStatus(directory: String): List<VcsStatusFileDto> = runCatching {
        val out: List<VcsStatusFileDto> =
            get("/vcs/status?directory=${urlEncode(directory)}").ok().body()
        out
    }.getOrDefault(emptyList())

    suspend fun vcsDiff(directory: String): List<VcsDiffFileDto> = runCatching {
        val out: List<VcsDiffFileDto> =
            get("/vcs/diff?directory=${urlEncode(directory)}&mode=git&context=2").ok().body()
        out
    }.getOrDefault(emptyList())

    suspend fun listDirectory(directory: String, path: String): List<FileEntryDto> = runCatching {
        val out: List<FileEntryDto> =
            get("/file?path=${urlEncode(path)}&directory=${urlEncode(directory)}").ok().body()
        out
    }.getOrDefault(emptyList())

    suspend fun fileContent(directory: String, path: String): String? = runCatching {
        val dto: FileContentDto =
            get("/file/content?path=${urlEncode(path)}&directory=${urlEncode(directory)}").ok().body()
        dto.content
    }.getOrNull()

    suspend fun findFiles(directory: String, query: String): List<String> = runCatching {
        val out: List<String> =
            get("/find/file?query=${urlEncode(query)}&directory=${urlEncode(directory)}").ok().body()
        out
    }.getOrDefault(emptyList())

    suspend fun todos(sessionId: String): List<TodoItemDto> = runCatching {
        val out: List<TodoItemDto> = get(Wire.todosPath(flavor, sessionId)).ok().body()
        out
    }.getOrDefault(emptyList())

    suspend fun permissions(): List<PermissionRequestDto> = runCatching {
        val text = get("/permission").ok().body<String>()
        json.decodeFromString(ListSerializer(PermissionRequestDto.serializer()), Wire.unwrap(text, flavor))
    }.getOrDefault(emptyList())

    suspend fun replyPermission(requestId: String, reply: String) {
        postJson("/permission/$requestId/reply", buildJsonObject { put("reply", reply) }).ok()
    }

    private suspend fun rawList(call: suspend () -> HttpResponse): String {
        val text = call().ok().body<String>()
        return Wire.unwrap(text, flavor)
    }

    fun eventStream(): SSEStream = SSEStream(client, target.baseUrl, json, Wire.eventPath(flavor))

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
