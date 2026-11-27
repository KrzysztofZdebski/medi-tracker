package pl.agh.meditracker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import android.app.Application
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pl.agh.meditracker.data.settings.AppTheme
import pl.agh.meditracker.ui.components.AlertTone
import pl.agh.meditracker.ui.components.alertColors
import pl.agh.meditracker.ui.components.statusTextColor
import pl.agh.meditracker.model.DoseStatus

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ColorsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    // obie palety z jednej kompozycji (setContent można wywołać raz na test)
    private fun schemes(): Map<AppTheme, androidx.compose.material3.ColorScheme> {
        val result = mutableMapOf<AppTheme, androidx.compose.material3.ColorScheme>()
        compose.setContent {
            MediTrackerTheme(AppTheme.Light) { result[AppTheme.Light] = MaterialTheme.colorScheme }
            MediTrackerTheme(AppTheme.Dark) { result[AppTheme.Dark] = MaterialTheme.colorScheme }
        }
        compose.waitForIdle()
        return result
    }

    // zaznaczona karta leku używa secondaryContainer; bez własnej wartości był to fiolet z domyślnej palety Material
    @Test fun secondaryContainerIsOurOwnColor() {
        val all = schemes()
        for ((theme, default) in listOf(AppTheme.Light to lightColorScheme(), AppTheme.Dark to darkColorScheme())) {
            val scheme = all.getValue(theme)
            assertNotEquals("$theme", default.secondaryContainer, scheme.secondaryContainer)
            assertTrue("$theme: tekst na zaznaczonej karcie", contrast(scheme.onSurface, scheme.secondaryContainer) >= 4.5f)
        }
    }

    // „Przyjęto” w historii było ciemnozielone na ciemnej karcie
    @Test fun historyStatusTextIsReadableOnCard() {
        for ((theme, scheme) in schemes()) {
            for (status in DoseStatus.entries) {
                val ratio = contrast(statusTextColor(status, scheme), scheme.surface)
                assertTrue("$theme $status: $ratio", ratio >= 4.5f)
            }
        }
    }

    // treść komunikatu była jasna na jasnym tle w ciemnym motywie
    @Test fun alertTextIsReadableInBothThemes() {
        for ((theme, scheme) in schemes()) {
            for (tone in AlertTone.entries) {
                val c = alertColors(tone, scheme)
                assertTrue("$theme $tone: treść ${contrast(c.message, c.container)}", contrast(c.message, c.container) >= 4.5f)
                assertTrue("$theme $tone: tytuł ${contrast(c.content, c.container)}", contrast(c.content, c.container) >= 4.5f)
            }
        }
    }
}
