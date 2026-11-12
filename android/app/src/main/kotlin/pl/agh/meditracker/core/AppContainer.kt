package pl.agh.meditracker.core

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
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
import pl.agh.meditracker.data.account.KdfBounds
import pl.agh.meditracker.data.account.SecretBox
import pl.agh.meditracker.data.account.api.AccountApi
import pl.agh.meditracker.data.account.crypto.KdfParams
import pl.agh.meditracker.data.account.BackupSync
import pl.agh.meditracker.data.account.KeystoreSecretBox
import pl.agh.meditracker.data.account.api.HttpAccountApi
import pl.agh.meditracker.data.catalog.CatalogRepository
import pl.agh.meditracker.data.repository.MedicationRepository
import pl.agh.meditracker.data.settings.SettingsStore
import pl.agh.meditracker.data.user.RoomMedicationStore
import pl.agh.meditracker.data.user.UserDatabase
import pl.agh.meditracker.reminder.ReminderScheduler
import java.time.Clock

// accountApi, secretBox i parametry KDF podmieniają tylko testy (serwer w pamięci, Argon2 w miniaturze)
class AppContainer(
    context: Context,
    private val accountApi: AccountApi = HttpAccountApi(BuildConfig.SERVER_URL, OkHttpClient()),
    secretBox: SecretBox = KeystoreSecretBox(),
    kdf: KdfParams = KdfParams(),
    kdfBounds: KdfBounds = KdfBounds(),
) {
    private val store = RoomMedicationStore(UserDatabase.open(context).dao())

    // Wczytanie przed pierwszym ekranem i przed obsługą alarmu w zimnym procesie; to kilka małych tabel.
    // Room nie pozwala na zapytania w wątku UI, więc odczyt idzie na IO, a start czeka na wynik.
    val medications = MedicationRepository(store = store, initial = runBlocking(Dispatchers.IO) { store.load() })
    val catalog = CatalogRepository(context.filesDir)
    val settings = SettingsStore(context)
    val reminders = ReminderScheduler(context, medications, settings)

    // konstrukcja bez sieci; Keystore dopiero przy pierwszym odczycie lub zapisie sekretu
    private val accountStore = AccountStore(context.getSharedPreferences("account", Context.MODE_PRIVATE), secretBox)
    val account = AccountRepository(accountApi, accountStore, kdf = kdf, kdfBounds = kdfBounds)

    // kopia zapasowa rusza dopiero w start() (MediTrackerApp.onCreate), więc sama konstrukcja nie sięga do sieci
    val backup = BackupSync(
        medications, settings, account, accountStore, accountApi,
        CoroutineScope(SupervisorJob() + Dispatchers.IO), Clock.systemDefaultZone(),
    )
}

// open: testy Robolectric podmieniają onCreate, żeby nie startować synchronizacji katalogu ani kopii z sieci
open class MediTrackerApp : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        container.reminders.start(scope)
        container.backup.start()
        scope.launch { runCatching { container.catalog.sync() } }
    }
}

@Composable
inline fun <reified VM : ViewModel> appViewModel(
    owner: ViewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current),
    crossinline create: (AppContainer) -> VM,
): VM {
    val container = (LocalContext.current.applicationContext as MediTrackerApp).container
    return viewModel(owner) { create(container) }
}
