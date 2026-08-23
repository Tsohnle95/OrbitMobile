package com.omniagent.mobile.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.omniagent.mobile.data.TodoItemDto
import com.omniagent.mobile.ui.theme.LocalOmniColors

private fun todoColor(status: String) = when (status) {
    "completed" -> "done"
    "in_progress" -> "active"
    else -> "pending"
}

@Composable
fun TodoStrip(todos: List<TodoItemDto>, onExpand: () -> Unit) {
    val colors = LocalOmniColors.current
    val done = todos.count { it.status == "completed" }
    val active = todos.firstOrNull { it.status == "in_progress" }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(colors.bgInset.copy(alpha = 0.55f))
            .clickable { onExpand() }
            .padding(horizontal = 11.dp, vertical = 7.dp),
    ) {
        Text(
            "Todos $done/${todos.size}",
            style = MaterialTheme.typography.labelMedium,
            color = colors.textFaint,
        )
        active?.let { todo ->
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulsingDot()
                Spacer(Modifier.size(7.dp))
                Text(
                    todo.content,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textDim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PulsingDot() {
    val colors = LocalOmniColors.current
    androidx.compose.foundation.layout.Box(
        Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(colors.yellow),
    )
}

@Composable
fun TodosSheet(todos: List<TodoItemDto>, onDismiss: () -> Unit) {
    val colors = LocalOmniColors.current
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
            Text("Todos", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.heightIn(max = 440.dp)) {
                items(todos.size) { index ->
                    val todo = todos[index]
                    val done = todo.status == "completed"
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(
                                    when (todo.status) {
                                        "completed" -> colors.accent
                                        "in_progress" -> colors.yellow.copy(alpha = 0.25f)
                                        else -> colors.bgActivePill
                                    }
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (done) {
                                Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = colors.sendContent,
                                    modifier = Modifier.size(13.dp),
                                )
                            }
                        }
                        Spacer(Modifier.size(10.dp))
                        Text(
                            todo.content,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (done) colors.textFaint else colors.text,
                            textDecoration = if (done) TextDecoration.LineThrough else null,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}
