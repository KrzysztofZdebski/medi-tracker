package pl.agh.meditracker.feature

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import pl.agh.meditracker.data.account.AccountState
import pl.agh.meditracker.data.account.LogoutResult
import pl.agh.meditracker.data.account.SyncStatus
import pl.agh.meditracker.data.account.api.ApiError
import pl.agh.meditracker.data.account.api.ApiResult
import pl.agh.meditracker.data.notifications.NotificationAccess
import pl.agh.meditracker.data.settings.AppSettings
import pl.agh.meditracker.data.settings.AppTheme
import pl.agh.meditracker.feature.settings.*
import pl.agh.meditracker.ui.theme.MediTrackerTheme
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

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

    @Test fun settingsAccountRowReflectsState() {
        val actions = mutableListOf<String>()
        var state by mutableStateOf<AccountState>(AccountState.LoggedOut)
        compose.setContent { MediTrackerTheme { SettingsContent(AppSettings(remindersEnabled = true), NotificationAccess(allowed = true), "0.1", state, { actions += "account" }, { actions += "notifications" }, { actions += "sound" }, { actions += "about" }, {}, {}) } }
        compose.onNodeWithText("Moje konto").assertDoesNotExist()
        screenshot("settings")
        compose.onNodeWithText("Zaloguj się lub utwórz konto").performClick()
        state = AccountState.LoggedIn("anna@example.com", "u1")
        compose.onNodeWithText("Moje konto").assertIsDisplayed()
        compose.onNodeWithText("anna@example.com").assertIsDisplayed()
        screenshot("settings-logged-in")
        compose.onNodeWithText("Moje konto").performClick()
        compose.onNodeWithContentDescription("Powiadomienia").performClick()
        compose.onNodeWithText("Powiadomienia").performClick()
        compose.onNodeWithText("Dźwięk i wibracje").performClick()
        compose.onNodeWithText("Informacje o aplikacji").performScrollTo().performClick()
        assertEquals(listOf("account", "account", "notifications", "notifications", "sound", "about"), actions)
    }

    @Test fun settingsRowForSessionExpired() {
        compose.setContent { MediTrackerTheme { SettingsContent(AppSettings(), NotificationAccess(allowed = true), "0.1", AccountState.SessionExpired("anna@example.com"), {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithText("Moje konto").assertIsDisplayed()
        compose.onNodeWithText("Sesja wygasła — zaloguj się").assertIsDisplayed()
    }

    private fun account(status: SyncStatus, decisionPending: Boolean = false, actions: MutableList<String> = mutableListOf()) {
        compose.setContent { MediTrackerTheme {
            AccountContent("anna@example.com", status, decisionPending, { actions += "back" }, { actions += "password" }, { actions += "logout" }, { actions += "delete" },
                { actions += "retry" }, { actions += "conflict" }, { actions += "choose" }, ZoneOffset.UTC)
        } }
    }

    @Test fun accountScreenShowsBackupStatusAndFooter() {
        var status by mutableStateOf<SyncStatus>(SyncStatus.Idle(Instant.parse("2026-11-12T09:05:00Z")))
        compose.setContent { MediTrackerTheme {
            AccountContent("anna@example.com", status, false, {}, {}, {}, {}, {}, {}, {}, ZoneOffset.UTC)
        } }
        compose.onNodeWithText("Ostatnia kopia: 12.11.2026 09:05").assertIsDisplayed()
        compose.onNodeWithText("anna@example.com").assertIsDisplayed()
        compose.onNodeWithText("Leki i historia są szyfrowane na telefonie i zapisywane na Twoim koncie. Po wylogowaniu znikną z tego urządzenia.").performScrollTo().assertIsDisplayed()
        screenshot("t05")
        status = SyncStatus.Idle(null)
        compose.onNodeWithText("Kopia jeszcze nie została wysłana").assertIsDisplayed()
        status = SyncStatus.Pending
        compose.onNodeWithText("Kopia oczekuje na wysłanie").assertIsDisplayed()
        status = SyncStatus.Uploading
        compose.onNodeWithText("Wysyłanie kopii…").assertIsDisplayed()
        compose.onNodeWithText("Wybierz, które dane zachować").assertDoesNotExist()
    }

    @Test fun accountScreenFailedOffersRetryAndConflictOpensChoice() {
        val actions = mutableListOf<String>()
        var status by mutableStateOf<SyncStatus>(SyncStatus.Failed)
        compose.setContent { MediTrackerTheme {
            AccountContent("anna@example.com", status, false, {}, {}, {}, {}, { actions += "retry" }, { actions += "conflict" }, {}, ZoneOffset.UTC)
        } }
        compose.onNodeWithText("Nie udało się wysłać kopii. Spróbujemy ponownie.").assertIsDisplayed()
        compose.onNodeWithText("Spróbuj teraz").performClick()
        status = SyncStatus.Conflict
        compose.onNodeWithText("Konto jest używane na innym urządzeniu").assertIsDisplayed()
        compose.onNodeWithText("Wybierz dane").performClick()
        assertEquals(listOf("retry", "conflict"), actions)
    }

    @Test fun accountScreenShowsDecisionEntryWhenPending() {
        val actions = mutableListOf<String>()
        account(SyncStatus.Pending, decisionPending = true, actions = actions)
        compose.onNodeWithText("Wybierz, które dane zachować").performScrollTo().performClick()
        assertEquals(listOf("choose"), actions)
    }

    @Test fun accountScreenButtonsCallTheirActions() {
        val actions = mutableListOf<String>()
        account(SyncStatus.Pending, actions = actions)
        compose.onNodeWithText("Zmień hasło").performClick()
        compose.onNodeWithText("Wyloguj się").performClick()
        compose.onNodeWithText("Usuń konto").performScrollTo().performClick()
        assertEquals(listOf("password", "logout", "delete"), actions)
    }

    private fun logoutVm(results: MutableList<Boolean>, decisionPending: Boolean = false, result: LogoutResult = LogoutResult.Unsynced) =
        LogoutViewModel({ force -> results += force; if (force) LogoutResult.Done else result }, { decisionPending })

    @Test fun logoutConfirmWithDoneLeavesTheSheet() {
        val calls = mutableListOf<Boolean>()
        var done = false
        compose.setContent { MediTrackerTheme { LogoutSheet(logoutVm(calls, result = LogoutResult.Done), { done = true }, {}, {}) } }
        compose.onNodeWithText("Wylogować się?").assertIsDisplayed()
        compose.onNodeWithText("Leki i historia zostaną usunięte z tego telefonu. Kopia na koncie zostanie zachowana — zaloguj się ponownie, aby je przywrócić.").assertIsDisplayed()
        compose.onNodeWithText("Zostań w aplikacji").assertIsDisplayed()
        screenshot("t06")
        compose.onNodeWithText("Wyloguj się").performClick()
        compose.waitForIdle()
        assertEquals(listOf(false), calls)
        assertTrue(done)
    }

    @Test fun logoutUnsyncedShowsWarningChoices() {
        val calls = mutableListOf<Boolean>()
        var done = false
        compose.setContent { MediTrackerTheme { LogoutSheet(logoutVm(calls), { done = true }, {}, {}) } }
        compose.onNodeWithText("Wyloguj się").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Ostatnie zmiany nie są jeszcze w kopii na koncie.").assertIsDisplayed()
        compose.onNodeWithText("Spróbuj ponownie").assertIsDisplayed()
        screenshot("t06-unsynced")
        compose.onNodeWithText("Spróbuj ponownie").performClick()
        compose.waitForIdle()
        assertFalse(done)
        compose.onNodeWithText("Wyloguj i usuń mimo to").performClick()
        compose.waitForIdle()
        assertEquals(listOf(false, false, true), calls)
        assertTrue(done)
    }

    @Test fun logoutWhileDecisionPendingExplains() {
        val calls = mutableListOf<Boolean>()
        var chosen = 0
        compose.setContent { MediTrackerTheme { LogoutSheet(logoutVm(calls, decisionPending = true), {}, {}, { chosen++ }) } }
        compose.onNodeWithText("Wyloguj się").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Najpierw wybierz, które dane zachować.").assertIsDisplayed()
        compose.onNodeWithText("Wyloguj i usuń mimo to").assertIsDisplayed()
        compose.onNodeWithText("Wybierz, które dane zachować").performClick()
        assertEquals(1, chosen)
    }

    @Test fun deleteAccountRequiresPassword() {
        val passwords = mutableListOf<String>()
        var done = false
        val vm = DeleteAccountViewModel { passwords += String(it); ApiResult.Ok(Unit) }
        compose.setContent { MediTrackerTheme { DeleteAccountSheet(vm, { done = true }, {}) } }
        compose.onNodeWithText("Usunąć konto?").assertIsDisplayed()
        compose.onNodeWithText("Konto i kopia danych na serwerze zostaną trwale usunięte. Dane na tym telefonie zostaną zachowane.").assertIsDisplayed()
        compose.onNode(hasText("Usuń konto") and hasClickAction()).assertIsNotEnabled()
        screenshot("delete-account")
        compose.onNode(hasSetTextAction()).performTextInput("tajne-haslo-123")
        compose.onNode(hasText("Usuń konto") and hasClickAction()).assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(listOf("tajne-haslo-123"), passwords)
        assertTrue(done)
    }

    @Test fun deleteAccountShowsErrors() {
        var result: ApiResult<Unit> = ApiResult.Err(ApiError.InvalidCredentials)
        val vm = DeleteAccountViewModel { result }
        compose.setContent { MediTrackerTheme { DeleteAccountSheet(vm, {}, {}) } }
        compose.onNode(hasSetTextAction()).performTextInput("zle")
        compose.onNode(hasText("Usuń konto") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Nieprawidłowe hasło").assertIsDisplayed()
        result = ApiResult.Err(ApiError.Network)
        compose.onNode(hasSetTextAction()).performTextInput("zle")
        compose.onNode(hasText("Usuń konto") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Brak połączenia").assertIsDisplayed()
    }

    private fun passwordFields() = compose.onAllNodes(hasSetTextAction())

    @Test fun changePasswordValidates() {
        val calls = mutableListOf<Pair<String, String>>()
        val vm = ChangePasswordViewModel { old, new -> calls += String(old) to String(new); ApiResult.Ok(Unit) }
        var saved = false
        compose.setContent { MediTrackerTheme { ChangePasswordScreen(vm, {}, { saved = true }) } }
        screenshot("t07")
        passwordFields()[0].performTextInput("stare-haslo-123")
        passwordFields()[1].performTextInput("nowe-haslo-12345")
        passwordFields()[2].performTextInput("inne-haslo-12345")
        compose.onNodeWithText("Zapisz nowe hasło").performClick()
        compose.onNodeWithText("Hasła muszą być identyczne.").assertIsDisplayed()
        assertTrue(calls.isEmpty())
        passwordFields()[2].performTextClearance()
        passwordFields()[2].performTextInput("nowe-haslo-12345")
        compose.onNodeWithText("Zapisz nowe hasło").performClick()
        compose.waitForIdle()
        assertEquals(listOf("stare-haslo-123" to "nowe-haslo-12345"), calls)
        assertTrue(saved)
    }

    @Test fun changePasswordRejectsShortAndMapsErrors() {
        var result: ApiResult<Unit> = ApiResult.Err(ApiError.InvalidCredentials)
        val vm = ChangePasswordViewModel { _, _ -> result }
        compose.setContent { MediTrackerTheme { ChangePasswordScreen(vm, {}, {}) } }
        passwordFields()[0].performTextInput("stare-haslo-123")
        passwordFields()[1].performTextInput("krotkie")
        passwordFields()[2].performTextInput("krotkie")
        compose.onNodeWithText("Zapisz nowe hasło").performClick()
        compose.onNodeWithText("Hasło musi mieć co najmniej 10 znaków.").assertIsDisplayed()
        passwordFields()[1].performTextClearance(); passwordFields()[1].performTextInput("nowe-haslo-12345")
        passwordFields()[2].performTextClearance(); passwordFields()[2].performTextInput("nowe-haslo-12345")
        compose.onNodeWithText("Zapisz nowe hasło").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Obecne hasło jest nieprawidłowe").assertIsDisplayed()
        // hasła są czyszczone po wysłaniu
        assertEquals("", passwordFields()[0].fetchSemanticsNode().config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.EditableText)?.text.orEmpty())
        result = ApiResult.Err(ApiError.Network)
        passwordFields()[0].performTextInput("stare-haslo-123")
        passwordFields()[1].performTextInput("nowe-haslo-12345")
        passwordFields()[2].performTextInput("nowe-haslo-12345")
        compose.onNodeWithText("Zapisz nowe hasło").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Brak połączenia").assertIsDisplayed()
    }

    @Test fun passwordChangedScreenReturnsToAccount() {
        var back = false
        compose.setContent { MediTrackerTheme { PasswordChangedContent { back = true } } }
        compose.onNodeWithText("Zmiana zapisana").assertIsDisplayed()
        screenshot("t08")
        compose.onNodeWithText("Wróć do mojego konta").performClick()
        assertTrue(back)
    }

    @Test fun conflictBannerOpensChoice() {
        var open by mutableStateOf(false)
        compose.setContent { MediTrackerTheme {
            Column {
                SyncConflictBannerContent { open = true }
                if (open) ConflictChoiceSheet(false, null, { open = false }) {}
            }
        } }
        compose.onNodeWithText("Konto jest używane na innym urządzeniu").assertIsDisplayed()
        screenshot("conflict-banner")
        compose.onNodeWithText("Co zrobić z danymi?").assertDoesNotExist()
        compose.onNodeWithText("Konto jest używane na innym urządzeniu").performClick()
        compose.onNodeWithText("Co zrobić z danymi?").assertIsDisplayed()
        compose.onNodeWithText("Przywróć dane z konta").assertIsDisplayed()
    }

    @Test fun snoozeRequiresSaveAndCancelPreservesOriginalValue() {
        var preferences by mutableStateOf(AppSettings())
        compose.setContent { MediTrackerTheme { SettingsContent(preferences, NotificationAccess(), "0.1", AccountState.LoggedOut, {}, {}, {}, {}, { preferences = preferences.copy(snoozeMinutes = it) }, {}) } }
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
            SettingsContent(preferences, NotificationAccess(), "0.1", AccountState.LoggedOut, {}, {}, {}, {}, {}, { preferences = preferences.copy(theme = it) })
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
