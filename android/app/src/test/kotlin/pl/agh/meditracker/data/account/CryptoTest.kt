package pl.agh.meditracker.data.account

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.agh.meditracker.data.account.crypto.EncryptedBlob
import pl.agh.meditracker.data.account.crypto.KdfParams
import pl.agh.meditracker.data.account.crypto.RecoveryCode
import pl.agh.meditracker.data.account.crypto.WrongKeyException
import pl.agh.meditracker.data.account.crypto.argon2id
import pl.agh.meditracker.data.account.crypto.blobAad
import pl.agh.meditracker.data.account.crypto.decryptBlob
import pl.agh.meditracker.data.account.crypto.encryptBlob
import pl.agh.meditracker.data.account.crypto.hkdf
import pl.agh.meditracker.data.account.crypto.passwordKeys
import pl.agh.meditracker.data.account.crypto.randomBytes
import pl.agh.meditracker.data.account.crypto.recoveryKeys
import pl.agh.meditracker.data.account.crypto.unwrap
import pl.agh.meditracker.data.account.crypto.wrap

class CryptoTest {
    // małe parametry, żeby testy były szybkie; prawdziwe tylko w teście czasu
    private val fast = KdfParams(m = 1024, t = 1, p = 1)
    private val salt = ByteArray(16) { it.toByte() }

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun wrapUnwrapRoundTrip() {
        val kek = randomBytes(32)
        val dek = randomBytes(32)
        val wrapped = wrap(kek, dek)
        assertEquals(12 + 32 + 16, wrapped.size)
        assertArrayEquals(dek, unwrap(kek, wrapped))
        assertFalse(wrapped.contentEquals(wrap(kek, dek)))
    }

    @Test
    fun unwrapWithWrongKekThrows() {
        val wrapped = wrap(randomBytes(32), randomBytes(32))
        assertThrows(WrongKeyException::class.java) { unwrap(randomBytes(32), wrapped) }
        assertThrows(WrongKeyException::class.java) { unwrap(randomBytes(32), ByteArray(10)) }
    }

    @Test
    fun passwordChangeKeepsSameDek() {
        val a = passwordKeys("stare-haslo-123".toCharArray(), salt, fast)
        val b = passwordKeys("nowe-haslo-456".toCharArray(), randomBytes(16), fast)
        val dek = randomBytes(32)
        val plain = "{\"format\":1}".toByteArray()
        val blob = encryptBlob(dek, plain, blobAad("u1"))

        val slotA = wrap(a.kek, dek)
        val slotB = wrap(b.kek, unwrap(a.kek, slotA))
        val dekAfter = unwrap(b.kek, slotB)

        assertArrayEquals(dek, dekAfter)
        assertArrayEquals(plain, decryptBlob(dekAfter, blob, blobAad("u1")))
        assertThrows(WrongKeyException::class.java) { unwrap(a.kek, slotB) }
    }

    @Test
    fun differentInfoStringsGiveDifferentKeys() {
        val pw = passwordKeys("haslo-testowe".toCharArray(), salt, fast)
        assertEquals(32, pw.authKey.size)
        assertEquals(32, pw.kek.size)
        assertFalse(pw.authKey.contentEquals(pw.kek))

        val rc = recoveryKeys(RecoveryCode.generate())
        assertEquals(32, rc.auth.size)
        assertEquals(32, rc.kek.size)
        assertFalse(rc.auth.contentEquals(rc.kek))
        assertFalse(rc.auth.contentEquals(pw.authKey))
    }

    @Test
    fun passwordIsNfcNormalized() {
        val composed = "zażółć"
        val decomposed = "zażółć"
        assertNotEquals(composed, decomposed)
        assertArrayEquals(
            passwordKeys(composed.toCharArray(), salt, fast).authKey,
            passwordKeys(decomposed.toCharArray(), salt, fast).authKey,
        )
    }

    @Test
    fun argon2idKnownVector() {
        // RFC 9106 §5.3 (Argon2id, wersja 0x13)
        val tag = argon2id(
            password = ByteArray(32) { 0x01 },
            salt = ByteArray(16) { 0x02 },
            params = KdfParams(m = 32, t = 3, p = 4),
            secret = ByteArray(8) { 0x03 },
            associatedData = ByteArray(12) { 0x04 },
        )
        assertArrayEquals(hex("0d640df58d78766c08c037a34a8b53c9d01ef0452d75b65eb52520e96b01e659"), tag)
    }

    @Test
    fun hkdfKnownVector() {
        // RFC 5869 A.3 (SHA-256, pusta sól i info) – ta sama konfiguracja soli co dla kluczy
        val okm = hkdf(ByteArray(22) { 0x0b }, "", 42)
        assertArrayEquals(
            hex("8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8"),
            okm,
        )
    }

    @Test
    fun blobFromOtherAccountFailsAad() {
        assertArrayEquals("u1:1".toByteArray(), blobAad("u1"))
        val dek = randomBytes(32)
        val blob = encryptBlob(dek, "dane".toByteArray(), blobAad("u1"))
        assertEquals(12, blob.nonce.size)
        assertThrows(WrongKeyException::class.java) { decryptBlob(dek, blob, blobAad("u2")) }
        assertThrows(WrongKeyException::class.java) { decryptBlob(randomBytes(32), blob, blobAad("u1")) }
        assertThrows(WrongKeyException::class.java) {
            decryptBlob(dek, EncryptedBlob(ByteArray(16), blob.ciphertext), blobAad("u1"))
        }
    }

    @Test
    fun recoveryCodeParsingIsForgiving() {
        val code = RecoveryCode.generate()
        assertEquals(code, RecoveryCode.parse(code.display().lowercase().replace("-", " ")))
        assertEquals(code, RecoveryCode.parse(code.display().replace("-", "")))

        val zeros = RecoveryCode.parse("0".repeat(26))
        assertEquals(zeros, RecoveryCode.parse("O".repeat(26)))
        assertEquals(zeros, RecoveryCode.parse("o".repeat(26)))
        val ones = RecoveryCode.parse("1".repeat(26))
        assertEquals(ones, RecoveryCode.parse("I".repeat(26)))
        assertEquals(ones, RecoveryCode.parse("l".repeat(13) + "L".repeat(13)))

        assertNull(RecoveryCode.parse("0".repeat(25)))
        assertNull(RecoveryCode.parse("0".repeat(27)))
        assertNull(RecoveryCode.parse("U" + "0".repeat(25)))
        assertNull(RecoveryCode.parse(""))
    }

    @Test
    fun recoveryCodeHas130Bits() {
        val code = RecoveryCode.generate()
        assertEquals(26, code.display().replace("-", "").length)
        assertEquals(17, code.bytes.size)
        assertEquals(0, code.bytes[0].toInt() and 0xFC)
        assertTrue(Regex("([0-9A-HJKMNP-TV-Z]{4}-){6}[0-9A-HJKMNP-TV-Z]{2}").matches(code.display()))
    }

    @Test
    fun recoveryCodeEncodingIsBigEndianOver130Bits() {
        assertArrayEquals(ByteArray(17), RecoveryCode.parse("0".repeat(26))!!.bytes)
        assertArrayEquals(ByteArray(16) + byteArrayOf(1), RecoveryCode.parse("0".repeat(25) + "1")!!.bytes)
        // pierwszy znak to najstarsze bity 129..125: "1" + zera = 2^125, czyli bajt 1 = 0x20
        assertArrayEquals(
            byteArrayOf(0x00, 0x20) + ByteArray(15),
            RecoveryCode.parse("1" + "0".repeat(25))!!.bytes,
        )
        // 26 znaków to dokładnie 130 bitów, więc "ZZ…Z" = 2^130 − 1 to największa wartość
        val max = RecoveryCode.parse("Z".repeat(26))!!
        assertArrayEquals(byteArrayOf(0x03) + ByteArray(16) { -1 }, max.bytes)
        assertEquals("ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZZZ-ZZ", max.display())
        repeat(50) {
            val code = RecoveryCode.generate()
            assertEquals(code, RecoveryCode.parse(code.display()))
        }
    }

    @Test
    fun wrapUnwrapWithRealParamsIsReasonablyFast() {
        // tylko pomiar (JVM na komputerze, nie telefon) – bez progu
        val start = System.nanoTime()
        val keys = passwordKeys("haslo-testowe-123".toCharArray(), randomBytes(16), KdfParams())
        val ms = (System.nanoTime() - start) / 1_000_000
        val dek = randomBytes(32)
        assertArrayEquals(dek, unwrap(keys.kek, wrap(keys.kek, dek)))
        println("passwordKeys(m=65536, t=3, p=1): $ms ms")
    }
}
