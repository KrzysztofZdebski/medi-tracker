package pl.agh.meditracker.data.account

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.agh.meditracker.data.account.api.AccountApi
import pl.agh.meditracker.data.account.api.ApiError
import pl.agh.meditracker.data.account.api.ApiResult
import pl.agh.meditracker.data.account.api.PutOutcome
import pl.agh.meditracker.data.account.api.RemoteBackup
import pl.agh.meditracker.data.account.crypto.blobAad
import pl.agh.meditracker.data.account.crypto.decryptBlob
import pl.agh.meditracker.data.account.crypto.encryptBlob
import pl.agh.meditracker.data.repository.MedicationRepository
import pl.agh.meditracker.data.settings.SettingsStore
import pl.agh.meditracker.data.user.SnapshotCodec
import pl.agh.meditracker.data.user.SyncedSettings
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/** Stan kopii dla „Moje konto” (spec §6.3.5). */
sealed interface SyncStatus {
    data class Idle(val lastBackupAt: Instant?) : SyncStatus
    data object Pending : SyncStatus
    data object Uploading : SyncStatus

    /** 409: inny telefon przejął konto albo zapisał pierwszy; automatyczne wysyłanie stoi do [BackupSync.resolve]. */
    data object Conflict : SyncStatus

    /** Ostatnia próba nie doszła (sieć, serwer); zmiany czekają na kolejną. */
    data object Failed : SyncStatus
}

/** Wynik decyzji po logowaniu (spec §6.2.4). */
sealed interface AfterLogin {
    /** Telefon był pusty: dane z konta są już na telefonie. */
    data object Restored : AfterLogin

    /** Dane z telefonu są (albo zaraz będą) kopią konta; także gdy obie strony są puste. */
    data object Uploaded : AfterLogin

    /**
     * Użytkownik wybiera [BackupSync.resolve]: obie strony mają dane albo dane innego konta trafiają na konto bez
     * kopii (R21; wtedy „przywróć” oznacza pusty telefon).
     */
    data class NeedsChoice(val fromOtherAccount: Boolean) : AfterLogin

    /** Nic nie zmieniono; decyzja wciąż czeka (przy błędzie sieci ponowimy ją w tle). */
    data class Failed(val error: ApiError) : AfterLogin
}

enum class LogoutResult { Done, Unsynced }

/**
 * Zaszyfrowana kopia zapasowa (spec §5.2, §6.2–6.6): po każdej zmianie w [repository] albo w zsynchronizowanych
 * ustawieniach oznacza dane jako brudne i po [debounceMillis] wysyła całość; rozstrzyga przywróć / zachowaj po
 * logowaniu i po konflikcie; wylogowanie najpierw wysyła zaległe zmiany, potem kasuje dane z telefonu.
 *
 * Bezpieczeństwo danych:
 * - dopóki [AccountRepository.decisionPending], nic nie jest wysyłane (dane mogą należeć do innego konta, R17);
 * - `base_version` to zawsze wersja serwera z GET, przejęcia albo ostatniego zapisu (R12), więc PUT nadpisze tylko
 *   kopię, którą ten telefon zna; inaczej 409 i pytanie użytkownika;
 * - po 409 nic nie jest wysyłane (także ręcznie i przy wylogowaniu) do [resolve]; przy wyborze „przywróć” nowy epoch
 *   z przejęcia trafia do pliku konta dopiero razem z przywróconymi danymi, więc dane, które użytkownik porzuca,
 *   nigdy nie nadpiszą kopii (także po błędzie i po śmierci procesu w trakcie);
 * - wylogowanie najpierw trwale zwalnia właściciela danych, dopiero potem kasuje telefon;
 * - dane innego konta nie trafiają po cichu na konto bez kopii (R21);
 * - nieudane przywrócenie (odszyfrowanie, format, baza) zostawia dane lokalne bez zmian;
 * - własne zmiany (przywrócenie, wylogowanie) nie oznaczają danych jako brudnych (R15): podmiana i kasowanie bazy nie
 *   wysyłają `changes`, a ustawienia, które sami zapisujemy, kolektor rozpoznaje po [knownSynced].
 *
 * Zmiany są zauważane od razu w wątku, który je zrobił (kolektory na [Dispatchers.Unconfined]), więc flaga `dirty`
 * i licznik [changeSeq] zgadzają się z kolejnością zapisów w repozytorium: wylogowanie tuż po zmianie jej nie zgubi,
 * a zmiana w trakcie wysyłania nie skasuje flagi.
 */
class BackupSync(
    private val repository: MedicationRepository,
    private val settings: SettingsStore,
    private val account: AccountRepository,
    private val accountStore: AccountStore,
    private val api: AccountApi,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val debounceMillis: Long = DEBOUNCE_MILLIS,
) {
    // jedna operacja na kopii naraz: wysyłanie, decyzja po logowaniu, konflikt, wylogowanie
    private val ops = Mutex()

    // stan wspólny dla kolektorów (różne wątki) i operacji; tylko krótkie sekcje bez zawieszania
    private val lock = Any()
    private var changeSeq = 0L
    private var knownSynced = SyncedSettings()
    private var uploading = false
    private var conflict = false
    private var failed = false
    private var debounceJob: Job? = null
    private var retryJob: Job? = null
    private var retryDelay = FIRST_RETRY_MILLIS
    private val started = AtomicBoolean(false)

    private val _status = MutableStateFlow(computeStatus())
    val status: StateFlow<SyncStatus> = _status

    /** Pytanie przywróć / zachowaj czekające na użytkownika (także po restarcie aplikacji, R17). */
    private val _pendingChoice = MutableStateFlow<AfterLogin.NeedsChoice?>(null)
    val pendingChoice: StateFlow<AfterLogin.NeedsChoice?> = _pendingChoice

    // pytanie dotyczy danych innego konta przy koncie bez kopii (R21): „przywróć” = zacznij od pustego telefonu
    @Volatile private var choiceForEmptyAccount = false

    // R20: wyjątek w pracy w tle nie może zabić procesu ani po cichu zatrzymać kopii
    private val guard = CoroutineExceptionHandler { _, e -> onCrash(e) }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        synchronized(lock) { knownSynced = currentSynced() }
        scope.launch(guard + Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) {
            repository.changes.collect { guarded { onLocalChange() } }
        }
        scope.launch(guard + Dispatchers.Unconfined, CoroutineStart.UNDISPATCHED) {
            settings.state.map { SyncedSettings(it.theme, it.snoozeMinutes) }.distinctUntilChanged().collect { s ->
                guarded {
                    val ours = synchronized(lock) { (s == knownSynced).also { knownSynced = s } }
                    if (!ours) onLocalChange()
                }
            }
        }
        scope.launch(guard, CoroutineStart.UNDISPATCHED) {
            var previous: AccountState? = null
            account.state.collect { current ->
                guarded { onAccountState(previous, current) }
                previous = current
            }
        }
    }

    /** Wysyła kopię, jeśli są niewysłane zmiany (także ręcznie, np. „Spróbuj ponownie”). */
    suspend fun upload(): SyncStatus {
        ops.withLock { uploadLocked() }
        return status.value
    }

    /** Wołane przez UI zaraz po udanym logowaniu (spec §6.2.4). */
    suspend fun afterLogin(): AfterLogin = ops.withLock { decideLocked() }

    /**
     * Wybór użytkownika po logowaniu albo po konflikcie (spec §6.3.3): przejęcie konta, potem przywrócenie kopii
     * ([restore]) albo nadpisanie jej danymi z telefonu.
     */
    suspend fun resolve(restore: Boolean): ApiResult<Unit> = ops.withLock {
        val s = account.session() ?: return@withLock ApiResult.Err(ApiError.Unauthorized)
        try {
            val (epoch, version) = when (val r = api.takeover(s.token)) {
                is ApiResult.Err -> return@withLock failure(s, r)
                is ApiResult.Ok -> r.value
            }
            cancelTimers()
            if (restore && choiceForEmptyAccount) {
                // R21: dane innego konta, konto bez kopii; „przywróć” = pusty telefon (nie ma czego pobierać)
                accountStore.epoch = epoch
                accountStore.backupVersion = version
                val seq = synchronized(lock) { changeSeq }
                wipeLocal()
                accountStore.lastBackupAt = null
                synchronized(lock) { if (changeSeq == seq) accountStore.dirty = false }
                account.confirmDataOwner()
                settled()
                ApiResult.Ok(Unit)
            } else if (restore) {
                // Nowy epoch zapisujemy dopiero razem z przywróconymi danymi. Do tego czasu telefon ma stary epoch,
                // więc każdy PUT jego (porzucanych) danych dostanie 409: po błędzie pobrania, po wylogowaniu bez
                // wymuszenia i po śmierci procesu w trakcie.
                val backup = when (val r = api.getBackup(s.token)) {
                    is ApiResult.Err -> return@withLock failure(s, r)
                    is ApiResult.Ok -> r.value
                }
                if (!restoreLocked(s, backup, epoch)) return@withLock ApiResult.Err(ApiError.UnreadableBackup)
                account.confirmDataOwner()
                settled()
                ApiResult.Ok(Unit)
            } else {
                accountStore.epoch = epoch
                accountStore.backupVersion = version
                // wybór zapada już tu; jeśli wysyłanie się nie uda, flaga dirty i ponowienie dokończą sprawę
                markDirty()
                account.confirmDataOwner()
                settled()
                uploadLocked()
            }
        } finally {
            s.dek.fill(0)
        }
    }

    /**
     * Wylogowanie (spec §6.6, R19). Bez [force] najpierw wysyła zaległe zmiany; gdy się nie da (sieć, konflikt,
     * wygasła sesja), zwraca [LogoutResult.Unsynced] i niczego nie kasuje. Tak samo przy nierozstrzygniętej
     * decyzji po logowaniu: dane telefonu nie są wtedy w kopii tego konta, nawet jeśli nie są brudne.
     */
    suspend fun logout(force: Boolean): LogoutResult = ops.withLock {
        if (!force && account.decisionPending) return@withLock LogoutResult.Unsynced
        if (!force && accountStore.dirty) {
            uploadLocked()
            if (accountStore.dirty) return@withLock LogoutResult.Unsynced
        }
        cancelTimers()
        // Trwale przed kasowaniem: jeśli proces zginie między kasowaniem a logoutLocally, telefon zostaje
        // zalogowany z decyzją do podjęcia (nic nie wysyła, następna decyzja po cichu przywraca kopię), a nie jako
        // właściciel pustych danych, które nadpisałyby jedyną kopię.
        account.releaseDataOwner()
        wipeLocal()
        account.session()?.let { s ->
            s.dek.fill(0)
            api.logout(s.token) // błąd (np. brak sieci) nie zatrzymuje wylogowania; sesja i tak wygaśnie
        }
        account.logoutLocally()
        cancelTimers()
        clearChoice()
        synchronized(lock) {
            conflict = false
            failed = false
            refresh()
        }
        LogoutResult.Done
    }

    private fun onLocalChange() {
        markDirty()
        if (canAutoUpload()) scheduleDebounce()
    }

    private fun onAccountState(previous: AccountState?, current: AccountState) {
        if (current !is AccountState.LoggedIn) {
            // bez sesji nic nie wysyłamy; zmiany czekają w dirty do następnego logowania (spec §6.8)
            cancelTimers()
            clearChoice()
            synchronized(lock) {
                failed = false
                refresh()
            }
            return
        }
        if (previous != null && previous !is AccountState.LoggedIn) {
            // nowa sesja ma nowy epoch; stary konflikt nie obowiązuje
            synchronized(lock) {
                conflict = false
                failed = false
                refresh()
            }
        }
        when {
            // start aplikacji z nierozstrzygniętą decyzją (proces zginął przy pytaniu albo bez sieci): ponów ją
            previous == null && account.decisionPending -> scope.launch(guard) { ops.withLock { decideLocked() } }
            // R6: pierwsza kopia po rejestracji, zaległe zmiany przy starcie i po ponownym logowaniu
            accountStore.dirty && canAutoUpload() -> launchUpload()
        }
    }

    // R20: nieoczekiwany wyjątek kończy się Failed; decyzja zostaje nierozstrzygnięta, dirty bez zmian
    private suspend fun decideLocked(): AfterLogin = try {
        decideUnguarded()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onCrash(e)
        AfterLogin.Failed(ApiError.Server)
    }

    private suspend fun decideUnguarded(): AfterLogin {
        val s = account.session() ?: return AfterLogin.Failed(ApiError.Unauthorized)
        try {
            val owner = accountStore.dataOwnerUserId
            val pending = owner != s.userId
            val fromOtherAccount = owner != null && owner != s.userId
            val backup = when (val r = api.getBackup(s.token)) {
                is ApiResult.Err -> {
                    failure(s, r)
                    if (r.error in RETRYABLE) scheduleRetry()
                    return AfterLogin.Failed(r.error)
                }
                is ApiResult.Ok -> r.value
            }
            if (backup.blob == null) {
                // R21: cudzych danych nie kopiujemy po cichu na nowe konto; użytkownik wybiera
                if (fromOtherAccount) {
                    return raiseChoice(AfterLogin.NeedsChoice(fromOtherAccount = true), emptyAccount = true)
                }
                // konto bez kopii (nowe albo po resecie bez kodu, R7): dane z telefonu stają się kopią, także puste
                accountStore.backupVersion = backup.version
                markDirty()
                account.confirmDataOwner()
                clearChoice()
                uploadLocked()
                return AfterLogin.Uploaded
            }
            if (!pending && backup.version == accountStore.backupVersion) {
                // to samo konto i nikt nic nie zapisał od naszej ostatniej kopii: zwykła synchronizacja (§6.8.3)
                clearChoice()
                uploadLocked()
                return AfterLogin.Uploaded
            }
            val local = repository.dumpSnapshot(currentSynced())
            if (local.medications.isEmpty() && local.history.isEmpty()) {
                if (!restoreLocked(s, backup)) return AfterLogin.Failed(ApiError.UnreadableBackup)
                account.confirmDataOwner()
                clearChoice()
                return AfterLogin.Restored
            }
            return raiseChoice(AfterLogin.NeedsChoice(fromOtherAccount), emptyAccount = false)
        } finally {
            s.dek.fill(0)
        }
    }

    // Odszyfrowanie i odczyt przed jakąkolwiek zmianą; każdy błąd (zły klucz lub AAD, uszkodzony blob, nieznany
    // format, błąd bazy) zostawia dane lokalne bez zmian. [epoch] z przejęcia trafia do pliku konta dopiero razem
    // z przywróconymi danymi.
    private suspend fun restoreLocked(s: Session, backup: RemoteBackup, epoch: Long? = null): Boolean {
        val blob = backup.blob ?: return false
        val snapshot = try {
            SnapshotCodec.decode(decryptBlob(s.dek, blob, blobAad(s.userId)))
        } catch (e: Exception) {
            return false
        }
        val seq = synchronized(lock) { changeSeq }
        try {
            repository.replaceAllFrom(snapshot) // jedna transakcja: przy błędzie zostają stare dane
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false
        }
        synchronized(lock) { knownSynced = snapshot.settings }
        settings.applySynced(snapshot.settings)
        epoch?.let { accountStore.epoch = it }
        accountStore.backupVersion = backup.version
        accountStore.lastBackupAt = clock.instant()
        synchronized(lock) {
            // zmiana zrobiona w trakcie przywracania nie jest w kopii, więc zostaje do wysłania
            if (changeSeq == seq) accountStore.dirty = false
            failed = false
            refresh()
        }
        return true
    }

    // Ok także wtedy, gdy nie było czego wysłać; po błędzie flaga dirty zostaje.
    private suspend fun uploadLocked(): ApiResult<Unit> {
        if (!accountStore.dirty) return ApiResult.Ok(Unit)
        // przed decyzją po logowaniu dane telefonu mogą należeć do innego konta (R17)
        if (account.decisionPending) return ApiResult.Err(ApiError.Conflict)
        // po 409 nic nie wysyłamy (także ręcznie i przy wylogowaniu), dopóki użytkownik nie wybierze w resolve
        if (synchronized(lock) { conflict }) return ApiResult.Err(ApiError.Conflict)
        val s = account.session() ?: return ApiResult.Err(ApiError.Unauthorized)
        val seq = synchronized(lock) {
            uploading = true
            failed = false
            refresh()
            changeSeq
        }
        try {
            val result = put(s)
            if (result is ApiResult.Err) {
                failure(s, result)
                synchronized(lock) { failed = result.error != ApiError.Unauthorized }
                if (result.error in RETRYABLE) scheduleRetry()
                return result
            }
            return when (val outcome = (result as ApiResult.Ok).value) {
                is PutOutcome.Saved -> {
                    accountStore.backupVersion = outcome.version
                    accountStore.lastBackupAt = clock.instant()
                    synchronized(lock) {
                        // dirty znika tylko, jeśli od zrzutu nic się nie zmieniło
                        if (changeSeq == seq) accountStore.dirty = false
                        retryJob?.cancel()
                        retryJob = null
                        retryDelay = FIRST_RETRY_MILLIS
                    }
                    ApiResult.Ok(Unit)
                }
                is PutOutcome.Conflict -> {
                    // wersji z 409 nie zapisujemy: następny PUT ma się opierać tylko na kopii, którą znamy
                    synchronized(lock) { conflict = true }
                    cancelTimers()
                    ApiResult.Err(ApiError.Conflict)
                }
            }
        } finally {
            s.dek.fill(0)
            synchronized(lock) {
                uploading = false
                refresh()
            }
        }
    }

    // Zrzut idzie kolejką zapisów repozytorium, więc zawiera każdą zmianę sprzed wywołania. Nieoczekiwany wyjątek
    // (baza, kodowanie, klient HTTP) to zwykła nieudana próba: Failed, dirty zostaje, ponowienie (R20).
    private suspend fun put(s: Session): ApiResult<PutOutcome> = try {
        val plain = SnapshotCodec.encode(repository.dumpSnapshot(currentSynced()), clock.instant())
        val blob = encryptBlob(s.dek, plain, blobAad(s.userId))
        api.putBackup(s.token, s.epoch, accountStore.backupVersion, blob)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logFailure(e)
        ApiResult.Err(ApiError.Server)
    }

    // 401: U13 bez kasowania czegokolwiek (spec §6.8); dirty zostaje, wysyłanie stoi do następnego LoggedIn
    private suspend fun failure(s: Session, result: ApiResult.Err): ApiResult.Err {
        if (result.error == ApiError.Unauthorized) account.onUnauthorized(s.token)
        return result
    }

    private fun canAutoUpload(): Boolean =
        account.state.value is AccountState.LoggedIn && !account.decisionPending &&
            _pendingChoice.value == null && !synchronized(lock) { conflict }

    private fun launchUpload() {
        scope.launch(guard) { ops.withLock { if (canAutoUpload()) uploadLocked() } }
    }

    // Odliczanie i ponowienie tylko uruchamiają osobne wysyłanie: anulowanie timera nie przerywa trwającego PUT.
    private fun scheduleDebounce() = synchronized(lock) {
        retryJob?.cancel()
        retryJob = null
        retryDelay = FIRST_RETRY_MILLIS
        debounceJob?.cancel()
        debounceJob = scope.launch(guard) {
            delay(debounceMillis)
            launchUpload()
        }
    }

    private fun scheduleRetry() = synchronized(lock) {
        retryJob?.cancel()
        val wait = retryDelay
        retryDelay = minOf(wait * 2, MAX_RETRY_MILLIS)
        retryJob = scope.launch(guard) {
            delay(wait)
            when {
                !account.decisionPending -> launchUpload()
                _pendingChoice.value == null -> scope.launch(guard) { ops.withLock { decideLocked() } }
            }
        }
    }

    private fun cancelTimers() = synchronized(lock) {
        debounceJob?.cancel()
        retryJob?.cancel()
        debounceJob = null
        retryJob = null
        retryDelay = FIRST_RETRY_MILLIS
    }

    private fun markDirty() = synchronized(lock) {
        changeSeq++
        if (!accountStore.dirty) accountStore.dirty = true
        refresh()
    }

    private fun settled() {
        clearChoice()
        synchronized(lock) {
            conflict = false
            refresh()
        }
    }

    private fun raiseChoice(choice: AfterLogin.NeedsChoice, emptyAccount: Boolean): AfterLogin.NeedsChoice {
        choiceForEmptyAccount = emptyAccount
        _pendingChoice.value = choice
        return choice
    }

    private fun clearChoice() {
        _pendingChoice.value = null
        choiceForEmptyAccount = false
    }

    // kasuje dane telefonu i zsynchronizowane ustawienia; własny zapis ustawień nie oznacza zmian (R15)
    private suspend fun wipeLocal() {
        repository.wipeAll()
        // resetSynced przywraca SyncedSettings(); kolektor rozpozna to jako własny zapis
        synchronized(lock) { knownSynced = SyncedSettings() }
        settings.resetSynced()
    }

    // kolektory nie mogą zginąć od wyjątku, bo to koniec automatycznej kopii (R20)
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onCrash(e)
        }
    }

    private fun onCrash(e: Throwable) {
        logFailure(e)
        synchronized(lock) {
            failed = true
            refresh()
        }
    }

    // tylko klasa wyjątku: komunikat mógłby zawierać dane konta
    private fun logFailure(e: Throwable) {
        Log.e(TAG, "Kopia zapasowa: nieoczekiwany błąd ${e.javaClass.name}")
    }

    // pod [lock]
    private fun refresh() {
        _status.value = computeStatus()
    }

    private fun computeStatus(): SyncStatus = when {
        conflict -> SyncStatus.Conflict
        uploading -> SyncStatus.Uploading
        !accountStore.dirty -> SyncStatus.Idle(accountStore.lastBackupAt)
        failed -> SyncStatus.Failed
        else -> SyncStatus.Pending
    }

    private fun currentSynced() = settings.state.value.let { SyncedSettings(it.theme, it.snoozeMinutes) }

    private companion object {
        const val TAG = "BackupSync"
        const val DEBOUNCE_MILLIS = 5_000L
        const val FIRST_RETRY_MILLIS = 30_000L
        const val MAX_RETRY_MILLIS = 5 * 60_000L
        val RETRYABLE = setOf(ApiError.Network, ApiError.Server, ApiError.RateLimited)
    }
}
