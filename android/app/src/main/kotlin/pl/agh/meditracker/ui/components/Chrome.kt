package pl.agh.meditracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Medication
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import pl.agh.meditracker.model.DoseStatus
import pl.agh.meditracker.ui.theme.MediTrackerTheme
import java.time.DayOfWeek

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTopBar(
    title: String,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = modifier,
        navigationIcon = {
            when {
                onBack != null -> IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Wstecz")
                }
                onClose != null -> IconButton(onClick = onClose) {
                    Icon(Icons.Outlined.Close, contentDescription = "Zamknij")
                }
            }
        },
        actions = actions,
        windowInsets = WindowInsets(0),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

@Composable
fun NotificationsAction(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Outlined.Notifications, contentDescription = "Powiadomienia")
    }
}

private val bottomItems = listOf(
    "Dzisiaj" to Icons.Outlined.Home,
    "Leki" to Icons.Outlined.Medication,
    "Historia" to Icons.Outlined.History,
    "Ustawienia" to Icons.Outlined.Settings,
)

@Composable
fun AppBottomBar(selectedIndex: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    NavigationBar(modifier = modifier, containerColor = colors.surface) {
        bottomItems.forEachIndexed { index, (label, icon) ->
            NavigationBarItem(
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
                icon = { Icon(icon, contentDescription = null) },
                label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = colors.onPrimaryContainer,
                    selectedTextColor = colors.onSurface,
                    indicatorColor = colors.primaryContainer,
                    unselectedIconColor = colors.onSurfaceVariant,
                    unselectedTextColor = colors.onSurfaceVariant,
                ),
            )
        }
    }
}

@Composable
fun AppSnackbar(data: SnackbarData, modifier: Modifier = Modifier) {
    Snackbar(
        snackbarData = data,
        modifier = modifier.padding(horizontal = 16.dp),
        shape = MaterialTheme.shapes.small,
        containerColor = MaterialTheme.colorScheme.onSurface,
        contentColor = MaterialTheme.colorScheme.surface,
        actionColor = MaterialTheme.colorScheme.primaryContainer,
        dismissActionContentColor = MaterialTheme.colorScheme.surface,
    )
}

@Composable
fun AppSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState, modifier) { AppSnackbar(it) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppBottomSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (icon != null) IconBadge(icon, size = 56.dp)
            if (title != null) Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
fun AppDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissText: String? = "Anuluj",
    icon: ImageVector? = null,
    destructive: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmText,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (destructive) colors.error else colors.primary,
                )
            }
        },
        dismissButton = dismissText?.let {
            {
                TextButton(onClick = onDismiss) { Text(it, style = MaterialTheme.typography.labelLarge) }
            }
        },
        icon = icon?.let { { IconBadge(it) } },
        title = { Text(title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(message, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant) },
        shape = MaterialTheme.shapes.extraLarge,
        containerColor = colors.surface,
    )
}

@Preview(showBackground = true, heightDp = 1600)
@Composable
private fun ComponentsPreview() {
    MediTrackerTheme {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            AppTopBar("Dzisiaj", actions = { NotificationsAction {} })
            PrimaryButton("Zapisz", {})
            PrimaryButton("Zapisywanie…", {}, loading = true)
            SecondaryButton("Anuluj", {})
            DestructiveButton("Usuń lek", {})
            StatusChip(DoseStatus.Pending)
            WarningChip()
            AppAlert("Niski zapas", "Zostało 5 tabletek.", AlertTone.Warning)
            AppTextField("Metformina", {}, "Nazwa leku")
            AppTextField("", {}, "Dawka", error = "Pole wymagane")
            NumberStepper("1", {}, {})
            TimeChip("08:00", {}, onEdit = {})
            DaySelector(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), {})
            DoseCard("08:00", "Metformina", "500 mg · 1 tabletka", DoseStatus.Pending, {})
            DoseCard("20:00", "Metformina", "500 mg · 1 tabletka", DoseStatus.Taken, {})
            MedicationCard(
                "Metformina", "500 mg · tabletki", "Codziennie · 08:00 i 20:00",
                "5 tabletek", StockLevel.Low, 0.15f, "Około 2 dni stosowania", {},
            )
            AppBottomBar(0) {}
        }
    }
}
