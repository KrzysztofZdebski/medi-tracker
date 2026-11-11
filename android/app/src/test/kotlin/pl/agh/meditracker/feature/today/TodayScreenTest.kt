package pl.agh.meditracker.feature.today

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import pl.agh.meditracker.model.DoseStatus
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
class TodayScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val repository = (RuntimeEnvironment.getApplication() as TestApp).container.medications
    private val today = LocalDate.now()
    private val eight = LocalTime.of(8, 0)
    private val med = repository.add(
        Medication(
            name = "Metformina", strength = "500 mg", form = "tabletki", unit = DoseUnit.Tablet,
            schedule = Schedule(1, DayOfWeek.entries.toSet(), listOf(eight, LocalTime.of(20, 0)), today),
            stock = 30, refillThreshold = 5,
        ),
    )
    private var visible by mutableStateOf(true)

    private fun showToday() = compose.setContent {
        MediTrackerTheme { if (visible) TodayScreen({}, {}, {}, {}) }
    }

    private fun take() = compose.onAllNodesWithText("Przyjmij")[0].performClick()

    private fun status() = runBlocking { repository.dosesFor(today).first() }.first { it.time == eight }.status

    @Test fun takenFeedbackDisappearsAfterSnackbarTimesOutAndStaysGoneOnReturn() {
        showToday()
        take()
        compose.onNodeWithText("Dawka zapisana").assertIsDisplayed()
        compose.onNodeWithText("Zapisano przyjęcie").assertIsDisplayed()

        compose.mainClock.advanceTimeBy(6_000)
        compose.onAllNodesWithText("Dawka zapisana").assertCountEquals(0)
        compose.onAllNodesWithText("Zapisano przyjęcie").assertCountEquals(0)

        // zakładka znika z kompozycji, ViewModel zostaje; po powrocie nie ma starego banera ani snackbara
        visible = false
        compose.waitForIdle()
        visible = true
        compose.waitForIdle()
        compose.onAllNodesWithText("Dawka zapisana").assertCountEquals(0)
        compose.onAllNodesWithText("Zapisano przyjęcie").assertCountEquals(0)
        compose.onNodeWithText("NAJBLIŻSZA DAWKA").assertIsDisplayed()
    }

    @Test fun undoRestoresPendingDoseAndStock() {
        showToday()
        take()
        assertEquals(DoseStatus.Taken, status())
        assertEquals(29, repository.medications.value.single().stock)

        compose.onNodeWithText("Cofnij").performClick()
        compose.waitForIdle()
        assertEquals(DoseStatus.Pending, status())
        assertEquals(30, repository.medications.value.single().stock)
        compose.onAllNodesWithText("Dawka zapisana").assertCountEquals(0)
    }
}
