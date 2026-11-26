package pl.agh.meditracker.feature.today

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import pl.agh.meditracker.model.Dose
import pl.agh.meditracker.model.Medication
import pl.agh.meditracker.ui.components.AlertTone
import pl.agh.meditracker.ui.components.AppAlert
import pl.agh.meditracker.ui.components.AppBottomSheet
import pl.agh.meditracker.ui.components.AppTextButton
import pl.agh.meditracker.ui.components.IconBadge
import pl.agh.meditracker.ui.components.NumberStepper
import pl.agh.meditracker.ui.components.PrimaryButton
import pl.agh.meditracker.ui.components.SecondaryButton
import pl.agh.meditracker.ui.theme.AppMotion
import pl.agh.meditracker.ui.theme.Warning
import pl.agh.meditracker.ui.theme.WarningContainer

private val snoozeOptions = listOf(10, 15, 30, 60)

@Composable
fun TodaySheets(
    sheet: TodaySheet?,
    onTake: (Dose) -> Unit,
    onSkip: (Dose) -> Unit,
    onOpenSnooze: (Dose) -> Unit,
    onSnooze: (Dose, Int) -> Unit,
    onOpenRefill: (Medication) -> Unit,
    onRefill: (Medication, Int) -> Unit,
    onDismiss: () -> Unit,
    defaultSnoozeMinutes: Int = 15,
) {
    if (sheet == null) return
    val title = when (sheet) {
        is TodaySheet.Confirm -> "Czas na lek"
        is TodaySheet.Snooze -> "Przypomnij później"
        is TodaySheet.LowStock -> "Kończy Ci się ${sheet.medication.name}"
        is TodaySheet.NoStock -> "Brak zapasu"
        is TodaySheet.Refill -> "Uzupełnij zapas"
    }
    // Keep one modal while switching its content. Replacing dialogs could dismiss
    // the new sheet through the outgoing dialog's asynchronous dismiss callback.
    // Treść przenika przy zmianie panelu, a wysokość arkusza dopasowuje AppBottomSheet (animateContentSize).
    val motion = AppMotion.current
    AppBottomSheet(onDismiss, title = title) {
        AnimatedContent<TodaySheet>(
            targetState = sheet,
            modifier = Modifier.fillMaxWidth(),
            transitionSpec = { motion.crossfade(animateSize = false) },
            contentKey = { it::class },
            label = "panelArkusza",
        ) { shown ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (shown) {
                    is TodaySheet.Confirm -> ConfirmSheet(shown.dose, onTake, onSkip, onOpenSnooze, onDismiss)
                    is TodaySheet.Snooze -> SnoozeSheet(shown.dose, onSnooze, onDismiss, defaultSnoozeMinutes)
                    is TodaySheet.LowStock -> LowStockSheet(shown.medication, onOpenRefill, onDismiss)
                    is TodaySheet.NoStock -> NoStockSheet(shown.medication, onOpenRefill, onDismiss)
                    is TodaySheet.Refill -> RefillSheet(shown.medication, onRefill, onDismiss)
                }
            }
        }
    }
}

@Composable
private fun Muted(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ConfirmSheet(
    dose: Dose,
    onTake: (Dose) -> Unit,
    onSkip: (Dose) -> Unit,
    onOpenSnooze: (Dose) -> Unit,
    onDismiss: () -> Unit,
) {
    val med = dose.medication
    val qty = med.schedule.doseQty
    Text("${med.name} ${med.strength}", style = MaterialTheme.typography.titleMedium)
    Muted("${med.unit.format(qty)} · planowana godzina ${dose.time.hhmm()}")
    Text(
        "Po potwierdzeniu zapas zmieni się z ${med.stock} na ${med.unit.format((med.stock - qty).coerceAtLeast(0))}.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PrimaryButton("Przyjęto", { onTake(dose) })
    SecondaryButton("Pomiń dawkę", { onSkip(dose) })
    AppTextButton("Przypomnij później", { onOpenSnooze(dose) }, Modifier.fillMaxWidth())
    AppTextButton("Anuluj", onDismiss, Modifier.fillMaxWidth())
}

@Composable
private fun SnoozeSheet(dose: Dose, onSnooze: (Dose, Int) -> Unit, onDismiss: () -> Unit, defaultMinutes: Int) {
    var minutes by remember { mutableIntStateOf(defaultMinutes) }
    Muted("Kiedy przypomnieć o tej dawce?")
    Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        snoozeOptions.forEach { option ->
            SnoozeChip("$option min", option == minutes, { minutes = option }, Modifier.weight(1f), spoken = "$option minut")
        }
    }
    Text(
        "Dawka pozostanie nieprzyjęta. Zapas się nie zmieni.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PrimaryButton("Zapisz · za $minutes minut", { onSnooze(dose, minutes) })
    SecondaryButton("Anuluj", onDismiss)
}

@Composable
private fun SnoozeChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier, spoken: String = text) {
    val colors = MaterialTheme.colorScheme
    // wybór jednej opcji: rola przycisku opcji i stan zaznaczenia, a nie tylko kolor wypełnienia
    val isSelected = selected
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 48.dp).semantics {
            role = Role.RadioButton
            this.selected = isSelected
            contentDescription = spoken
        },
        shape = CircleShape,
        color = if (selected) colors.primary else colors.surfaceContainer,
        contentColor = if (selected) colors.onPrimary else colors.primary,
    ) {
        Box(contentAlignment = Alignment.Center) { Text(text, style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
private fun LowStockSheet(med: Medication, onOpenRefill: (Medication) -> Unit, onDismiss: () -> Unit) {
    IconBadge(Icons.Outlined.WarningAmber, size = 56.dp, containerColor = WarningContainer, contentColor = Warning)
    Text("Pozostało ${med.unit.format(med.stock)}", style = MaterialTheme.typography.titleMedium)
    med.daysLeft?.let { Muted("To około ${daysText(it)} stosowania według Twojego harmonogramu.") }
    PrimaryButton("Uzupełnij zapas", { onOpenRefill(med) })
    SecondaryButton("Zamknij", onDismiss)
}

@Composable
private fun NoStockSheet(med: Medication, onOpenRefill: (Medication) -> Unit, onDismiss: () -> Unit) {
    val qty = med.schedule.doseQty
    val need = if (qty == 1) "Potrzebna ${med.unit.format(qty)}" else "Potrzebne: ${med.unit.format(qty)}"
    AppAlert("Nie masz wystarczającego zapasu", "$need. Zapisany stan: ${med.unit.format(med.stock)}.", AlertTone.Error)
    Muted("Uzupełnij zapisany stan, jeśli masz nowe opakowanie.")
    PrimaryButton("Uzupełnij zapas", { onOpenRefill(med) })
    SecondaryButton("Wróć", onDismiss)
}

@Composable
private fun RefillSheet(med: Medication, onRefill: (Medication, Int) -> Unit, onDismiss: () -> Unit) {
    var amount by remember { mutableIntStateOf(med.packageSize?.filter(Char::isDigit)?.toIntOrNull() ?: 30) }
    Muted("Ile ${med.unit.many} dodajesz do zapasu?")
    NumberStepper(amount.toString(), { amount = (amount - 1).coerceAtLeast(1) }, { amount++ })
    Text(
        "Nowy stan: ${med.unit.format(med.stock + amount)}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    PrimaryButton("Zapisz", { onRefill(med, amount) })
    SecondaryButton("Anuluj", onDismiss)
}
