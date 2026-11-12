package pl.agh.meditracker.data.account

import android.app.Application
import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import pl.agh.meditracker.data.account.api.ApiError
import pl.agh.meditracker.data.account.api.ApiResult
import pl.agh.meditracker.data.account.crypto.KdfParams
import pl.agh.meditracker.data.account.crypto.RecoveryCode

private const val PASSWORD = "dobre-haslo-123"
private const val EMAIL = "anna@x.pl"

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class AccountRepositoryTest {
    // małe parametry Argon2, żeby testy były szybkie; produkcyjne granice R14 tylko tam, gdzie je sprawdzamy
    private val fast = KdfParams(m = 1024, t = 1, p = 1)
    private val testBounds = KdfBounds(m = 8..1048576, t = 1..10, p = 1..4)

    private val context = RuntimeEnvironment.getApplication() as Context
    private val api = FakeAccountApi()
    private val box = PlainSecretBox()
    private val store = AccountStore(context.getSharedPreferences("account", Context.MODE_PRIVATE), box)

    // drugi telefon: osobny plik konta
    private val otherStore = AccountStore(context.getSharedPreferences("other", Context.MODE_PRIVATE), box)

    private fun TestScope.repo(s: AccountStore = store, bounds: KdfBounds = testBounds) =
        AccountRepository(api, s, StandardTestDispatcher(testScheduler), kdf = fast, kdfBounds = bounds)

    private fun pw(s: String = PASSWORD) = s.toCharArray()

    private fun ok(value: Any? = Unit) = ApiResult.Ok(value)
    private fun err(e: ApiError) = ApiResult.Err(e)

    private suspend fun AccountRepository.registered(email: String = EMAIL, password: String = PASSWORD): RecoveryCode {
        assertEquals(ok(), register(email, pw(password)))
        assertEquals(ok(), verify(api.verifyCode(email.trim().lowercase())!!))
        val code = (state.value as AccountState.ShowRecoveryCode).code
        acknowledgeRecoveryCode()
        return code
    }

    @Test
    fun registerVerifyShowsRecoveryCodeThenLoggedIn() = runTest {
        val repo = repo()
        assertEquals(AccountState.LoggedOut, repo.state.value)

        assertEquals(ok(), repo.register(EMAIL, pw()))
        assertEquals(AccountState.AwaitingVerification(EMAIL), repo.state.value)
        assertEquals(PendingRegistration.Stage.Verify, store.pending!!.stage)

        assertEquals(ok(), repo.verify(api.verifyCode(EMAIL)!!))
        val shown = repo.state.value as AccountState.ShowRecoveryCode
        assertEquals(EMAIL, shown.email)
        assertEquals(store.pending!!.recoveryCode, shown.code)
        assertNull("klucz auth niepotrzebny po weryfikacji", store.pending!!.authKey)
        val userId = api.account(EMAIL).userId
        assertEquals(userId, store.userId)
        assertEquals(userId, store.dataOwnerUserId)
        assertNotNull(store.token)

        repo.acknowledgeRecoveryCode()
        assertEquals(AccountState.LoggedIn(EMAIL, userId), repo.state.value)
        assertNull(store.pending)
        // R6: pierwsza kopia czeka na BackupSync
        assertTrue(store.dirty)
        assertNull(store.backupVersion)
        val session = repo.session()!!
        assertEquals(userId, session.userId)
        assertEquals(0L, session.epoch)
    }

    @Test
    fun resumesPendingRegistrationAfterRestart() = runTest {
        repo().register(EMAIL, pw())
        val pendingCode = store.pending!!.recoveryCode

        val restarted = repo(AccountStore(context.getSharedPreferences("account", Context.MODE_PRIVATE), box))
        assertEquals(AccountState.AwaitingVerification(EMAIL), restarted.state.value)
        assertEquals(ok(), restarted.verify(api.verifyCode(EMAIL)!!))
        assertEquals(AccountState.ShowRecoveryCode(EMAIL, pendingCode), restarted.state.value)

        // także ekran kodu odzyskiwania przeżywa restart
        val again = repo(AccountStore(context.getSharedPreferences("account", Context.MODE_PRIVATE), box))
        assertEquals(AccountState.ShowRecoveryCode(EMAIL, pendingCode), again.state.value)
        again.acknowledgeRecoveryCode()
        assertTrue(again.state.value is AccountState.LoggedIn)
    }

    @Test
    fun emailNormalizedBeforeKdfAndApi() = runTest {
        repo().registered(" Anna@X.pl")
        assertEquals(setOf(EMAIL), api.accounts.keys)
        assertEquals(EMAIL, store.email)

        val other = repo(otherStore)
        assertEquals(ok(LoginNext.Ready(null)), other.login(EMAIL, pw()))
        assertEquals(ok(LoginNext.Ready(null)), repo(otherStore).login("  ANNA@x.PL ", pw()))
    }

    @Test
    fun loginUnwrapsDekMatchingRegistration() = runTest {
        val first = repo()
        first.registered()
        val dek = first.session()!!.dek

        val second = repo(otherStore)
        assertEquals(ok(LoginNext.Ready(null)), second.login(EMAIL, pw()))
        assertArrayEquals(dek, second.session()!!.dek)
    }

    @Test
    fun loginStoresSessionEpochAndBackupVersion() = runTest {
        repo().registered()
        api.account(EMAIL).version = 7
        val repo = repo(otherStore)

        assertEquals(ok(LoginNext.Ready(7L)), repo.login(EMAIL, pw()))
        val userId = api.account(EMAIL).userId
        assertEquals(AccountState.LoggedIn(EMAIL, userId), repo.state.value)
        assertEquals(1L, otherStore.epoch)
        assertEquals(api.account(EMAIL).epoch, repo.session()!!.epoch)
        assertEquals(7L, otherStore.backupVersion)
        assertEquals(EMAIL, otherStore.email)
    }

    @Test
    fun loginDoesNotClaimDataOwner() = runTest {
        val repo = repo()
        repo.registered()
        val anna = store.userId!!
        assertFalse("świeże konto nie ma kopii, nie ma czego rozstrzygać", repo.decisionPending)
        repo(otherStore).registered("ola@x.pl")
        val ola = api.account("ola@x.pl").userId

        // inne konto na tym samym telefonie: dane wciąż należą do Anny, decyzja przywróć/zachowaj czeka
        assertEquals(ok(LoginNext.Ready(null)), repo.login("ola@x.pl", pw()))
        assertEquals(anna, store.dataOwnerUserId)
        assertTrue(repo.decisionPending)
        assertTrue("przeżywa restart", repo().decisionPending)

        repo.confirmDataOwner()
        assertEquals(ola, store.dataOwnerUserId)
        assertFalse(repo.decisionPending)

        // telefon bez właściciela danych też musi zdecydować
        val fresh = repo(AccountStore(context.getSharedPreferences("fresh", Context.MODE_PRIVATE), box))
        assertFalse(fresh.decisionPending)
        fresh.login(EMAIL, pw())
        assertTrue(fresh.decisionPending)
    }

    @Test
    fun wrongPasswordIsInvalidCredentials() = runTest {
        repo().registered()
        val repo = repo(otherStore)
        assertEquals(err(ApiError.InvalidCredentials), repo.login(EMAIL, pw("zle-haslo-123")))
        assertEquals(AccountState.LoggedOut, repo.state.value)
        assertNull(otherStore.token)
        assertEquals(err(ApiError.InvalidCredentials), repo.login("nikt@x.pl", pw()))
    }

    @Test
    fun loginToUnverifiedAccountIsInvalidCredentials() = runTest {
        repo().register(EMAIL, pw())
        val repo = repo(otherStore)
        assertEquals(err(ApiError.InvalidCredentials), repo.login(EMAIL, pw()))
        assertEquals(AccountState.LoggedOut, repo.state.value)
    }

    @Test
    fun loginClearsLeftoverPendingRegistration() = runTest {
        repo().registered()
        val repo = repo(otherStore)
        repo.register("inny@x.pl", pw())
        assertEquals(ok(LoginNext.Ready(null)), repo.login(EMAIL, pw()))
        assertNull(otherStore.pending)
        assertTrue(repo(otherStore).state.value is AccountState.LoggedIn)
    }

    @Test
    fun rejectsWeakKdfFromServer() = runTest {
        repo().registered()
        api.preloginKdfOverride = KdfParams(m = 8, t = 1, p = 1)
        val repo = repo(otherStore, bounds = KdfBounds())
        assertEquals(err(ApiError.Server), repo.login(EMAIL, pw()))
        assertFalse(api.calls.drop(api.calls.lastIndexOf("prelogin")).contains("login"))
        assertEquals(AccountState.LoggedOut, repo.state.value)
    }

    @Test
    fun rejectsHugeKdfFromServer() = runTest {
        repo().registered()
        val repo = repo(otherStore, bounds = KdfBounds())
        for (k in listOf(KdfParams(m = 4 * 1048576), KdfParams(t = 11), KdfParams(p = 5), KdfParams(m = 65535))) {
            api.preloginKdfOverride = k
            api.calls.clear()
            assertEquals(err(ApiError.Server), repo.login(EMAIL, pw()))
            assertEquals(listOf("prelogin"), api.calls)
        }
    }

    @Test
    fun productionBoundsAcceptPinnedParams() {
        assertTrue(KdfBounds().accepts(KdfParams()))
        assertTrue(KdfBounds().accepts(KdfParams(m = 1048576, t = 10, p = 4)))
    }

    @Test
    fun registerValidatesInputWithoutCallingServer() = runTest {
        val repo = repo()
        assertEquals(err(ApiError.InvalidInput), repo.register("bez-malpy", pw()))
        assertEquals(err(ApiError.InvalidInput), repo.register("a@b", pw()))
        assertEquals(err(ApiError.InvalidInput), repo.register(EMAIL, pw("krotkie12")))
        assertTrue(api.calls.isEmpty())
        assertNull(store.pending)
        assertEquals(AccountState.LoggedOut, repo.state.value)
    }

    @Test
    fun registerNetworkErrorClearsPendingAndStaysPut() = runTest {
        val repo = repo()
        api.offline = true
        assertEquals(err(ApiError.Network), repo.register(EMAIL, pw()))
        assertNull(store.pending)
        assertEquals(AccountState.LoggedOut, repo.state.value)

        api.offline = false
        api.registerError = ApiError.RateLimited
        assertEquals(err(ApiError.RateLimited), repo.register(EMAIL, pw()))
        assertNull(store.pending)
        assertEquals(AccountState.LoggedOut, repo.state.value)

        // ponowna próba z tego samego formularza
        api.registerError = null
        assertEquals(ok(), repo.register(EMAIL, pw()))
        assertEquals(AccountState.AwaitingVerification(EMAIL), repo.state.value)
        assertEquals(ok(), repo.verify(api.verifyCode(EMAIL)!!))
        assertTrue(repo.state.value is AccountState.ShowRecoveryCode)
    }

    @Test
    fun concurrentRegisterCallsLeaveConsistentPending() = runTest {
        val repo = repo()
        // pierwsze żądanie dochodzi do serwera później niż drugie
        var first = true
        api.beforeCall = { name ->
            if (name == "register" && first) {
                first = false
                delay(1000)
            }
        }
        val a = async { repo.register(EMAIL, pw()) }
        val b = async { repo.register(EMAIL, pw()) }
        assertEquals(listOf(ok(), ok()), awaitAll(a, b))

        val pending = store.pending!!
        assertTrue("lokalny klucz auth == ostatnio zapisany na serwerze", api.authKeyMatches(EMAIL, pending.authKey!!))
        assertEquals(ok(), repo.verify(api.verifyCode(EMAIL)!!))
        assertEquals(AccountState.ShowRecoveryCode(EMAIL, pending.recoveryCode), repo.state.value)
    }

    @Test
    fun wrongVerifyCodeKeepsAwaitingAndResendWorks() = runTest {
        val repo = repo()
        repo.register(EMAIL, pw())
        val code = api.verifyCode(EMAIL)!!
        val wrong = if (code == "000000") "111111" else "000000"
        assertEquals(err(ApiError.InvalidCode), repo.verify(wrong))
        assertEquals(AccountState.AwaitingVerification(EMAIL), repo.state.value)

        assertEquals(ok(), repo.resendCode())
        val fresh = api.verifyCode(EMAIL)!!
        assertEquals(ok(), repo.verify(" $fresh "))
        assertTrue(repo.state.value is AccountState.ShowRecoveryCode)
    }

    @Test
    fun cancelRegistrationGoesBackToLoggedOut() = runTest {
        val repo = repo()
        repo.register(EMAIL, pw())
        repo.cancelRegistration()
        assertNull(store.pending)
        assertEquals(AccountState.LoggedOut, repo.state.value)
        assertEquals(AccountState.LoggedOut, repo().state.value)
    }

    @Test
    fun changePasswordKeepsDek() = runTest {
        val repo = repo()
        repo.registered()
        val dek = repo.session()!!.dek

        assertEquals(err(ApiError.InvalidCredentials), repo.changePassword(pw("zle-haslo-123"), pw("nowe-haslo-456")))
        assertEquals(err(ApiError.InvalidInput), repo.changePassword(pw(), pw("krotkie")))
        assertEquals(ok(), repo.changePassword(pw(), pw("nowe-haslo-456")))
        assertTrue("bieżąca sesja zostaje", repo.state.value is AccountState.LoggedIn)
        assertEquals(1, api.sessionCount(EMAIL))

        val other = repo(otherStore)
        assertEquals(err(ApiError.InvalidCredentials), other.login(EMAIL, pw()))
        assertEquals(ok(LoginNext.Ready(null)), other.login(EMAIL, pw("nowe-haslo-456")))
        assertArrayEquals(dek, other.session()!!.dek)
    }

    @Test
    fun resetWithRecoveryCodeKeepsDek() = runTest {
        val first = repo()
        val code = first.registered()
        val dek = first.session()!!.dek

        val repo = repo(otherStore)
        assertEquals(ok(), repo.requestReset(" Anna@x.pl"))
        assertEquals(ok(), repo.verifyReset(EMAIL, api.resetCode(EMAIL)!!))

        val calls = api.calls.size
        assertFalse(repo.checkRecoveryCode(RecoveryCode.generate().display()))
        assertFalse(repo.checkRecoveryCode("to nie jest kod"))
        assertEquals("zły kod sprawdzany lokalnie", calls, api.calls.size)

        assertTrue(repo.checkRecoveryCode(code.display().lowercase().replace("-", " ")))
        assertEquals(ok(), repo.completeReset(pw("nowe-haslo-456")))
        assertEquals(AccountState.LoggedOut, repo.state.value)
        assertEquals("reset unieważnia wszystkie sesje", 0, api.sessionCount(EMAIL))

        assertEquals(err(ApiError.InvalidCredentials), repo.login(EMAIL, pw()))
        assertEquals(ok(LoginNext.Ready(null)), repo.login(EMAIL, pw("nowe-haslo-456")))
        assertArrayEquals(dek, repo.session()!!.dek)
    }

    @Test
    fun completeResetNeedsCheckedRecoveryCode() = runTest {
        repo().registered()
        val repo = repo(otherStore)
        assertEquals(err(ApiError.InvalidInput), repo.completeReset(pw("nowe-haslo-456")))
        repo.requestReset(EMAIL)
        repo.verifyReset(EMAIL, api.resetCode(EMAIL)!!)
        assertEquals(err(ApiError.InvalidInput), repo.completeReset(pw("nowe-haslo-456")))
        assertTrue(api.calls.none { it == "completeReset" })
    }

    @Test
    fun resetOfLoggedInAccountClearsSessionButKeepsDataOwner() = runTest {
        val repo = repo()
        val code = repo.registered()
        val userId = store.userId
        repo.requestReset(EMAIL)
        repo.verifyReset(EMAIL, api.resetCode(EMAIL)!!)
        assertTrue(repo.checkRecoveryCode(code.display()))
        assertEquals(ok(), repo.completeReset(pw("nowe-haslo-456")))
        assertEquals(AccountState.LoggedOut, repo.state.value)
        assertNull(store.token)
        assertNull(store.dek)
        assertNull(store.email)
        assertEquals(userId, store.dataOwnerUserId)
        assertEquals(AccountState.LoggedOut, repo().state.value)
    }

    @Test
    fun wipeResetGivesNewDekAndNewCode() = runTest {
        val first = repo()
        val oldCode = first.registered()
        val oldDek = first.session()!!.dek
        api.account(EMAIL).version = 3

        val repo = repo(otherStore)
        repo.requestReset(EMAIL)
        repo.verifyReset(EMAIL, api.resetCode(EMAIL)!!)
        val result = repo.wipeReset(pw("nowe-haslo-456"))
        val newCode = (result as ApiResult.Ok).value
        assertNotEquals(oldCode, newCode)
        // R7: sesje unieważnione, trzeba się zalogować
        assertEquals(AccountState.LoggedOut, repo.state.value)
        assertNull(api.account(EMAIL).blob)
        assertEquals(3L, api.account(EMAIL).version)

        assertEquals(ok(LoginNext.Ready(3L)), repo.login(EMAIL, pw("nowe-haslo-456")))
        assertFalse(oldDek.contentEquals(repo.session()!!.dek))
        val newDek = repo.session()!!.dek

        // nowy kod otwiera nowy slot 2, stary już nie
        repo.requestReset(EMAIL)
        repo.verifyReset(EMAIL, api.resetCode(EMAIL)!!)
        assertFalse(repo.checkRecoveryCode(oldCode.display()))
        assertTrue(repo.checkRecoveryCode(newCode.display()))
        assertEquals(ok(), repo.completeReset(pw("trzecie-haslo-789")))
        repo.login(EMAIL, pw("trzecie-haslo-789"))
        assertArrayEquals(newDek, repo.session()!!.dek)
    }

    @Test
    fun wipeResetValidatesPasswordAndToken() = runTest {
        repo().registered()
        val repo = repo(otherStore)
        assertEquals(err(ApiError.InvalidInput), repo.wipeReset(pw("nowe-haslo-456")))
        repo.requestReset(EMAIL)
        repo.verifyReset(EMAIL, api.resetCode(EMAIL)!!)
        assertEquals(err(ApiError.InvalidInput), repo.wipeReset(pw("krotkie")))
        assertTrue(api.calls.none { it == "wipeReset" })
    }

    @Test
    fun unauthorizedMovesToSessionExpiredWithoutClearingDataOwner() = runTest {
        val repo = repo()
        repo.registered()
        val userId = store.userId!!
        val dek = repo.session()!!.dek

        repo.onUnauthorized(store.token!!)
        assertEquals(AccountState.SessionExpired(EMAIL), repo.state.value)
        assertNull(repo.session())
        assertNull(store.token)
        assertEquals(userId, store.dataOwnerUserId)
        assertTrue("niewysłane zmiany zostają", store.dirty)
        assertEquals(AccountState.SessionExpired(EMAIL), repo().state.value)

        assertEquals(ok(LoginNext.Ready(null)), repo.login(EMAIL, pw()))
        assertEquals(AccountState.LoggedIn(EMAIL, userId), repo.state.value)
        assertArrayEquals(dek, repo.session()!!.dek)
        assertFalse("to samo konto: zwykła synchronizacja", repo.decisionPending)
    }

    @Test
    fun staleUnauthorizedDoesNotEndNewerSession() = runTest {
        val repo = repo()
        repo.registered()
        val old = store.token!!
        repo.login(EMAIL, pw())
        val current = store.token!!
        assertNotEquals(old, current)

        repo.onUnauthorized(old)
        assertTrue(repo.state.value is AccountState.LoggedIn)
        assertEquals(current, store.token)

        repo.onUnauthorized(current)
        assertEquals(AccountState.SessionExpired(EMAIL), repo.state.value)
    }

    @Test
    fun unauthorizedResponseFromServerExpiresSession() = runTest {
        val repo = repo()
        repo.registered()
        api.revokeAllSessions()
        assertEquals(err(ApiError.Unauthorized), repo.changePassword(pw(), pw("nowe-haslo-456")))
        assertEquals(AccountState.SessionExpired(EMAIL), repo.state.value)
    }

    @Test
    fun deleteAccountClearsStoreButNotLocalData() = runTest {
        val repo = repo()
        repo.registered()
        val userId = store.userId

        assertEquals(err(ApiError.InvalidCredentials), repo.deleteAccount(pw("zle-haslo-123")))
        assertTrue(repo.state.value is AccountState.LoggedIn)

        assertEquals(ok(), repo.deleteAccount(pw()))
        assertEquals(AccountState.LoggedOut, repo.state.value)
        assertTrue(api.accounts.isEmpty())
        assertNull(store.token)
        assertNull(store.dek)
        assertNull(store.email)
        assertNull(store.userId)
        // dane na telefonie zostają i wciąż wiadomo, z którego konta pochodzą
        assertEquals(userId, store.dataOwnerUserId)
        assertEquals(AccountState.LoggedOut, repo().state.value)
    }

    @Test
    fun unreadableSecretsGiveSessionExpired() = runTest {
        repo().registered()
        box.failOpen = true
        val repo = repo()
        assertEquals(AccountState.SessionExpired(EMAIL), repo.state.value)
        assertNull(repo.session())
    }

    @Test
    fun unreadablePendingGivesLoggedOut() = runTest {
        repo().register(EMAIL, pw())
        box.failOpen = true
        assertNull(store.pending)
        assertEquals(AccountState.LoggedOut, repo().state.value)
    }

    @Test
    fun storeRoundTripsPlainFields() {
        val now = java.time.Instant.ofEpochMilli(1_700_000_000_123)
        store.lastBackupAt = now
        store.backupVersion = 5
        store.epoch = 2
        store.dirty = true
        val reopened = AccountStore(context.getSharedPreferences("account", Context.MODE_PRIVATE), box)
        assertEquals(now, reopened.lastBackupAt)
        assertEquals(5L, reopened.backupVersion)
        assertEquals(2L, reopened.epoch)
        assertTrue(reopened.dirty)
        reopened.backupVersion = null
        assertNull(store.backupVersion)
    }

    @Test
    fun secretsAreStoredSealed() = runTest {
        repo().registered()
        val raw = context.getSharedPreferences("account", Context.MODE_PRIVATE).all
        val token = store.token!!
        assertTrue(raw.values.none { it == token })
    }

    @Test
    fun passwordArraysAreZeroed() = runTest {
        val repo = repo()
        val password = pw()
        repo.register(EMAIL, password)
        assertTrue(password.all { it == '\u0000' })
        repo.verify(api.verifyCode(EMAIL)!!)
        repo.acknowledgeRecoveryCode()

        val login = pw()
        repo(otherStore).login(EMAIL, login)
        assertTrue(login.all { it == '\u0000' })

        val old = pw()
        val new = pw("nowe-haslo-456")
        repo.changePassword(old, new)
        assertTrue(old.all { it == '\u0000' } && new.all { it == '\u0000' })

        val short = pw("krotkie")
        repo.register(EMAIL, short)
        assertTrue(short.all { it == '\u0000' })
    }
}
