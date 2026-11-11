package pl.agh.meditracker.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.agh.meditracker.data.settings.AppTheme

val Primary = Color(0xFF176B5B)
val PrimaryContainer = Color(0xFFD5EDE4)
val OnPrimaryContainer = Color(0xFF123D33)
val Background = Color(0xFFF7FAF8)
val SurfaceContainer = Color(0xFFEDF3EF)
val TextColor = Color(0xFF1A2722)
val Muted = Color(0xFF4A5C53)
val Outline = Color(0xFF718078)
val Line = Color(0xFFD7E1DA)
val Error = Color(0xFFB3261E)
val ErrorContainer = Color(0xFFFCE9E7)
val Warning = Color(0xFF785300)
val WarningContainer = Color(0xFFFFF0C2)
val Success = Color(0xFF21643C)
val SuccessContainer = Color(0xFFDDF1E3)

private val colors = lightColorScheme(
    primary = Primary,
    onPrimary = Color.White,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = Color(0xFF4D635B),
    background = Background,
    onBackground = TextColor,
    surface = Color.White,
    onSurface = TextColor,
    surfaceContainer = SurfaceContainer,
    surfaceContainerLow = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = SurfaceContainer,
    onSurfaceVariant = Muted,
    outline = Outline,
    outlineVariant = Line,
    error = Error,
    errorContainer = ErrorContainer,
)

private val darkColors = darkColorScheme(
    primary = Color(0xFF95D5BE), onPrimary = Color(0xFF00382B),
    primaryContainer = Color(0xFF185142), onPrimaryContainer = Color(0xFFD5EDE4),
    secondary = Color(0xFFB2CCC0), background = Color(0xFF101A16), onBackground = Color(0xFFE0EAE3),
    surface = Color(0xFF18241E), onSurface = Color(0xFFE0EAE3),
    surfaceContainer = Color(0xFF223029), surfaceContainerLow = Color(0xFF18241E),
    surfaceContainerHigh = Color(0xFF29382F), surfaceContainerHighest = Color(0xFF304137),
    onSurfaceVariant = Color(0xFFBACCC0), outline = Color(0xFF8B9C90), outlineVariant = Color(0xFF405247),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF642520),
)

private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight)

private val typography = Typography(
    displaySmall = style(32, 40),
    headlineMedium = style(28, 36),
    titleLarge = style(22, 28),
    titleMedium = style(20, 28),
    bodyLarge = style(16, 24),
    labelLarge = style(16, 24, FontWeight.Medium),
    bodyMedium = style(14, 20),
    labelMedium = style(14, 20, FontWeight.Medium),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun MediTrackerTheme(theme: AppTheme = AppTheme.Light, content: @Composable () -> Unit) {
    val dark = when (theme) { AppTheme.System -> isSystemInDarkTheme(); AppTheme.Dark -> true; AppTheme.Light -> false }
    MaterialTheme(colorScheme = if (dark) darkColors else colors, typography = typography, shapes = shapes, content = content)
}
