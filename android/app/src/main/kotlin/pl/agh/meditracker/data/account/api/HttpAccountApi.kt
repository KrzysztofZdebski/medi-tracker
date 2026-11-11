package pl.agh.meditracker.data.account.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import pl.agh.meditracker.data.account.crypto.EncryptedBlob
import pl.agh.meditracker.data.account.crypto.KdfParams
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/** Klient HTTP API kont i kopii zapasowej. Nigdy nie loguje adresów e-mail, kodów, tokenów ani blobów. */
class HttpAccountApi(baseUrl: String, client: OkHttpClient) : AccountApi {
    private val base = baseUrl.trimEnd('/')
    private val http = client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private class Raw(val code: Int, val body: String)

    private fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)

    private fun kdfJson(k: KdfParams) = JSONObject().put("m", k.m).put("t", k.t).put("p", k.p)

    private fun JSONObject.putCredentials(c: Credentials): JSONObject = this
        .put("salt_pw", b64(c.salt))
        .put("kdf", kdfJson(c.kdf))
        .put("auth_key", b64(c.authKey))
        .put("slot1", b64(c.slot1))

    private fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else getLong(key)

    // blokujące wywołanie OkHttp; ponowienie tylko dla idempotentnych GET-ów
    private suspend fun exec(
        method: String,
        path: String,
        token: String? = null,
        body: JSONObject? = null,
        retryOnIo: Boolean = false,
    ): Raw? = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(base + path)
        if (token != null) builder.header("Authorization", "Bearer $token")
        val rb = body?.toString()?.toRequestBody("application/json".toMediaType())
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.method("DELETE", rb ?: ByteArray(0).toRequestBody())
            else -> builder.method(method, rb ?: ByteArray(0).toRequestBody())
        }
        val request = builder.build()
        repeat(if (retryOnIo) 2 else 1) {
            try {
                return@withContext http.newCall(request).execute().use { Raw(it.code, it.body?.string().orEmpty()) }
            } catch (e: IOException) {
                // kolejna próba (tylko GET) albo Network
            }
        }
        null
    }

    private fun errorOf(raw: Raw): ApiError {
        val code = runCatching { JSONObject(raw.body).optString("error", "") }.getOrDefault("")
        return when (code) {
            "invalid_input" -> ApiError.InvalidInput
            "invalid_code" -> ApiError.InvalidCode
            "invalid_credentials" -> ApiError.InvalidCredentials
            "unauthorized" -> ApiError.Unauthorized
            "invalid_token" -> ApiError.InvalidToken
            "invalid_recovery" -> ApiError.InvalidRecovery
            "too_large" -> ApiError.TooLarge
            "rate_limited" -> ApiError.RateLimited
            else -> when {
                raw.code in 500..599 -> ApiError.Server
                raw.code == 401 && raw.body.isBlank() -> ApiError.Unauthorized
                else -> ApiError.Server
            }
        }
    }

    private suspend fun <T> call(
        method: String,
        path: String,
        token: String? = null,
        body: JSONObject? = null,
        retryOnIo: Boolean = false,
        handle: (Raw) -> ApiResult<T>? = { null },
        parse: (JSONObject) -> T,
    ): ApiResult<T> {
        val raw = exec(method, path, token, body, retryOnIo) ?: return ApiResult.Err(ApiError.Network)
        return try {
            handle(raw) ?: if (raw.code in 200..299) {
                ApiResult.Ok(parse(if (raw.body.isBlank()) JSONObject() else JSONObject(raw.body)))
            } else {
                ApiResult.Err(errorOf(raw))
            }
        } catch (e: JSONException) {
            ApiResult.Err(ApiError.Server)
        } catch (e: IllegalArgumentException) { // zły base64
            ApiResult.Err(ApiError.Server)
        }
    }

    private suspend fun callUnit(
        method: String,
        path: String,
        token: String? = null,
        body: JSONObject? = null,
    ): ApiResult<Unit> = call(method, path, token, body) { }

    override suspend fun register(req: RegisterRequest) = callUnit(
        "POST", "/auth/register",
        body = JSONObject().put("email", req.email).putCredentials(req.credentials)
            .put("slot2", b64(req.slot2)).put("recovery_auth", b64(req.recoveryAuth)),
    )

    override suspend fun verify(email: String, code: String, authKey: ByteArray) = call(
        "POST", "/auth/verify",
        body = JSONObject().put("email", email).put("code", code).put("auth_key", b64(authKey)),
    ) { VerifyResult(it.getString("token"), it.getString("user_id"), it.getLong("epoch")) }

    override suspend fun resendVerify(email: String) =
        callUnit("POST", "/auth/verify/resend", body = JSONObject().put("email", email))

    override suspend fun prelogin(email: String) = call(
        "POST", "/auth/prelogin", body = JSONObject().put("email", email),
    ) {
        val k = it.getJSONObject("kdf")
        Prelogin(unb64(it.getString("salt_pw")), KdfParams(k.getInt("m"), k.getInt("t"), k.getInt("p")))
    }

    override suspend fun login(email: String, authKey: ByteArray) = call(
        "POST", "/auth/login",
        body = JSONObject().put("email", email).put("auth_key", b64(authKey)),
    ) {
        LoginResult(
            token = it.getString("token"),
            userId = it.getString("user_id"),
            slot1 = unb64(it.getString("slot1")),
            epoch = it.getLong("epoch"),
            backupVersion = it.longOrNull("backup_version"),
        )
    }

    override suspend fun logout(token: String) = callUnit("POST", "/auth/logout", token)

    override suspend fun changePassword(token: String, oldAuthKey: ByteArray, new: Credentials) = callUnit(
        "POST", "/auth/password", token,
        JSONObject().put("old_auth_key", b64(oldAuthKey)).putCredentials(new),
    )

    override suspend fun requestReset(email: String) =
        callUnit("POST", "/auth/reset/request", body = JSONObject().put("email", email))

    override suspend fun verifyReset(email: String, code: String) = call(
        "POST", "/auth/reset/verify", body = JSONObject().put("email", email).put("code", code),
    ) { ResetVerify(it.getString("reset_token"), unb64(it.getString("slot2"))) }

    override suspend fun completeReset(resetToken: String, recoveryAuth: ByteArray, new: Credentials) = callUnit(
        "POST", "/auth/reset/complete",
        body = JSONObject().put("reset_token", resetToken).put("recovery_auth", b64(recoveryAuth)).putCredentials(new),
    )

    override suspend fun wipeReset(resetToken: String, new: Credentials, slot2: ByteArray, recoveryAuth: ByteArray) =
        callUnit(
            "POST", "/auth/reset/wipe",
            body = JSONObject().put("reset_token", resetToken).putCredentials(new)
                .put("slot2", b64(slot2)).put("recovery_auth", b64(recoveryAuth)),
        )

    override suspend fun getBackup(token: String) = call("GET", "/backup", token, retryOnIo = true) {
        val nonce = if (it.isNull("nonce")) null else unb64(it.getString("nonce"))
        val ct = if (it.isNull("ciphertext")) null else unb64(it.getString("ciphertext"))
        RemoteBackup(
            version = it.longOrNull("version"),
            epoch = it.getLong("epoch"),
            blob = if (nonce != null && ct != null) EncryptedBlob(nonce, ct) else null,
        )
    }

    override suspend fun putBackup(token: String, epoch: Long, baseVersion: Long?, blob: EncryptedBlob) = call<PutOutcome>(
        "PUT", "/backup", token,
        JSONObject().put("epoch", epoch).put("base_version", baseVersion ?: JSONObject.NULL)
            .put("nonce", b64(blob.nonce)).put("ciphertext", b64(blob.ciphertext)),
        handle = { raw ->
            if (raw.code == 409) {
                val j = JSONObject(raw.body)
                ApiResult.Ok(PutOutcome.Conflict(j.getLong("epoch"), j.longOrNull("version")))
            } else null
        },
    ) { PutOutcome.Saved(it.getLong("version")) }

    override suspend fun takeover(token: String) = call("POST", "/backup/takeover", token) {
        Pair(it.getLong("epoch"), it.longOrNull("version"))
    }

    override suspend fun deleteAccount(token: String, authKey: ByteArray) =
        callUnit("DELETE", "/account", token, JSONObject().put("auth_key", b64(authKey)))
}
