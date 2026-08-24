package com.orbit.mobile.ui.sessions

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
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.orbit.mobile.data.ProjectDto
import com.orbit.mobile.ui.theme.LocalOrbitColors

fun workspaceLabel(directory: String): String =
    directory.trimEnd('/').substringAfterLast('/').ifBlank { directory }

@Composable
fun WorkspacePickerSheet(
    projects: List<ProjectDto>,
    currentDirectory: String?,
    onLoadDirectories: (projectId: String, onDone: (List<String>) -> Unit) -> Unit,
    onPick: (directory: String, title: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalOrbitColors.current
    var expandedProject by remember { mutableStateOf<String?>(null) }
    var title by remember { mutableStateOf("") }
    var chosenDirectory by remember { mutableStateOf(currentDirectory.orEmpty()) }
    var pendingLoad by remember { mutableStateOf<String?>(null) }
    val directoriesByProject = remember { mutableStateMapOf<String, List<String>>() }

    LaunchedEffect(pendingLoad) {
        val pid = pendingLoad ?: return@LaunchedEffect
        pendingLoad = null
        onLoadDirectories(pid) { dirs ->
            directoriesByProject[pid] = dirs
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .padding(horizontal = 18.dp, vertical = 20.dp),
        ) {
            Text("New session in…", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))

            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(projects.size) { index ->
                    val project = projects[index]
                    val expanded = expandedProject == project.id
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expandedProject = if (expanded) null else project.id
                                    if (!expanded && project.id !in directoriesByProject && project.id != "global") {
                                        pendingLoad = project.id
                                    }
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Rounded.Folder,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.padding(end = 10.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    project.worktree?.let { workspaceLabel(it) } ?: project.id.take(8),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = colors.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                project.worktree?.let { wt ->
                                    Text(
                                        wt,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textFaint,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Icon(
                                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = null,
                                tint = colors.textFaint,
                            )
                        }

                        if (expanded) {
                            val dirs = directoriesByProject[project.id]
                                ?: project.worktree?.let { listOf(it) }
                                ?: emptyList()
                            dirs.forEach { dir ->
                                val selected = chosenDirectory == dir
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { chosenDirectory = dir }
                                        .padding(start = 34.dp, top = 4.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    androidx.compose.foundation.layout.Box(
                                        modifier = Modifier
                                            .padding(end = 9.dp)
                                            .clip(androidx.compose.foundation.shape.CircleShape)
                                            .background(if (selected) colors.accent else Color.Transparent)
                                            .padding(2.dp),
                                    ) {}
                                    Text(
                                        dir,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (selected) colors.accent else colors.textDim,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (directoriesByProject[project.id] == null && project.id != "global") {
                                Text(
                                    "Loading workspaces…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textFaint,
                                    modifier = Modifier.padding(start = 34.dp, bottom = 6.dp),
                                )
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(4.dp)) }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                placeholder = { Text("Session title (optional)", color = colors.textFaint) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    cursorColor = colors.accent,
                ),
            )
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel", color = colors.textDim) }
                TextButton(
                    enabled = chosenDirectory.isNotBlank(),
                    onClick = {
                        onPick(chosenDirectory, title.ifBlank { null })
                        onDismiss()
                    },
                ) { Text("Create session", color = if (chosenDirectory.isBlank()) colors.textFaint else colors.accent) }
            }
        }
    }
}
