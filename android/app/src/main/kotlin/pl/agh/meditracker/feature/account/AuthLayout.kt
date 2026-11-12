package pl.agh.meditracker.feature.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import pl.agh.meditracker.ui.components.AlertTone
import pl.agh.meditracker.ui.components.AppAlert
import pl.agh.meditracker.ui.components.AppTopBar
import pl.agh.meditracker.ui.components.IconBadge

/** Układ formularzy z Figmy (U01, U02, U03, U05…): pasek z tytułem, mała ikona z nazwą aplikacji, nagłówek, treść. */
@Composable
internal fun AuthLayout(
    title: String,
    headline: String,
    body: String?,
    onBack: (() -> Unit)?,
    icon: ImageVector = Icons.Outlined.Person,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AppTopBar(title = title, onBack = onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconBadge(icon, size = 40.dp)
                Text("mediTracker", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text(headline, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
            if (body != null) {
                Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

/** Treść błędu w jednym miejscu, żeby ten sam błąd brzmiał tak samo na każdym ekranie. */
@Composable
internal fun AuthErrorAlert(error: AuthError, modifier: Modifier = Modifier) {
    val (title, message) = when (error) {
        AuthError.Network -> "Brak połączenia" to "Sprawdź połączenie z internetem i spróbuj ponownie."
        AuthError.RateLimited -> "Zbyt wiele prób, spróbuj później" to "Odczekaj chwilę i spróbuj jeszcze raz."
        AuthError.InvalidCredentials -> "Nieprawidłowy e-mail lub hasło" to "Popraw dane i spróbuj zalogować się ponownie."
        AuthError.InvalidCode -> "Kod jest nieprawidłowy lub wygasł" to "Sprawdź kod w wiadomości albo poproś o nowy."
        AuthError.WrongRecoveryCode -> "Kod odzyskiwania jest nieprawidłowy" to "Sprawdź go i spróbuj ponownie."
        AuthError.ResetExpired -> "Czas na zmianę hasła minął" to "Zacznij od nowa: wróć do logowania i wybierz „Nie pamiętasz hasła?”."
        AuthError.Server -> "Coś poszło nie tak" to "Spróbuj ponownie za chwilę."
    }
    AppAlert(title, message, AlertTone.Error, modifier)
}

internal const val MIN_PASSWORD = 10

private val EMAIL_SHAPE = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

internal fun isValidEmail(email: String) = EMAIL_SHAPE.matches(email.trim())
