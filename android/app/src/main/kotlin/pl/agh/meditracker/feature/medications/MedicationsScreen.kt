package pl.agh.meditracker.feature.medications

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.agh.meditracker.core.appViewModel
import pl.agh.meditracker.ui.components.AlertTone
import pl.agh.meditracker.ui.components.AppAlert
import pl.agh.meditracker.ui.components.AppBottomSheet
import pl.agh.meditracker.ui.components.AppSnackbarHost
import pl.agh.meditracker.ui.components.AppTopBar
import pl.agh.meditracker.ui.components.DestructiveButton
import pl.agh.meditracker.ui.components.EmptyState
import pl.agh.meditracker.ui.components.MedicationCard
import pl.agh.meditracker.ui.components.NotificationsAction
import pl.agh.meditracker.ui.components.PrimaryButton
import pl.agh.meditracker.ui.components.SecondaryButton
import pl.agh.meditracker.ui.theme.AppMotion
import pl.agh.meditracker.ui.theme.itemMotion

// [removedInDetails]: id leków usuniętych na ekranie szczegółów (wynik nawigacji). Po przekazaniu ich do ViewModelu
// ekran woła [onRemovedShown], żeby kolejny powrót na listę nie pokazał snackbara drugi raz.
@Composable
fun MedicationsScreen(
    justAdded: Boolean,
    onAddMedication: () -> Unit,
    onOpenToday: () -> Unit,
    onOpenMedication: (String) -> Unit,
    onNotifications: () -> Unit,
    removedInDetails: List<String>?,
    onRemovedShown: () -> Unit,
) {
    val viewModel = appViewModel { container -> MedicationsViewModel(container.medications) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(removedInDetails) {
        if (removedInDetails == null) return@LaunchedEffect
        viewModel.showRemoved(removedInDetails)
        onRemovedShown()
    }
    val notice = state.removal
    LaunchedEffect(notice) {
        if (notice == null) return@LaunchedEffect
        val result = snackbar.showSnackbar(notice.message, "Cofnij", duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) viewModel.undo(notice) else viewModel.clearRemoval(notice)
    }
    // ViewModel przeżywa zmianę zakładki; po powrocie nie pokazujemy starego „Cofnij”
    val latest by rememberUpdatedState(notice)
    DisposableEffect(Unit) {
        onDispose { latest?.let(viewModel::clearRemoval) }
    }
    MedicationsContent(
        state = state,
        justAdded = justAdded,
        snackbar = snackbar,
        onAddMedication = onAddMedication,
        onOpenToday = onOpenToday,
        onOpenMedication = onOpenMedication,
        onNotifications = onNotifications,
        onRemove = viewModel::remove,
        onToggle = viewModel::toggle,
        onSelectAll = viewModel::selectAll,
        onClearSelection = viewModel::clearSelection,
        onRemoveSelected = viewModel::removeSelected,
    )
}

@Composable
fun MedicationsContent(
    state: MedicationsUiState,
    justAdded: Boolean,
    snackbar: SnackbarHostState,
    onAddMedication: () -> Unit,
    onOpenToday: () -> Unit,
    onOpenMedication: (String) -> Unit,
    onNotifications: () -> Unit,
    onRemove: (String) -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onRemoveSelected: () -> Unit,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    val selecting = state.selecting
    val motion = AppMotion.current
    // Wstecz w trybie zaznaczania tylko go zamyka
    BackHandler(enabled = selecting, onBack = onClearSelection)
    Scaffold(
        topBar = {
            if (selecting) {
                AppTopBar("Zaznaczono: ${state.selected.size}", onClose = onClearSelection, actions = {
                    IconButton(onClick = onSelectAll) { Icon(Icons.Outlined.SelectAll, contentDescription = "Zaznacz wszystkie") }
                    IconButton(onClick = { confirming = true }) { Icon(Icons.Outlined.Delete, contentDescription = "Usuń zaznaczone") }
                })
            } else {
                AppTopBar("Moje leki", actions = { NotificationsAction(onClick = onNotifications) })
            }
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
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
                        SwipeToDelete(
                            enabled = !selecting,
                            restoreCount = state.restoreCount,
                            onDelete = { onRemove(item.id) },
                            // jak na Dzisiaj: animateItem, ale bez animacji przy „Usuń animacje”
                            modifier = itemMotion(motion),
                        ) {
                            MedicationCard(
                                name = item.name,
                                subtitle = item.subtitle,
                                scheduleText = item.scheduleText,
                                stockText = item.stockText,
                                stockLevel = item.stockLevel,
                                stockProgress = item.stockProgress,
                                daysText = item.daysText,
                                onClick = { if (selecting) onToggle(item.id) else onOpenMedication(item.id) },
                                selected = if (selecting) item.id in state.selected else null,
                                onLongClick = { onToggle(item.id) },
                                // alternatywa dla przesunięcia: akcja „Usuń” w menu czytnika ekranu
                                modifier = if (selecting) Modifier else Modifier.semantics {
                                    customActions = listOf(CustomAccessibilityAction("Usuń") { onRemove(item.id); true })
                                },
                            )
                        }
                    }
                    if (justAdded) {
                        item { SecondaryButton("Przejdź do dzisiejszego planu", onOpenToday) }
                    }
                }
            }
        }
    }
    if (confirming && selecting) {
        val count = state.selected.size
        val one = count == 1
        AppBottomSheet({ confirming = false }, title = "Usunąć $count ${medicationsWord(count)}?") {
            Text(
                if (one) "Lek zniknie z Twojej listy, a przyszłe przypomnienia zostaną wyłączone. Dotychczasowa historia pozostanie w dzienniku."
                else "Leki znikną z Twojej listy, a przyszłe przypomnienia zostaną wyłączone. Dotychczasowa historia pozostanie w dzienniku.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrimaryButton("Anuluj", { confirming = false })
            DestructiveButton(if (one) "Usuń lek" else "Usuń leki", { confirming = false; onRemoveSelected() })
        }
    }
}

// Przesunięcie karty od prawej do lewej odsłania czerwone tło z „Usuń” i od razu usuwa lek (snackbar daje „Cofnij”).
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDelete(
    enabled: Boolean,
    restoreCount: Int,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // Zwykłe remember, nie rememberSwipeToDismissBoxState: tamten stan jest zapisywany pod kluczem karty, więc karta
    // przywrócona przez „Cofnij” wracała jako już przesunięta i od razu usuwała lek drugi raz.
    // Próg 40% szerokości: krótkie, wolne przeciągnięcie (np. przy przewijaniu) nie usuwa leku; szybki gest nadal tak.
    val density = LocalDensity.current
    val state = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, density, { true }, { distance -> distance * 0.4f }) }
    val delete by rememberUpdatedState(onDelete)
    // currentValue zmienia się dopiero po puszczeniu karty za progiem, więc samo przeciąganie niczego nie usuwa
    LaunchedEffect(state.currentValue) {
        if (state.currentValue == SwipeToDismissBoxValue.EndToStart) delete()
    }
    // lista może jeszcze trzymać znikającą kartę; gdy „Cofnij” przywróci ją w trakcie animacji, karta wraca na miejsce
    LaunchedEffect(restoreCount) {
        if (state.currentValue != SwipeToDismissBoxValue.Settled) state.snapTo(SwipeToDismissBoxValue.Settled)
    }
    val colors = MaterialTheme.colorScheme
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = enabled,
        backgroundContent = {
            if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) {
                // tło jest tylko ozdobą gestu; czytnik ekranu ma akcję „Usuń” na samej karcie
                Row(
                    Modifier
                        .fillMaxSize()
                        .clip(MaterialTheme.shapes.medium)
                        .background(colors.error)
                        .padding(horizontal = 24.dp)
                        .clearAndSetSemantics {},
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, tint = colors.onError)
                    Text("Usuń", style = MaterialTheme.typography.labelLarge, color = colors.onError)
                }
            }
        },
    ) {
        content()
    }
}
