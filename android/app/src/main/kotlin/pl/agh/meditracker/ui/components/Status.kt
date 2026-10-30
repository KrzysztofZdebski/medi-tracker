package pl.agh.meditracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Snooze
import androidx.compose.material.icons.outlined.WarningAmber
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import pl.agh.meditracker.model.DoseStatus
import pl.agh.meditracker.ui.theme.ErrorContainer
import pl.agh.meditracker.ui.theme.Line
import pl.agh.meditracker.ui.theme.Success
import pl.agh.meditracker.ui.theme.SuccessContainer
import pl.agh.meditracker.ui.theme.Warning
import pl.agh.meditracker.ui.theme.WarningContainer
import pl.agh.meditracker.R

enum class StockLevel { Normal, Low, Empty }

enum class AlertTone { Info, Warning, Error, Success }

internal data class StatusStyle(val label: String, val icon: ImageVector, val container: Color, val content: Color)

@Composable
internal fun statusStyle(status: DoseStatus): StatusStyle {
    val colors = MaterialTheme.colorScheme
    return when (status) {
        DoseStatus.Pending -> StatusStyle("Oczekuje", Icons.Outlined.Schedule, colors.surfaceContainer, colors.onSurfaceVariant)
        DoseStatus.Taken -> StatusStyle("Przyjęto", Icons.Outlined.Check, SuccessContainer, Success)
        DoseStatus.Skipped -> StatusStyle("Pominięto", Icons.Outlined.Close, colors.surfaceContainer, colors.onSurfaceVariant)
        DoseStatus.Snoozed -> StatusStyle("Odroczono", Icons.Outlined.Snooze, colors.primaryContainer, colors.primary)
    }
}

@Composable
fun AppChip(
    text: String,
    icon: ImageVector?,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(containerColor)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(18.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = contentColor)
    }
}

@Composable
fun StatusChip(status: DoseStatus, modifier: Modifier = Modifier) {
    val style = statusStyle(status)
    AppChip(style.label, style.icon, style.container, style.content, modifier)
}

@Composable
fun WarningChip(text: String = "Niski zapas", modifier: Modifier = Modifier) {
    AppChip(text, Icons.Outlined.WarningAmber, WarningContainer, Warning, modifier)
}

@Composable
fun StockIndicator(
    text: String,
    level: StockLevel,
    progress: Float,
    daysText: String?,
    modifier: Modifier = Modifier,
    designIcons: Boolean = false,
) {
    val color = when (level) {
        StockLevel.Normal -> MaterialTheme.colorScheme.primary
        StockLevel.Low -> Warning
        StockLevel.Empty -> MaterialTheme.colorScheme.error
    }
    val textColor = if (designIcons) color else if (level == StockLevel.Normal) MaterialTheme.colorScheme.onSurface else color
    val icon = when (level) {
        StockLevel.Normal -> Icons.Outlined.Inventory2
        StockLevel.Low -> Icons.Outlined.WarningAmber
        StockLevel.Empty -> Icons.Outlined.ErrorOutline
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (designIcons && level == StockLevel.Normal) DesignIcon(R.raw.design_box_20, 20.dp)
            else Icon(icon, contentDescription = null, tint = textColor, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.bodyLarge, color = textColor)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(if (designIcons) 6.dp else 8.dp)
                .clip(CircleShape)
                .background(Line),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(if (designIcons) 6.dp else 8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
        if (daysText != null) {
            Text(daysText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun AppAlert(title: String, message: String?, tone: AlertTone, modifier: Modifier = Modifier, designIcons: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val (container, content, icon) = when (tone) {
        AlertTone.Info -> Triple(colors.primaryContainer, colors.primary, Icons.Outlined.Info)
        AlertTone.Warning -> Triple(WarningContainer, Warning, Icons.Outlined.WarningAmber)
        AlertTone.Error -> Triple(ErrorContainer, colors.error, Icons.Outlined.ErrorOutline)
        AlertTone.Success -> Triple(SuccessContainer, Success, Icons.Outlined.CheckCircle)
    }
    Surface(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = container) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (designIcons && tone == AlertTone.Info) DesignIcon(R.raw.design_info_24, 24.dp)
            else Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = content)
                if (message != null) {
                    Text(message, style = MaterialTheme.typography.bodyMedium, color = if (designIcons) content else colors.onSurface)
                }
            }
        }
    }
}

@Composable
fun IconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.primary,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(if (size >= 72.dp) MaterialTheme.shapes.large else MaterialTheme.shapes.small)
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(size / 2))
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        IconBadge(icon, size = 88.dp)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (actionText != null) PrimaryButton(actionText, onAction, Modifier.padding(top = 8.dp))
    }
}
