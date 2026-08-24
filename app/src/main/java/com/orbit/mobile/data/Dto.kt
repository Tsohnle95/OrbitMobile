package com.orbit.mobile.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

@Serializable
data class SessionDto(
    val id: String,
    val title: String? = null,
    val directory: String = "",
    val projectID: String? = null,
    val parentID: String? = null,
    val revert: JsonElement? = null,
    val time: SessionTimeDto = SessionTimeDto(),
    val cost: Double = 0.0,
    val tokens: TokenUsageDto? = null,
    val model: JsonObject? = null,
) {
    val displayTitle: String
        get() = title?.takeIf { it.isNotBlank() } ?: "Untitled session"
}

@Serializable
data class SessionTimeDto(
    val created: Long = 0,
    val updated: Long = 0,
)

@Serializable
data class TokenUsageDto(
    val input: Long = 0,
    val output: Long = 0,
    val reasoning: Long = 0,
    val cache: CacheTokensDto? = null,
)

@Serializable
data class CacheTokensDto(
    val read: Long = 0,
    val write: Long = 0,
)

@Serializable
data class MessageDto(
    val id: String,
    val role: String,
    val sessionID: String = "",
    val time: MessageTimeDto? = null,
    val cost: Double = 0.0,
    val tokens: TokenUsageDto? = null,
    val modelID: String? = null,
    val providerID: String? = null,
    val error: JsonObject? = null,
)

@Serializable
data class MessageTimeDto(
    val created: Long = 0,
    val completed: Long? = null,
)

@Serializable
data class PartDto(
    val id: String? = null,
    val type: String,
    val messageID: String? = null,
    val sessionID: String? = null,
    val text: String? = null,
    val callID: String? = null,
    val tool: String? = null,
    val name: String? = null,
    val state: JsonObject? = null,
    val metadata: JsonElement? = null,
    val synthetic: Boolean? = null,
)

/**
 * Normalized message with parts. Built from either wire shape:
 *  - v1: {info:{role,id,time,...}, parts:[{type,text,tool,state}]}
 *  - v2: {type:"assistant"|"user", id, text?, content?:[{type,text,name,state,...}], time}
 */
data class MessageWithPartsDto(
    val info: MessageDto,
    val parts: List<PartDto>,
) {
    @Serializable
    data class RawV1(
        val info: MessageDto,
        val parts: List<PartDto> = emptyList(),
    )

    companion object {
        fun fromJson(element: JsonObject, flavor: ApiFlavor): MessageWithPartsDto =
            if (flavor == ApiFlavor.V1) fromV1(element) else fromV2(element)

        private fun fromV1(obj: JsonObject): MessageWithPartsDto {
            val json = Wire.json
            val raw = json.decodeFromJsonElement(RawV1.serializer(), obj)
            return MessageWithPartsDto(info = raw.info, parts = raw.parts)
        }

        private fun fromV2(m: JsonObject): MessageWithPartsDto {
            val type = primitive(m, "type") ?: "user"
            val id = primitive(m, "id") ?: ""
            val timeObj = m["time"] as? JsonObject
            val created = (timeObj?.get("created") as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            val completed = (timeObj?.get("completed") as? JsonPrimitive)?.content?.toLongOrNull()
            val modelObj = m["model"] as? JsonObject

            val parts = mutableListOf<PartDto>()
            val topLevelText = primitive(m, "text")
            if (!topLevelText.isNullOrBlank()) {
                parts.add(PartDto(type = "text", text = topLevelText))
            }
            (m["content"] as? JsonArray)?.forEach { element ->
                val obj = element as? JsonObject ?: return@forEach
                when (primitive(obj, "type")) {
                    "text", "reasoning" -> parts.add(
                        PartDto(type = "text", text = primitive(obj, "text"))
                    )
                    "tool" -> parts.add(
                        PartDto(
                            type = "tool",
                            tool = primitive(obj, "name"),
                            state = obj["state"] as? JsonObject,
                        )
                    )
                }
            }

            val info = MessageDto(
                id = id,
                role = if (type == "assistant") "assistant" else "user",
                sessionID = "",
                time = MessageTimeDto(created = created, completed = completed),
                modelID = modelObj?.get("id")?.let { (it as? JsonPrimitive)?.content },
                providerID = modelObj?.get("providerID")?.let { (it as? JsonPrimitive)?.content },
            )
            return MessageWithPartsDto(info = info, parts = parts)
        }

        private fun primitive(obj: JsonObject, key: String): String? =
            (obj[key] as? JsonPrimitive)?.contentOrNull
    }
}

@Serializable
data class ToolStateDto(
    val status: String = "",
    val input: JsonObject? = null,
    val output: String? = null,
    val title: String? = null,
    val metadata: JsonObject? = null,
)

@Serializable
data class PermissionRequestDto(
    val id: String,
    val sessionID: String,
    val permission: String,
    val patterns: List<String> = emptyList(),
    val metadata: JsonObject? = null,
)

@Serializable
data class PartInputDto(
    val id: String,
    val sessionID: String,
    val permission: String,
    val patterns: List<String> = emptyList(),
    val metadata: JsonObject? = null,
    val tool: PermissionToolRefDto? = null,
)

@Serializable
data class PermissionToolRefDto(
    val messageID: String,
    val callID: String,
)

@Serializable
data class AgentDto(
    val name: String,
    val mode: String? = null,
    val description: String? = null,
)

@Serializable
data class ProjectDto(
    val id: String,
    val worktree: String? = null,
    val vcs: String? = null,
    val name: String? = null,
)

@Serializable
data class HealthDto(
    val healthy: Boolean = false,
    val version: String? = null,
)
