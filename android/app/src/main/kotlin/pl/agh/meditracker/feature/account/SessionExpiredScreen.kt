package pl.agh.meditracker.feature.account

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.runtime.Composable
import pl.agh.meditracker.feature.onboarding.OnboardingLayout
import pl.agh.meditracker.ui.components.AlertTone
import pl.agh.meditracker.ui.components.AppAlert
import pl.agh.meditracker.ui.components.PrimaryButton

/**
 * U13. Nic nie kasuje (spec §6.8): wygasła tylko sesja. Pokazuje ją miejsce związane z kontem (wiersz w ustawieniach,
 * Task 13) albo jednorazowy komunikat; start aplikacji jej nie wymusza.
 */
@Composable
fun SessionExpiredContent(onBack: (() -> Unit)?, onLogin: () -> Unit) {
    OnboardingLayout(
        title = "Sesja wygasła",
        icon = Icons.Outlined.Lock,
        headline = "Zaloguj się ponownie",
        body = "Twoja sesja wygasła. Zaloguj się, aby wrócić do swojego planu.",
        onBack = onBack,
    ) {
        AppAlert(
            "Zapisane dane pozostają na urządzeniu",
            "Leki i historia będą dostępne po zalogowaniu na to samo konto.",
            AlertTone.Info,
        )
        PrimaryButton("Zaloguj się", onLogin)
    }
}
