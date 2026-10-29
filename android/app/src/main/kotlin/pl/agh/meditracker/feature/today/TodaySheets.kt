package pl.agh.meditracker.feature.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
) {
    when (sheet) {
        is TodaySheet.Confirm -> ConfirmSheet(sheet.dose, onTake, onSkip, onOpenSnooze, onDismiss)
        is TodaySheet.Snooze -> SnoozeSheet(sheet.dose, onSnooze, onDismiss)
        is TodaySheet.LowStock -> LowStockSheet(sheet.medication, onOpenRefill, onDismiss)
        is TodaySheet.NoStock -> NoStockSheet(sheet.medication, onOpenRefill, onDismiss)
        is TodaySheet.Refill -> RefillSheet(sheet.medication, onRefill, onDismiss)
        null -> Unit
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
    AppBottomSheet(onDismiss, title = "Czas na lek") {
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
}

@Composable
private fun SnoozeSheet(dose: Dose, onSnooze: (Dose, Int) -> Unit, onDismiss: () -> Unit) {
    var minutes by remember { mutableIntStateOf(15) }
    AppBottomSheet(onDismiss, title = "Przypomnij później") {
        Muted("Kiedy przypomnieć o tej dawce?")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            snoozeOptions.forEach { option ->
                SnoozeChip("$option min", option == minutes, { minutes = option }, Modifier.weight(1f))
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
}

@Composable
private fun SnoozeChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        shape = CircleShape,
        color = if (selected) colors.primary else colors.surfaceContainer,
        contentColor = if (selected) colors.onPrimary else colors.primary,
    ) {
        Box(contentAlignment = Alignment.Center) { Text(text, style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
private fun LowStockSheet(med: Medication, onOpenRefill: (Medication) -> Unit, onDismiss: () -> Unit) {
    AppBottomSheet(onDismiss, title = "Kończy Ci się ${med.name}") {
        IconBadge(Icons.Outlined.WarningAmber, size = 56.dp, containerColor = WarningContainer, contentColor = Warning)
        Text("Pozostało ${med.unit.format(med.stock)}", style = MaterialTheme.typography.titleMedium)
        med.daysLeft?.let { Muted("To około ${daysText(it)} stosowania według Twojego harmonogramu.") }
        PrimaryButton("Uzupełnij zapas", { onOpenRefill(med) })
        SecondaryButton("Przypomnij później", onDismiss)
    }
}

@Composable
private fun NoStockSheet(med: Medication, onOpenRefill: (Medication) -> Unit, onDismiss: () -> Unit) {
    val qty = med.schedule.doseQty
    val need = if (qty == 1) "Potrzebna ${med.unit.format(qty)}" else "Potrzebne: ${med.unit.format(qty)}"
    AppBottomSheet(onDismiss, title = "Brak zapasu") {
        AppAlert("Nie masz wystarczającego zapasu", "$need. Zapisany stan: ${med.unit.format(med.stock)}.", AlertTone.Error)
        Muted("Uzupełnij zapisany stan, jeśli masz nowe opakowanie.")
        PrimaryButton("Uzupełnij zapas", { onOpenRefill(med) })
        SecondaryButton("Wróć", onDismiss)
    }
}

@Composable
private fun RefillSheet(med: Medication, onRefill: (Medication, Int) -> Unit, onDismiss: () -> Unit) {
    var amount by remember { mutableIntStateOf(med.packageSize?.filter(Char::isDigit)?.toIntOrNull() ?: 30) }
    AppBottomSheet(onDismiss, title = "Uzupełnij zapas") {
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
}
