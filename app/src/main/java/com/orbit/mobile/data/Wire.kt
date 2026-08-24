package com.orbit.mobile.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject

enum class ApiFlavor { V1, V2 }

fun detectFlavor(docJson: JsonObject): ApiFlavor =
    if (docJson.containsKey("paths") &&
        docJson["paths"].toString().contains("/api/session")
    ) ApiFlavor.V2 else ApiFlavor.V1

object Wire {
    fun sessionBody(flavor: ApiFlavor, title: String?): JsonObject = buildJsonObject {
        title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
    }

    fun promptBody(flavor: ApiFlavor, text: String, agent: String? = null): JsonObject = when (flavor) {
        ApiFlavor.V1 -> buildJsonObject {
            agent?.takeIf { it.isNotBlank() }?.let { put("agent", it) }
            putJsonArray("parts") {
                addJsonObject {
                    put("type", "text")
                    put("text", text)
                }
            }
        }
        ApiFlavor.V2 -> buildJsonObject {
            put("text", text)
            agent?.takeIf { it.isNotBlank() && it != "build" }?.let { put("agent", it) }
        }
    }

    fun sessionsPath(flavor: ApiFlavor, directory: String?): String = when (flavor) {
        ApiFlavor.V1 -> "/session" + (directory?.let { "?directory=${it}" } ?: "")
        ApiFlavor.V2 -> "/api/session" + (directory?.let { "?directory=${it}" } ?: "")
    }

    fun sessionPath(flavor: ApiFlavor, id: String) = when (flavor) {
        ApiFlavor.V1 -> "/session/$id"
        ApiFlavor.V2 -> "/api/session/$id"
    }

    fun promptPath(flavor: ApiFlavor, id: String) = when (flavor) {
        ApiFlavor.V1 -> "/session/$id/message"
        ApiFlavor.V2 -> "/api/session/$id/prompt"
    }

    fun messagesPath(flavor: ApiFlavor, id: String) = when (flavor) {
        ApiFlavor.V1 -> "/session/$id/message"
        ApiFlavor.V2 -> "/api/session/$id/message"
    }

    fun abortPath(flavor: ApiFlavor, id: String) = when (flavor) {
        ApiFlavor.V1 -> "/session/$id/abort"
        ApiFlavor.V2 -> "/api/session/$id/abort"
    }

    fun todosPath(flavor: ApiFlavor, id: String) = when (flavor) {
        ApiFlavor.V1 -> "/session/$id/todo"
        ApiFlavor.V2 -> "/api/session/$id/todo"
    }

    fun modelPath(flavor: ApiFlavor, id: String) = when (flavor) {
        ApiFlavor.V1 -> "/api/session/$id/model"
        ApiFlavor.V2 -> "/api/session/$id/model"
    }

    fun eventPath(flavor: ApiFlavor) = when (flavor) {
        ApiFlavor.V1 -> "/event"
        ApiFlavor.V2 -> "/api/event"
    }

    fun providersPath(flavor: ApiFlavor) = when (flavor) {
        ApiFlavor.V1 -> "/provider"
        ApiFlavor.V2 -> "/api/provider"
    }

    fun agentsPath(flavor: ApiFlavor) = when (flavor) {
        ApiFlavor.V1 -> "/agent"
        ApiFlavor.V2 -> "/api/agent"
    }

    fun statusPath(flavor: ApiFlavor) = when (flavor) {
        ApiFlavor.V1 -> "/session/status"
        ApiFlavor.V2 -> "/api/session/status"
    }

    fun projectsPath(flavor: ApiFlavor) = when (flavor) {
        ApiFlavor.V1 -> "/project"
        ApiFlavor.V2 -> "/api/project"
    }

    fun worktreesPath(flavor: ApiFlavor, projectId: String) = when (flavor) {
        ApiFlavor.V1 -> "/project/$projectId/directories"
        ApiFlavor.V2 -> "/api/worktree/$projectId"
    }

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** v2 wraps single-resource responses in {"data": ...}; unwrap transparently. */
    fun unwrap(body: String, flavor: ApiFlavor = ApiFlavor.V1): String {
        if (flavor != ApiFlavor.V2) return body
        return runCatching {
            val element = json.parseToJsonElement(body)
            if (element !is JsonObject) return body
            val data = element["data"] ?: return body
            data.toString()
        }.getOrDefault(body)
    }

    /**
     * v2 list endpoints return either a bare array or {data:[...], cursor}.
     * Normalize to a bare-array JSON string for ListSerializer decoding.
     */
    fun unwrapList(body: String, flavor: ApiFlavor = ApiFlavor.V1): String {
        val unwrapped = unwrap(body, flavor)
        return runCatching {
            val element = json.parseToJsonElement(unwrapped)
            when {
                element is JsonObject && element.containsKey("items") -> element["items"].toString()
                else -> unwrapped
            }
        }.getOrDefault(unwrapped)
    }
}
