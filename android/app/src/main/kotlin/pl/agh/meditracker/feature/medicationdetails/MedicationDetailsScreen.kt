package pl.agh.meditracker.feature.medicationdetails

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.agh.meditracker.core.appViewModel
import pl.agh.meditracker.data.repository.MedicationRepository
import pl.agh.meditracker.feature.addmedication.*
import pl.agh.meditracker.feature.history.HistoryCard
import pl.agh.meditracker.model.Medication
import pl.agh.meditracker.model.HistoryEntry
import pl.agh.meditracker.ui.components.*
import pl.agh.meditracker.R

class MedicationDetailsViewModel(val repository: MedicationRepository) : ViewModel()

@Composable
fun MedicationDetailsScreen(id: String, onBack: () -> Unit, onEdit: (Boolean) -> Unit) {
    val model = appViewModel { MedicationDetailsViewModel(it.medications) }
    val medications by model.repository.medications.collectAsStateWithLifecycle()
    val history by model.repository.history.collectAsStateWithLifecycle()
    val medication = medications.find { it.id == id }
    if (medication == null) {
        Column { AppTopBar("Szczegóły leku", onBack = onBack); Text("Lek nie jest już na Twojej liście.", Modifier.padding(20.dp)) }
        return
    }
    MedicationDetailsContent(medication, history, onBack, onEdit, { model.repository.refill(id, it) }, { model.repository.remove(id); onBack() })
}

@Composable
fun MedicationDetailsContent(
    medication: Medication,
    history: List<HistoryEntry>,
    onBack: () -> Unit,
    onEdit: (Boolean) -> Unit,
    onRefill: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val id = medication.id
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(colors.background)) {
        AppTopBar("Szczegóły leku", onBack = onBack, designIcons = true)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                DesignBadge(R.raw.design_pill_36, size = 64.dp, iconSize = 36.dp)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(medication.name, style = MaterialTheme.typography.headlineMedium)
                    Text("${medication.strength} · ${medication.form}", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                }
            }
            DetailsCard(spacing = 12.dp) {
                Text("Twój zapas", style = MaterialTheme.typography.titleMedium)
                StockIndicator(
                    medication.unit.format(medication.stock),
                    when { medication.stock == 0 -> StockLevel.Empty; medication.stock <= medication.refillThreshold -> StockLevel.Low; else -> StockLevel.Normal },
                    medication.stock.toFloat() / maxOf(initialStock(null, medication.packageSize.orEmpty()), medication.stock, 1),
                    medication.daysLeft?.let(::formatDaysLeft),
                    designIcons = true,
                )
                Text("Według zapisanego harmonogramu.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            PrimaryButton("Uzupełnij zapas", { sheet = "refill" })
            Text("Dawkowanie", style = MaterialTheme.typography.titleMedium)
            DetailsCard {
                Text("${medication.unit.format(medication.schedule.doseQty)} · ${formatDays(medication.schedule.days)}", style = MaterialTheme.typography.titleMedium)
                Text(formatTimes(medication.schedule.times), style = MaterialTheme.typography.titleLarge, color = colors.primary)
                val end = medication.schedule.endDate?.let { "do ${formatDate(it)}" } ?: "bez daty zakończenia"
                Text("Od ${formatDate(medication.schedule.startDate)} · $end", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            SecondaryButton("Edytuj harmonogram", { onEdit(true) })
            Text("Informacje o leku", style = MaterialTheme.typography.titleMedium)
            DetailsCard {
                Text(medication.manufacturer ?: "Producent nie został podany", color = colors.onSurfaceVariant)
                Text(medication.ean?.let { "EAN $it" } ?: "Kod EAN nie został podany", color = colors.onSurfaceVariant)
                medication.packageSize?.let { Text(it, color = colors.onSurfaceVariant) }
            }
            Text("Ostatnie przyjęcia", style = MaterialTheme.typography.titleMedium)
            val recent = history.filter { it.medication.id == id }.sortedByDescending { it.recordedAt }.take(3)
            if (recent.isEmpty()) DetailsCard { Text("Nie zapisano jeszcze przyjęć ani pominięć.", color = colors.onSurfaceVariant) }
            else HistoryCard(recent)
            SecondaryButton("Edytuj lek", { onEdit(false) })
            DestructiveButton("Usuń lek", { sheet = "delete" })
        }
    }
    when (sheet) {
        "refill" -> RefillSheet(medication, { onRefill(it); sheet = null }, { sheet = null })
        "delete" -> AppBottomSheet({ sheet = null }, title = "Usunąć lek?") {
            Text("${medication.name} ${medication.strength}", style = MaterialTheme.typography.titleMedium)
            Text("Lek zniknie z Twojej listy, a przyszłe przypomnienia zostaną wyłączone. Dotychczasowa historia pozostanie w dzienniku.", color = colors.onSurfaceVariant)
            PrimaryButton("Anuluj", { sheet = null })
            DestructiveButton("Usuń lek", { sheet = null; onRemove() })
        }
    }
}

@Composable
private fun DetailsCard(spacing: androidx.compose.ui.unit.Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
    }
}

@Composable
private fun RefillSheet(medication: Medication, onSave: (Int) -> Unit, onDismiss: () -> Unit) {
    var amount by rememberSaveable { mutableIntStateOf(initialStock(null, medication.packageSize.orEmpty()).coerceAtLeast(1)) }
    AppBottomSheet(onDismiss, title = "Uzupełnij zapas") {
        Text("${medication.name} ${medication.strength}", style = MaterialTheme.typography.titleMedium)
        Caption("Aktualnie: ${medication.unit.format(medication.stock)}")
        Caption("Ile ${medication.unit.many} dodajesz?")
        NumberStepper(amount.toString(), { amount = (amount - 1).coerceAtLeast(1) }, { amount = (amount + 1).coerceAtMost(99999) }, designIcons = true)
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Caption("Nowy stan")
                Text(medication.unit.format(medication.stock + amount), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
        PrimaryButton("Zapisz", { onSave(amount) })
        SecondaryButton("Anuluj", onDismiss)
    }
}
