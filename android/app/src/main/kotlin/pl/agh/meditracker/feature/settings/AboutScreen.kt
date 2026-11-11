package pl.agh.meditracker.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import pl.agh.meditracker.R
import pl.agh.meditracker.core.MediTrackerApp
import pl.agh.meditracker.data.catalog.CatalogSync
import pl.agh.meditracker.ui.components.*
import pl.agh.meditracker.feature.addmedication.Caption

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val catalog = (context.applicationContext as MediTrackerApp).container.catalog
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    var syncing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var catalogDate by remember { mutableStateOf(catalog.asOf) }
    AboutContent(appVersion(context), syncing, message, onBack,
        onSource = {
            try { uriHandler.openUri("https://rejestry.ezdrowie.gov.pl/rpl/search/public") }
            catch (_: IllegalArgumentException) { message = "Nie znaleziono przeglądarki. Źródło danych: rejestry.ezdrowie.gov.pl." }
        },
        onSync = {
            if (!syncing) scope.launch {
                syncing = true
                val result = try { catalog.sync() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { CatalogSync.Failed }
                catalogDate = catalog.asOf
                message = when (result) {
                    CatalogSync.Updated -> "Pobrano nowy katalog leków."
                    CatalogSync.UpToDate -> "Katalog leków jest aktualny."
                    CatalogSync.Unsupported -> "Nowszy katalog wymaga aktualizacji aplikacji."
                    CatalogSync.Failed -> "Nie udało się zaktualizować katalogu. Sprawdź połączenie i spróbuj ponownie. Dotychczasowy katalog został zachowany."
                }
                syncing = false
            }
        },
        catalogDate = catalogDate,
    )
}

@Composable
fun AboutContent(version: String, syncing: Boolean, message: String?, onBack: () -> Unit, onSource: () -> Unit, onSync: () -> Unit, catalogDate: String? = null) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(colors.background)) {
        AppTopBar("O aplikacji", onBack = onBack, designIcons = true)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            DesignBadge(R.raw.design_pill_44)
            Spacer(Modifier.height(24.dp))
            Text("mediTracker", style = MaterialTheme.typography.headlineMedium, color = colors.onBackground)
            Text("Twój plan przyjmowania leków i zapas w jednym miejscu.", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            SettingsCard {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Wersja $version · projekt studencki", style = MaterialTheme.typography.bodyLarge)
                    Text("Leki i historia są zapisane lokalnie na tym urządzeniu.", color = colors.onSurfaceVariant)
                    Text("Dane leku są wyszukiwane na podstawie kodu EAN. Zweryfikuj je z opakowaniem.", color = colors.onSurfaceVariant)
                }
            }
            Caption("Źródło danych: Rejestr Produktów Leczniczych (Centrum e-Zdrowia), CC BY 4.0.")
            Caption(catalogDate?.let { "Stan danych: $it" } ?: "Katalog nie został jeszcze pobrany.")
            SecondaryButton("Otwórz źródło danych", onSource)
            PrimaryButton(if (syncing) "Aktualizowanie katalogu…" else "Aktualizuj katalog leków", onSync, enabled = !syncing, loading = syncing)
            message?.let { AppAlert("Katalog leków", it, AlertTone.Info) }
        }
    }
}
