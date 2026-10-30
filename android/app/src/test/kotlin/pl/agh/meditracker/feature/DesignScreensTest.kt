package pl.agh.meditracker.feature

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import pl.agh.meditracker.data.catalog.CatalogRepository
import pl.agh.meditracker.data.repository.MedicationRepository
import pl.agh.meditracker.feature.addmedication.*
import pl.agh.meditracker.feature.history.*
import pl.agh.meditracker.feature.medicationdetails.MedicationDetailsContent
import pl.agh.meditracker.feature.scanner.ScannerFlow
import pl.agh.meditracker.model.*
import pl.agh.meditracker.ui.theme.MediTrackerTheme
import java.io.File
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h859dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DesignScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val date = LocalDate.now()
    private val medication = Medication(
        id = "test", name = "Metformina", strength = "500 mg", form = "tabletki", unit = DoseUnit.Tablet,
        manufacturer = "Producent przykładowy", ean = "5901234123457", packageSize = "30 tabletek",
        schedule = Schedule(1, DayOfWeek.entries.toSet(), listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)), date),
        stock = 30, refillThreshold = 5,
    )

    private fun screenshot(name: String) {
        // captureToImage forces a PixelCopy redraw, which Robolectric cannot dispatch.
        lateinit var image: Bitmap
        compose.runOnIdle {
            val view = WindowInspector.getGlobalWindowViews().last()
            image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(image))
        }
        val directory = File("build/reports/design-screens").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun historyEmptyStateOpensToday() {
        var opened = false
        compose.setContent { MediTrackerTheme { Surface { Box(Modifier.fillMaxSize()) { HistoryContent(HistoryUiState(), { opened = true }, {}) } } } }
        compose.onNodeWithText("Tutaj powstanie\nTwój dziennik").assertIsDisplayed()
        screenshot("history-empty")
        compose.onNodeWithText("Przejdź do dzisiejszych leków").performClick()
        assertEquals(true, opened)
    }

    @Test fun historyDisplaysRecordedEntriesAndOpensFilters() {
        val entry = HistoryEntry(medication, date, LocalTime.of(8, 0), DoseStatus.Taken, date.atTime(8, 0), 29)
        compose.setContent { MediTrackerTheme { Surface { HistoryContent(HistoryUiState(listOf(entry), listOf("test" to "Metformina"), hasHistory = true), {}, {}) } } }
        compose.onNodeWithText("08:00 · Metformina").assertIsDisplayed()
        screenshot("history")
        compose.onNodeWithContentDescription("Filtruj historię").performClick()
        compose.onNodeWithText("Pokaż wyniki").assertIsDisplayed()
        screenshot("history-filters")
    }

    @Test fun medicationRefillShowsNewStockAndSavesAmount() {
        var added = 0
        compose.setContent { MediTrackerTheme { Surface { MedicationDetailsContent(medication, emptyList(), {}, {}, { added = it }, {}) } } }
        screenshot("medication-details")
        compose.onNodeWithText("Uzupełnij zapas").performClick()
        compose.onNodeWithText("60 tabletek").assertIsDisplayed()
        screenshot("medication-refill")
        compose.onNodeWithText("Zapisz").performClick()
        assertEquals(30, added)
    }

    @Test fun manualEanRetainsInvalidInputAndExplainsError() {
        val model = AddMedicationViewModel(MedicationRepository(), CatalogRepository(File("build/test-catalog")))
        compose.setContent { MediTrackerTheme { Surface { ScannerFlow(AddMedicationUiState(step = Step.Ean), model) } } }
        compose.onNode(hasSetTextAction()).performTextInput("123")
        compose.onNodeWithText("Szukaj leku").performClick()
        compose.onNodeWithText("Wpisz kod zawierający od 8 do 14 cyfr.").assertIsDisplayed()
        compose.onNodeWithText("123").assertIsDisplayed()
        screenshot("scanner-ean-validation")
    }

    @Test fun scannerStatesPreserveReadEan() {
        val model = AddMedicationViewModel(MedicationRepository(), CatalogRepository(File("build/test-catalog")))
        var step by mutableStateOf(Step.NotFound)
        compose.setContent { MediTrackerTheme { Surface { ScannerFlow(AddMedicationUiState(step = step, ean = "5901234123457"), model) } } }
        compose.onNodeWithText("5901234123457").assertIsDisplayed()
        screenshot("scanner-not-found")
        compose.runOnIdle { step = Step.LookupError }
        compose.onNodeWithText("5901234123457").assertIsDisplayed()
        screenshot("scanner-connection-error")
        compose.runOnIdle { step = Step.Recognizing }
        compose.onNodeWithText("5901234123457").assertIsDisplayed()
        screenshot("scanner-recognizing")
    }

    @Test fun scheduleEditSavesWithoutAddingAnotherMedication() {
        val repository = MedicationRepository()
        val saved = repository.add(medication)
        val model = AddMedicationViewModel(repository, CatalogRepository(File("build/test-catalog")), saved.id, editSchedule = true)
        compose.setContent { val state by model.state.collectAsState(); MediTrackerTheme { Surface { AddMedicationContent(state, model) } } }
        screenshot("medication-edit-schedule")
        compose.onNodeWithContentDescription("Zwiększ").performClick()
        compose.onNodeWithText("Zapisz zmiany").performScrollTo().performClick()
        assertEquals(2, repository.medications.value.single().schedule.doseQty)
        assertEquals(Exit.Saved, model.state.value.exit)
    }

    @Test fun medicationEditValidatesBeforeSaving() {
        val repository = MedicationRepository()
        val saved = repository.add(medication)
        val model = AddMedicationViewModel(repository, CatalogRepository(File("build/test-catalog")), saved.id)
        compose.setContent { val state by model.state.collectAsState(); MediTrackerTheme { Surface { AddMedicationContent(state, model) } } }
        screenshot("medication-edit")
        compose.onAllNodes(hasSetTextAction())[0].performTextClearance()
        compose.onNodeWithText("Zapisz zmiany").performScrollTo().performClick()
        compose.onNodeWithText("Wpisz nazwę leku.").performScrollTo().assertIsDisplayed()
        assertEquals("Metformina", repository.medications.value.single().name)
    }
}
