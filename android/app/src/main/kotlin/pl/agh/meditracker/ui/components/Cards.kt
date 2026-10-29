package pl.agh.meditracker.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pl.agh.meditracker.model.DoseStatus
import pl.agh.meditracker.ui.theme.Warning

@Composable
fun DoseCard(
    time: String,
    name: String,
    details: String,
    status: DoseStatus,
    onTake: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    highlighted: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val style = statusStyle(status)
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colors.surface,
        border = BorderStroke(if (highlighted) 2.dp else 1.dp, if (highlighted) colors.primary else colors.outlineVariant),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(style.icon, containerColor = style.container, contentColor = style.content)
                Column {
                    Text(time, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(details, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            if (status == DoseStatus.Pending) {
                DoseAction("Przyjmij", Icons.Outlined.Check, colors.primary, colors.onPrimary, onTake)
            } else {
                DoseAction(style.label, style.icon, style.container, style.content, null)
            }
        }
    }
}

@Composable
private fun DoseAction(text: String, icon: ImageVector, container: Color, content: Color, onClick: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(CircleShape)
            .background(container)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = content)
    }
}

@Composable
fun MedicationCard(
    name: String,
    subtitle: String,
    scheduleText: String,
    stockText: String,
    stockLevel: StockLevel,
    stockProgress: Float,
    daysText: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val borderColor = when (stockLevel) {
        StockLevel.Normal -> colors.outlineVariant
        StockLevel.Low -> Warning
        StockLevel.Empty -> colors.error
    }
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colors.surface,
        border = BorderStroke(1.dp, borderColor),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Outlined.Medication)
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                }
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.onSurfaceVariant,
                )
            }
            Text(scheduleText, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            StockIndicator(stockText, stockLevel, stockProgress, daysText)
        }
    }
}
