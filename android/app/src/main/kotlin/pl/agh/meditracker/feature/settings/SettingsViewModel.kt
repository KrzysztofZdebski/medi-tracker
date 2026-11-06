package pl.agh.meditracker.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.agh.meditracker.data.catalog.CatalogRepository
import pl.agh.meditracker.data.catalog.CatalogSync
import pl.agh.meditracker.data.settings.SettingsStore

data class SettingsUiState(
    val remindersEnabled: Boolean = false,
    // stan zgód systemowych; ekran odświeża go po powrocie z ustawień Androida
    val notificationsAllowed: Boolean = true,
    val exactAlarmsAllowed: Boolean = true,
    val catalogAsOf: String? = null,
    val syncing: Boolean = false,
    val syncResult: CatalogSync? = null,
    val version: String = "",
)

private data class Local(
    val notificationsAllowed: Boolean = true,
    val exactAlarmsAllowed: Boolean = true,
    val catalogAsOf: String? = null,
    val syncing: Boolean = false,
    val syncResult: CatalogSync? = null,
)

class SettingsViewModel(
    private val settings: SettingsStore,
    private val catalog: CatalogRepository,
    private val version: String,
) : ViewModel() {
    private val local = MutableStateFlow(Local(catalogAsOf = catalog.asOf))

    val state: StateFlow<SettingsUiState> = combine(settings.remindersFlow, local) { enabled, l ->
        SettingsUiState(enabled, l.notificationsAllowed, l.exactAlarmsAllowed, l.catalogAsOf, l.syncing, l.syncResult, version)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState(settings.remindersEnabled, version = version))

    fun setReminders(enabled: Boolean) {
        settings.remindersEnabled = enabled
    }

    fun updateSystemState(notificationsAllowed: Boolean, exactAlarmsAllowed: Boolean) = local.update {
        it.copy(notificationsAllowed = notificationsAllowed, exactAlarmsAllowed = exactAlarmsAllowed, catalogAsOf = catalog.asOf)
    }

    fun syncCatalog() {
        if (local.value.syncing) return
        local.update { it.copy(syncing = true, syncResult = null) }
        viewModelScope.launch {
            val result = runCatching { catalog.sync() }.getOrDefault(CatalogSync.Failed)
            local.update { it.copy(syncing = false, syncResult = result, catalogAsOf = catalog.asOf) }
        }
    }
}
