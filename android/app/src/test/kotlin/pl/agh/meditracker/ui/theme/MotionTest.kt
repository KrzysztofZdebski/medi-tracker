package pl.agh.meditracker.ui.theme

import android.app.Application
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MotionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = RuntimeEnvironment.getApplication()

    private fun setAnimatorScale(scale: Float) =
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)

    @After fun restoreScale() {
        setAnimatorScale(1f)
    }

    @Test fun reducedMotionHelpersReturnNoOpSpecs() {
        val motion = AppMotion.Reduced
        assertTrue(motion.spec<Float>() is SnapSpec<*>)
        assertTrue(motion.spec<Float>(MotionDuration.Long, MotionEasing.Emphasized, delayMillis = 50) is SnapSpec<*>)
        listOf(motion.fadeEnter(), motion.revealEnter(), motion.sharedAxisEnter(true), motion.sharedAxisEnter(false), motion.fadeThroughEnter())
            .forEach { assertSame(EnterTransition.None, it) }
        listOf(motion.fadeExit(), motion.revealExit(), motion.sharedAxisExit(true), motion.sharedAxisExit(false), motion.fadeThroughExit())
            .forEach { assertSame(ExitTransition.None, it) }
        listOf(motion.sharedAxis(true), motion.sharedAxis(false), motion.fadeThrough(), motion.crossfade(), motion.crossfade(animateSize = false), motion.popIn())
            .forEach {
                assertSame(EnterTransition.None, it.targetContentEnter)
                assertSame(ExitTransition.None, it.initialContentExit)
            }
    }

    @Test fun fullMotionUsesShortTweens() {
        val motion = AppMotion.Full
        val spec = motion.spec<Float>(MotionDuration.Medium)
        assertTrue(spec is TweenSpec<*>)
        assertEquals(MotionDuration.Medium, (spec as TweenSpec<*>).durationMillis)
        assertNotEquals(EnterTransition.None, motion.sharedAxisEnter())
        assertNotEquals(ExitTransition.None, motion.sharedAxisExit())
        assertNotEquals(EnterTransition.None, motion.revealEnter())
        assertTrue(listOf(MotionDuration.Quick, MotionDuration.Short, MotionDuration.Medium, MotionDuration.Long).all { it <= 400 })
    }

    @Test fun removedAnimationsSettingTurnsOnReducedMotion() {
        setAnimatorScale(0f)
        assertTrue(isAnimationDisabled(context))
        var reduced: Boolean? = null
        compose.setContent { MediTrackerTheme { reduced = LocalReducedMotion.current } }
        compose.waitForIdle()
        assertEquals(true, reduced)
    }

    @Test fun defaultAnimatorScaleKeepsAnimations() {
        assertFalse(isAnimationDisabled(context))
        var motion: AppMotion? = null
        compose.setContent { MediTrackerTheme { motion = AppMotion.current } }
        compose.waitForIdle()
        assertSame(AppMotion.Full, motion)
    }

    @Test fun reducedMotionHidesAlertWithoutExitAnimation() {
        assertFalse(alertStillShownShortlyAfterHiding(reducedMotion = true))
    }

    @Test fun fullMotionKeepsAlertDuringExitAnimation() {
        assertTrue(alertStillShownShortlyAfterHiding(reducedMotion = false))
    }

    private fun alertStillShownShortlyAfterHiding(reducedMotion: Boolean): Boolean {
        var visible by mutableStateOf(true)
        compose.setContent { MediTrackerTheme(reducedMotion = reducedMotion) { MotionVisibility(visible) { Text("Alert") } } }
        compose.onNodeWithText("Alert").assertExists()
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { visible = false }
        // wyjście trwa 150 ms; po 100 ms bez ograniczenia ruchu alert wciąż jest w drzewie
        compose.mainClock.advanceTimeBy(100)
        return compose.onAllNodesWithText("Alert").fetchSemanticsNodes().isNotEmpty()
    }
}
