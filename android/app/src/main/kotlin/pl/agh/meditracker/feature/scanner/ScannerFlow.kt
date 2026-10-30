package pl.agh.meditracker.feature.scanner

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import pl.agh.meditracker.feature.addmedication.*
import pl.agh.meditracker.ui.components.*
import pl.agh.meditracker.R

private val ScannerBackground = Color(0xFF25362F)

@Composable
fun ScannerFlow(state: AddMedicationUiState, model: AddMedicationViewModel) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    fun permitted() = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    var granted by remember { mutableStateOf(permitted()) }
    var denied by rememberSaveable { mutableStateOf(false) }
    var cameraError by rememberSaveable { mutableStateOf(false) }
    var torch by rememberSaveable { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        denied = !it
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) { granted = permitted(); if (granted) cameraError = false } }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    if (state.step == Step.Scanner && granted && !cameraError) {
        Column(Modifier.fillMaxSize().background(ScannerBackground)) {
            ScannerTopBar(model::back)
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Skieruj aparat na kod EAN\nna opakowaniu leku.", color = Color.White, style = MaterialTheme.typography.bodyLarge)
                Box(Modifier.fillMaxWidth().height(430.dp).clip(MaterialTheme.shapes.small).background(Color(0xFF71877B))) {
                    CameraPreview(torch, model::lookup, { cameraError = true }, { hasFlash = it })
                    ScannerFrame(Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 20.dp).height(164.dp))
                    Surface(Modifier.align(Alignment.BottomCenter).padding(bottom = 38.dp), shape = CircleShape, color = ScannerBackground) {
                        Text("Umieść kod w ramce", Modifier.padding(horizontal = 40.dp, vertical = 12.dp), color = Color.White, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    FilledIconToggleButton(checked = torch, onCheckedChange = { torch = it }, enabled = hasFlash, modifier = Modifier.size(64.dp), colors = IconButtonDefaults.filledIconToggleButtonColors(containerColor = Color(0xFF4D635B), contentColor = Color.White)) {
                        if (torch) Icon(Icons.Outlined.FlashlightOff, "Wyłącz latarkę")
                        else DesignIcon(R.raw.design_flash_28, 28.dp, "Włącz latarkę")
                    }
                    Text("Przytrzymaj telefon nieruchomo", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
                ScannerWhiteButton("Wpisz kod ręcznie", model::enterEan)
                ScannerWhiteButton("Anuluj", model::back)
            }
        }
        return
    }
    val title = when (state.step) {
        Step.Ean -> "Wpisz kod EAN"
        Step.Recognizing -> "Rozpoznawanie"
        Step.NotFound -> "Nie znaleziono leku"
        Step.LookupError -> "Nie udało się połączyć"
        else -> if (denied || cameraError) "Aparat niedostępny" else "Dostęp do aparatu"
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AppTopBar(title, onBack = model::back, designIcons = true)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = if (state.step == Step.Scanner && !denied && !cameraError) 68.dp else 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val icon = when (state.step) { Step.LookupError -> R.raw.design_wifi_44; Step.Scanner -> R.raw.design_camera_44; else -> R.raw.design_barcode_44 }
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
                    PrimaryButton("Spróbuj ponownie", model::retryScan)
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
                else -> if (denied || cameraError) {
                    Text("Brak dostępu\ndo aparatu", style = MaterialTheme.typography.headlineMedium)
                    Caption("Możesz zezwolić na użycie aparatu w ustawieniach telefonu lub dodać lek bez skanowania.")
                    PrimaryButton("Zezwól w ustawieniach", { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) })
                    SecondaryButton("Wpisz kod ręcznie", model::enterEan)
                    AppTextButton("Dodaj lek ręcznie", model::addManually, Modifier.fillMaxWidth())
                } else {
                    Text("Zeskanuj opakowanie", style = MaterialTheme.typography.headlineMedium)
                    Text("Aparat jest potrzebny tylko do odczytania kodu kreskowego leku.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AppAlert("Zdjęcia nie są zapisywane", "Aplikacja wyszukuje lek na podstawie odczytanego kodu EAN.", AlertTone.Info, designIcons = true)
                    PrimaryButton("Zezwól na użycie aparatu", { launcher.launch(Manifest.permission.CAMERA) })
                    SecondaryButton("Dodaj ręcznie", model::addManually)
                    Caption("Jeśli odmówisz, nadal możesz dodać lek ręcznie.")
                }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScannerTopBar(onBack: () -> Unit) {
    TopAppBar(title = { Text("Zeskanuj kod", style = MaterialTheme.typography.titleLarge) }, navigationIcon = { IconButton(onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Wstecz") } }, windowInsets = WindowInsets(0), colors = TopAppBarDefaults.topAppBarColors(containerColor = ScannerBackground, titleContentColor = Color.White, navigationIconContentColor = Color.White))
}

@Composable
private fun ScannerWhiteButton(text: String, onClick: () -> Unit) {
    Button(onClick, Modifier.fillMaxWidth().height(56.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = MaterialTheme.colorScheme.primary), shape = CircleShape) { Text(text, style = MaterialTheme.typography.labelLarge) }
}
