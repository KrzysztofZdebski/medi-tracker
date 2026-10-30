package pl.agh.meditracker.feature.scanner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import pl.agh.meditracker.feature.addmedication.*
import pl.agh.meditracker.ui.components.*
import pl.agh.meditracker.R

@Composable
fun ScannerFlow(state: AddMedicationUiState, model: AddMedicationViewModel) {
    val context = LocalContext.current
    val title = when (state.step) {
        Step.Ean -> "Wpisz kod EAN"
        Step.Recognizing -> "Rozpoznawanie"
        Step.NotFound -> "Nie znaleziono leku"
        else -> "Nie udało się połączyć"
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AppTopBar(title, onBack = model::back, designIcons = true)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val icon = if (state.step == Step.LookupError) R.raw.design_wifi_44 else R.raw.design_barcode_44
            DesignBadge(icon)
            when (state.step) {
                Step.Ean -> ManualEan(state.ean, model::lookup, model::addManually)
                Step.Recognizing -> {
                    Text("Rozpoznawanie leku...", style = MaterialTheme.typography.headlineMedium)
                    Caption("Szukamy danych pasujących do odczytanego kodu.")
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary, trackColor = MaterialTheme.colorScheme.outlineVariant)
                    AppTextField(state.ean, {}, "ODCZYTANY EAN", readOnly = true)
                    SecondaryButton("Anuluj", model::back)
                }
                Step.NotFound -> {
                    Text("Nie znaleziono leku", style = MaterialTheme.typography.headlineMedium)
                    Caption("Ten kod nie zwrócił danych. Sprawdź opakowanie lub dodaj lek ręcznie.")
                    AppTextField(state.ean, {}, "Odczytany EAN", readOnly = true)
                    PrimaryButton("Spróbuj ponownie", { scanBarcode(context, { model.rescan(it.gtin14) }, model::enterEan) })
                    SecondaryButton("Wpisz kod ręcznie", model::enterEan)
                    AppTextButton("Dodaj lek ręcznie", model::addManually, Modifier.fillMaxWidth())
                }
                Step.LookupError -> {
                    Text("Sprawdź połączenie", style = MaterialTheme.typography.headlineMedium)
                    Caption("Nie udało się pobrać danych leku. Odczytany kod został zachowany.")
                    AppTextField(state.ean, {}, "EAN", readOnly = true, tall = true)
                    PrimaryButton("Spróbuj ponownie", { model.lookup(state.ean) })
                    SecondaryButton("Dodaj ręcznie", model::addManually)
                    Caption("Brak połączenia nie oznacza, że lek nie istnieje w bazie.")
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun ManualEan(initial: String, onSearch: (String) -> Unit, onManual: () -> Unit) {
    var ean by rememberSaveable { mutableStateOf(initial) }
    var submitted by rememberSaveable { mutableStateOf(false) }
    Text("Kod z opakowania", style = MaterialTheme.typography.headlineMedium)
    Caption("Wpisz cyfry znajdujące się pod kodem kreskowym.")
    AppTextField(ean, { ean = it.filter { char -> char in '0'..'9' }.take(14) }, "Kod EAN", keyboardType = KeyboardType.Number, error = if (submitted && !isEanInput(ean)) "Wpisz kod zawierający od 8 do 14 cyfr." else null, tall = true)
    Spacer(Modifier.height(8.dp))
    PrimaryButton("Szukaj leku", { submitted = true; if (isEanInput(ean)) onSearch(ean) })
    SecondaryButton("Dodaj bez kodu", onManual)
    Caption("Jeśli kod jest nieprawidłowy, zobaczysz opis przy polu.")
}
