package pl.agh.meditracker.feature.medications

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import pl.agh.meditracker.model.DoseUnit
import pl.agh.meditracker.model.Medication
import pl.agh.meditracker.model.Schedule
import pl.agh.meditracker.reminder.TestApp
import pl.agh.meditracker.ui.theme.MediTrackerTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h859dp-mdpi", application = TestApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MedicationsScreenTest {
    @get:org.junit.Rule val drainWrites = pl.agh.meditracker.data.user.DrainAppWritesRule()
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val repository = (RuntimeEnvironment.getApplication() as TestApp).container.medications
    private val meds = listOf("Metformina", "Apap", "Ibuprom").map { name ->
        repository.add(
            Medication(
                name = name, strength = "500 mg", form = "tabletki", unit = DoseUnit.Tablet,
                schedule = Schedule(1, DayOfWeek.entries.toSet(), listOf(LocalTime.of(8, 0)), LocalDate.now()),
                stock = 30, refillThreshold = 5,
            ),
        )
    }

    private fun showList() = compose.setContent {
        MediTrackerTheme { MedicationsScreen(false, {}, {}, {}, {}, removedInDetails = null, onRemovedShown = {}) }
    }

    private fun names() = repository.medications.value.map { it.name }

    // akcja „Usuń” z menu czytnika ekranu (alternatywa dla przesunięcia)
    private fun deleteWithAccessibilityAction(name: String) {
        val actions = compose.onNodeWithText(name).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnUiThread { actions.single { it.label == "Usuń" }.action() }
        compose.waitForIdle()
    }

    @Test fun accessibilityDeleteShowsSnackbarAndUndoRestoresSameMedication() {
        showList()
        deleteWithAccessibilityAction("Apap")
        assertEquals(listOf("Metformina", "Ibuprom"), names())
        compose.onAllNodesWithText("Apap").assertCountEquals(0)
        compose.onNodeWithText("Usunięto Apap").assertIsDisplayed()

        compose.onNodeWithText("Cofnij").performClick()
        compose.waitForIdle()
        assertEquals(meds, repository.medications.value)
        compose.onNodeWithText("Apap").assertIsDisplayed()
        compose.onAllNodesWithText("Usunięto Apap").assertCountEquals(0)
    }

    @Test fun swipingCardToTheLeftRemovesIt() {
        showList()
        compose.onNodeWithText("Metformina").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(listOf("Apap", "Ibuprom"), names())
        compose.onNodeWithText("Usunięto Metformina").assertIsDisplayed()
    }

    @Test fun longPressSelectsCardsAndBackLeavesSelectionMode() {
        showList()
        compose.onNodeWithText("Metformina").performTouchInput { longClick() }
        compose.onNodeWithText("Zaznaczono: 1").assertIsDisplayed()
        compose.onNodeWithText("Metformina").assertIsSelected()
        compose.onNodeWithText("Apap").assertIsNotSelected()

        // w trybie zaznaczania dotknięcie przełącza zaznaczenie zamiast otwierać szczegóły
        compose.onNodeWithText("Apap").performClick()
        compose.onNodeWithText("Zaznaczono: 2").assertIsDisplayed()
        compose.onNodeWithText("Apap").assertIsSelected()
        compose.onNodeWithContentDescription("Zaznacz wszystkie").performClick()
        compose.onNodeWithText("Zaznaczono: 3").assertIsDisplayed()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("Moje leki").assertIsDisplayed()
        compose.onAllNodesWithText("Zaznaczono: 3").assertCountEquals(0)
        assertEquals(3, names().size)
    }

    @Test fun deletingSelectionAsksForConfirmationAndUndoRestoresAll() {
        showList()
        compose.onNodeWithText("Metformina").performTouchInput { longClick() }
        compose.onNodeWithText("Ibuprom").performClick()
        compose.onNodeWithContentDescription("Usuń zaznaczone").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Usunąć 2 leki?").assertIsDisplayed()
        compose.onNodeWithText("Usuń leki").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Apap"), names())
        compose.onNodeWithText("Usunięto 2 leki").assertIsDisplayed()
        compose.onNodeWithText("Moje leki").assertIsDisplayed()

        compose.onNodeWithText("Cofnij").performClick()
        compose.waitForIdle()
        assertEquals(meds, repository.medications.value)
        assertTrue(listOf("Metformina", "Apap", "Ibuprom").all { compose.onAllNodesWithText(it).fetchSemanticsNodes().size == 1 })
    }
}
