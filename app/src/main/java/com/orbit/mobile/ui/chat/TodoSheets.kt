package com.orbit.mobile.ui.chat

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.orbit.mobile.data.TodoItemDto
import com.orbit.mobile.ui.theme.LocalOrbitColors

@Composable
fun TodoStrip(todos: List<TodoItemDto>, onExpand: () -> Unit) {
    val colors = LocalOrbitColors.current
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
                Text(
                    "●",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.yellow,
                )
                Spacer(Modifier.padding(start = 6.dp))
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
fun TodosSheet(todos: List<TodoItemDto>, onDismiss: () -> Unit) {
    val colors = LocalOrbitColors.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(20.dp))
                .padding(horizontal = 18.dp, vertical = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Todos",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.text,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close", tint = colors.textDim)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (todos.isEmpty()) {
                Text(
                    "No todos yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textFaint,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
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
                            Text(
                                when (todo.status) {
                                    "completed" -> "✓"
                                    "in_progress" -> "●"
                                    else -> "○"
                                },
                                style = MaterialTheme.typography.titleMedium,
                                color = when (todo.status) {
                                    "completed" -> colors.accent
                                    "in_progress" -> colors.yellow
                                    else -> colors.textFaint
                                },
                            )
                            Spacer(Modifier.padding(start = 10.dp))
                            Text(
                                todo.content,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (done) colors.textFaint else colors.text,
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
}
