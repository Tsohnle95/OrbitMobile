package com.omniagent.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.omniagent.mobile.data.FileEntryDto
import com.omniagent.mobile.ui.theme.LocalOmniColors

@Composable
fun FilesSheet(
    directory: String,
    listDirectory: suspend (String, String) -> List<FileEntryDto>,
    readFile: suspend (String, String) -> String?,
    onDismiss: () -> Unit,
) {
    val colors = LocalOmniColors.current
    var cwd by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<FileEntryDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var fileContent by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pendingRead by remember { mutableStateOf<String?>(null) }
    var readKey by remember { mutableStateOf(0) }
    var errorText by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(cwd) {
        loading = true
        errorText = null
        entries = runCatching { listDirectory(directory, cwd) }.getOrElse {
            android.util.Log.e("OmniFiles", "list failed", it)
            emptyList()
        }
        loading = false
    }

    LaunchedEffect(readKey) {
        if (readKey == 0) return@LaunchedEffect
        val path = pendingRead ?: return@LaunchedEffect
        loading = true
        errorText = null
        try {
            val content = readFile(directory, path)
            android.util.Log.d(
                "OmniFiles",
                "read '$path' (dir='$directory') -> ${content?.length ?: "null"} chars"
            )
            if (!content.isNullOrEmpty()) fileContent = path to content
            else errorText = "Empty or unreadable file."
        } catch (ce: kotlinx.coroutines.CancellationException) {
            android.util.Log.w("OmniFiles", "read cancelled for '$path'")
            throw ce
        } catch (t: Throwable) {
            android.util.Log.e("OmniFiles", "read failed for '$path'", t)
            errorText = "${t::class.simpleName}: ${t.message ?: "read failed"}"
        }
        loading = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                .padding(vertical = 16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp),
            ) {
                if (cwd.isNotEmpty() || fileContent != null) {
                    IconButton(onClick = {
                        if (fileContent != null) fileContent = null
                        else cwd = cwd.trimEnd('/').substringBeforeLast('/', "")
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Up", tint = colors.textDim)
                    }
                }
                Text(
                    fileContent?.first ?: cwd.ifBlank { directory.substringAfterLast('/') },
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close", tint = colors.textDim)
                }
            }
            Spacer(Modifier.height(8.dp))
            val content = fileContent
            when {
                content != null -> LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .padding(horizontal = 12.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(colors.bgInset),
                ) {
                    val lines = content.second.lines()
                    items(lines.size) { index ->
                        Text(
                            lines[index].ifBlank { " " },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = colors.textDim,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 1.dp),
                            maxLines = 4,
                            overflow = TextOverflow.Clip,
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
                loading && content == null -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp)
                        .padding(vertical = 32.dp),
                ) {
                    CircularProgressIndicator(color = colors.accent, strokeWidth = 2.5.dp)
                }
                errorText != null -> Text(
                    errorText!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.red,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                )
                entries.isEmpty() -> Text(
                    "Empty folder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textFaint,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                )
                else -> LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(entries.size) { index ->
                        val entry = entries[index]
                        val isDir = entry.type == "directory"
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isDir) {
                                        cwd = entry.path
                                    } else {
                                        pendingRead = entry.path
                                        readKey++
                                    }
                                }
                                .padding(horizontal = 18.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (isDir) Icons.Rounded.Folder else Icons.Rounded.InsertDriveFile,
                                contentDescription = null,
                                tint = if (isDir) colors.accent else colors.textFaint,
                                modifier = Modifier
                                    .padding(end = 10.dp)
                                    .height(18.dp),
                            )
                            Text(
                                entry.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}
