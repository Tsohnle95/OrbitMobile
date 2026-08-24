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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.orbit.mobile.ui.theme.LocalOrbitColors

data class ProviderEntry(
    val id: String,
    val name: String,
    val connected: Boolean,
    val models: List<ProviderModelRow>,
)

data class ProviderModelRow(
    val providerId: String,
    val modelName: String,
    val modelId: String,
)

@Composable
private fun colorsTextDim() = LocalOrbitColors.current.textDim

@Composable
fun ModelPickerSheet(
    providers: List<ProviderEntry>,
    currentProviderId: String?,
    currentModelId: String?,
    onSelect: (providerId: String, modelId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = LocalOrbitColors.current
    var expandedProvider by remember { mutableStateOf(currentProviderId) }

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
                    "Change model",
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.text,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close", tint = colors.textDim)
                }
            }
            Spacer(Modifier.height(8.dp))
            if (providers.isEmpty()) {
                Text(
                    "No providers loaded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textFaint,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
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
}

@Composable
private fun ProviderRow(
    provider: ProviderEntry,
    expanded: Boolean,
    currentModelId: String?,
    onToggle: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val colors = LocalOrbitColors.current
    val currentProviderId = provider.models.firstOrNull { it.modelId == currentModelId }?.providerId
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
            Spacer(Modifier.size(10.dp))
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = colors.textFaint,
            )
        }
        if (expanded) {
            provider.models.forEach { model ->
                val selected = model.providerId == currentProviderId && model.modelId == currentModelId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(model.modelId) }
                        .padding(start = 26.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(if (selected) colors.accent else colors.borderStrong),
                    )
                    Spacer(Modifier.size(9.dp))
                    Text(
                        model.modelName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selected) colors.accent else colors.textDim,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
