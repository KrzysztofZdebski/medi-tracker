package pl.agh.meditracker.feature

import android.app.Application
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import pl.agh.meditracker.R
import pl.agh.meditracker.feature.history.HistoryContent
import pl.agh.meditracker.feature.history.HistoryUiState
import pl.agh.meditracker.feature.medicationdetails.MedicationDetailsContent
import pl.agh.meditracker.feature.today.TodaySheet
import pl.agh.meditracker.feature.today.TodaySheets
import pl.agh.meditracker.model.*
import pl.agh.meditracker.ui.components.*
import pl.agh.meditracker.ui.theme.MediTrackerTheme
import pl.agh.meditracker.widget.TodayWidgetState
import pl.agh.meditracker.widget.renderTodayWidget
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

// Semantyka dla TalkBack: nagłówki, opisy przycisków z ikoną, scalone karty, stany i regiony na żywo.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h859dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccessibilityTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val date = LocalDate.now()
    private val medication = Medication(
        id = "test", name = "Metformina", strength = "500 mg", form = "tabletki", unit = DoseUnit.Tablet,
        manufacturer = "Producent przykładowy", ean = "5901234123457", packageSize = "30 tabletek",
        schedule = Schedule(1, DayOfWeek.entries.toSet(), listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)), date),
        stock = 30, refillThreshold = 5,
    )

    private val headingNode = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
    private fun state(text: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, text)
    private val isButton = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)
    private fun clickLabel(label: String) = SemanticsMatcher("etykieta akcji „$label”") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == label
    }

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) =
        compose.setContent { MediTrackerTheme { Surface { content() } } }

    @Test fun screenTitlesAndSectionsAreHeadings() {
        show { MedicationDetailsContent(medication, emptyList(), {}, {}, {}, {}) }
        compose.onNode(hasText("Szczegóły leku") and headingNode).assertExists()
        compose.onNode(hasText("Metformina") and headingNode).assertExists()
        compose.onNode(hasText("Dawkowanie") and headingNode).assertExists()
        compose.onNode(hasText("Ostatnie przyjęcia") and headingNode).assertExists()
    }

    @Test fun iconOnlyButtonsHaveLabels() {
        val entry = HistoryEntry(medication, date, LocalTime.of(8, 0), DoseStatus.Taken, date.atTime(8, 0), 29)
        show { HistoryContent(HistoryUiState(listOf(entry), listOf("test" to "Metformina"), hasHistory = true), {}, {}, {}) }
        compose.onNodeWithContentDescription("Filtruj historię").assertHasClickAction()
        compose.onNodeWithContentDescription("Powiadomienia").assertHasClickAction()
        compose.onNode(hasText("Historia") and headingNode).assertExists()
        // wpis historii to jeden węzeł z godziną, nazwą i stanem
        compose.onNodeWithText("08:00 · Metformina").assert(hasText("1 tabletka · Przyjęto"))
    }

    @Test fun completedDoseCardIsOneButtonWithStatusState() {
        show { DoseCard("08:00", "Metformina", "500 mg · 1 tabletka", DoseStatus.Taken, {}, onClick = {}, onClickLabel = "otworzyć szczegóły leku") }
        compose.onNodeWithText("Metformina")
            .assert(hasText("08:00"))
            .assert(hasText("500 mg · 1 tabletka"))
            // etykieta stanu nie jest osobnym przyciskiem, tylko częścią karty
            .assert(hasText("Przyjęto"))
            .assert(state("przyjęta"))
            .assert(isButton)
            .assert(clickLabel("otworzyć szczegóły leku"))
            .assertHasClickAction()
        compose.onAllNodes(hasClickAction()).assertCountEquals(1)
    }

    @Test fun pendingDoseCardOffersTakeAndSkipActions() {
        var taken = 0
        var skipped = 0
        show {
            DoseCard("08:00", "Metformina", "500 mg · 1 tabletka", DoseStatus.Pending, { taken++ }, onClick = {}, highlighted = true, onSkip = { skipped++ })
        }
        val card = compose.onNodeWithText("Metformina").assert(state("oczekuje, najbliższa dawka"))
        val actions = card.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Przyjmij", "Pomiń dawkę"), actions.map { it.label })
        compose.runOnIdle { actions.single { it.label == "Pomiń dawkę" }.action() }
        assertEquals(1, skipped)

        compose.onNodeWithText("Przyjmij").assert(isButton).assert(clickLabel("przyjąć dawkę: Metformina, 08:00")).performClick()
        assertEquals(1, taken)
    }

    @Test fun lowStockIsSpokenNotOnlyColored() {
        show { MedicationCard("Metformina", "500 mg · tabletki", "Codziennie · 08:00", "5 tabletek", StockLevel.Low, 0.15f, "Około 2 dni stosowania", {}) }
        compose.onNodeWithText("Metformina")
            .assert(hasContentDescription("Niski zapas"))
            .assert(hasText("5 tabletek"))
            .assert(isButton)
            .assertHasClickAction()
    }

    @Test fun stockBarOutsideCardReportsProgress() {
        show { StockIndicator("15 tabletek", StockLevel.Normal, 0.5f, null) }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assert(state("Zapas: 50%"))
    }

    @Test fun alertsAreLiveRegionsAndErrorsAreAssertive() {
        show { AppAlert("Popraw zaznaczone pola", "Wprowadzone dane zostały zachowane.", AlertTone.Error) }
        compose.onNodeWithText("Popraw zaznaczone pola")
            .assert(hasText("Wprowadzone dane zostały zachowane."))
            .assert(hasContentDescription("Błąd"))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Assertive))
    }

    @Test fun textFieldErrorAndPickerFieldAreAnnounced() {
        var opened = false
        show {
            androidx.compose.foundation.layout.Column {
                AppTextField("", {}, "Dawka", error = "Pole wymagane")
                AppTextField("Codziennie", {}, "Częstotliwość", onClick = { opened = true })
            }
        }
        compose.onNode(hasSetTextAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.Error, "Pole wymagane"))
        compose.onNode(hasText("Pole wymagane") and SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).assertExists()
        // pole wyboru to jeden przycisk, bez drugiego węzła pola tekstowego tylko do odczytu
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithContentDescription("Częstotliwość").assert(state("Codziennie")).assert(isButton).performClick()
        assertTrue(opened)
    }

    @Test fun daySelectorDaysAreCheckboxesWithFullNames() {
        var toggled: DayOfWeek? = null
        show { DaySelector(setOf(DayOfWeek.MONDAY), { toggled = it }) }
        compose.onNodeWithContentDescription("poniedziałek").assertIsOn()
        compose.onNodeWithContentDescription("wtorek").assertIsOff().performClick()
        assertEquals(DayOfWeek.TUESDAY, toggled)
    }

    @Test fun snoozeOptionsAreSelectable() {
        val dose = Dose(medication, date, LocalTime.of(8, 0), DoseStatus.Pending)
        show { TodaySheets(TodaySheet.Snooze(dose), {}, {}, {}, { _, _ -> }, {}, { _, _ -> }, {}, defaultSnoozeMinutes = 15) }
        compose.onNodeWithContentDescription("15 minut").assertIsSelected()
        compose.onNodeWithContentDescription("30 minut").assertIsNotSelected().performClick().assertIsSelected()
    }

    @Test fun widgetTakeButtonNamesTheDose() {
        val context = RuntimeEnvironment.getApplication()
        val dose = Dose(medication, date, LocalTime.of(8, 0), DoseStatus.Pending)
        val view = renderTodayWidget(context, TodayWidgetState(taken = 1, total = 2, next = dose, tomorrow = null))
            .apply(context, FrameLayout(context))
        assertEquals("Oznacz jako przyjętą: Metformina 500 mg, 08:00", view.findViewById<View>(R.id.widget_take).contentDescription.toString())
        assertEquals("Przyjęto 1 z 2 dzisiejszych dawek", view.findViewById<View>(R.id.widget_progress).contentDescription.toString())
    }
}
