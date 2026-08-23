package com.omniagent.mobile.ui.sessions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.omniagent.mobile.data.ProjectDto
import com.omniagent.mobile.ui.theme.LocalOmniColors

@Composable
fun WorkspacePickerSheet(
    projects: List<ProjectDto>,
    currentDirectory: String?,
    onLoadDirectories: (projectId: String, onDone: (List<String>) -> Unit) -> Unit,
    onPick: (directory: String, title: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalOmniColors.current
    var expandedProject by remember { mutableStateOf(projects.firstOrNull()?.id) }
    var directoriesByProject by remember { mutableStateOf<Map<String, List<String>>>(emptyMap()) }
    var selectedDir by remember { mutableStateOf(currentDirectory) }
    var title by remember { mutableStateOf("") }

    LaunchedEffect(expandedProject) {
        expandedProject?.let { pid ->
            if (!directoriesByProject.containsKey(pid)) {
                onLoadDirectories(pid) { list ->
                    directoriesByProject = directoriesByProject + (pid to list)
                }
            }
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 20.dp),
        ) {
            Text("New session in…", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            projects.forEach { project ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            expandedProject =
                                if (expandedProject == project.id) null else project.id
                        }
                        .padding(vertical = 10.dp),
                ) {
                    Text(
                        project.worktree.substringAfterLast('/').ifBlank { project.worktree },
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.text,
                    )
                    if (expandedProject == project.id) {
                        val dirs = directoriesByProject[project.id]
                        if (dirs == null) {
                            Text(
                                "Loading…",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textFaint,
                                modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                            )
                        } else {
                            dirs.forEach { dir ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { selectedDir = dir }
                                        .padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        dir,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (dir == selectedDir) colors.accent else colors.textDim,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (dir == selectedDir) {
                                        Icon(
                                            Icons.Rounded.Check,
                                            contentDescription = null,
                                            tint = colors.accent,
                                            modifier = Modifier.size(15.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                placeholder = { Text("Session title (optional)", color = colors.textFaint) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                shape = RoundedCornerShape(9.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.borderStrong,
                    cursorColor = colors.accent,
                ),
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    onPick(selectedDir ?: projects.firstOrNull()?.worktree.orEmpty(), title.ifBlank { null })
                },
                enabled = projects.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.sendContent),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("Create session")
            }
        }
    }
}
