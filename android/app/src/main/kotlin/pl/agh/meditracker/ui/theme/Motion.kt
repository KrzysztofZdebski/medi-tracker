package pl.agh.meditracker.ui.theme

import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize

/** Czasy trwania animacji (ms). Wszystko krótkie: aplikacja ma się ruszać subtelnie, nic powyżej ~400 ms. */
object MotionDuration {
    const val Quick = 100
    const val Short = 150
    const val Medium = 250
    const val Long = 300
}

/** Krzywe Material 3 (emphasized / standard). */
object MotionEasing {
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val StandardDecelerate: Easing = CubicBezierEasing(0f, 0f, 0f, 1f)
    val StandardAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
}

/** `true`, gdy w systemie włączono „Usuń animacje” (skala animacji = 0). Ustawia go [MediTrackerTheme]. */
val LocalReducedMotion = compositionLocalOf { false }

/** Czy w ustawieniach Androida wyłączono animacje (Opcje programisty / Ułatwienia dostępu → „Usuń animacje”). */
fun isAnimationDisabled(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

/** Odczyt [isAnimationDisabled], aktualizowany, gdy użytkownik zmieni ustawienie przy otwartej aplikacji. */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    var reduced by remember(context) { mutableStateOf(isAnimationDisabled(context)) }
    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                reduced = isAnimationDisabled(context)
            }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}

/**
 * Wspólne specyfikacje animacji. Przy ograniczonym ruchu ([reduced]) każda zwraca `snap()` albo
 * `EnterTransition.None` / `ExitTransition.None`, więc ekrany nie muszą tego sprawdzać same.
 * W kompozycji: `val motion = AppMotion.current`; poza nią (np. lambdy przejść NavHost) używamy przechwyconej wartości.
 */
@Immutable
class AppMotion(val reduced: Boolean) {
    /** `tween` z krzywą Material albo `snap()` przy ograniczonym ruchu. */
    fun <T> spec(
        durationMillis: Int = MotionDuration.Medium,
        easing: Easing = MotionEasing.Standard,
        delayMillis: Int = 0,
    ): FiniteAnimationSpec<T> = if (reduced) snap() else tween(durationMillis, delayMillis, easing)

    /** Zmiana rozmiaru w [androidx.compose.animation.AnimatedContent]; `animate = false` przeskakuje od razu. */
    fun sizeTransform(animate: Boolean = true): SizeTransform =
        SizeTransform(clip = true) { _, _ -> if (animate) spec<IntSize>(MotionDuration.Medium, MotionEasing.Emphasized) else snap<IntSize>() }

    fun fadeEnter(durationMillis: Int = MotionDuration.Medium): EnterTransition =
        if (reduced) EnterTransition.None else fadeIn(spec(durationMillis, MotionEasing.StandardDecelerate))

    fun fadeExit(durationMillis: Int = MotionDuration.Short): ExitTransition =
        if (reduced) ExitTransition.None else fadeOut(spec(durationMillis, MotionEasing.StandardAccelerate))

    /** Pojawienie się banera / alertu: rozwinięcie w pionie i rozjaśnienie. */
    fun revealEnter(): EnterTransition =
        if (reduced) EnterTransition.None
        else expandVertically(spec(MotionDuration.Medium, MotionEasing.EmphasizedDecelerate), expandFrom = Alignment.Top) +
            fadeIn(spec(MotionDuration.Medium, MotionEasing.StandardDecelerate))

    fun revealExit(): ExitTransition =
        if (reduced) ExitTransition.None
        else shrinkVertically(spec(MotionDuration.Short, MotionEasing.EmphasizedAccelerate), shrinkTowards = Alignment.Top) +
            fadeOut(spec(MotionDuration.Short, MotionEasing.StandardAccelerate))

    /** Oś współdzielona X (Material): wejście z przesunięciem o 1/10 szerokości. `forward = false` przy powrocie. */
    fun sharedAxisEnter(forward: Boolean = true): EnterTransition =
        if (reduced) EnterTransition.None
        else slideInHorizontally(spec(MotionDuration.Long, MotionEasing.Emphasized)) { width -> if (forward) width / 10 else -width / 10 } +
            fadeIn(spec(MotionDuration.Long - MotionDuration.Quick, MotionEasing.StandardDecelerate, delayMillis = MotionDuration.Quick))

    fun sharedAxisExit(forward: Boolean = true): ExitTransition =
        if (reduced) ExitTransition.None
        else slideOutHorizontally(spec(MotionDuration.Long, MotionEasing.Emphasized)) { width -> if (forward) -width / 10 else width / 10 } +
            fadeOut(spec(MotionDuration.Quick, MotionEasing.StandardAccelerate))

    fun sharedAxis(forward: Boolean = true): ContentTransform =
        ContentTransform(sharedAxisEnter(forward), sharedAxisExit(forward), sizeTransform = sizeTransform(animate = false))

    /** Przenikanie przez tło (zmiana zakładki): stara treść znika, nowa pojawia się z lekkim opóźnieniem. */
    fun fadeThroughEnter(): EnterTransition =
        if (reduced) EnterTransition.None
        else fadeIn(spec(MotionDuration.Short, MotionEasing.StandardDecelerate, delayMillis = MotionDuration.Quick))

    fun fadeThroughExit(): ExitTransition =
        if (reduced) ExitTransition.None else fadeOut(spec(MotionDuration.Quick, MotionEasing.StandardAccelerate))

    fun fadeThrough(): ContentTransform =
        ContentTransform(fadeThroughEnter(), fadeThroughExit(), sizeTransform = sizeTransform(animate = false))

    /** Zwykłe przenikanie; `animateSize = false`, gdy rozmiar ma przeskoczyć (np. element listy z animateItem). */
    fun crossfade(animateSize: Boolean = true): ContentTransform =
        ContentTransform(fadeEnter(), fadeExit(), sizeTransform = sizeTransform(animateSize))

    /** Ikona potwierdzenia: lekkie powiększenie z 60 % i rozjaśnienie. */
    fun popIn(): ContentTransform =
        if (reduced) ContentTransform(EnterTransition.None, ExitTransition.None, sizeTransform = null)
        else ContentTransform(
            scaleIn(spec(MotionDuration.Medium, MotionEasing.EmphasizedDecelerate), initialScale = 0.6f) +
                fadeIn(spec(MotionDuration.Short, MotionEasing.StandardDecelerate)),
            fadeOut(spec(MotionDuration.Quick, MotionEasing.StandardAccelerate)),
            sizeTransform = null,
        )

    companion object {
        val Full = AppMotion(reduced = false)
        val Reduced = AppMotion(reduced = true)

        val current: AppMotion
            @Composable @ReadOnlyComposable
            get() = if (LocalReducedMotion.current) Reduced else Full
    }
}

/** Animacja dodania, usunięcia i przesunięcia elementu `LazyColumn` (bez animacji przy ograniczonym ruchu). */
fun LazyItemScope.itemMotion(motion: AppMotion, modifier: Modifier = Modifier): Modifier =
    if (motion.reduced) modifier
    else modifier.animateItem(
        fadeInSpec = motion.spec(MotionDuration.Medium, MotionEasing.StandardDecelerate),
        placementSpec = motion.spec(MotionDuration.Medium, MotionEasing.Emphasized),
        fadeOutSpec = motion.spec(MotionDuration.Short, MotionEasing.StandardAccelerate),
    )

/** Treść pokazywana warunkowo (alerty, banery): rozwija się i znika płynnie. */
@Composable
fun MotionVisibility(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val motion = AppMotion.current
    AnimatedVisibility(visible, modifier, enter = motion.revealEnter(), exit = motion.revealExit()) { content() }
}

/** Jak [MotionVisibility] dla wartości opcjonalnej; podczas znikania pokazuje ostatnią niepustą wartość. */
@Composable
fun <T : Any> MotionPresence(value: T?, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    val retained = remember { Retained<T>() }
    val shown = value ?: retained.value
    retained.value = shown
    MotionVisibility(value != null, modifier) { shown?.let { content(it) } }
}

private class Retained<T : Any> {
    var value: T? = null
}
