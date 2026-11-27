package pl.agh.meditracker.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import pl.agh.meditracker.ui.theme.MediTrackerTheme

// Przy czcionce 200% podpis „Ustawienia” łamał się na dwa wiersze i rozpychał dolny pasek.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h846dp-xxhdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BottomBarFontTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun labelsStayOnOneLineAtDoubleFontSize() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                MediTrackerTheme { AppBottomBar(3, designIcons = true) {} }
            }
        }
        for (label in listOf("Dzisiaj", "Leki", "Historia", "Ustawienia")) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
            val layout = layouts.single()
            assertEquals(label, 1, layout.lineCount)
            assertTrue("$label szerszy niż miejsce", layout.size.width <= layout.layoutInput.constraints.maxWidth)
        }
    }
}
