package pl.agh.meditracker.feature

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
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
import pl.agh.meditracker.data.notifications.NotificationAccess
import pl.agh.meditracker.data.settings.AppSettings
import pl.agh.meditracker.data.settings.AppTheme
import pl.agh.meditracker.feature.settings.*
import pl.agh.meditracker.ui.theme.MediTrackerTheme
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h859dp-mdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreensTest {
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

    @Test fun settingsHasNoAccountAndEveryRowCallsItsAction() {
        val actions = mutableListOf<String>()
        compose.setContent { MediTrackerTheme { SettingsContent(AppSettings(remindersEnabled = true), NotificationAccess(allowed = true), "0.1", { actions += "notifications" }, { actions += "sound" }, { actions += "about" }, {}, {}) } }
        compose.onNodeWithText("Moje konto").assertDoesNotExist()
        screenshot("settings")
        compose.onNodeWithContentDescription("Powiadomienia").performClick()
        compose.onNodeWithText("Powiadomienia").performClick()
        compose.onNodeWithText("Dźwięk i wibracje").performClick()
        compose.onNodeWithText("Informacje o aplikacji").performScrollTo().performClick()
        assertEquals(listOf("notifications", "notifications", "sound", "about"), actions)
    }

    @Test fun snoozeRequiresSaveAndCancelPreservesOriginalValue() {
        var preferences by mutableStateOf(AppSettings())
        compose.setContent { MediTrackerTheme { SettingsContent(preferences, NotificationAccess(), "0.1", {}, {}, {}, { preferences = preferences.copy(snoozeMinutes = it) }, {}) } }
        compose.onNodeWithText("Przypomnij później").performClick()
        screenshot("settings-snooze")
        compose.onNodeWithText("30 minut").performClick()
        compose.onNodeWithText("Anuluj").performClick()
        assertEquals(15, preferences.snoozeMinutes)
        compose.onNodeWithText("Przypomnij później").performClick()
        compose.onNodeWithText("30 minut").performClick()
        compose.onNodeWithText("Zapisz").performClick()
        compose.onNodeWithText("Domyślnie 30 minut").assertIsDisplayed()
        assertEquals(30, preferences.snoozeMinutes)
    }

    @Test fun choosingDarkThemeChangesRenderedColors() {
        var preferences by mutableStateOf(AppSettings())
        var background = androidx.compose.ui.graphics.Color.White
        compose.setContent { MediTrackerTheme(preferences.theme) {
            background = MaterialTheme.colorScheme.background
            SettingsContent(preferences, NotificationAccess(), "0.1", {}, {}, {}, {}, { preferences = preferences.copy(theme = it) })
        } }
        val before = background
        compose.onNodeWithText("Motyw aplikacji").performClick()
        compose.onNodeWithText("Ciemny").performClick()
        compose.onNodeWithText("Zapisz").performClick()
        compose.waitForIdle()
        assertNotEquals(before, background)
        assertEquals(AppTheme.Dark, preferences.theme)
        screenshot("settings-dark")
    }

    @Test fun blockedNotificationsOfferSystemSettingsAndBack() {
        val actions = mutableListOf<String>()
        compose.setContent { MediTrackerTheme { NotificationSettingsContent(false, NotificationAccess(), { actions += "permission" }, { actions += "system" }, { actions += "test" }, { actions += "back" }, { actions += "settings" }) } }
        screenshot("settings-notifications-disabled")
        compose.onNodeWithText("Otwórz ustawienia Android").performClick()
        compose.onNodeWithText("Poproś o zgodę").performClick()
        compose.onNodeWithText("Wróć do ustawień").performScrollTo().performClick()
        assertEquals(listOf("system", "permission", "settings"), actions)
    }

    @Test fun enabledNotificationsHaveWorkingToggleAndTest() {
        var enabled by mutableStateOf(true)
        var tested = false
        compose.setContent { MediTrackerTheme { NotificationSettingsContent(enabled, NotificationAccess(allowed = true), { enabled = !enabled }, {}, { tested = true }, {}, {}) } }
        compose.onNodeWithText("Wyślij powiadomienie testowe").performScrollTo().performClick()
        assertTrue(tested)
        compose.onNodeWithText("Wyłącz przypomnienia").performScrollTo().performClick()
        compose.onNodeWithText("Powiadomienia\nsą wyłączone").assertIsDisplayed()
        assertFalse(enabled)
    }

    @Test fun aboutShowsRealVersionSourceAndWorkingSyncAction() {
        val actions = mutableListOf<String>()
        compose.setContent { MediTrackerTheme { AboutContent("0.1", false, null, {}, { actions += "source" }, { actions += "sync" }) } }
        compose.onNodeWithText("Wersja 0.1 · projekt studencki").assertIsDisplayed()
        screenshot("settings-about")
        compose.onNodeWithText("Otwórz źródło danych").performScrollTo().performClick()
        compose.onNodeWithText("Aktualizuj katalog leków").performScrollTo().performClick()
        assertEquals(listOf("source", "sync"), actions)
    }
}
