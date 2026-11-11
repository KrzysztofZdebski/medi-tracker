package pl.agh.meditracker.data.account.api

import pl.agh.meditracker.data.account.crypto.EncryptedBlob
import pl.agh.meditracker.data.account.crypto.KdfParams

sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    data class Err(val error: ApiError) : ApiResult<Nothing>
}

/** Błędy zmapowane z kodu `error` w odpowiedzi serwera; brak sieci to [Network]. */
enum class ApiError {
    Network, InvalidInput, InvalidCode, InvalidCredentials, Unauthorized, InvalidToken,
    InvalidRecovery, Conflict, TooLarge, RateLimited, Server
}

class LoginResult(
    val token: String,
    val userId: String,
    val slot1: ByteArray,
    val epoch: Long,
    val backupVersion: Long?,
)

class VerifyResult(val token: String, val userId: String, val epoch: Long)

class Prelogin(val salt: ByteArray, val kdf: KdfParams)

class ResetVerify(val resetToken: String, val slot2: ByteArray)

/** `blob` jest null, gdy serwer nie ma szyfrogramu (nawet jeśli `version` nie jest null, np. po wipe). */
class RemoteBackup(val version: Long?, val epoch: Long, val blob: EncryptedBlob?)

data class PutResult(val version: Long)

sealed interface PutOutcome {
    data class Saved(val version: Long) : PutOutcome
    data class Conflict(val epoch: Long, val version: Long?) : PutOutcome
}

class Credentials(val salt: ByteArray, val kdf: KdfParams, val authKey: ByteArray, val slot1: ByteArray)

class RegisterRequest(
    val email: String,
    val credentials: Credentials,
    val slot2: ByteArray,
    val recoveryAuth: ByteArray,
)

interface AccountApi {
    suspend fun register(req: RegisterRequest): ApiResult<Unit>
    suspend fun verify(email: String, code: String, authKey: ByteArray): ApiResult<VerifyResult>
    suspend fun resendVerify(email: String): ApiResult<Unit>
    suspend fun prelogin(email: String): ApiResult<Prelogin>
    suspend fun login(email: String, authKey: ByteArray): ApiResult<LoginResult>
    suspend fun logout(token: String): ApiResult<Unit>
    suspend fun changePassword(token: String, oldAuthKey: ByteArray, new: Credentials): ApiResult<Unit>

    suspend fun requestReset(email: String): ApiResult<Unit>
    suspend fun verifyReset(email: String, code: String): ApiResult<ResetVerify>
    suspend fun completeReset(resetToken: String, recoveryAuth: ByteArray, new: Credentials): ApiResult<Unit>
    suspend fun wipeReset(
        resetToken: String,
        new: Credentials,
        slot2: ByteArray,
        recoveryAuth: ByteArray,
    ): ApiResult<Unit>

    suspend fun getBackup(token: String): ApiResult<RemoteBackup>
    suspend fun putBackup(token: String, epoch: Long, baseVersion: Long?, blob: EncryptedBlob): ApiResult<PutOutcome>

    /** Zwraca (epoch, version). */
    suspend fun takeover(token: String): ApiResult<Pair<Long, Long?>>
    suspend fun deleteAccount(token: String, authKey: ByteArray): ApiResult<Unit>
}
