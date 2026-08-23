package com.omniagent.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.omniagent.mobile.app.ChatMessage
import com.omniagent.mobile.app.ChatViewModel
import com.omniagent.mobile.data.ServerTarget
import com.omniagent.mobile.data.ProviderModelDto
import com.omniagent.mobile.data.ToolStateDto
import com.omniagent.mobile.ui.theme.LocalOmniColors
import com.omniagent.mobile.voice.SpeechManager
import com.omniagent.mobile.voice.VoiceButton

@Composable
fun ChatScreen(
    sessionId: String,
    target: ServerTarget,
    password: String?,
    speech: SpeechManager,
    onBack: () -> Unit,
    viewModel: ChatViewModel = viewModel(),
) {
    val colors = LocalOmniColors.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showModelPicker by remember { mutableStateOf(false) }
    var showAgentPicker by remember { mutableStateOf(false) }
    var showChanges by remember { mutableStateOf(false) }
    var showTodos by remember { mutableStateOf(false) }
    var showFiles by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId) {
        viewModel.start(sessionId, target, password)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .imePadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = colors.textDim)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    state.session?.displayTitle ?: "Session",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)),
                ) {
                    Box(
                        modifier = Modifier
                            .padding(end = 6.dp, top = 1.dp)
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (state.connected) colors.green else colors.red),
                    )
                    Text(
                        when {
                            state.busy -> "working…"
                            state.connected ->
                                state.session?.directory?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                                    ?: "connected"
                            else -> "reconnecting"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.textFaint,
                        maxLines = 1,
                    )
                }
            }
            TextButton(onClick = { showAgentPicker = true }) {
                Text(
                    state.currentAgent,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.textDim,
                    maxLines = 1,
                )
            }
            TextButton(onClick = { showModelPicker = true }) {
                Text(
                    currentModelLabel(state),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 130.dp),
                )
            }
            if (state.busy) {
                TextButton(onClick = { viewModel.abort() }) {
                    Text("Stop", color = colors.red)
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showFiles = true }) {
                        Text("Files", style = MaterialTheme.typography.labelLarge, color = colors.textDim)
                    }
                    TextButton(onClick = { showChanges = true; viewModel.loadChanges() }) {
                        Text(
                            "Changes (${state.changes.size})",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.textDim,
                        )
                    }
                }
            }
        }

        if (state.todos.isNotEmpty()) {
            TodoStrip(todos = state.todos, onExpand = { showTodos = true })
        }

        MessageList(
            messages = state.messages,
            modifier = Modifier.weight(1f),
        )

        state.pendingPermission?.let { permission ->
            PermissionCard(
                permission = permission,
                onReply = { reply -> viewModel.replyPermission(permission.id, reply) },
            )
        }

        Composer(
            draft = state.draft,
            enabled = !state.busy && !state.sending,
            speech = speech,
            onDraftChange = { viewModel.updateDraft(it) },
            onSend = { viewModel.send() },
        )
    }

    if (showAgentPicker) {
        AgentPickerSheet(
            agents = state.agents,
            current = state.currentAgent,
            onSelect = { viewModel.setAgent(it) },
            onDismiss = { showAgentPicker = false },
        )
    }

    if (showChanges) {
        ChangesSheet(
            changes = state.changes,
            onDismiss = { showChanges = false },
        )
    }

    if (showTodos) {
        TodosSheet(
            todos = state.todos,
            onDismiss = { showTodos = false },
        )
    }

    if (showFiles) {
        val dir = state.session?.directory
        if (dir != null) {
            val client = viewModel.clientForFiles()
            if (client != null) {
                FilesSheet(
                    directory = dir,
                    listDirectory = { d, p -> client.listDirectory(d, p) },
                    readFile = { d, p -> client.fileContent(d, p) },
                    onDismiss = { showFiles = false },
                )
            }
        }
    }

    if (showModelPicker) {
        ModelPickerSheet(
            providers = state.providers.map { group ->
                ProviderEntry(
                    id = group.id,
                    name = group.name,
                    connected = group.connected,
                    models = group.models.map { m ->
                        ProviderModelDto(id = m.modelId, name = m.modelName)
                    },
                )
            },
            currentProviderId = state.currentProviderId,
            currentModelId = state.currentModelId,
            onSelect = { providerId, modelId -> viewModel.setModel(providerId, modelId) },
            onDismiss = { showModelPicker = false },
        )
    }
}

private fun currentModelLabel(state: com.omniagent.mobile.app.ChatUiState): String =
    state.currentModelId ?: "model"

@Composable
private fun MessageList(messages: List<ChatMessage>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem((messages.size - 1).coerceAtLeast(0))
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(messages, key = { it.id }) { message ->
            MessageBubble(message)
        }
        if (messages.isEmpty()) {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.size(48.dp))
                    Text("No messages yet", color = LocalOmniColors.current.textFaint)
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val colors = LocalOmniColors.current
    val isUser = message.role == "user"
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        if (!isUser && message.activity.isNotEmpty()) {
            ToolActivityBlock(message.activity)
            Spacer(Modifier.size(8.dp))
        }
        if (message.text.isNotBlank()) {
            Box(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isUser) 16.dp else 5.dp,
                            bottomEnd = if (isUser) 5.dp else 16.dp,
                        )
                    )
                    .background(if (isUser) colors.accentDim else colors.bgInset)
                    .padding(horizontal = 13.dp, vertical = 9.dp),
            ) {
                Text(message.text, style = MaterialTheme.typography.bodyLarge, color = colors.text)
            }
        } else if (!isUser && message.streaming) {
            ThinkingDots()
        }
    }
}

@Composable
private fun ToolActivityBlock(tools: List<ToolStateDto>) {
    val colors = LocalOmniColors.current
    val latest = tools.takeLast(2)
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.bgInset.copy(alpha = 0.55f))
            .padding(horizontal = 11.dp, vertical = 7.dp),
    ) {
        latest.forEach { tool ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val dotColor = when {
                    tool.status == "completed" -> colors.green
                    tool.status == "error" -> colors.red
                    else -> colors.yellow
                }
                Box(
                    Modifier
                        .padding(end = 7.dp)
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                Text(
                    toolTitle(tool),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

private fun toolTitle(tool: ToolStateDto): String {
    val base = tool.title ?: tool.input?.toString() ?: ""
    return if (base.isBlank()) "${tool.status}…" else base.take(90)
}

@Composable
private fun ThinkingDots() {
    val colors = LocalOmniColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            Box(
                Modifier
                    .padding(end = 4.dp)
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(colors.textFaint.copy(alpha = 0.9f - index * 0.25f)),
            )
        }
    }
}

@Composable
private fun Composer(
    draft: String,
    enabled: Boolean,
    speech: SpeechManager,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
) {
    val colors = LocalOmniColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        VoiceButton(
            speech = speech,
            disabled = !enabled,
            onTranscribed = { text ->
                onDraftChange(if (draft.isBlank()) text else "$draft $text")
            },
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Spacer(Modifier.width(2.dp))
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = { Text("Guide your agent…", color = colors.textFaint) },
            modifier = Modifier.weight(1f).heightIn(min = 52.dp, max = 140.dp),
            shape = RoundedCornerShape(20.dp),
            maxLines = 5,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.accent,
                unfocusedBorderColor = colors.borderStrong,
                cursorColor = colors.accent,
                focusedContainerColor = colors.bgElev,
                unfocusedContainerColor = colors.bgElev,
            ),
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(
                    if (draft.isNotBlank()) {
                        Brush.linearGradient(listOf(colors.sendBackgroundTop, colors.sendBackgroundBottom))
                    } else {
                        Brush.linearGradient(listOf(colors.bgActivePill, colors.bgActivePill))
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(onClick = onSend, enabled = enabled && draft.isNotBlank()) {
                Icon(
                    Icons.AutoMirrored.Rounded.Send,
                    contentDescription = "Send",
                    tint = if (draft.isNotBlank()) colors.sendContent else colors.textFaint,
                )
            }
        }
    }
}
