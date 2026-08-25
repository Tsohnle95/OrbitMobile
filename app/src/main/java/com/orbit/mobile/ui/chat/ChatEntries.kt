package com.orbit.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.orbit.mobile.app.ChatMessage
import com.orbit.mobile.data.ToolStateDto
import com.orbit.mobile.ui.theme.LocalOrbitColors

@Composable
fun MessageEntry(message: ChatMessage) {
    when {
        message.role == "tool" && message.activity.isNotEmpty() ->
            ToolEntry(message.activity.first(), message.toolName)
        message.role == "reasoning" -> ReasoningEntry(message.text)
        else -> MessageBubble(message)
    }
}

@Composable
fun MessageBubble(message: ChatMessage) {
    val colors = LocalOrbitColors.current
    val isUser = message.role == "user"
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
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
            Text("thinking…", style = MaterialTheme.typography.bodySmall, color = colors.textFaint)
        }
    }
}

@Deprecated("Legacy combined block; kept until fully removed")
fun toolTitle(tool: ToolStateDto): String = toolTitle(tool, null)
