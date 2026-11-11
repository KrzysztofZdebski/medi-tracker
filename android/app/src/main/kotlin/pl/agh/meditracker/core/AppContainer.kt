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
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import pl.agh.meditracker.BuildConfig
import pl.agh.meditracker.data.account.AccountRepository
import pl.agh.meditracker.data.account.AccountStore
import pl.agh.meditracker.data.account.KeystoreSecretBox
import pl.agh.meditracker.data.account.api.HttpAccountApi
import pl.agh.meditracker.data.catalog.CatalogRepository
import pl.agh.meditracker.data.repository.MedicationRepository
import pl.agh.meditracker.data.settings.SettingsStore
import pl.agh.meditracker.data.user.RoomMedicationStore
import pl.agh.meditracker.data.user.UserDatabase
import pl.agh.meditracker.reminder.ReminderScheduler

class AppContainer(context: Context) {
    private val store = RoomMedicationStore(UserDatabase.open(context).dao())

    // Wczytanie przed pierwszym ekranem i przed obsługą alarmu w zimnym procesie; to kilka małych tabel.
    // Room nie pozwala na zapytania w wątku UI, więc odczyt idzie na IO, a start czeka na wynik.
    val medications = MedicationRepository(store = store, initial = runBlocking(Dispatchers.IO) { store.load() })
    val catalog = CatalogRepository(context.filesDir)
    val settings = SettingsStore(context)
    val reminders = ReminderScheduler(context, medications, settings)

    // konstrukcja bez sieci; Keystore dopiero przy pierwszym odczycie lub zapisie sekretu
    val account = AccountRepository(
        HttpAccountApi(BuildConfig.SERVER_URL, OkHttpClient()),
        AccountStore(context.getSharedPreferences("account", Context.MODE_PRIVATE), KeystoreSecretBox()),
    )
}

// open: testy Robolectric podmieniają onCreate, żeby nie startować synchronizacji katalogu z sieci
open class MediTrackerApp : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        container.reminders.start(scope)
        scope.launch { runCatching { container.catalog.sync() } }
    }
}

@Composable
inline fun <reified VM : ViewModel> appViewModel(crossinline create: (AppContainer) -> VM): VM {
    val container = (LocalContext.current.applicationContext as MediTrackerApp).container
    return viewModel { create(container) }
}
