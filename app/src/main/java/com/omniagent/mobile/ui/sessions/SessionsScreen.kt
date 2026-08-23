package com.omniagent.mobile.ui.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omniagent.mobile.app.SessionsViewModel
import com.omniagent.mobile.data.SessionDto
import com.omniagent.mobile.ui.theme.LocalOmniColors

@Composable
fun SessionsScreen(
    onOpenSession: (SessionDto) -> Unit,
    viewModel: SessionsViewModel = viewModel(),
) {
    val colors = LocalOmniColors.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showWorkspaceSheet by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<SessionDto?>(null) }
    var deleteTarget by remember { mutableStateOf<SessionDto?>(null) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showWorkspaceSheet = true },
                containerColor = colors.accent,
                contentColor = colors.sendContent,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.size(6.dp))
                Text("New session")
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Sessions", style = MaterialTheme.typography.titleLarge, color = colors.text)
                    Text(
                        "via ${viewModel.targetLabel()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textFaint,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(if (state.error == null) colors.green else colors.red),
                )
            }

            when {
                state.loading -> Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Connecting…", color = colors.textDim, style = MaterialTheme.typography.bodySmall)
                }
                state.error != null && state.sessions.isEmpty() -> Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Can't reach your Mac", style = MaterialTheme.typography.titleMedium, color = colors.text)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        state.error ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textDim,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        SERVER_DOWN_HINT,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textFaint,
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { viewModel.refresh() }) { Text("Try again") }
                    TextButton(onClick = { viewModel.forgetPairing() }) {
                        Text("Change server", color = colors.textDim)
                    }
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.sessions, key = { it.id }) { session ->
                        SessionRow(
                            session = session,
                            onClick = { onOpenSession(session) },
                            onRename = { renameTarget = session },
                            onDelete = { deleteTarget = session },
                        )
                    }
                    item { Spacer(Modifier.height(96.dp)) }
                }
            }
        }
    }

    if (showWorkspaceSheet && !state.loading && state.projects.isNotEmpty()) {
        WorkspacePickerSheet(
            projects = state.projects,
            currentDirectory = state.sessions.firstOrNull()?.directory,
            onLoadDirectories = { pid, done -> viewModel.loadDirectories(pid, done) },
            onPick = { directory, title ->
                showWorkspaceSheet = false
                viewModel.newSession(directory, title, onReady = onOpenSession)
            },
            onDismiss = { showWorkspaceSheet = false },
        )
    }

    renameTarget?.let { session ->
        RenameDialog(
            initial = session.displayTitle,
            onConfirm = { newTitle ->
                viewModel.renameSession(session.id, newTitle)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { session ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete session?") },
            text = { Text("\u201C${session.displayTitle}\u201D will be removed from your Mac. This cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteSession(session.id)
                        deleteTarget = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.red),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: SessionDto,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalOmniColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 20.dp, top = 13.dp, bottom = 13.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .padding(end = 12.dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(colors.borderStrong),
                )
                Text(
                    session.displayTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                relativeTime(session.time.updated),
                style = MaterialTheme.typography.labelMedium,
                color = colors.textFaint,
                modifier = Modifier.padding(start = 19.dp, top = 2.dp),
            )
        }
        IconButton(onClick = onRename) {
            Icon(Icons.Rounded.Edit, contentDescription = "Rename", tint = colors.textFaint, modifier = Modifier.size(17.dp))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = colors.textFaint, modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
private fun RenameDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val colors = LocalOmniColors.current
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename session") },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.accent,
                    unfocusedBorderColor = colors.borderStrong,
                    cursorColor = colors.accent,
                ),
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(value.trim()) }, enabled = value.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

internal fun relativeTime(epochMillis: Long): String {
    if (epochMillis <= 0) return ""
    val diff = System.currentTimeMillis() - epochMillis
    val minute = 60_000L
    val hour = 60 * minute
    val day = 24 * hour
    return when {
        diff < minute -> "just now"
        diff < hour -> "${diff / minute}m ago"
        diff < day -> "${diff / hour}h ago"
        diff < 7 * day -> "${diff / day}d ago"
        else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
            .format(java.util.Date(epochMillis))
    }
}

internal const val SERVER_DOWN_HINT =
    "On your Mac run: mobile/scripts/omni-serve.sh — then reconnect here."
