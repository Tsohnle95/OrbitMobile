package com.orbit.mobile.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.orbit.mobile.data.ToolStateDto
import com.orbit.mobile.ui.theme.LocalOrbitColors

private const val REASONING_SUMMARY_MAX_CHARS = 80

private val TOOL_DISPLAY_NAMES: Map<String, String> = mapOf(
    "read" to "Read File",
    "view" to "Read File",
    "file_read" to "Read File",
    "cat" to "Read File",
    "write" to "Write File",
    "create" to "Write File",
    "file_write" to "Write File",
    "edit" to "Edit File",
    "multiedit" to "Multi-Edit",
    "apply_patch" to "Apply Patch",
    "bash" to "Shell Command",
    "shell" to "Shell Command",
    "glob" to "Find Files",
    "find" to "Find Files",
    "grep" to "Search Files",
    "search" to "Search Files",
    "ls" to "List Directory",
    "task" to "Agent Task",
    "webfetch" to "Fetch URL",
    "websearch" to "Web Search",
    "todowrite" to "Update Todos",
    "todoread" to "Read Todos",
)

private fun normalizeToolName(toolName: String?): String {
    if (toolName.isNullOrBlank()) return ""
    val trimmed = toolName.trim().lowercase()
    val withoutIndex = trimmed.replace(Regex(":\\d+$"), "")
    return if (withoutIndex.contains('.')) {
        withoutIndex.split('.').filter { it.isNotEmpty() }.lastOrNull() ?: withoutIndex
    } else {
        withoutIndex
    }
}

fun toolDisplayName(toolName: String?): String {
    val normalized = normalizeToolName(toolName)
    return TOOL_DISPLAY_NAMES[normalized]
        ?: normalized.replace(Regex("[_-]+"), " ").replaceFirstChar { it.uppercase() }
}

private fun inputString(state: ToolStateDto?, vararg keys: String): String? {
    val obj = state?.input ?: return null
    for (key in keys) {
        val value = obj[key] as? kotlinx.serialization.json.JsonPrimitive ?: continue
        val s = value.content
        if (!s.isNullOrBlank()) return s
    }
    return null
}

/**
 * Mirrors OpenChamber's getToolDescription fallback chain:
 * state.title -> filePath -> bash command first line -> task description.
 */
fun toolTitle(state: ToolStateDto, toolName: String?): String {
    if (!state.title.isNullOrBlank()) {
        val t = state.title!!.take(100)
        if (t.isNotBlank()) return t
    }
    val normalized = normalizeToolName(toolName)
    val path = inputString(state, "filePath", "file_path", "path")
    if (path != null && normalized in setOf("read", "view", "file_read", "cat", "write", "create", "file_write", "edit", "multiedit", "apply_patch")) {
        val short = path.substringAfterLast('/')
        return "${toolDisplayName(toolName)} $short"
    }
    if (normalized == "bash" || normalized == "shell") {
        val command = inputString(state, "command") ?: ""
        val firstLine = command.lineSequence().firstOrNull() ?: ""
        return firstLine.take(100).ifBlank { toolDisplayName(toolName) }
    }
    if (normalized == "task") {
        val description = inputString(state, "description") ?: ""
        return description.take(80).ifBlank { toolDisplayName(toolName) }
    }
    if (normalized == "glob") {
        val pattern = inputString(state, "pattern")
        if (!pattern.isNullOrBlank()) return pattern.take(80)
    }
    val description = inputString(state, "description")
    return when {
        !description.isNullOrBlank() -> description.take(90)
        else -> toolDisplayName(toolName)
    }
}

/** Strip markdown so the collapsed reasoning header reads as plain text. */
private fun stripMarkdown(text: String): String = text
    .replace(Regex("<!--[\\s\\S]*?-->"), "")
    .replace(Regex("```[\\w]*\\n?([\\s\\S]*?)```"), "$1")
    .replace(Regex("`([^`]+)`"), "$1")
    .replace(Regex("\\*{1,3}([^*]+)\\*{1,3}"), "$1")
    .replace(Regex("_{1,3}([^_]+)_{1,3}"), "$1")
    .replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
    .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
    .replace(Regex("^>\\s?", RegexOption.MULTILINE), "")
    .replace(Regex("^[-*_]{3,}\\s*$", RegexOption.MULTILINE), "")
    .trim()

private fun reasoningSummary(text: String): String {
    if (text.isBlank()) return ""
    val flat = stripMarkdown(text).replace(Regex("\\s+"), " ").trim()
    if (flat.length <= REASONING_SUMMARY_MAX_CHARS) return flat
    val cut = flat.lastIndexOf(' ', REASONING_SUMMARY_MAX_CHARS)
    val end = if (cut > 0) cut else REASONING_SUMMARY_MAX_CHARS
    return flat.substring(0, end).trimEnd() + "…"
}

@Composable
fun ToolEntry(tool: ToolStateDto, toolName: String?) {
    val colors = LocalOrbitColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        val dotColor = when {
            tool.status == "completed" -> colors.green
            tool.status == "error" -> colors.red
            tool.status.isBlank() -> colors.textFaint
            else -> colors.yellow
        }
        Box(
            Modifier
                .padding(end = 8.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(
            toolTitle(tool, toolName),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            color = colors.textFaint,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun ReasoningEntry(text: String) {
    val colors = LocalOrbitColors.current
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(9.dp))
            .background(colors.bgInset.copy(alpha = 0.45f))
            .clickable { expanded = !expanded }
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse reasoning" else "Expand reasoning",
                tint = colors.textFaint,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.size(4.dp))
            Text(
                reasoningSummary(text),
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = colors.textFaint,
                maxLines = if (expanded) 4 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = colors.textDim,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
