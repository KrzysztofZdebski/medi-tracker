package pl.agh.meditracker.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pl.agh.meditracker.model.DoseStatus
import pl.agh.meditracker.ui.theme.AppMotion
import pl.agh.meditracker.ui.theme.Warning

@Composable
fun DoseCard(
    time: String,
    name: String,
    details: String,
    status: DoseStatus,
    onTake: () -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    highlighted: Boolean = false,
    // bezokolicznik, bo TalkBack czyta „Kliknij dwukrotnie, aby …”
    onClickLabel: String = "otworzyć szczegóły",
    onSkip: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val style = statusStyle(status)
    val motion = AppMotion.current
    // wyróżnienie przechodzi płynnie na następną dawkę
    val borderWidth by animateDpAsState(if (highlighted) 2.dp else 1.dp, motion.spec(), label = "grubośćObramowania")
    val borderColor by animateColorAsState(if (highlighted) colors.primary else colors.outlineVariant, motion.spec(), label = "kolorObramowania")
    val open = onClick
    val actionable = status == DoseStatus.Pending || status == DoseStatus.Snoozed
    // Karta to jeden węzeł TalkBack (Surface z onClick scala potomków): godzina, nazwa, dawka, a stan dawki
    // i wyróżnienie najbliższej dawki (dotąd tylko kolor ramki) jako stateDescription. Przyjęcie i pominięcie
    // są też akcjami niestandardowymi, żeby nie trzeba było szukać przycisku ani otwierać arkusza.
    val stateText = doseStateDescription(status) + (if (highlighted) ", najbliższa dawka" else "")
    val actions = buildList {
        if (actionable) add(CustomAccessibilityAction("Przyjmij") { onTake(); true })
        if (actionable && onSkip != null) add(CustomAccessibilityAction("Pomiń dawkę") { onSkip(); true })
    }
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().semantics {
            role = Role.Button
            stateDescription = stateText
            this.onClick(label = onClickLabel) { open(); true }
            if (actions.isNotEmpty()) customActions = actions
        },
        shape = MaterialTheme.shapes.medium,
        color = colors.surface,
        border = BorderStroke(borderWidth, borderColor),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusBadge(status)
                Column {
                    Text(time, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(details, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            // jedno wywołanie, żeby kolory przechodziły płynnie przy zmianie statusu; etykieta stanu nie jest osobnym
            // przyciskiem: dotknięcie trafia w kartę, a TalkBack czyta ją razem z kartą
            val pending = status == DoseStatus.Pending
            DoseAction(
                text = if (pending) "Przyjmij" else style.label,
                icon = if (pending) Icons.Outlined.Check else style.icon,
                container = if (pending) colors.primary else style.container,
                content = if (pending) colors.onPrimary else style.content,
                onClick = if (pending) onTake else null,
                onClickLabel = if (pending) "przyjąć dawkę: $name, $time" else null,
            )
        }
    }
}

/** Jak [IconBadge], ale zmiana statusu przenika kolorem, a ikona potwierdzenia lekko się powiększa. */
@Composable
private fun StatusBadge(status: DoseStatus) {
    val motion = AppMotion.current
    val container by animateColorAsState(statusStyle(status).container, motion.spec(), label = "tłoStatusu")
    Box(
        Modifier
            .size(48.dp)
            .clip(MaterialTheme.shapes.small)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = status,
            transitionSpec = { if (targetState == DoseStatus.Taken) motion.popIn() else motion.crossfade() },
            label = "ikonaStatusu",
        ) { shown ->
            val style = statusStyle(shown)
            Icon(style.icon, contentDescription = null, tint = style.content, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun DoseAction(text: String, icon: ImageVector, container: Color, content: Color, onClick: (() -> Unit)?, onClickLabel: String? = null) {
    val motion = AppMotion.current
    val containerColor by animateColorAsState(container, motion.spec(), label = "tłoAkcji")
    val contentColor by animateColorAsState(content, motion.spec(), label = "treśćAkcji")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(containerColor)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = contentColor)
    }
}

@OptIn(ExperimentalFoundationApi::class)
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
    // tryb zaznaczania na liście leków: null = zwykła karta ze strzałką, true/false = pole wyboru
    selected: Boolean? = null,
    // długie przytrzymanie (wejście w tryb zaznaczania); null = brak
    onLongClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.medium
    val borderColor = when {
        selected == true -> colors.primary
        stockLevel == StockLevel.Normal -> colors.outlineVariant
        stockLevel == StockLevel.Low -> Warning
        else -> colors.error
    }
    // poziom zapasu (kolor ramki) czyta ikona w StockIndicator, więc tu tylko rola, stan zaznaczenia i etykiety akcji
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                onClickLabel = if (selected != null) "zmienić zaznaczenie" else "otworzyć szczegóły leku",
                role = Role.Button,
                onLongClickLabel = if (onLongClick != null) "zaznaczyć" else null,
                onLongClick = onLongClick,
                onClick = onClick,
            )
            .then(if (selected != null) Modifier.semantics { this.selected = selected } else Modifier),
        shape = shape,
        color = if (selected == true) colors.secondaryContainer else colors.surface,
        border = BorderStroke(if (selected == true) 2.dp else 1.dp, borderColor),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Outlined.Medication)
                Column(Modifier.weight(1f)) {
                    Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                }
                // stan zaznaczenia czyta czytnik ekranu z semantyki karty, pole wyboru jest tylko obrazkiem
                if (selected != null) Checkbox(checked = selected, onCheckedChange = null)
                else Icon(
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
