package pl.agh.meditracker.feature

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import pl.agh.meditracker.core.AppContainer
import pl.agh.meditracker.core.MediTrackerApp
import pl.agh.meditracker.model.*
import pl.agh.meditracker.ui.navigation.AppNavHost
import pl.agh.meditracker.ui.theme.MediTrackerTheme
import java.time.*

// Real repositories and navigation; no network sync or background alarm observer in UI tests.
class NavigationTestApp : MediTrackerApp() {
    override fun onCreate() { container = AppContainer(this) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h859dp-mdpi", application = NavigationTestApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationActionsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = RuntimeEnvironment.getApplication() as NavigationTestApp

    private fun addMedication(status: DoseStatus = DoseStatus.Pending) {
        val time = LocalTime.of(8, 0)
        val med = app.container.medications.add(Medication(name = "Metformina", strength = "500 mg", form = "tabletki", unit = DoseUnit.Tablet,
            schedule = Schedule(1, DayOfWeek.entries.toSet(), listOf(time), LocalDate.now()), stock = 30, refillThreshold = 5))
        if (status != DoseStatus.Pending) app.container.medications.setDoseStatus(med.id, LocalDate.now(), time, status)
    }

    @Test fun everyMainTabBellOpensNotificationsAndBackReturnsToTab() {
        compose.setContent { MediTrackerTheme { AppNavHost(true, {}) } }
        listOf("Dzisiaj", "Leki", "Historia", "Ustawienia").forEach { tab ->
            compose.onNode(hasText(tab) and hasClickAction()).performClick()
            compose.onNodeWithContentDescription("Powiadomienia").performClick()
            compose.onNodeWithText("Powiadomienia\nsą wyłączone").assertIsDisplayed()
            compose.onNodeWithContentDescription("Wstecz").performClick()
            compose.onNode(hasText(tab) and hasClickAction()).assertIsDisplayed()
        }
        compose.onNodeWithText("Informacje o aplikacji").performScrollTo().performClick()
        compose.onNodeWithText("mediTracker").assertIsDisplayed()
    }

    @Test fun completedDoseStatusOpensMedicationDetails() {
        addMedication(DoseStatus.Taken)
        compose.setContent { MediTrackerTheme { AppNavHost(true, {}) } }
        compose.onNodeWithText("Przyjęto").performScrollTo().performClick()
        compose.onNodeWithText("Szczegóły leku").assertIsDisplayed()
    }

    @Test fun snoozeUsesDefaultFromSettingsAndReturnsVisibleFeedback() {
        addMedication()
        app.container.settings.snoozeMinutes = 30
        compose.setContent { MediTrackerTheme { AppNavHost(true, {}) } }
        compose.onNodeWithText("Metformina").performScrollTo().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Przypomnij później").performScrollTo().assertIsDisplayed().performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithText("Zapisz · za 30 minut").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Dawka odroczona").assertIsDisplayed()
    }
}
