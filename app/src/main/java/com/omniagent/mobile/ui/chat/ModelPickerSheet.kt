package com.omniagent.mobile.ui.chat

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.omniagent.mobile.data.ProviderModelDto
import com.omniagent.mobile.ui.theme.LocalOmniColors

data class ProviderEntry(
    val id: String,
    val name: String,
    val connected: Boolean,
    val models: List<ProviderModelDto>,
)

@Composable
fun ModelPickerSheet(
    providers: List<ProviderEntry>,
    currentProviderId: String?,
    currentModelId: String?,
    onSelect: (providerId: String, modelId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var expandedProvider by remember { mutableStateOf(currentProviderId) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, androidx.compose.foundation.shape.RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .padding(horizontal = 18.dp, vertical = 20.dp),
        ) {
            Text("Change model", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.heightIn(max = 520.dp)) {
                items(providers.size) { index ->
                    val provider = providers[index]
                    ProviderRow(
                        provider = provider,
                        expanded = expandedProvider == provider.id,
                        currentModelId = if (provider.id == currentProviderId) currentModelId else null,
                        onToggle = {
                            expandedProvider = if (expandedProvider == provider.id) null else provider.id
                        },
                        onSelect = { mid ->
                            onSelect(provider.id, mid)
                            onDismiss()
                        },
                    )
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun ProviderRow(
    provider: ProviderEntry,
    expanded: Boolean,
    currentModelId: String?,
    onToggle: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val colors = LocalOmniColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (provider.connected) colors.green else colors.borderStrong),
            )
            Spacer(Modifier.size(9.dp))
            Text(
                provider.name,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${provider.models.size}",
                style = MaterialTheme.typography.labelMedium,
                color = colors.textFaint,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(2.dp))
            provider.models.sortedBy { it.id }.forEach { model ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(model.id) }
                        .padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        model.name?.takeIf { it.isNotBlank() } ?: model.id,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (model.id == currentModelId) colors.accent else colors.textDim,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (model.id == currentModelId) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
    }
}
