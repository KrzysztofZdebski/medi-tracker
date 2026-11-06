package pl.agh.meditracker.core

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import pl.agh.meditracker.data.notifications.ReminderScheduler
import pl.agh.meditracker.data.notifications.NotificationController
import pl.agh.meditracker.data.catalog.CatalogRepository
import pl.agh.meditracker.data.repository.MedicationRepository
import pl.agh.meditracker.data.repository.MedicationStore
import pl.agh.meditracker.data.settings.SettingsStore

class AppContainer(context: Context) {
    val medications = MedicationRepository(storage = MedicationStore(java.io.File(context.filesDir, "medications.json")))
    val catalog = CatalogRepository(context.filesDir)
    val settings = SettingsStore(context)
    val reminders = ReminderScheduler(context, medications, settings)
}

open class MediTrackerApp : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        NotificationController(this).ensureChannel()
        applicationScope.launch {
            combine(container.medications.changes, container.settings.state) { change, settings -> change to settings }
                .collectLatest { container.reminders.refresh() }
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { container.catalog.sync() }
        }
    }
}

@Composable
inline fun <reified VM : ViewModel> appViewModel(crossinline create: (AppContainer) -> VM): VM {
    val container = (LocalContext.current.applicationContext as MediTrackerApp).container
    return viewModel { create(container) }
}
