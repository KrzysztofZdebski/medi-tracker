package pl.agh.meditracker.data.account

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import pl.agh.meditracker.data.account.api.AccountApi
import pl.agh.meditracker.data.account.api.ApiError
import pl.agh.meditracker.data.account.api.ApiResult
import pl.agh.meditracker.data.account.api.Credentials
import pl.agh.meditracker.data.account.api.RegisterRequest
import pl.agh.meditracker.data.account.crypto.KdfParams
import pl.agh.meditracker.data.account.crypto.PasswordKeys
import pl.agh.meditracker.data.account.crypto.RecoveryCode
import pl.agh.meditracker.data.account.crypto.RecoveryKeys
import pl.agh.meditracker.data.account.crypto.WrongKeyException
import pl.agh.meditracker.data.account.crypto.passwordKeys
import pl.agh.meditracker.data.account.crypto.randomBytes
import pl.agh.meditracker.data.account.crypto.recoveryKeys
import pl.agh.meditracker.data.account.crypto.unwrap
import pl.agh.meditracker.data.account.crypto.wrap

/** Jedyne źródło prawdy o koncie dla UI. */
sealed interface AccountState {
    data object LoggedOut : AccountState
    data class AwaitingVerification(val email: String) : AccountState
    data class ShowRecoveryCode(val email: String, val code: RecoveryCode) : AccountState
    data class LoggedIn(val email: String, val userId: String) : AccountState
    data class SessionExpired(val email: String) : AccountState
}

/** Niezweryfikowane konto nie może się zalogować (R10), więc po logowaniu jest tylko jeden wariant. */
sealed interface LoginNext {
    data class Ready(val backupVersion: Long?) : LoginNext
}

data class Session(val token: String, val userId: String, val dek: ByteArray, val epoch: Long) {
    override fun toString() = "Session(userId=$userId, epoch=$epoch)"
}

/**
 * Parametry KDF akceptowane z prelogin (R14): przejęty serwer nie może podsunąć słabych (tani brute-force
 * auth_key, a z nim kek) ani ogromnych (OOM).
 */
data class KdfBounds(
    val m: IntRange = 65536..1048576,
    val t: IntRange = 3..10,
    val p: IntRange = 1..4,
) {
    fun accepts(k: KdfParams) = k.m in m && k.t in t && k.p in p
}

/**
 * Wszystkie przepływy konta (spec §6.1–6.8; wylogowanie prowadzi BackupSync, tu tylko [logoutLocally]). Argon2
 * liczy się na [cpu]. Hasła (CharArray) są zerowane po użyciu, tak jak wyprowadzone klucze. [kdf] to parametry nowych poświadczeń (rejestracja, zmiana
 * i reset hasła); testy podają mniejsze razem z luźniejszymi [kdfBounds].
 *
 * Każdy przepływ, który czyta lub zmienia stan konta (store, reset w pamięci, [state]), biegnie pod jednym
 * [mutex]: np. dwa szybkie „Utwórz konto” albo logowanie w trakcie rejestracji nie mogą przeplatać zapisów, bo
 * lokalny pending i auth_hash na serwerze by się rozjechały. Bez blokady są tylko odczyty ([state], [session],
 * [decisionPending]).
 */
class AccountRepository(
    private val api: AccountApi,
    private val store: AccountStore,
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
    private val kdf: KdfParams = KdfParams(),
    private val kdfBounds: KdfBounds = KdfBounds(),
) {
    private val _state = MutableStateFlow(fromStore())
    val state: StateFlow<AccountState> = _state

    private val mutex = Mutex()

    // reset hasła trzymany tylko w pamięci: token resetu, slot 2, a po sprawdzeniu kodu DEK i recovery_auth
    private class ResetFlow(val email: String, val token: String, val slot2: ByteArray) {
        var dek: ByteArray? = null
        var recoveryAuth: ByteArray? = null

        fun wipe() {
            dek?.fill(0)
            recoveryAuth?.fill(0)
        }
    }

    private var reset: ResetFlow? = null

    private fun fromStore(): AccountState {
        store.pending?.let { p ->
            p.dek.fill(0)
            p.authKey?.fill(0)
            return when (p.stage) {
                PendingRegistration.Stage.Verify -> AccountState.AwaitingVerification(p.email)
                PendingRegistration.Stage.ShowRecoveryCode -> AccountState.ShowRecoveryCode(p.email, p.recoveryCode)
            }
        }
        val email = store.email ?: return AccountState.LoggedOut
        val userId = store.userId
        val hasDek = store.dek?.also { it.fill(0) } != null
        return if (userId != null && store.token != null && hasDek) {
            AccountState.LoggedIn(email, userId)
        } else {
            AccountState.SessionExpired(email)
        }
    }

    suspend fun register(email: String, password: CharArray): ApiResult<Unit> = password.locked {
        val normalized = normalize(email)
        if (!EMAIL_SHAPE.matches(normalized) || password.size < MIN_PASSWORD) {
            return ApiResult.Err(ApiError.InvalidInput)
        }
        val dek = randomBytes(DEK_LEN)
        val salt = randomBytes(SALT_LEN)
        val code = RecoveryCode.generate()
        val keys = derive(password, salt, kdf)
        val recovery = recoveryKeys(code)
        try {
            val request = RegisterRequest(
                email = normalized,
                credentials = Credentials(salt, kdf, keys.authKey, wrap(keys.kek, dek)),
                slot2 = wrap(recovery.kek, dek),
                recoveryAuth = recovery.auth,
            )
            // zapis przed wysłaniem: jeśli proces zginie, gdy żądanie już doszło, aplikacja wraca do U03
            store.pending = PendingRegistration(normalized, PendingRegistration.Stage.Verify, dek, code, keys.authKey)
            val result = api.register(request)
            if (result is ApiResult.Err) {
                // także brak sieci (R16): żądanie mogło nie dojść, a wtedy kod nigdy nie przyjdzie; U01 pokazuje
                // U12 i użytkownik ponawia (ponowna rejestracja nadpisuje oczekujące konto na serwerze)
                store.pending = null
                _state.value = fromStore()
            } else {
                _state.value = AccountState.AwaitingVerification(normalized)
            }
            result
        } finally {
            keys.wipe()
            recovery.wipe()
            dek.fill(0)
        }
    }

    suspend fun verify(code: String): ApiResult<Unit> = mutex.withLock {
        val p = store.pending?.takeIf { it.stage == PendingRegistration.Stage.Verify && it.authKey != null }
            ?: return ApiResult.Err(ApiError.InvalidInput)
        try {
            val r = when (val result = api.verify(p.email, code.trim(), p.authKey!!)) {
                is ApiResult.Err -> return result
                is ApiResult.Ok -> result.value
            }
            // DEK i kod zostają (zaszyfrowane) do potwierdzenia „Zapisałem kod”; klucz auth już niepotrzebny.
            // Świeże konto nie ma kopii, więc dane z telefonu od razu należą do niego.
            val next = p.copy(stage = PendingRegistration.Stage.ShowRecoveryCode, authKey = null)
            store.startSession(
                p.email, r.userId, r.token, p.dek, r.epoch,
                backupVersion = null, pending = next, claimData = true,
            )
            store.lastBackupAt = null
            _state.value = AccountState.ShowRecoveryCode(p.email, p.recoveryCode)
            ApiResult.Ok(Unit)
        } finally {
            p.dek.fill(0)
            p.authKey?.fill(0)
        }
    }

    suspend fun resendCode(): ApiResult<Unit> = mutex.withLock {
        val p = store.pending?.takeIf { it.stage == PendingRegistration.Stage.Verify }
            ?: return ApiResult.Err(ApiError.InvalidInput)
        p.dek.fill(0)
        p.authKey?.fill(0)
        api.resendVerify(p.email)
    }

    /** „Zmień adres e-mail” w U03: porzuca niepotwierdzoną rejestrację. */
    suspend fun cancelRegistration() {
        mutex.withLock {
            val p = store.pending?.takeIf { it.stage == PendingRegistration.Stage.Verify } ?: return
            p.dek.fill(0)
            p.authKey?.fill(0)
            store.pending = null
            _state.value = fromStore()
        }
    }

    suspend fun acknowledgeRecoveryCode() {
        mutex.withLock {
            val p = store.pending?.takeIf { it.stage == PendingRegistration.Stage.ShowRecoveryCode } ?: return
            p.dek.fill(0)
            // R6: pierwsza kopia (spec §6.1.7) czeka na BackupSync; najpierw flagi, potem pending, więc zabicie
            // procesu w środku najwyżej pokaże kod jeszcze raz
            store.backupVersion = null
            store.dirty = true
            store.pending = null
            _state.value = fromStore()
        }
    }

    /** Nie przejmuje danych na telefonie: do decyzji „przywróć / zachowaj” [decisionPending] jest true (R17). */
    suspend fun login(email: String, password: CharArray): ApiResult<LoginNext.Ready> = password.locked {
        val normalized = normalize(email)
        val keys = when (val k = keysFromServer(normalized, password)) {
            is ApiResult.Err -> return k
            is ApiResult.Ok -> k.value
        }
        try {
            val r = when (val result = api.login(normalized, keys.authKey)) {
                is ApiResult.Err -> return result
                is ApiResult.Ok -> result.value
            }
            val dek = try {
                unwrap(keys.kek, r.slot1)
            } catch (e: WrongKeyException) {
                // serwer przyjął klucz, ale slot 1 się nie otwiera: dla użytkownika to złe dane logowania
                api.logout(r.token)
                return ApiResult.Err(ApiError.InvalidCredentials)
            }
            // Wersja bazowa PUT to wersja, którą ten telefon ostatnio zgrał z serwerem (R12), nie ta z logowania:
            // przy ponownym logowaniu na to samo konto zwykła synchronizacja (spec §6.8.3) dostanie wtedy 409,
            // jeśli inny telefon zapisał coś w międzyczasie, zamiast cicho nadpisać jego kopię. Dla innego konta
            // wersję ustala dopiero decyzja po logowaniu (BackupSync).
            val keepVersion = store.backupVersion.takeIf { store.dataOwnerUserId == r.userId }
            store.startSession(
                normalized, r.userId, r.token, dek, r.epoch,
                backupVersion = keepVersion, pending = null, claimData = false,
            )
            dek.fill(0)
            _state.value = AccountState.LoggedIn(normalized, r.userId)
            ApiResult.Ok(LoginNext.Ready(r.backupVersion))
        } finally {
            keys.wipe()
        }
    }

    /**
     * Trwały znacznik R17: zalogowani, a dane na telefonie nie należą (jeszcze) do tego konta (także gdy nie
     * należą do żadnego). BackupSync nie wysyła wtedy kopii i po starcie ponawia pytanie przywróć / zachowaj.
     */
    val decisionPending: Boolean
        get() {
            val loggedIn = _state.value as? AccountState.LoggedIn ?: return false
            return store.dataOwnerUserId != loggedIn.userId
        }

    /** Decyzja po logowaniu podjęta (przywrócenie, wysłanie albo wybór użytkownika): dane należą do tego konta. */
    suspend fun confirmDataOwner() {
        mutex.withLock {
            val loggedIn = _state.value as? AccountState.LoggedIn ?: return
            store.dataOwnerUserId = loggedIn.userId
        }
    }

    suspend fun changePassword(old: CharArray, new: CharArray): ApiResult<Unit> = old.wipeAfter {
        new.locked {
            val s = session() ?: return ApiResult.Err(ApiError.Unauthorized)
            try {
                if (new.size < MIN_PASSWORD) return ApiResult.Err(ApiError.InvalidInput)
                val email = store.email ?: return ApiResult.Err(ApiError.Unauthorized)
                val oldKeys = when (val k = keysFromServer(email, old)) {
                    is ApiResult.Err -> return k
                    is ApiResult.Ok -> k.value
                }
                val salt = randomBytes(SALT_LEN)
                val newKeys = derive(new, salt, kdf)
                try {
                    val credentials = Credentials(salt, kdf, newKeys.authKey, wrap(newKeys.kek, s.dek))
                    authed(s.token, api.changePassword(s.token, oldKeys.authKey, credentials))
                } finally {
                    oldKeys.wipe()
                    newKeys.wipe()
                }
            } finally {
                s.dek.fill(0)
            }
        }
    }

    suspend fun requestReset(email: String): ApiResult<Unit> = mutex.withLock {
        api.requestReset(normalize(email))
    }

    suspend fun verifyReset(email: String, code: String): ApiResult<Unit> = mutex.withLock {
        val normalized = normalize(email)
        when (val result = api.verifyReset(normalized, code.trim())) {
            is ApiResult.Err -> result
            is ApiResult.Ok -> {
                reset?.wipe()
                reset = ResetFlow(normalized, result.value.resetToken, result.value.slot2)
                ApiResult.Ok(Unit)
            }
        }
    }

    /**
     * Otwiera slot 2 lokalnie; zły kod nie idzie do serwera i można próbować dalej. Pod blokadą, bo podmienia
     * (i zeruje) DEK resetu, którego może właśnie używać [completeReset].
     */
    suspend fun checkRecoveryCode(input: String): Boolean = mutex.withLock {
        val flow = reset ?: return false
        val code = RecoveryCode.parse(input) ?: return false
        val keys = recoveryKeys(code)
        try {
            val dek = unwrap(keys.kek, flow.slot2)
            flow.wipe()
            flow.dek = dek
            flow.recoveryAuth = keys.auth
            true
        } catch (e: WrongKeyException) {
            keys.auth.fill(0)
            false
        } finally {
            keys.kek.fill(0)
        }
    }

    /** Nowe hasło, ten sam DEK. Serwer unieważnia wszystkie sesje, więc koniec bez zalogowania (U08 → login). */
    suspend fun completeReset(newPassword: CharArray): ApiResult<Unit> = newPassword.locked {
        val flow = reset
        val dek = flow?.dek
        val recoveryAuth = flow?.recoveryAuth
        if (flow == null || dek == null || recoveryAuth == null || newPassword.size < MIN_PASSWORD) {
            return ApiResult.Err(ApiError.InvalidInput)
        }
        val salt = randomBytes(SALT_LEN)
        val keys = derive(newPassword, salt, kdf)
        try {
            val credentials = Credentials(salt, kdf, keys.authKey, wrap(keys.kek, dek))
            api.completeReset(flow.token, recoveryAuth, credentials).also { afterReset(flow, it) }
        } finally {
            keys.wipe()
        }
    }

    /**
     * Reset bez kodu odzyskiwania: nowy DEK i nowy kod, kopia na serwerze znika. Zwraca nowy kod do pokazania;
     * stan nie przechodzi w LoggedIn (R7), po logowaniu BackupSync wyśle dane z tego telefonu.
     */
    suspend fun wipeReset(newPassword: CharArray): ApiResult<RecoveryCode> = newPassword.locked {
        val flow = reset
        if (flow == null || newPassword.size < MIN_PASSWORD) return ApiResult.Err(ApiError.InvalidInput)
        val dek = randomBytes(DEK_LEN)
        val code = RecoveryCode.generate()
        val salt = randomBytes(SALT_LEN)
        val keys = derive(newPassword, salt, kdf)
        val recovery = recoveryKeys(code)
        try {
            val credentials = Credentials(salt, kdf, keys.authKey, wrap(keys.kek, dek))
            when (val result = api.wipeReset(flow.token, credentials, wrap(recovery.kek, dek), recovery.auth)) {
                is ApiResult.Err -> result.also { afterReset(flow, it) }
                is ApiResult.Ok -> ApiResult.Ok(code).also { afterReset(flow, result) }
            }
        } finally {
            keys.wipe()
            recovery.wipe()
            dek.fill(0)
        }
    }

    // sesje tego konta są już nieważne; dane na telefonie zostają razem z dataOwnerUserId
    private fun afterReset(flow: ResetFlow, result: ApiResult<Unit>) {
        // np. invalid_recovery: token zostaje i można spróbować jeszcze raz
        if (result is ApiResult.Err && result.error != ApiError.InvalidToken) return
        flow.wipe()
        reset = null
        if (result is ApiResult.Ok) {
            if (store.email == flow.email) store.clear()
            _state.value = fromStore()
        }
    }

    /** Usuwa konto na serwerze (spec §6.7); dane na telefonie zostają. */
    suspend fun deleteAccount(password: CharArray): ApiResult<Unit> = password.locked {
        val s = session() ?: return ApiResult.Err(ApiError.Unauthorized)
        try {
            val email = store.email ?: return ApiResult.Err(ApiError.Unauthorized)
            val keys = when (val k = keysFromServer(email, password)) {
                is ApiResult.Err -> return k
                is ApiResult.Ok -> k.value
            }
            try {
                authed(s.token, api.deleteAccount(s.token, keys.authKey)).also {
                    if (it is ApiResult.Ok) {
                        store.clear()
                        _state.value = AccountState.LoggedOut
                    }
                }
            } finally {
                keys.wipe()
            }
        } finally {
            s.dek.fill(0)
        }
    }

    /**
     * 401 `unauthorized` z trasy wywołanej z [token]: U13, bez kasowania czegokolwiek poza sesją (spec §6.8).
     * Spóźniona odpowiedź dla starego tokenu nie kończy nowszej sesji (R18).
     */
    suspend fun onUnauthorized(token: String) {
        mutex.withLock { expireSession(token) }
    }

    private fun expireSession(token: String) {
        if (_state.value !is AccountState.LoggedIn || token != store.token) return
        store.clearSession()
        _state.value = fromStore()
    }

    /**
     * Lokalna połowa wylogowania (R19), wołana przez BackupSync po skasowaniu danych telefonu: cały plik konta
     * razem z [AccountStore.dataOwnerUserId] (telefon nie ma już danych, więc nie mają właściciela).
     */
    suspend fun logoutLocally() {
        mutex.withLock {
            store.clear()
            store.dataOwnerUserId = null
            _state.value = AccountState.LoggedOut
        }
    }

    /** Sesja dla BackupSync; null, gdy nie jesteśmy zalogowani. `dek` to kopia, wołający może ją wyzerować. */
    internal fun session(): Session? {
        if (_state.value !is AccountState.LoggedIn) return null
        val token = store.token ?: return null
        val userId = store.userId ?: return null
        val epoch = store.epoch ?: return null
        val dek = store.dek ?: return null
        return Session(token, userId, dek, epoch)
    }

    // wołane już pod blokadą
    private fun <T> authed(token: String, result: ApiResult<T>): ApiResult<T> {
        if (result is ApiResult.Err && result.error == ApiError.Unauthorized) expireSession(token)
        return result
    }

    // prelogin, sprawdzenie parametrów (R14), dopiero potem Argon2
    private suspend fun keysFromServer(email: String, password: CharArray): ApiResult<PasswordKeys> {
        val pre = when (val result = api.prelogin(email)) {
            is ApiResult.Err -> return result
            is ApiResult.Ok -> result.value
        }
        if (!kdfBounds.accepts(pre.kdf)) return ApiResult.Err(ApiError.Server)
        return ApiResult.Ok(derive(password, pre.salt, pre.kdf))
    }

    private suspend fun derive(password: CharArray, salt: ByteArray, params: KdfParams): PasswordKeys =
        withContext(cpu) { passwordKeys(password, salt, params) }

    // przepływ z hasłem: pod blokadą, hasło zerowane także wtedy, gdy czekało na blokadę
    private suspend inline fun <T> CharArray.locked(block: () -> T): T = wipeAfter { mutex.withLock(action = block) }

    private companion object {
        const val MIN_PASSWORD = 10
        const val DEK_LEN = 32
        const val SALT_LEN = 16
        val EMAIL_SHAPE = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

        fun normalize(email: String) = email.trim().lowercase()

        fun PasswordKeys.wipe() {
            authKey.fill(0)
            kek.fill(0)
        }

        fun RecoveryKeys.wipe() {
            auth.fill(0)
            kek.fill(0)
        }

        inline fun <T> CharArray.wipeAfter(block: () -> T): T = try {
            block()
        } finally {
            fill('\u0000')
        }
    }
}
