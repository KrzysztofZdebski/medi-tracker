package pl.agh.meditracker.feature

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import pl.agh.meditracker.calendar.CalendarInfo
import pl.agh.meditracker.data.account.AccountState
import pl.agh.meditracker.data.notifications.NotificationAccess
import pl.agh.meditracker.data.settings.AppSettings
import pl.agh.meditracker.feature.medicationdetails.CalendarToggle
import pl.agh.meditracker.feature.medicationdetails.MedicationDetailsContent
import pl.agh.meditracker.feature.settings.CalendarSettingsContent
import pl.agh.meditracker.feature.settings.SettingsContent
import pl.agh.meditracker.model.DoseUnit
import pl.agh.meditracker.model.Medication
import pl.agh.meditracker.model.Schedule
import pl.agh.meditracker.ui.theme.MediTrackerTheme
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h859dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CalendarScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun screenshot(name: String) {
        compose.runOnIdle {
            val view = WindowInspector.getGlobalWindowViews().last()
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val directory = File("build/reports/design-screens").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun settingsRowOpensCalendarScreen() {
        var opened = 0
        compose.setContent { MediTrackerTheme {
            SettingsContent(AppSettings(), NotificationAccess(allowed = true), "0.1", AccountState.LoggedOut, {}, {}, {}, {}, {}, {},
                calendarEnabled = true, onCalendar = { opened++ })
        } }
        compose.onNodeWithText("Dawki w kalendarzu telefonu").assertIsDisplayed()
        compose.onNodeWithText("Kalendarz").performClick()
        assertEquals(1, opened)
    }

    @Test fun withoutPermissionExplainsAndLinksToAndroidSettings() {
        val actions = mutableListOf<String>()
        compose.setContent { MediTrackerTheme {
            CalendarSettingsContent(false, false, emptyList(), null, null,
                { actions += "toggle" }, { actions += "choose" }, { actions += "delete" }, { actions += "system" }, { actions += "back" })
        } }
        screenshot("settings-calendar-no-permission")
        compose.onNodeWithText("Brak dostępu do kalendarza").assertIsDisplayed()
        compose.onNodeWithText("Kalendarz docelowy").assertDoesNotExist()
        compose.onNodeWithText("Dodawaj dawki do kalendarza").assertIsOff().performClick()
        compose.onNodeWithText("Otwórz ustawienia Android").performClick()
        compose.onNodeWithText("Usuń wydarzenia z kalendarza").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Wstecz").performClick()
        assertEquals(listOf("toggle", "system", "delete", "back"), actions)
    }

    @Test fun withPermissionTogglesAndChoosesCalendar() {
        var enabled by mutableStateOf(false)
        var selected by mutableStateOf<Long?>(null)
        var message by mutableStateOf<String?>(null)
        compose.setContent { MediTrackerTheme {
            CalendarSettingsContent(enabled, true, listOf(CalendarInfo(7, "Praca", "anna@example.com")), selected, message,
                { enabled = !enabled }, { selected = it }, { message = "Usunięto wydarzenia dodane przez mediTracker." }, {}, {})
        } }
        compose.onNodeWithText("Brak dostępu do kalendarza").assertDoesNotExist()
        compose.onNodeWithText("Dodawaj dawki do kalendarza").assertIsOff().performClick()
        compose.onNodeWithText("Dodawaj dawki do kalendarza").assertIsOn()
        compose.onNodeWithText("Kalendarz docelowy").assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Heading))
        compose.onNodeWithText("mediTracker").assertIsSelected()
        compose.onNodeWithText("Praca").performScrollTo().performClick()
        assertEquals(7L, selected)
        compose.onNodeWithText("Praca").assertIsSelected()
        compose.onNodeWithText("mediTracker").assertIsNotSelected()
        screenshot("settings-calendar")
        compose.onNodeWithText("Usuń wydarzenia z kalendarza").performScrollTo().performClick()
        compose.onNodeWithText("Usunięto wydarzenia dodane przez mediTracker.").performScrollTo().assertIsDisplayed()
    }

    @Test fun medicationDetailsTogglesCalendarVisibility() {
        val medication = Medication(
            id = "m1", name = "Metformina", strength = "500 mg", form = "tabletki", unit = DoseUnit.Tablet,
            schedule = Schedule(1, DayOfWeek.entries.toSet(), listOf(LocalTime.of(8, 0)), LocalDate.of(2026, 11, 1)),
            stock = 30, refillThreshold = 5,
        )
        var shown by mutableStateOf(true)
        compose.setContent { MediTrackerTheme { Surface {
            MedicationDetailsContent(medication, emptyList(), {}, {}, {}, {}, calendar = CalendarToggle(shown, exportEnabled = true) { shown = it })
        } } }
        compose.onNodeWithText("Pokazuj w kalendarzu").performScrollTo().assertIsOn().performClick()
        assertFalse(shown)
        compose.onNodeWithText("Pokazuj w kalendarzu").assertIsOff()
    }
}
