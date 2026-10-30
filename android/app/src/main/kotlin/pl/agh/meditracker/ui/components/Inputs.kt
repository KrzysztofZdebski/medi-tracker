package pl.agh.meditracker.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import pl.agh.meditracker.R

@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    enabled: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    readOnly: Boolean = false,
    onClick: (() -> Unit)? = null,
    placeholder: String? = null,
    trailingIcon: ImageVector? = null,
    tall: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    val borderColor = when {
        !enabled -> colors.outlineVariant
        error != null -> colors.error
        focused -> colors.primary
        else -> colors.outline
    }
    val labelColor = when {
        error != null -> colors.error
        focused -> colors.primary
        else -> colors.onSurfaceVariant
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                readOnly = readOnly || onClick != null,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
                ),
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                singleLine = true,
                interactionSource = interaction,
                cursorBrush = SolidColor(colors.primary),
                decorationBox = { inner ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = if (tall) 80.dp else 64.dp)
                            .clip(shape)
                            .background(if (enabled) colors.surface else colors.surfaceContainer)
                            .border(if (focused || error != null) 2.dp else 1.dp, borderColor, shape)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.bodyMedium, color = labelColor)
                            Box {
                                if (value.isEmpty() && placeholder != null) {
                                    Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                                }
                                inner()
                            }
                        }
                        if (trailingIcon != null) {
                            Icon(trailingIcon, contentDescription = null, tint = colors.onSurfaceVariant)
                        }
                    }
                },
            )
            if (onClick != null && enabled) {
                Box(
                    Modifier
                        .matchParentSize()
                        .clip(shape)
                        .clickable(onClick = onClick),
                )
            }
        }
        if (error != null) {
            Text(
                error,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}

@Composable
fun NumberStepper(
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    designIcons: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    Row(
        modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(shape)
            .background(colors.surface)
            .then(if (designIcons) Modifier else Modifier.border(if (isError) 2.dp else 1.dp, if (isError) colors.error else colors.outline, shape))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val buttonModifier = if (designIcons) Modifier.size(48.dp).padding(6.dp).background(colors.surfaceContainer, MaterialTheme.shapes.extraSmall) else Modifier
        IconButton(onClick = onMinus, modifier = buttonModifier) {
            if (designIcons) DesignIcon(R.raw.design_minus_24, 24.dp, "Zmniejsz")
            else Icon(Icons.Outlined.Remove, contentDescription = "Zmniejsz", tint = colors.primary)
        }
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
            color = if (isError) colors.error else colors.onSurface,
            textAlign = TextAlign.Center,
        )
        IconButton(onClick = onPlus, modifier = buttonModifier) {
            if (designIcons) DesignIcon(R.raw.design_plus_24, 24.dp, "Zwiększ")
            else Icon(Icons.Outlined.Add, contentDescription = "Zwiększ", tint = colors.primary)
        }
    }
}

@Composable
fun TimeChip(
    time: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onEdit: (() -> Unit)? = null,
    designIcons: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier.height(if (designIcons) 64.dp else 48.dp),
        shape = if (designIcons) MaterialTheme.shapes.small else CircleShape,
        color = if (selected) colors.primaryContainer else colors.surface,
        contentColor = if (selected) colors.onPrimaryContainer else colors.onSurface,
        border = BorderStroke(1.dp, if (selected) colors.primary else colors.outlineVariant),
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = if (onEdit != null) 4.dp else 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (designIcons) DesignIcon(R.raw.design_clock_24, 24.dp)
            else Icon(Icons.Outlined.Schedule, contentDescription = null, tint = colors.primary, modifier = Modifier.size(20.dp))
            Text(time, style = MaterialTheme.typography.labelLarge)
            if (onEdit != null) {
                IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
                    if (designIcons) DesignIcon(R.raw.design_edit_20, 20.dp, "Edytuj")
                    else Icon(Icons.Outlined.Edit, contentDescription = "Edytuj", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

private val dayLabels = listOf("Pn", "Wt", "Śr", "Cz", "Pt", "So", "Nd")

@Composable
fun DaySelector(
    selected: Set<DayOfWeek>,
    onToggle: (DayOfWeek) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        DayOfWeek.entries.forEachIndexed { index, day ->
            val isSelected = day in selected
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) colors.primary else colors.surface)
                    .border(1.dp, if (isSelected) colors.primary else colors.outlineVariant, CircleShape)
                    .clickable { onToggle(day) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    dayLabels[index],
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) colors.onPrimary else colors.onSurface,
                )
            }
        }
    }
}
