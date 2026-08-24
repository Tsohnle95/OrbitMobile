package com.orbit.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.orbit.mobile.data.PermissionRequestDto
import com.orbit.mobile.ui.theme.LocalOrbitColors

@Composable
fun PermissionCard(
    permission: PermissionRequestDto,
    onReply: (String) -> Unit,
) {
    val colors = LocalOrbitColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.yellow.copy(alpha = 0.12f))
            .padding(14.dp),
    ) {
        Text(
            "Permission requested",
            style = MaterialTheme.typography.labelLarge,
            color = colors.yellow,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            permission.patterns.take(3).joinToString().ifBlank { permission.permission },
            style = MaterialTheme.typography.bodySmall,
            color = colors.textDim,
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { onReply("once") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(9.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = colors.sendContent),
            ) { Text("Allow once") }
            Spacer(Modifier.height(0.dp))
            OutlinedButton(
                onClick = { onReply("always") },
                modifier = Modifier.weight(1f).padding(start = 8.dp),
                shape = RoundedCornerShape(9.dp),
            ) { Text("Always") }
            OutlinedButton(
                onClick = { onReply("reject") },
                modifier = Modifier.weight(1f).padding(start = 8.dp),
                shape = RoundedCornerShape(9.dp),
            ) { Text("Deny") }
        }
    }
}
