package pl.agh.meditracker.data.account

import pl.agh.meditracker.data.account.api.AccountApi
import pl.agh.meditracker.data.account.api.ApiError
import pl.agh.meditracker.data.account.api.ApiResult
import pl.agh.meditracker.data.account.api.Credentials
import pl.agh.meditracker.data.account.api.LoginResult
import pl.agh.meditracker.data.account.api.Prelogin
import pl.agh.meditracker.data.account.api.PutOutcome
import pl.agh.meditracker.data.account.api.RegisterRequest
import pl.agh.meditracker.data.account.api.RemoteBackup
import pl.agh.meditracker.data.account.api.ResetVerify
import pl.agh.meditracker.data.account.api.VerifyResult
import pl.agh.meditracker.data.account.crypto.EncryptedBlob
import pl.agh.meditracker.data.account.crypto.KdfParams
import java.security.MessageDigest
import java.util.UUID

/**
 * Serwer w pamięci, zachowujący się jak prawdziwy (kontrakt w shared-context): trzyma hashe kluczy i opakowane
 * sloty, podbija epoch przy logowaniu, ukrywa niezweryfikowane konta (R10), sprawdza auth_key przy weryfikacji
 * (R9), zostawia reset token po invalid_recovery, a wipe kasuje blob z zachowaniem licznika wersji (R12, R13).
 */
class FakeAccountApi : AccountApi {

    class Account(
        val email: String,
        val userId: String = UUID.randomUUID().toString(),
        var verified: Boolean = false,
        var salt: ByteArray,
        var kdf: KdfParams,
        var authHash: ByteArray,
        var slot1: ByteArray,
        var slot2: ByteArray,
        var recoveryHash: ByteArray,
        var epoch: Long = 0,
        var version: Long? = null,
        var blob: EncryptedBlob? = null,
    )

    private class Code(val value: String, var attempts: Int = 0)

    val accounts = mutableMapOf<String, Account>()
    private val sessions = mutableMapOf<String, String>() // token -> userId
    private val resetTokens = mutableMapOf<String, String>() // reset token -> userId
    private val verifyCodes = mutableMapOf<String, Code>() // email -> kod
    private val resetCodes = mutableMapOf<String, Code>()

    /** Nazwy wywołanych metod, w kolejności. */
    val calls = mutableListOf<String>()

    /** Adresy, na które poszło powiadomienie „ktoś próbował się zarejestrować”. */
    val notices = mutableListOf<String>()

    /** Wszystkie wywołania kończą się błędem sieci. */
    var offline = false

    /** Udaje przejęty serwer, który w prelogin podaje własne parametry KDF. */
    var preloginKdfOverride: KdfParams? = null

    /** Odpowiedź na register, gdy nie null (np. RateLimited). */
    var registerError: ApiError? = null

    /** Wołane przed obsługą każdego wywołania (z nazwą metody); np. `delay`, żeby zmienić kolejność odpowiedzi. */
    var beforeCall: suspend (String) -> Unit = {}

    fun verifyCode(email: String): String? = verifyCodes[email]?.value
    fun resetCode(email: String): String? = resetCodes[email]?.value
    fun account(email: String): Account = accounts.getValue(email)
    fun sessionCount(email: String): Int = accounts[email]?.let { a -> sessions.values.count { it == a.userId } } ?: 0
    fun revokeAllSessions() = sessions.clear()
    fun authKeyMatches(email: String, authKey: ByteArray) = matches(authKey, account(email).authHash)

    private fun hash(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
    private fun matches(secret: ByteArray, hash: ByteArray) = MessageDigest.isEqual(hash(secret), hash)
    private fun newCode() = Code((100000..999999).random().toString())
    private fun newToken() = UUID.randomUUID().toString()
    private fun byId(userId: String) = accounts.values.first { it.userId == userId }

    private fun validCredentials(c: Credentials) = c.salt.size == 16 && c.authKey.size == 32 && c.slot1.size == 60

    private suspend inline fun <T> serve(name: String, block: () -> ApiResult<T>): ApiResult<T> {
        calls += name
        beforeCall(name)
        if (offline) return ApiResult.Err(ApiError.Network)
        return block()
    }

    private suspend inline fun <T> authed(name: String, token: String, block: (Account) -> ApiResult<T>): ApiResult<T> =
        serve(name) {
            val userId = sessions[token] ?: return@serve ApiResult.Err(ApiError.Unauthorized)
            block(byId(userId))
        }

    // kod jest zużywany po trafieniu; 5 prób na kod, zła próba też się liczy
    private fun claim(codes: MutableMap<String, Code>, email: String, code: String, extraOk: Boolean = true): Boolean {
        val c = codes[email] ?: return false
        if (c.attempts >= 5) return false
        c.attempts++
        if (c.value != code || !extraOk) return false
        codes.remove(email)
        return true
    }

    override suspend fun register(req: RegisterRequest) = serve("register") {
        registerError?.let { return@serve ApiResult.Err(it) }
        if (!req.email.contains('@') || !validCredentials(req.credentials) || req.slot2.size != 60) {
            return@serve ApiResult.Err(ApiError.InvalidInput)
        }
        val existing = accounts[req.email]
        if (existing?.verified == true) {
            notices += req.email
        } else {
            val c = req.credentials
            accounts[req.email] = Account(
                email = req.email, salt = c.salt, kdf = c.kdf, authHash = hash(c.authKey), slot1 = c.slot1,
                slot2 = req.slot2, recoveryHash = hash(req.recoveryAuth),
            )
            verifyCodes[req.email] = newCode()
        }
        ApiResult.Ok(Unit)
    }

    override suspend fun verify(email: String, code: String, authKey: ByteArray) = serve("verify") {
        val a = accounts[email]?.takeIf { !it.verified } ?: return@serve ApiResult.Err(ApiError.InvalidCode)
        if (!claim(verifyCodes, email, code, extraOk = matches(authKey, a.authHash))) {
            return@serve ApiResult.Err(ApiError.InvalidCode)
        }
        a.verified = true
        val token = newToken()
        sessions[token] = a.userId
        ApiResult.Ok(VerifyResult(token, a.userId, a.epoch))
    }

    override suspend fun resendVerify(email: String) = serve("resendVerify") {
        if (accounts[email]?.verified == false) verifyCodes[email] = newCode()
        ApiResult.Ok(Unit)
    }

    override suspend fun prelogin(email: String) = serve("prelogin") {
        val a = accounts[email]?.takeIf { it.verified }
        val salt = a?.salt ?: hash("fake:$email".toByteArray()).copyOf(16)
        ApiResult.Ok(Prelogin(salt, preloginKdfOverride ?: a?.kdf ?: KdfParams()))
    }

    override suspend fun login(email: String, authKey: ByteArray) = serve("login") {
        val a = accounts[email]?.takeIf { it.verified && matches(authKey, it.authHash) }
            ?: return@serve ApiResult.Err(ApiError.InvalidCredentials)
        a.epoch++
        val token = newToken()
        sessions[token] = a.userId
        ApiResult.Ok(LoginResult(token, a.userId, a.slot1, a.epoch, a.version))
    }

    override suspend fun logout(token: String) = serve("logout") {
        sessions.remove(token)
        ApiResult.Ok(Unit)
    }

    override suspend fun changePassword(token: String, oldAuthKey: ByteArray, new: Credentials) =
        authed("changePassword", token) { a ->
            if (!matches(oldAuthKey, a.authHash)) return@authed ApiResult.Err(ApiError.InvalidCredentials)
            if (!validCredentials(new)) return@authed ApiResult.Err(ApiError.InvalidInput)
            a.salt = new.salt
            a.kdf = new.kdf
            a.authHash = hash(new.authKey)
            a.slot1 = new.slot1
            sessions.entries.removeAll { it.value == a.userId && it.key != token }
            ApiResult.Ok(Unit)
        }

    override suspend fun requestReset(email: String) = serve("requestReset") {
        if (accounts[email]?.verified == true) resetCodes[email] = newCode()
        ApiResult.Ok(Unit)
    }

    override suspend fun verifyReset(email: String, code: String) = serve("verifyReset") {
        val a = accounts[email]?.takeIf { it.verified } ?: return@serve ApiResult.Err(ApiError.InvalidCode)
        if (!claim(resetCodes, email, code)) return@serve ApiResult.Err(ApiError.InvalidCode)
        val token = newToken()
        resetTokens[token] = a.userId
        ApiResult.Ok(ResetVerify(token, a.slot2))
    }

    override suspend fun completeReset(resetToken: String, recoveryAuth: ByteArray, new: Credentials) =
        serve("completeReset") {
            val a = resetTokens[resetToken]?.let(::byId) ?: return@serve ApiResult.Err(ApiError.InvalidToken)
            // token zostaje, można spróbować jeszcze raz
            if (!matches(recoveryAuth, a.recoveryHash)) return@serve ApiResult.Err(ApiError.InvalidRecovery)
            if (!validCredentials(new)) return@serve ApiResult.Err(ApiError.InvalidInput)
            resetTokens.remove(resetToken)
            a.salt = new.salt
            a.kdf = new.kdf
            a.authHash = hash(new.authKey)
            a.slot1 = new.slot1
            sessions.values.removeAll { it == a.userId }
            ApiResult.Ok(Unit)
        }

    override suspend fun wipeReset(resetToken: String, new: Credentials, slot2: ByteArray, recoveryAuth: ByteArray) =
        serve("wipeReset") {
            val a = resetTokens[resetToken]?.let(::byId) ?: return@serve ApiResult.Err(ApiError.InvalidToken)
            if (!validCredentials(new) || slot2.size != 60) return@serve ApiResult.Err(ApiError.InvalidInput)
            resetTokens.remove(resetToken)
            a.salt = new.salt
            a.kdf = new.kdf
            a.authHash = hash(new.authKey)
            a.slot1 = new.slot1
            a.slot2 = slot2
            a.recoveryHash = hash(recoveryAuth)
            a.blob = null // licznik wersji zostaje (R12)
            a.epoch++ // R13
            sessions.values.removeAll { it == a.userId }
            ApiResult.Ok(Unit)
        }

    override suspend fun getBackup(token: String) = authed("getBackup", token) { a ->
        ApiResult.Ok(RemoteBackup(a.version, a.epoch, a.blob))
    }

    override suspend fun putBackup(token: String, epoch: Long, baseVersion: Long?, blob: EncryptedBlob) =
        authed<PutOutcome>("putBackup", token) { a ->
            if (blob.ciphertext.size > 5 * 1024 * 1024) return@authed ApiResult.Err(ApiError.TooLarge)
            if (epoch != a.epoch || baseVersion != a.version) {
                return@authed ApiResult.Ok(PutOutcome.Conflict(a.epoch, a.version))
            }
            val version = (a.version ?: 0) + 1
            a.version = version
            a.blob = blob
            ApiResult.Ok(PutOutcome.Saved(version))
        }

    override suspend fun takeover(token: String) = authed("takeover", token) { a ->
        a.epoch++
        ApiResult.Ok(Pair(a.epoch, a.version))
    }

    override suspend fun deleteAccount(token: String, authKey: ByteArray) = authed("deleteAccount", token) { a ->
        if (!matches(authKey, a.authHash)) return@authed ApiResult.Err(ApiError.InvalidCredentials)
        accounts.remove(a.email)
        sessions.values.removeAll { it == a.userId }
        resetTokens.values.removeAll { it == a.userId }
        ApiResult.Ok(Unit)
    }
}
