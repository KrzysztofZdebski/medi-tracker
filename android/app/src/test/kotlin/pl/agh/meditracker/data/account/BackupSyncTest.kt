package pl.agh.meditracker.data.account

import android.app.Application
import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import pl.agh.meditracker.data.account.api.ApiError
import pl.agh.meditracker.data.account.api.ApiResult
import pl.agh.meditracker.data.account.api.PutOutcome
import pl.agh.meditracker.data.account.crypto.EncryptedBlob
import pl.agh.meditracker.data.account.crypto.KdfParams
import pl.agh.meditracker.data.account.crypto.blobAad
import pl.agh.meditracker.data.account.crypto.decryptBlob
import pl.agh.meditracker.data.account.crypto.encryptBlob
import pl.agh.meditracker.data.repository.MedicationRepository
import pl.agh.meditracker.data.settings.AppTheme
import pl.agh.meditracker.data.settings.SettingsStore
import pl.agh.meditracker.data.user.RoomMedicationStore
import pl.agh.meditracker.data.user.Snapshot
import pl.agh.meditracker.data.user.SnapshotCodec
import pl.agh.meditracker.data.user.SyncedSettings
import pl.agh.meditracker.data.user.UserDatabase
import pl.agh.meditracker.model.DoseUnit
import pl.agh.meditracker.model.Medication
import pl.agh.meditracker.model.Schedule
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private const val PASSWORD = "dobre-haslo-123"
private const val EMAIL = "anna@x.pl"
private const val OLA = "ola@x.pl"

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BackupSyncTest {
    private val fast = KdfParams(m = 1024, t = 1, p = 1)
    private val testBounds = KdfBounds(m = 8..1048576, t = 1..10, p = 1..4)

    // jeden zegar wirtualny dla synchronizacji, kolejki zapisów repozytorium i Argon2
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)

    private val context = RuntimeEnvironment.getApplication() as Context
    private val api = FakeAccountApi()
    private val box = PlainSecretBox()
    private val zone = ZoneId.of("Europe/Warsaw")
    private val today = LocalDate.of(2026, 11, 12)
    private val clock = Clock.fixed(today.atTime(9, 0).atZone(zone).toInstant(), zone)

    // ten telefon
    private val db = Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java).allowMainThreadQueries().build()
    private val repo = MedicationRepository(clock, RoomMedicationStore(db.dao()), writeDispatcher = dispatcher)
    private val settings = SettingsStore(context)
    private val accountStore = AccountStore(prefs("account"), box)
    private val account = accountRepo(accountStore)

    // drugi telefon tego samego (albo innego) konta: własna baza i własny plik konta
    private val otherDb = Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java).allowMainThreadQueries().build()
    private val otherRepo = MedicationRepository(clock, RoomMedicationStore(otherDb.dao()), writeDispatcher = dispatcher)
    private val otherStore = AccountStore(prefs("other"), box)
    private val other = accountRepo(otherStore)

    @After fun close() {
        db.close()
        otherDb.close()
    }

    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private fun accountRepo(s: AccountStore) = AccountRepository(api, s, dispatcher, kdf = fast, kdfBounds = testBounds)
    private fun test(body: suspend TestScope.() -> Unit) = runTest(dispatcher) { body() }
    private fun pw() = PASSWORD.toCharArray()

    private fun TestScope.sync(scope: CoroutineScope = backgroundScope, acc: AccountRepository = account) =
        BackupSync(repo, settings, acc, accountStore, api, scope, clock).also { it.start() }

    private suspend fun AccountRepository.registered(email: String = EMAIL) {
        assertEquals(ApiResult.Ok(Unit), register(email, pw()))
        assertEquals(ApiResult.Ok(Unit), verify(api.verifyCode(email)!!))
        acknowledgeRecoveryCode()
    }

    private fun med(name: String) = Medication(
        name = name, strength = "500 mg", form = "tabletki", unit = DoseUnit.Tablet,
        schedule = Schedule(1, DayOfWeek.entries.toSet(), listOf(LocalTime.of(8, 0)), today),
        stock = 30, refillThreshold = 5,
    )

    private fun puts() = api.calls.count { it == "putBackup" }
    private fun localMeds() = repo.medications.value.map { it.name }.toSet()
    private fun storedMeds() = RoomMedicationStore(db.dao()).load().medications.map { it.name }.toSet()

    private fun serverSnapshot(email: String = EMAIL): Snapshot {
        val a = api.account(email)
        val dek = (if (account.session()?.userId == a.userId) account else other).session()!!.dek
        return SnapshotCodec.decode(decryptBlob(dek, a.blob!!, blobAad(a.userId)))
    }

    private fun serverMeds(email: String = EMAIL) = serverSnapshot(email).medications.map { it.medication.name }.toSet()

    // drugi telefon (zalogowany w `other`) wysyła własne dane
    private suspend fun otherPhoneUploads(vararg names: String, synced: SyncedSettings = SyncedSettings(AppTheme.Dark, 30)) {
        names.forEach { otherRepo.add(med(it)) }
        val s = other.session()!!
        val snapshot = otherRepo.dumpSnapshot(synced)
        val blob = encryptBlob(s.dek, SnapshotCodec.encode(snapshot, Instant.EPOCH), blobAad(s.userId))
        val email = otherStore.email!!
        val result = api.putBackup(s.token, s.epoch, api.account(email).version, blob)
        assertTrue("$result", (result as ApiResult.Ok).value is PutOutcome.Saved)
    }

    // --- automatyczna kopia ---

    @Test
    fun registrationUploadsFirstBackup() = test {
        account.registered()
        val sync = sync()
        runCurrent()

        assertEquals("pierwsza kopia po rejestracji (R6)", 1, puts())
        assertEquals(1L, api.account(EMAIL).version)
        assertEquals(1L, accountStore.backupVersion)
        assertEquals(clock.instant(), accountStore.lastBackupAt)
        assertFalse(accountStore.dirty)
        assertEquals(SyncStatus.Idle(clock.instant()), sync.status.value)
        assertEquals(emptySet<String>(), serverMeds())
    }

    @Test
    fun changeTriggersUploadAfterDebounce() = test {
        account.registered()
        val sync = sync()
        runCurrent()

        repo.add(med("Metformina"))
        advanceTimeBy(3_000); runCurrent()
        repo.add(med("Apap")) // kolejna zmiana przesuwa odliczanie
        assertEquals(SyncStatus.Pending, sync.status.value)
        advanceTimeBy(4_999); runCurrent()
        assertEquals(1, puts())
        advanceTimeBy(1); runCurrent()

        assertEquals(2, puts())
        assertEquals(setOf("Metformina", "Apap"), serverMeds())
        assertFalse(accountStore.dirty)
        assertEquals(SyncStatus.Idle(clock.instant()), sync.status.value)
    }

    @Test
    fun syncedSettingChangeTriggersUpload() = test {
        account.registered()
        sync()
        runCurrent()

        settings.theme = AppTheme.Dark
        settings.remindersEnabled = true // nie jedzie z kopią
        advanceTimeBy(5_000); runCurrent()

        assertEquals(2, puts())
        assertEquals(SyncedSettings(AppTheme.Dark, 15), serverSnapshot().settings)
        settings.remindersEnabled = false
        advanceTimeBy(60_000); runCurrent()
        assertEquals("przypomnienia są lokalne", 2, puts())
    }

    @Test
    fun networkFailureKeepsDirtyAndRetries() = test {
        account.registered()
        val sync = sync()
        runCurrent()

        api.offline = true
        repo.add(med("Metformina"))
        advanceTimeBy(5_000); runCurrent()
        assertEquals(2, puts())
        assertEquals(SyncStatus.Failed, sync.status.value)
        assertTrue(accountStore.dirty)

        advanceTimeBy(30_000); runCurrent()
        assertEquals("pierwsza ponowna próba po 30 s", 3, puts())
        assertEquals(SyncStatus.Failed, sync.status.value)

        api.offline = false
        advanceTimeBy(59_999); runCurrent()
        assertEquals(3, puts())
        advanceTimeBy(1); runCurrent()
        assertEquals("druga po kolejnych 60 s", 4, puts())
        assertEquals(setOf("Metformina"), serverMeds())
        assertFalse(accountStore.dirty)
        assertEquals(SyncStatus.Idle(clock.instant()), sync.status.value)
    }

    @Test
    fun backoffDoublesUpToFiveMinutes() = test {
        account.registered()
        sync()
        runCurrent()
        val times = mutableListOf<Long>()
        api.beforeCall = { if (it == "putBackup") times += currentTime }
        api.offline = true

        repo.add(med("Metformina"))
        advanceTimeBy(30 * 60_000L); runCurrent()

        val gaps = times.zipWithNext { a, b -> (b - a) / 1000 }
        assertEquals(listOf(30L, 60L, 120L, 240L, 300L, 300L), gaps.take(6))
        assertTrue(gaps.drop(4).all { it == 300L })
    }

    @Test
    fun newChangeCancelsBackoffAndDebouncesAgain() = test {
        account.registered()
        sync()
        runCurrent()
        api.offline = true
        repo.add(med("Metformina"))
        advanceTimeBy(5_000); runCurrent()
        assertEquals(2, puts())

        api.offline = false
        advanceTimeBy(10_000); runCurrent()
        repo.add(med("Apap"))
        advanceTimeBy(5_000); runCurrent()
        assertEquals("po 5 s od zmiany, nie po 30 s od błędu", 3, puts())
        assertEquals(setOf("Metformina", "Apap"), serverMeds())
        advanceTimeBy(10 * 60_000L); runCurrent()
        assertEquals("anulowana ponowna próba nie wysyła drugi raz", 3, puts())
    }

    @Test
    fun concurrentUploadsPutOnce() = test {
        account.registered()
        val sync = sync()
        val results = listOf(async { sync.upload() }, async { sync.upload() }).awaitAll()
        runCurrent()

        assertEquals(1, puts())
        assertEquals(1L, api.account(EMAIL).version)
        assertTrue(results.none { it == SyncStatus.Conflict })
        assertEquals(SyncStatus.Idle(clock.instant()), sync.status.value)
    }

    @Test
    fun changeDuringUploadKeepsDirty() = test {
        account.registered()
        val sync = sync()
        var added = false
        api.beforeCall = {
            if (it == "putBackup" && !added) {
                added = true
                repo.add(med("Apap"))
                delay(1)
            }
        }
        runCurrent(); advanceTimeBy(1); runCurrent()

        assertEquals(1, puts())
        assertEquals("kopia sprzed zmiany", emptySet<String>(), serverMeds())
        assertTrue(accountStore.dirty)
        assertEquals(SyncStatus.Pending, sync.status.value)

        advanceTimeBy(5_000); runCurrent()
        assertEquals(2, puts())
        assertEquals(setOf("Apap"), serverMeds())
        assertFalse(accountStore.dirty)
    }

    @Test
    fun expiredSessionMovesToSessionExpiredAndKeepsData() = test {
        account.registered()
        val sync = sync()
        runCurrent()

        api.revokeAllSessions()
        repo.add(med("Metformina"))
        advanceTimeBy(5_000); runCurrent()

        assertEquals(AccountState.SessionExpired(EMAIL), account.state.value)
        assertEquals(setOf("Metformina"), localMeds())
        assertTrue(accountStore.dirty)
        assertEquals(SyncStatus.Pending, sync.status.value)
        val before = puts()
        repo.add(med("Apap"))
        advanceTimeBy(30 * 60_000L); runCurrent()
        assertEquals("bez sesji nic nie wysyłamy", before, puts())
        assertEquals(setOf("Metformina", "Apap"), localMeds())
    }

    @Test
    fun reloginToSameAccountContinuesSync() = test {
        account.registered()
        val sync = sync()
        runCurrent()
        api.revokeAllSessions()
        repo.add(med("Metformina"))
        advanceTimeBy(5_000); runCurrent()
        assertEquals(AccountState.SessionExpired(EMAIL), account.state.value)

        assertTrue(account.login(EMAIL, pw()) is ApiResult.Ok)
        runCurrent()
        assertEquals("zaległe zmiany wysłane po ponownym logowaniu", setOf("Metformina"), serverMeds())
        assertFalse(accountStore.dirty)

        val before = puts()
        assertEquals(AfterLogin.Uploaded, sync.afterLogin())
        assertNull(sync.pendingChoice.value)
        assertEquals("nic nowego do wysłania", before, puts())
        assertEquals(setOf("Metformina"), localMeds())
    }

    @Test
    fun otherPhoneLoginCausesConflict() = test {
        account.registered()
        val sync = sync()
        runCurrent()

        assertTrue(other.login(EMAIL, pw()) is ApiResult.Ok) // podbija epoch
        repo.add(med("Metformina"))
        advanceTimeBy(5_000); runCurrent()
        assertEquals(SyncStatus.Conflict, sync.status.value)
        assertEquals(2, puts())

        repo.add(med("Apap"))
        advanceTimeBy(30 * 60_000L); runCurrent()
        assertEquals("po konflikcie bez automatycznych prób", 2, puts())
        assertEquals(SyncStatus.Conflict, sync.status.value)
        assertTrue(accountStore.dirty)
        assertTrue("telefon nie jest wylogowywany", account.state.value is AccountState.LoggedIn)
    }

    // inny telefon przejął konto i wysłał swoje dane; ten ma lokalną zmianę i dostaje konflikt
    private suspend fun TestScope.conflictWithOtherPhone(): BackupSync {
        account.registered()
        val sync = sync()
        runCurrent()
        repo.add(med("Metformina"))
        advanceTimeBy(5_000); runCurrent()
        other.login(EMAIL, pw())
        otherPhoneUploads("Ibuprom")
        repo.add(med("Apap"))
        advanceTimeBy(5_000); runCurrent()
        assertEquals(SyncStatus.Conflict, sync.status.value)
        return sync
    }

    @Test
    fun resolveRestoreReplacesLocalData() = test {
        val sync = conflictWithOtherPhone()
        val before = puts()

        assertEquals(ApiResult.Ok(Unit), sync.resolve(restore = true))
        runCurrent()
        assertEquals(setOf("Ibuprom"), localMeds())
        assertEquals(setOf("Ibuprom"), storedMeds())
        assertEquals(SyncedSettings(AppTheme.Dark, 30), SyncedSettings(settings.theme, settings.snoozeMinutes))
        assertEquals(api.account(EMAIL).epoch, accountStore.epoch)
        assertEquals(api.account(EMAIL).version, accountStore.backupVersion)
        assertFalse(accountStore.dirty)
        assertEquals(SyncStatus.Idle(clock.instant()), sync.status.value)
        advanceTimeBy(30 * 60_000L); runCurrent()
        assertEquals("przywrócenie nie wysyła się z powrotem", before, puts())

        // ten telefon jest teraz aktywny: zwykła synchronizacja działa dalej
        repo.add(med("Nowy"))
        advanceTimeBy(5_000); runCurrent()
        assertEquals(setOf("Ibuprom", "Nowy"), serverMeds())
        assertEquals(SyncStatus.Idle(clock.instant()), sync.status.value)
    }

    @Test
    fun resolveKeepUploadsLocalData() = test {
        val sync = conflictWithOtherPhone()

        assertEquals(ApiResult.Ok(Unit), sync.resolve(restore = false))
        runCurrent()
        assertEquals(setOf("Metformina", "Apap"), serverMeds())
        assertEquals(setOf("Metformina", "Apap"), localMeds())
        assertEquals(SyncedSettings(), serverSnapshot().settings)
        assertFalse(accountStore.dirty)
        assertEquals(SyncStatus.Idle(clock.instant()), sync.status.value)

        // drugi telefon dostaje teraz konflikt
        val s = other.session()!!
        val stale = api.putBackup(s.token, s.epoch, api.account(EMAIL).version, EncryptedBlob(ByteArray(12), ByteArray(32)))
        assertTrue((stale as ApiResult.Ok).value is PutOutcome.Conflict)
    }

    // --- decyzja po logowaniu ---

    @Test
    fun afterLoginEmptyPhoneRestoresSilently() = test {
        other.registered()
        otherPhoneUploads("Ibuprom")
        val sync = sync()
        val before = puts()

        assertTrue(account.login(EMAIL, pw()) is ApiResult.Ok)
        assertEquals(AfterLogin.Restored, sync.afterLogin())
        runCurrent()

        assertEquals(setOf("Ibuprom"), localMeds())
        assertEquals(setOf("Ibuprom"), storedMeds())
        assertEquals(AppTheme.Dark, settings.theme)
        assertFalse(account.decisionPending)
        assertEquals(api.account(EMAIL).userId, accountStore.dataOwnerUserId)
        assertEquals(api.account(EMAIL).version, accountStore.backupVersion)
        assertNull(sync.pendingChoice.value)
        assertEquals(before, puts())
    }

    @Test
    fun restoreDoesNotTriggerUpload() = test {
        other.registered()
        otherPhoneUploads("Ibuprom", synced = SyncedSettings(AppTheme.Light, 60))
        val sync = sync()
        settings.snoozeMinutes = 30 // zmiana przed logowaniem: dane telefonu są brudne
        runCurrent()
        val before = puts()

        account.login(EMAIL, pw())
        assertEquals(AfterLogin.Restored, sync.afterLogin())
        advanceTimeBy(30 * 60_000L); runCurrent()

        assertEquals(SyncedSettings(AppTheme.Light, 60), SyncedSettings(settings.theme, settings.snoozeMinutes))
        assertEquals("ani podmiana danych, ani applySynced nie oznaczają zmian", before, puts())
        assertFalse(accountStore.dirty)
        assertEquals(SyncStatus.Idle(clock.instant()), sync.status.value)

        // własna zmiana po przywróceniu znów idzie normalnie
        settings.theme = AppTheme.Dark
        advanceTimeBy(5_000); runCurrent()
        assertEquals(before + 1, puts())
        assertEquals(SyncedSettings(AppTheme.Dark, 60), serverSnapshot().settings)
    }

    @Test
    fun afterLoginWithLocalDataNeedsChoice() = test {
        other.registered()
        otherPhoneUploads("Ibuprom")
        val sync = sync()
        repo.add(med("Metformina"))
        runCurrent()
        val before = puts()

        account.login(EMAIL, pw())
        assertEquals(AfterLogin.NeedsChoice(fromOtherAccount = false), sync.afterLogin())
        assertEquals(AfterLogin.NeedsChoice(fromOtherAccount = false), sync.pendingChoice.value)
        assertTrue(account.decisionPending)

        repo.add(med("Apap"))
        advanceTimeBy(30 * 60_000L); runCurrent()
        assertEquals("przed decyzją nic nie nadpisuje kopii konta", before, puts())
        assertEquals(setOf("Ibuprom"), serverMeds())
        assertEquals(setOf("Metformina", "Apap"), localMeds())
    }

    @Test
    fun noUploadBetweenLoginAndDecision() = test {
        other.registered()
        otherPhoneUploads("Ibuprom")
        sync()
        repo.add(med("Metformina"))
        runCurrent()
        val before = puts()

        // UI jeszcze nie zawołało afterLogin (albo proces zginął zaraz po logowaniu), a dane są brudne
        account.login(EMAIL, pw())
        repo.add(med("Apap"))
        advanceTimeBy(30 * 60_000L); runCurrent()

        assertTrue(account.decisionPending)
        assertEquals(before, puts())
        assertEquals(setOf("Ibuprom"), serverMeds())
    }

    @Test
    fun pendingChoiceIsRaisedAgainAfterRestart() = test {
        other.registered()
        otherPhoneUploads("Ibuprom")
        val firstRun = CoroutineScope(dispatcher + Job())
        val first = sync(firstRun)
        repo.add(med("Metformina"))
        account.login(EMAIL, pw())
        assertTrue(first.afterLogin() is AfterLogin.NeedsChoice)
        firstRun.cancel() // proces ginie z otwartym pytaniem
        val before = puts()

        val restartedAccount = accountRepo(AccountStore(prefs("account"), box))
        val sync = sync(acc = restartedAccount)
        runCurrent()
        assertEquals(AfterLogin.NeedsChoice(fromOtherAccount = false), sync.pendingChoice.value)
        advanceTimeBy(30 * 60_000L); runCurrent()
        assertEquals(before, puts())

        assertEquals(ApiResult.Ok(Unit), sync.resolve(restore = false))
        assertEquals(setOf("Metformina"), serverMeds())
        assertFalse(restartedAccount.decisionPending)
        assertNull(sync.pendingChoice.value)
    }

    @Test
    fun afterLoginNoBackupUploads() = test {
        other.registered()
        // po resecie z utratą kopii serwer trzyma licznik wersji bez blobu (R7, R12)
        api.account(EMAIL).version = 3
        val sync = sync()
        repo.add(med("Metformina"))
        runCurrent()

        account.login(EMAIL, pw())
        assertEquals(AfterLogin.Uploaded, sync.afterLogin())
        runCurrent()

        assertEquals(setOf("Metformina"), serverMeds())
        assertEquals(4L, api.account(EMAIL).version)
        assertEquals(4L, accountStore.backupVersion)
        assertFalse(account.decisionPending)
        assertFalse(accountStore.dirty)
        assertEquals(SyncStatus.Idle(clock.instant()), sync.status.value)
    }

    @Test
    fun afterLoginBothEmptyUploadsEmptyBackup() = test {
        other.registered()
        val sync = sync()
        account.login(EMAIL, pw())

        assertEquals(AfterLogin.Uploaded, sync.afterLogin())
        runCurrent()
        assertEquals(emptySet<String>(), serverMeds())
        assertFalse(account.decisionPending)
    }

    @Test
    fun afterLoginDifferentDataOwnerFlagsOtherAccount() = test {
        account.registered()
        val sync = sync()
        runCurrent()
        repo.add(med("Metformina"))
        advanceTimeBy(5_000); runCurrent()
        other.registered(OLA)
        otherPhoneUploads("Ibuprom")
        val before = puts()

        // sesja Anny wygasła, na tym telefonie loguje się Ola
        account.onUnauthorized(accountStore.token!!)
        assertTrue(account.login(OLA, pw()) is ApiResult.Ok)
        assertEquals(AfterLogin.NeedsChoice(fromOtherAccount = true), sync.afterLogin())
        assertEquals(AfterLogin.NeedsChoice(fromOtherAccount = true), sync.pendingChoice.value)

        advanceTimeBy(30 * 60_000L); runCurrent()
        assertEquals(before, puts())
        assertEquals(setOf("Ibuprom"), serverMeds(OLA))
        assertEquals(setOf("Metformina"), localMeds())
    }

    @Test
    fun restoreFailureKeepsLocalData() = test {
        other.registered()
        val sync = sync()
        repo.add(med("Metformina"))
        runCurrent()
        account.login(EMAIL, pw())
        val s = account.session()!!
        val a = api.account(EMAIL)

        // nie do odszyfrowania (losowe bajty) i poprawnie zaszyfrowane śmieci (nie da się zdekodować)
        val corrupt = listOf(
            EncryptedBlob(ByteArray(12), ByteArray(64) { it.toByte() }),
            encryptBlob(s.dek, "to nie jest gzip".toByteArray(), blobAad(s.userId)),
            encryptBlob(s.dek, SnapshotCodec.encode(otherRepo.dumpSnapshot(SyncedSettings()), Instant.EPOCH), blobAad("inne-konto")),
        )
        corrupt.forEach { blob ->
            a.blob = blob
            a.version = 5
            assertTrue(sync.afterLogin() is AfterLogin.NeedsChoice)
            assertEquals(ApiResult.Err(ApiError.UnreadableBackup), sync.resolve(restore = true))
            runCurrent()
            assertEquals(setOf("Metformina"), localMeds())
            assertEquals(setOf("Metformina"), storedMeds())
            assertTrue("decyzja wciąż czeka", account.decisionPending)
            assertEquals(AfterLogin.NeedsChoice(fromOtherAccount = false), sync.pendingChoice.value)
        }
    }

    @Test
    fun afterLoginWithUnreadableBackupOnEmptyPhoneKeepsDecisionPending() = test {
        other.registered()
        api.account(EMAIL).blob = EncryptedBlob(ByteArray(12), ByteArray(64))
        api.account(EMAIL).version = 1
        val sync = sync()
        account.login(EMAIL, pw())

        assertEquals(AfterLogin.Failed(ApiError.UnreadableBackup), sync.afterLogin())
        assertTrue(account.decisionPending)
        assertEquals(emptySet<String>(), localMeds())
    }

    @Test
    fun afterLoginOfflineRetriesInBackground() = test {
        other.registered()
        otherPhoneUploads("Ibuprom")
        val sync = sync()
        account.login(EMAIL, pw())
        api.offline = true

        assertEquals(AfterLogin.Failed(ApiError.Network), sync.afterLogin())
        assertTrue(account.decisionPending)
        api.offline = false
        advanceTimeBy(30_000); runCurrent()

        assertEquals(setOf("Ibuprom"), localMeds())
        assertFalse(account.decisionPending)
    }

    // --- wylogowanie ---

    @Test
    fun logoutWithUnsyncedChangesDoesNotWipe() = test {
        account.registered()
        val sync = sync()
        runCurrent()
        settings.theme = AppTheme.Dark
        settings.remindersEnabled = true
        api.offline = true
        repo.add(med("Metformina"))

        assertEquals(LogoutResult.Unsynced, sync.logout(force = false))
        assertEquals(setOf("Metformina"), localMeds())
        assertTrue(account.state.value is AccountState.LoggedIn)
        assertTrue(accountStore.dirty)

        assertEquals(LogoutResult.Done, sync.logout(force = true))
        runCurrent()
        assertEquals(emptySet<String>(), localMeds())
        assertEquals(emptySet<String>(), storedMeds())
        assertEquals(AccountState.LoggedOut, account.state.value)
        assertEquals(SyncedSettings(), SyncedSettings(settings.theme, settings.snoozeMinutes))
        assertTrue("przypomnienia zależą od urządzenia", settings.remindersEnabled)
        assertNull(accountStore.token)
        assertNull(accountStore.dek)
        assertNull(accountStore.dataOwnerUserId)
        assertFalse(accountStore.dirty)
        assertEquals(SyncStatus.Idle(null), sync.status.value)
    }

    @Test
    fun logoutWithConflictIsUnsynced() = test {
        val sync = conflictWithOtherPhone()
        assertEquals(LogoutResult.Unsynced, sync.logout(force = false))
        assertEquals(setOf("Metformina", "Apap"), localMeds())
    }

    @Test
    fun logoutUploadsPendingChangesFirst() = test {
        account.registered()
        val sync = sync()
        runCurrent()
        repo.add(med("Metformina"))

        assertEquals(LogoutResult.Done, sync.logout(force = false))
        assertEquals(emptySet<String>(), localMeds())
        assertEquals(AccountState.LoggedOut, account.state.value)

        // DEK zniknął z tego telefonu; kopię odczytujemy kluczem z drugiego telefonu tego konta
        other.login(EMAIL, pw())
        assertEquals(setOf("Metformina"), serverMeds())
    }

    @Test
    fun logoutWhenSyncedWipesEverything() = test {
        account.registered()
        val sync = sync()
        runCurrent()
        repo.add(med("Metformina"))
        advanceTimeBy(5_000); runCurrent()
        assertFalse(accountStore.dirty)
        val before = puts()

        assertEquals(LogoutResult.Done, sync.logout(force = false))
        runCurrent()
        assertEquals(before, puts())
        assertTrue("logout" in api.calls)
        assertEquals(0, api.sessionCount(EMAIL))
        assertEquals(emptySet<String>(), localMeds())
        assertEquals(emptySet<String>(), storedMeds())
        assertEquals(AccountState.LoggedOut, account.state.value)
        assertNull(accountStore.email)
        assertNull(accountStore.dataOwnerUserId)

        // telefon bez konta działa dalej, bez prób wysyłania
        repo.add(med("Apap"))
        advanceTimeBy(30 * 60_000L); runCurrent()
        assertEquals(before, puts())
    }

    @Test
    fun logoutOfflineStillWipesWhenSynced() = test {
        account.registered()
        val sync = sync()
        runCurrent()
        api.offline = true

        assertEquals(LogoutResult.Done, sync.logout(force = false))
        assertEquals(AccountState.LoggedOut, account.state.value)
    }
}
