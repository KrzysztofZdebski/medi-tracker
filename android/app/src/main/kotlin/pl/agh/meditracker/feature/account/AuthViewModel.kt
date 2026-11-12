package pl.agh.meditracker.feature.account

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import pl.agh.meditracker.data.account.AccountRepository
import pl.agh.meditracker.data.account.AccountState
import pl.agh.meditracker.data.account.api.ApiError
import pl.agh.meditracker.data.account.api.ApiResult
import pl.agh.meditracker.data.account.crypto.RecoveryCode

/** Błąd do pokazania na ekranie; ekrany same dobierają treść do swojego miejsca w przepływie. */
enum class AuthError { Network, InvalidCredentials, RateLimited, InvalidCode, WrongRecoveryCode, ResetExpired, Server }

internal fun ApiError.toAuthError() = when (this) {
    ApiError.Network -> AuthError.Network
    ApiError.InvalidCredentials -> AuthError.InvalidCredentials
    ApiError.RateLimited -> AuthError.RateLimited
    ApiError.InvalidCode -> AuthError.InvalidCode
    ApiError.InvalidToken -> AuthError.ResetExpired
    else -> AuthError.Server
}

/**
 * Stan przepływów konta od strony onboardingu (rejestracja, logowanie, reset hasła). Jeden egzemplarz na cały
 * graf "auth", więc e-mail resetu i nowy kod z resetu bez kodu przechodzą między ekranami. Argon2 trwa ułamek
 * sekundy, więc [busy] blokuje przyciski i pokazuje postęp (U11).
 */
class AuthViewModel(
    private val account: AccountRepository,
    private val afterLogin: suspend () -> Unit,
) : ViewModel() {
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<AuthError?>(null)
        private set

    /** Krótka informacja po udanej akcji pomocniczej, np. ponownym wysłaniu kodu. */
    var notice by mutableStateOf<String?>(null)
        private set
    var resetEmail by mutableStateOf("")
        private set

    /** Reset bez kodu odzyskiwania: po nowym haśle pokazujemy nowy kod, a kopia na koncie przepada. */
    var wipePath by mutableStateOf(false)
        private set

    /** Nowy kod z resetu bez kodu; null poza tą ścieżką (przy rejestracji kod jest w [AccountState.ShowRecoveryCode]). */
    var wipeCode by mutableStateOf<RecoveryCode?>(null)
        private set

    val accountState: StateFlow<AccountState> = account.state

    fun clearError() {
        error = null
        notice = null
    }

    private fun launchOp(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        notice = null
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = AuthError.Server
            } finally {
                busy = false
            }
        }
    }

    private inline fun <T> ApiResult<T>.ifOk(onOk: (T) -> Unit) {
        when (this) {
            is ApiResult.Ok -> onOk(value)
            is ApiResult.Err -> this@AuthViewModel.error = this.error.toAuthError()
        }
    }

    fun register(email: String, password: CharArray, onDone: () -> Unit) {
        // hasło idzie do repozytorium w tej samej chwili; jeśli busy, zerujemy je tu
        if (busy) return password.fill('\u0000')
        launchOp { account.register(email, password).ifOk { onDone() } }
    }

    fun verify(code: String, onDone: () -> Unit) = launchOp { account.verify(code).ifOk { onDone() } }

    fun resendCode() = launchOp {
        account.resendCode().ifOk { notice = "Wysłaliśmy nowy kod." }
    }

    fun changeEmail(onDone: () -> Unit) = launchOp {
        account.cancelRegistration()
        onDone()
    }

    fun acknowledgeRecoveryCode(onDone: () -> Unit) = launchOp {
        account.acknowledgeRecoveryCode()
        onDone()
    }

    /**
     * Po zalogowaniu od razu rozstrzyga dane (spec §6.2.4). Pytanie przywróć / zachowaj pokazuje [RestoreChoiceHost]
     * nad następnym ekranem; błąd sieci przy rozstrzyganiu nie cofa logowania, bo BackupSync ponowi decyzję, a do
     * tego czasu niczego nie wysyła.
     */
    fun login(email: String, password: CharArray, onDone: () -> Unit) {
        if (busy) return password.fill('\u0000')
        launchOp {
            account.login(email, password).ifOk {
                afterLogin() // wynik niesie pendingChoice
                onDone()
            }
        }
    }

    fun requestReset(email: String, onDone: () -> Unit) = launchOp {
        account.requestReset(email).ifOk {
            resetEmail = email.trim().lowercase()
            onDone()
        }
    }

    fun resendResetCode() = launchOp {
        account.requestReset(resetEmail).ifOk { notice = "Jeśli konto istnieje, wysłaliśmy nowy kod." }
    }

    fun verifyReset(code: String, onDone: () -> Unit) = launchOp {
        account.verifyReset(resetEmail, code).ifOk {
            wipePath = false
            onDone()
        }
    }

    fun checkRecoveryCode(input: String, onDone: () -> Unit) = launchOp {
        if (account.checkRecoveryCode(input)) {
            // poprawny kod = zwykły reset z zachowaniem kopii, także gdy wcześniej wybrano "Nie mam kodu" i cofnięto się
            wipePath = false
            onDone()
        } else {
            error = AuthError.WrongRecoveryCode
        }
    }

    fun chooseNoRecoveryCode() {
        wipePath = true
    }

    /** Powrót na ekran kodu odzyskiwania odwołuje wybór "Nie mam kodu", żeby Wstecz z U07 nie zostawił ścieżki kasującej kopię. */
    fun cancelNoRecoveryCode() {
        wipePath = false
    }

    /** Nowe hasło: z kodem odzyskiwania ([wipePath] false) albo z nowym DEK i kodem. [onDone] dostaje true, gdy jest nowy kod do pokazania. */
    fun setNewPassword(password: CharArray, onDone: (showCode: Boolean) -> Unit) {
        if (busy) return password.fill('\u0000')
        launchOp {
            if (wipePath) {
                account.wipeReset(password).ifOk { code ->
                    wipeCode = code
                    onDone(true)
                }
            } else {
                account.completeReset(password).ifOk { onDone(false) }
            }
        }
    }

    fun finishReset() {
        wipeCode = null
        wipePath = false
    }
}
