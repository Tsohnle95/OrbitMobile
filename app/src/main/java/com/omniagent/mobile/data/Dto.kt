package com.omniagent.mobile.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class SessionDto(
    val id: String,
    val title: String? = null,
    val directory: String = "",
    val projectID: String? = null,
    val parentID: String? = null,
    @SerialName("revert") val revert: JsonElement? = null,
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
    val sessionID: String,
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
    val state: JsonObject? = null,
    val metadata: JsonElement? = null,
    val synthetic: Boolean? = null,
)

@Serializable
data class MessageWithPartsDto(
    val info: MessageDto,
    val parts: List<PartDto> = emptyList(),
)

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
    val worktree: String = "",
    val vcs: String? = null,
)

@Serializable
data class HealthDto(
    val healthy: Boolean = false,
    val version: String? = null,
)

@Serializable
data class PromptRequestDto(
    val parts: List<PartInputDto>,
)

@Serializable
data class PartInputDto(
    val type: String = "text",
    val text: String,
)

@Serializable
data class PermissionReplyDto(
    val reply: String,
)
