package pl.agh.meditracker.feature

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import pl.agh.meditracker.data.account.AfterLogin
import pl.agh.meditracker.data.account.api.ApiError
import pl.agh.meditracker.data.account.crypto.RecoveryCode
import pl.agh.meditracker.feature.account.*
import pl.agh.meditracker.feature.onboarding.WelcomeContent
import pl.agh.meditracker.ui.navigation.AppNavHost
import pl.agh.meditracker.ui.theme.MediTrackerTheme
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h859dp-mdpi", application = NavigationTestApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountScreensTest {
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

    private fun show(content: @androidx.compose.runtime.Composable () -> Unit) = compose.setContent { MediTrackerTheme { content() } }

    // pola AppTextField nie mają własnej etykiety w drzewie semantyki, więc wskazujemy je kolejnością
    private fun field(index: Int) = compose.onAllNodes(hasSetTextAction())[index]

    private fun type(index: Int, text: String) = field(index).performTextInput(text)

    @Test fun welcomeOffersContinueWithoutAccount() {
        val actions = mutableListOf<String>()
        show { WelcomeContent({ actions += "create" }, { actions += "login" }, { actions += "skip" }) }
        screenshot("account-o01-welcome")
        compose.onNodeWithText("Utwórz konto").assertIsDisplayed()
        compose.onNodeWithText("Zaloguj się").assertIsDisplayed()
        compose.onNodeWithText("Kontynuuj bez konta").performClick()
        assertEquals(listOf("skip"), actions)
    }

    @Test fun registerValidatesLikeU10() {
        var submitted = 0
        show { RegisterContent(false, null, {}, { _, _ -> submitted++ }, {}) }
        screenshot("account-u01-register")

        type(0, "anna@")
        type(1, "dobre-haslo-1")
        type(2, "inne-haslo-123")
        compose.onNode(hasText("Utwórz konto") and hasClickAction()).performScrollTo().performClick()
        compose.onNodeWithText("Wpisz poprawny adres e-mail.").assertIsDisplayed()
        compose.onNodeWithText("Hasła muszą być identyczne.").assertIsDisplayed()
        screenshot("account-u10-register-errors")

        // za krótkie, ale zgodne hasła
        field(1).performTextClearance()
        field(2).performTextClearance()
        type(1, "123456789")
        type(2, "123456789")
        compose.onNodeWithText("Hasło musi mieć co najmniej 10 znaków.").assertIsDisplayed()
        assertEquals(0, submitted)
    }

    @Test fun registerSubmitsValidForm() {
        var submitted: Pair<String, String>? = null
        show { RegisterContent(false, null, {}, { e, p -> submitted = e to p }, {}) }
        type(0, "anna@example.com")
        type(1, "dobre-haslo-1")
        type(2, "dobre-haslo-1")
        compose.onNode(hasText("Utwórz konto") and hasClickAction()).performScrollTo().performClick()
        assertEquals("anna@example.com" to "dobre-haslo-1", submitted)
    }

    @Test fun registerShowsEncryptionNote() {
        show { RegisterContent(false, null, {}, { _, _ -> }, {}) }
        compose.onNodeWithText("szyfrowane na telefonie", substring = true).assertExists()
    }

    @Test fun registerShowsNetworkErrorInlineAndKeepsForm() {
        show { RegisterContent(false, AuthError.Network, {}, { _, _ -> }, {}) }
        compose.onNodeWithText("Brak połączenia").assertIsDisplayed()
        screenshot("account-u12-register-offline")
    }

    @Test fun verifyShowsU14AfterWrongCode() {
        show { VerifyEmailContent("anna@example.com", false, AuthError.InvalidCode, null, {}, {}, {}) }
        compose.onNodeWithText("Kod jest nieprawidłowy").assertIsDisplayed()
        compose.onNodeWithText("Wyślij nowy kod").assertIsDisplayed()
        screenshot("account-u14-wrong-code")
    }

    @Test fun verifySubmitsSixDigitCode() {
        var code = ""
        show { VerifyEmailContent("anna@example.com", false, null, null, { code = it }, {}, {}) }
        screenshot("account-u03-verify")
        compose.onNodeWithText("Potwierdź konto").assertIsNotEnabled()
        type(0, "123456")
        compose.onNodeWithText("Potwierdź konto").performClick()
        assertEquals("123456", code)
    }

    @Test fun accountReadyShowsEmail() {
        var next = false
        show { AccountReadyContent("anna@example.com") { next = true } }
        screenshot("account-u04-ready")
        compose.onNodeWithText("anna@example.com").assertIsDisplayed()
        compose.onNodeWithText("Dalej").performClick()
        assertTrue(next)
    }

    @Test fun recoveryCodeRequiresAcknowledgement() {
        val code = RecoveryCode.generate()
        var next = false
        show { RecoveryCodeContent(code, false, {}, {}, { next = true }) }
        screenshot("account-recovery-code")
        // 26 znaków w 7 grupach: zawsze dwie stałe linie (4 + 3 grupy), nigdy łamanie w środku grupy
        val groups = code.display().split("-")
        assertEquals(7, groups.size)
        compose.onNodeWithText(groups.take(4).joinToString("-")).assertIsDisplayed()
        compose.onNodeWithText(groups.drop(4).joinToString("-")).assertIsDisplayed()
        compose.onNodeWithText("Dalej").assertIsNotEnabled()
        compose.onNodeWithText("Zapisałem kod").performClick()
        compose.onNodeWithText("Dalej").assertIsEnabled().performClick()
        assertTrue(next)
    }

    @Test fun recoveryCodeOffersCopyAndShare() {
        val actions = mutableListOf<String>()
        show { RecoveryCodeContent(RecoveryCode.generate(), false, { actions += "copy" }, { actions += "share" }, {}) }
        compose.onNodeWithText("Kopiuj").performClick()
        compose.onNodeWithText("Udostępnij").performClick()
        assertEquals(listOf("copy", "share"), actions)
    }

    @Test fun loginErrorShowsU09Banner() {
        show { LoginContent("anna@example.com", false, AuthError.InvalidCredentials, {}, { _, _ -> }, {}, {}, {}) }
        compose.onNodeWithText("Nieprawidłowy e-mail lub hasło").assertIsDisplayed()
        screenshot("account-u09-invalid-credentials")
    }

    @Test fun loginNetworkErrorShowsU12() {
        var dismissed = false
        show { LoginContent("anna@example.com", false, AuthError.Network, {}, { _, _ -> }, {}, {}, { dismissed = true }) }
        compose.onNodeWithText("Nie można się zalogować").assertIsDisplayed()
        screenshot("account-u12-login-offline")
        compose.onNodeWithText("Wróć do logowania").performClick()
        assertTrue(dismissed)
    }

    @Test fun loginRateLimitedShowsMessage() {
        show { LoginContent("anna@example.com", false, AuthError.RateLimited, {}, { _, _ -> }, {}, {}, {}) }
        compose.onNodeWithText("Zbyt wiele prób, spróbuj później").assertIsDisplayed()
    }

    @Test fun loginSubmitsAndShowsProgressWhileBusy() {
        var submitted: Pair<String, String>? = null
        show { LoginContent("", false, null, {}, { e, p -> submitted = e to p }, {}, {}, {}) }
        screenshot("account-u02-login")
        type(0, "anna@example.com")
        type(1, "dobre-haslo-1")
        compose.onNode(hasText("Zaloguj się") and hasClickAction()).performClick()
        assertEquals("anna@example.com" to "dobre-haslo-1", submitted)
    }

    @Test fun loginBusyShowsU11Progress() {
        show { LoginContent("anna@example.com", true, null, {}, { _, _ -> }, {}, {}, {}) }
        compose.onAllNodesWithText("Logowanie…").assertCountEquals(2)
        screenshot("account-u11-logging-in")
    }

    @Test fun resetEmailAndCodeScreens() {
        var sent: String? = null
        show { ResetEmailContent("", false, null, {}, { sent = it }) }
        screenshot("account-u05-reset-email")
        type(0, "anna@example.com")
        compose.onNodeWithText("Wyślij kod resetujący").performClick()
        assertEquals("anna@example.com", sent)
    }

    @Test fun resetCodeScreen() {
        var code = ""
        show { ResetCodeContent("anna@example.com", false, null, null, {}, { code = it }, {}) }
        screenshot("account-u06-reset-code")
        type(0, "654321")
        compose.onNodeWithText("Dalej").performClick()
        assertEquals("654321", code)
    }

    @Test fun resetRecoveryScreenOffersNoCodePathWithWarning() {
        var noCode = false
        show { ResetRecoveryContent(false, null, {}, {}, { noCode = true }) }
        screenshot("account-reset-recovery")
        compose.onNodeWithText("Nie mam kodu odzyskiwania").performClick()
        compose.onNodeWithText("Kopia danych na koncie zostanie trwale usunięta. Dane na tym telefonie zostaną zachowane.").assertIsDisplayed()
        screenshot("account-reset-no-code-warning")
        assertTrue(!noCode)
        compose.onNodeWithText("Usuń kopię i ustaw nowe hasło").performClick()
        assertTrue(noCode)
    }

    @Test fun resetRecoveryWrongCodeShowsErrorAndSubmits() {
        var code = ""
        show { ResetRecoveryContent(false, AuthError.WrongRecoveryCode, {}, { code = it }, {}) }
        compose.onNodeWithText("Kod odzyskiwania jest nieprawidłowy").assertIsDisplayed()
        type(0, "AAAA")
        compose.onNodeWithText("Dalej · nowe hasło").performClick()
        assertEquals("AAAA", code)
    }

    @Test fun newPasswordValidatesAndSubmits() {
        var password: String? = null
        show { NewPasswordContent(false, null, {}, { password = it }) }
        screenshot("account-u07-new-password")
        type(0, "123456789")
        type(1, "123456789")
        compose.onNodeWithText("Zapisz nowe hasło").performClick()
        compose.onNodeWithText("Hasło musi mieć co najmniej 10 znaków.").assertIsDisplayed()
        assertEquals(null, password)
        type(0, "0")
        type(1, "0")
        compose.onNodeWithText("Zapisz nowe hasło").performClick()
        assertEquals("1234567890", password)
    }

    @Test fun resetDoneAndSessionExpiredScreens() {
        var login = 0
        show { ResetDoneContent { login++ } }
        screenshot("account-u08-reset-done")
        compose.onNodeWithText("Przejdź do logowania").performClick()
        assertEquals(1, login)
    }

    @Test fun sessionExpiredKeepsDataMessage() {
        var login = false
        show { SessionExpiredContent(null) { login = true } }
        screenshot("account-u13-session-expired")
        compose.onNodeWithText("Zapisane dane pozostają na urządzeniu").assertIsDisplayed()
        compose.onNodeWithText("Zaloguj się").performClick()
        assertTrue(login)
    }

    @Test fun restoreChoiceSheetDefaultsToRestoreForOtherAccount() {
        val choices = mutableListOf<Boolean>()
        show { RestoreChoiceSheet(AfterLogin.NeedsChoice(fromOtherAccount = true), false, null, {}) { choices += it } }
        compose.onNodeWithText("innego konta", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Zachowaj dane z tego telefonu").assertIsDisplayed()
        // domyślny (główny, wyższy) przycisk to „przywróć”
        val restoreTop = compose.onNode(hasText("Przywróć dane z konta") and hasClickAction()).fetchSemanticsNode().boundsInRoot.top
        val keepTop = compose.onNode(hasText("Zachowaj dane z tego telefonu") and hasClickAction()).fetchSemanticsNode().boundsInRoot.top
        assertTrue(restoreTop < keepTop)
        screenshot("account-restore-choice-other-account")
        compose.onNodeWithText("Przywróć dane z konta").performClick()
        assertEquals(listOf(true), choices)
    }

    @Test fun restoreChoiceSheetWordingWithoutBackup() {
        val choices = mutableListOf<Boolean>()
        show { RestoreChoiceSheet(AfterLogin.NeedsChoice(fromOtherAccount = true, accountHasBackup = false), false, null, {}) { choices += it } }
        compose.onNodeWithText("Zacznij z pustym kontem").assertIsDisplayed()
        compose.onNodeWithText("Przenieś dane z tego telefonu na konto").assertIsDisplayed()
        compose.onNodeWithText("Przywróć dane z konta").assertDoesNotExist()
        compose.onNodeWithText("innego konta", substring = true).assertIsDisplayed()
        screenshot("account-restore-choice-no-backup")
        compose.onNodeWithText("Przenieś dane z tego telefonu na konto").performClick()
        assertEquals(listOf(false), choices)
    }

    @Test fun restoreChoiceSheetShowsErrorAndStaysOpen() {
        show { RestoreChoiceSheet(AfterLogin.NeedsChoice(fromOtherAccount = false), false, ApiError.UnreadableBackup, {}) {} }
        compose.onNodeWithText("Nie udało się odczytać kopii z konta. Dane na telefonie pozostały bez zmian.").assertIsDisplayed()
        compose.onNodeWithText("Przywróć dane z konta").assertIsDisplayed()
        screenshot("account-restore-choice-error")
    }

    @Test fun appUsableWithoutAccount() {
        show { AppNavHost(false, {}) }
        compose.onNodeWithText("Twoje leki.\nSpokojniejszy dzień.").assertIsDisplayed()
        compose.onNodeWithText("Kontynuuj bez konta").performClick()
        compose.onNodeWithText("Łatwiej pamiętać").assertIsDisplayed()
        compose.onNodeWithText("Teraz nie").performClick()
        compose.onNode(hasText("Dzisiaj") and hasClickAction()).assertIsDisplayed()
        compose.onNodeWithText("Łatwiej pamiętać").assertDoesNotExist()
    }

    @Test fun onboardingOffersAccountRoutes() {
        show { AppNavHost(false, {}) }
        compose.onNodeWithText("Zaloguj się").performClick()
        compose.onNodeWithText("Dobrze Cię widzieć").assertIsDisplayed()
        compose.onNodeWithText("Nie pamiętasz hasła?").performClick()
        compose.onNodeWithText("Odzyskaj dostęp").assertIsDisplayed()
        compose.onNodeWithContentDescription("Wstecz").performClick()
        compose.onNodeWithContentDescription("Wstecz").performClick()
        compose.onNodeWithText("Utwórz konto").performClick()
        compose.onNodeWithText("Zacznij od swojego konta").assertIsDisplayed()
    }
}
