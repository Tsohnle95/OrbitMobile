package dev.orbit.mobile.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ProviderListDto(
    val all: List<ProviderDto> = emptyList(),
    val connected: List<String> = emptyList(),
    val default: JsonObject? = null,
)

@Serializable
data class ProviderDto(
    val id: String,
    val name: String? = null,
    val status: String? = null,
    val models: Map<String, ProviderModelDto> = emptyMap(),
)

@Serializable
data class ProviderModelDto(
    val id: String,
    val name: String? = null,
)

@Serializable
data class AgentInfoDto(
    val name: String,
    val mode: String? = null,
    val description: String? = null,
)

@Serializable
data class ProjectDirectoryDto(
    val directory: String,
)
