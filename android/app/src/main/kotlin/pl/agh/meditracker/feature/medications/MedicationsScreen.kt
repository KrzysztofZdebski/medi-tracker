package pl.agh.meditracker.feature.medications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.agh.meditracker.core.appViewModel
import pl.agh.meditracker.ui.components.AlertTone
import pl.agh.meditracker.ui.components.AppAlert
import pl.agh.meditracker.ui.components.AppTopBar
import pl.agh.meditracker.ui.components.EmptyState
import pl.agh.meditracker.ui.components.MedicationCard
import pl.agh.meditracker.ui.components.NotificationsAction
import pl.agh.meditracker.ui.components.PrimaryButton
import pl.agh.meditracker.ui.components.SecondaryButton

@Composable
fun MedicationsScreen(justAdded: Boolean, onAddMedication: () -> Unit, onOpenToday: () -> Unit) {
    val viewModel = appViewModel { container -> MedicationsViewModel(container.medications) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    MedicationsContent(state, justAdded, onAddMedication, onOpenToday)
}

@Composable
fun MedicationsContent(
    state: MedicationsUiState,
    justAdded: Boolean,
    onAddMedication: () -> Unit,
    onOpenToday: () -> Unit,
    onOpenMedication: (String) -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        AppTopBar("Moje leki", actions = { NotificationsAction {} })
        when {
            state.loading -> Unit
            state.items.isEmpty() -> EmptyState(
                icon = Icons.Outlined.Inventory2,
                title = "Twoja apteczka\nw jednym miejscu",
                message = "Zeskanuj opakowanie lub wpisz dane leku. Potem ustaw dawkowanie i zapas.",
                actionText = "+ Dodaj lek",
                onAction = onAddMedication,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 48.dp),
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item {
                    if (justAdded) {
                        AppAlert("Lek dodany", "Harmonogram i zapas zostały zapisane.", AlertTone.Success)
                    } else {
                        Text(
                            state.countText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item { PrimaryButton("+ Dodaj lek", onAddMedication) }
                items(state.items, key = { it.id }) { item ->
                    MedicationCard(
                        name = item.name,
                        subtitle = item.subtitle,
                        scheduleText = item.scheduleText,
                        stockText = item.stockText,
                        stockLevel = item.stockLevel,
                        stockProgress = item.stockProgress,
                        daysText = item.daysText,
                        onClick = { onOpenMedication(item.id) },
                    )
                }
                if (justAdded) {
                    item { SecondaryButton("Przejdź do dzisiejszego planu", onOpenToday) }
                }
            }
        }
    }
}
