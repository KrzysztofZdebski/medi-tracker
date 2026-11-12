package pl.agh.meditracker.data.account

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pl.agh.meditracker.data.account.api.ApiError
import pl.agh.meditracker.data.account.api.ApiResult
import pl.agh.meditracker.data.account.api.Credentials
import pl.agh.meditracker.data.account.api.HttpAccountApi
import pl.agh.meditracker.data.account.api.PutOutcome
import pl.agh.meditracker.data.account.api.RegisterRequest
import pl.agh.meditracker.data.account.crypto.EncryptedBlob
import pl.agh.meditracker.data.account.crypto.KdfParams
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HttpAccountApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: HttpAccountApi

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        api = HttpAccountApi(server.url("/").toString().trimEnd('/'), OkHttpClient())
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun errOf(r: ApiResult<*>) = (r as ApiResult.Err).error

    @Test
    fun loginParsesSuccess() = runBlocking {
        val slot1 = byteArrayOf(1, 2, 3, 4)
        server.enqueue(
            json(200, """{"token":"tok","user_id":"u1","slot1":"${b64(slot1)}","epoch":7,"backup_version":null}""")
        )
        val r = api.login("a@b.pl", byteArrayOf(9, 9))
        val ok = (r as ApiResult.Ok).value
        assertEquals("tok", ok.token)
        assertEquals("u1", ok.userId)
        assertArrayEquals(slot1, ok.slot1)
        assertEquals(7L, ok.epoch)
        assertNull(ok.backupVersion)
        val req = server.takeRequest()
        assertEquals("/auth/login", req.path)
        val body = JSONObject(req.body.readUtf8())
        assertEquals("a@b.pl", body.getString("email"))
        assertEquals(b64(byteArrayOf(9, 9)), body.getString("auth_key"))
    }

    @Test
    fun errorCodesMapToEnum() = runBlocking {
        server.enqueue(json(401, """{"error":"invalid_credentials"}"""))
        assertEquals(ApiError.InvalidCredentials, errOf(api.login("a@b.pl", ByteArray(1))))
        server.enqueue(json(429, """{"error":"rate_limited"}"""))
        assertEquals(ApiError.RateLimited, errOf(api.resendVerify("a@b.pl")))
        server.enqueue(json(400, """{"error":"invalid_code"}"""))
        assertEquals(ApiError.InvalidCode, errOf(api.verify("a@b.pl", "123456", ByteArray(1))))
        server.enqueue(json(400, """{"error":"invalid_input"}"""))
        assertEquals(ApiError.InvalidInput, errOf(api.resendVerify("x")))
        server.enqueue(json(401, """{"error":"invalid_token"}"""))
        assertEquals(ApiError.InvalidToken, errOf(api.verifyReset("a@b.pl", "1")))
        server.enqueue(json(401, """{"error":"invalid_recovery"}"""))
        val creds = Credentials(ByteArray(16), KdfParams(), ByteArray(32), ByteArray(60))
        assertEquals(ApiError.InvalidRecovery, errOf(api.completeReset("t", ByteArray(32), creds)))
        server.enqueue(json(413, """{"error":"too_large"}"""))
        assertEquals(ApiError.TooLarge, errOf(api.putBackup("t", 1, null, EncryptedBlob(ByteArray(12), ByteArray(3)))))
        server.enqueue(json(401, """{"error":"unauthorized"}"""))
        assertEquals(ApiError.Unauthorized, errOf(api.getBackup("t")))
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(ApiError.Unauthorized, errOf(api.logout("t")))
        server.enqueue(json(500, """{"error":"internal"}"""))
        assertEquals(ApiError.Server, errOf(api.resendVerify("a@b.pl")))
        server.enqueue(json(200, "<html>not json</html>"))
        assertEquals(ApiError.Server, errOf(api.login("a@b.pl", ByteArray(1))))
        server.enqueue(json(502, "bad gateway"))
        assertEquals(ApiError.Server, errOf(api.resendVerify("a@b.pl")))
    }

    @Test
    fun putBackupConflictReturnsConflictOutcome() = runBlocking {
        server.enqueue(json(409, """{"error":"conflict","epoch":5,"version":12}"""))
        val r = api.putBackup("t", 4, 11, EncryptedBlob(ByteArray(12), ByteArray(3)))
        assertEquals(PutOutcome.Conflict(5, 12), (r as ApiResult.Ok).value)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(4L, body.getLong("epoch"))
        assertEquals(11L, body.getLong("base_version"))
    }

    @Test
    fun putBackupSavedAndNullBaseVersion() = runBlocking {
        server.enqueue(json(200, """{"version":1}"""))
        val blob = EncryptedBlob(byteArrayOf(1), byteArrayOf(2, 3))
        val r = api.putBackup("t", 1, null, blob)
        assertEquals(PutOutcome.Saved(1), (r as ApiResult.Ok).value)
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/backup", req.path)
        val body = JSONObject(req.body.readUtf8())
        assertTrue(body.isNull("base_version"))
        assertEquals(b64(byteArrayOf(2, 3)), body.getString("ciphertext"))
    }

    @Test
    fun getBackupWithoutCiphertextHasNullBlob() = runBlocking {
        server.enqueue(json(200, """{"version":3,"epoch":2,"nonce":null,"ciphertext":null,"updated_at":null}"""))
        val rb = (api.getBackup("t") as ApiResult.Ok).value
        assertEquals(3L, rb.version)
        assertEquals(2L, rb.epoch)
        assertNull(rb.blob)
    }

    @Test
    fun getBackupParsesBlob() = runBlocking {
        server.enqueue(
            json(200, """{"version":3,"epoch":2,"nonce":"${b64(byteArrayOf(1, 2))}","ciphertext":"${b64(byteArrayOf(5, 6, 7))}","updated_at":1}""")
        )
        val rb = (api.getBackup("t") as ApiResult.Ok).value
        assertArrayEquals(byteArrayOf(1, 2), rb.blob!!.nonce)
        assertArrayEquals(byteArrayOf(5, 6, 7), rb.blob!!.ciphertext)
    }

    @Test
    fun connectionFailureIsNetwork() = runBlocking {
        server.shutdown()
        assertEquals(ApiError.Network, errOf(api.login("a@b.pl", ByteArray(1))))
        assertEquals(ApiError.Network, errOf(api.getBackup("t")))
    }

    @Test
    fun bearerHeaderSent() = runBlocking {
        server.enqueue(json(200, """{"epoch":3,"version":null}"""))
        val r = api.takeover("tok123")
        assertEquals(Pair(3L, null as Long?), (r as ApiResult.Ok).value)
        val req = server.takeRequest()
        assertEquals("Bearer tok123", req.getHeader("Authorization"))
        assertEquals("POST", req.method)
        assertEquals("/backup/takeover", req.path)
    }

    @Test
    fun getRetriesOnceOnIoException() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(json(200, """{"version":null,"epoch":1,"nonce":null,"ciphertext":null,"updated_at":null}"""))
        assertTrue(api.getBackup("t") is ApiResult.Ok)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun postIsNotRetried() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(json(202, "{}"))
        assertEquals(ApiError.Network, errOf(api.resendVerify("a@b.pl")))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun registerAndPreloginBodies() = runBlocking {
        server.enqueue(json(202, "{}"))
        val creds = Credentials(byteArrayOf(1), KdfParams(65536, 3, 1), byteArrayOf(2), byteArrayOf(3))
        assertTrue(api.register(RegisterRequest("a@b.pl", creds, byteArrayOf(4), byteArrayOf(5))) is ApiResult.Ok)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(b64(byteArrayOf(1)), body.getString("salt_pw"))
        assertEquals(65536, body.getJSONObject("kdf").getInt("m"))
        assertEquals(b64(byteArrayOf(2)), body.getString("auth_key"))
        assertEquals(b64(byteArrayOf(3)), body.getString("slot1"))
        assertEquals(b64(byteArrayOf(4)), body.getString("slot2"))
        assertEquals(b64(byteArrayOf(5)), body.getString("recovery_auth"))

        server.enqueue(json(200, """{"salt_pw":"${b64(byteArrayOf(8, 9))}","kdf":{"m":65536,"t":3,"p":1}}"""))
        val pre = (api.prelogin("a@b.pl") as ApiResult.Ok).value
        assertArrayEquals(byteArrayOf(8, 9), pre.salt)
        assertEquals(KdfParams(65536, 3, 1), pre.kdf)
    }
}
