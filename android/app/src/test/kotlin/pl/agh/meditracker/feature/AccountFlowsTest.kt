package pl.agh.meditracker.feature

import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import pl.agh.meditracker.core.AppContainer
import pl.agh.meditracker.core.MediTrackerApp
import pl.agh.meditracker.data.account.AccountRepository
import pl.agh.meditracker.data.account.AccountState
import pl.agh.meditracker.data.account.AccountStore
import pl.agh.meditracker.data.account.AfterLogin
import pl.agh.meditracker.data.account.FakeAccountApi
import pl.agh.meditracker.data.account.KdfBounds
import pl.agh.meditracker.data.account.PlainSecretBox
import pl.agh.meditracker.data.account.api.ApiResult
import pl.agh.meditracker.data.account.crypto.EncryptedBlob
import pl.agh.meditracker.data.account.crypto.KdfParams
import pl.agh.meditracker.feature.account.AuthViewModel
import pl.agh.meditracker.feature.account.RestoreChoiceHost
import pl.agh.meditracker.model.DoseUnit
import pl.agh.meditracker.model.Medication
import pl.agh.meditracker.model.Schedule
import pl.agh.meditracker.ui.navigation.AppNavHost
import pl.agh.meditracker.ui.theme.MediTrackerTheme
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

private val FAST = KdfParams(m = 1024, t = 1, p = 1)
private val BOUNDS = KdfBounds(m = 8..1048576, t = 1..10, p = 1..4)
private const val ANNA = "anna@x.pl"
private const val OLA = "ola@x.pl"
private const val PASSWORD = "dobre-haslo-123"

// prawdziwy kontener z serwerem w pamięci i Argon2 w miniaturze
class AccountFlowApp : MediTrackerApp() {
    val api = FakeAccountApi()
    val box = PlainSecretBox()
    override fun onCreate() { container = AppContainer(this, api, box, FAST, BOUNDS) }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h859dp-mdpi", application = AccountFlowApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AccountFlowsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = RuntimeEnvironment.getApplication() as AccountFlowApp
    private val account get() = app.container.account

    private fun pw() = PASSWORD.toCharArray()

    private suspend fun AccountRepository.registered(email: String) {
        assertTrue(register(email, pw()) is ApiResult.Ok)
        assertTrue(verify(app.api.verifyCode(email)!!) is ApiResult.Ok)
        acknowledgeRecoveryCode()
    }

    // operacje ViewModelu biegną na Main i Default; czekamy, aż skończy
    private fun settle(vm: AuthViewModel) {
        repeat(500) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!vm.busy) return
            Thread.sleep(20)
        }
        error("operacja nie skończyła się")
    }

    @Test fun validRecoveryCodeAfterChoosingNoCodeUsesCompleteReset() {
        val code = runBlocking {
            account.register(ANNA, pw())
            account.verify(app.api.verifyCode(ANNA)!!)
            val shown = (account.state.value as AccountState.ShowRecoveryCode).code.display()
            account.acknowledgeRecoveryCode()
            shown
        }
        app.api.account(ANNA).apply { blob = EncryptedBlob(ByteArray(12), ByteArray(16)); version = 1 }
        val vm = AuthViewModel(account) {}

        vm.requestReset(ANNA) {}; settle(vm)
        vm.verifyReset(app.api.resetCode(ANNA)!!) {}; settle(vm)
        // "Nie mam kodu", potem Wstecz i poprawny kod odzyskiwania
        vm.chooseNoRecoveryCode()
        var accepted = false
        vm.checkRecoveryCode(code) { accepted = true }; settle(vm)
        assertTrue(accepted)
        var showCode = true
        vm.setNewPassword("zupelnie-nowe-haslo".toCharArray()) { showCode = it }; settle(vm)

        assertFalse("nie ma nowego kodu do pokazania", showCode)
        assertTrue("completeReset" in app.api.calls)
        assertFalse("wipeReset" in app.api.calls)
        assertNotNull("kopia na serwerze przetrwała", app.api.account(ANNA).blob)
    }

    @Test fun backFromAccountReadyDoesNotReturnToRegister() {
        compose.setContent { MediTrackerTheme { AppNavHost(false, {}) } }
        compose.onNodeWithText("Utwórz konto").performClick()
        val fields = compose.onAllNodes(hasSetTextAction())
        fields[0].performTextInput(ANNA)
        fields[1].performTextInput(PASSWORD)
        fields[2].performTextInput(PASSWORD)
        compose.onNode(hasText("Utwórz konto") and hasClickAction()).performScrollTo().performClick()
        waitForText("Sprawdź swoją skrzynkę")
        compose.onAllNodes(hasSetTextAction())[0].performTextInput(app.api.verifyCode(ANNA)!!)
        compose.onNodeWithText("Potwierdź konto").performClick()
        waitForText("Witaj w mediTracker")

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()

        compose.onNodeWithText("Zacznij od swojego konta").assertDoesNotExist()
        compose.onNodeWithText("Zapisz kod odzyskiwania").assertIsDisplayed()
    }

    @Test fun restoreChoiceCanBePostponed() {
        val context = RuntimeEnvironment.getApplication() as Context
        val other = AccountRepository(app.api, AccountStore(context.getSharedPreferences("other", Context.MODE_PRIVATE), app.box), kdf = FAST, kdfBounds = BOUNDS)
        val choice = runBlocking {
            account.registered(ANNA)
            app.container.medications.add(
                Medication(
                    name = "Metformina", strength = "500 mg", form = "tabletki", unit = DoseUnit.Tablet,
                    schedule = Schedule(1, DayOfWeek.entries.toSet(), listOf(LocalTime.of(8, 0)), LocalDate.now()),
                    stock = 30, refillThreshold = 5,
                ),
            )
            other.registered(OLA)
            // sesja Anny wygasła, na tym telefonie loguje się Ola (konto bez kopii, dane Anny)
            account.onUnauthorized(account.session()!!.token)
            account.login(OLA, pw())
            app.api.calls.clear()
            app.container.backup.afterLogin()
        }
        assertTrue(choice is AfterLogin.NeedsChoice)

        compose.setContent { MediTrackerTheme { RestoreChoiceHost() } }
        compose.onNodeWithText("Zacznij z pustym kontem").assertIsDisplayed()
        compose.onNodeWithText("Później").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Zacznij z pustym kontem").assertDoesNotExist()
        assertTrue(account.decisionPending)
        assertNotNull(app.container.backup.pendingChoice.value)
        assertFalse("takeover" in app.api.calls)
        assertFalse("putBackup" in app.api.calls)
    }

    @Test fun logoutFromSettingsWipesPhoneAndShowsLoggedOutRow() {
        runBlocking {
            account.registered(ANNA)
            app.container.medications.add(
                Medication(
                    name = "Metformina", strength = "500 mg", form = "tabletki", unit = DoseUnit.Tablet,
                    schedule = Schedule(1, DayOfWeek.entries.toSet(), listOf(LocalTime.of(8, 0)), LocalDate.now()),
                    stock = 30, refillThreshold = 5,
                ),
            )
        }
        assertTrue(app.container.medications.medications.value.isNotEmpty())
        compose.setContent { MediTrackerTheme { AppNavHost(true, {}) } }
        compose.onNode(hasText("Ustawienia") and hasClickAction()).performClick()
        compose.onNodeWithText("anna@x.pl").assertIsDisplayed()
        compose.onNodeWithText("Moje konto").performClick()
        compose.onNodeWithText("Twoje konto").assertIsDisplayed()
        compose.onNodeWithText("Wyloguj się").performClick()
        compose.onNodeWithText("Wylogować się?").assertIsDisplayed()
        compose.onAllNodesWithText("Wyloguj się").onLast().performClick()
        waitForText("Zaloguj się lub utwórz konto")

        assertTrue(account.state.value is AccountState.LoggedOut)
        assertTrue("leki zniknęły z telefonu", app.container.medications.medications.value.isEmpty())
        compose.onNodeWithText("Moje konto").assertDoesNotExist()
    }

    @Test fun conflictBannerOnTodayOpensChoiceAndKeepingPhoneDataResolvesIt() {
        runBlocking {
            account.registered(ANNA)
            app.container.medications.add(
                Medication(
                    name = "Metformina", strength = "500 mg", form = "tabletki", unit = DoseUnit.Tablet,
                    schedule = Schedule(1, DayOfWeek.entries.toSet(), listOf(LocalTime.of(8, 0)), LocalDate.now()),
                    stock = 30, refillThreshold = 5,
                ),
            )
            // inny telefon przejął konto: epoch na serwerze poszedł do przodu, następne wysłanie dostaje 409
            app.api.account(ANNA).epoch += 1
            app.container.backup.upload()
        }
        compose.setContent { MediTrackerTheme { AppNavHost(true, {}) } }
        waitForText("Konto jest używane na innym urządzeniu")
        compose.onNodeWithText("Konto jest używane na innym urządzeniu").performClick()
        compose.onNodeWithText("Co zrobić z danymi?").assertIsDisplayed()
        compose.onNodeWithText("Zachowaj dane z tego telefonu").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Co zrobić z danymi?").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Konto jest używane na innym urządzeniu").assertDoesNotExist()
    }

    private fun waitForText(text: String) =
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
}
