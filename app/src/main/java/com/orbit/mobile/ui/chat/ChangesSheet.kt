package com.orbit.mobile.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.orbit.mobile.data.VcsDiffFileDto
import com.orbit.mobile.ui.theme.LocalOrbitColors

@Composable
fun ChangesSheet(
    changes: List<VcsDiffFileDto>,
    onDismiss: () -> Unit,
) {
    val colors = LocalOrbitColors.current
    var selected by remember { mutableStateOf<VcsDiffFileDto?>(null) }

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
                modifier = Modifier.padding(horizontal = 18.dp),
            ) {
                Text(
                    if (selected == null) "Changes (${changes.size})" else selected!!.file,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    if (selected != null) selected = null else onDismiss()
                }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close", tint = colors.textDim)
                }
            }
            Spacer(Modifier.height(8.dp))
            val current = selected
            if (current == null) {
                if (changes.isEmpty()) {
                    Text(
                        "No uncommitted changes in this workspace yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textFaint,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        items(changes.size) { index ->
                            val change = changes[index]
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selected = change }
                                    .padding(horizontal = 18.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    change.file,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.padding(start = 8.dp))
                                Text(
                                    "+${change.additions}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.green,
                                )
                                Spacer(Modifier.padding(start = 6.dp))
                                Text(
                                    "-${change.deletions}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.red,
                                )
                            }
                        }
                    }
                }
            } else {
                DiffPatchView(current.patch)
            }
        }
    }
}

@Composable
fun DiffPatchView(patch: String) {
    val colors = LocalOrbitColors.current
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 420.dp)
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(colors.bgInset),
    ) {
        val lines = patch.lines()
        items(lines.size) { index ->
            val line = lines[index]
            val bg = when {
                line.startsWith("+") && !line.startsWith("+++") -> colors.green.copy(alpha = 0.13f)
                line.startsWith("-") && !line.startsWith("---") -> colors.red.copy(alpha = 0.13f)
                else -> Color.Transparent
            }
            val fg = when {
                line.startsWith("+") && !line.startsWith("+++") -> colors.green
                line.startsWith("-") && !line.startsWith("---") -> colors.red
                line.startsWith("@@") -> colors.sky
                else -> colors.textDim
            }
            Box(Modifier.background(bg)) {
                Text(
                    line.ifBlank { " " },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = fg,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 1.dp),
                    maxLines = 4,
                    overflow = TextOverflow.Clip,
                )
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}
