package com.orbit.mobile.data

import kotlinx.serialization.Serializable

@Serializable
data class FileEntryDto(
    val name: String,
    val path: String,
    val absolute: String? = null,
    val type: String,
    val ignored: Boolean = false,
)

@Serializable
data class VcsStatusFileDto(
    val file: String,
    val additions: Int = 0,
    val deletions: Int = 0,
    val status: String = "",
)

@Serializable
data class VcsDiffFileDto(
    val file: String,
    val patch: String = "",
    val additions: Int = 0,
    val deletions: Int = 0,
    val status: String = "",
)

@Serializable
data class TodoItemDto(
    val content: String,
    val status: String = "pending",
    val priority: String? = null,
)

@Serializable
data class FileContentDto(
    val type: String? = null,
    val content: String? = null,
)
