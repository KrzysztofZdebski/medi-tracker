package pl.agh.meditracker.data.account.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.crypto.params.HKDFParameters
import java.nio.CharBuffer
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Cała kryptografia konta dzieje się na telefonie; serwer dostaje tylko hashe, opakowane klucze i szyfrogram.
// Zmiana czegokolwiek tutaj (parametry, info, AAD, kodowanie) oznacza, że istniejących kopii nie da się odczytać.

data class KdfParams(val m: Int = 65536, val t: Int = 3, val p: Int = 1)

class PasswordKeys(val authKey: ByteArray, val kek: ByteArray)

class RecoveryKeys(val auth: ByteArray, val kek: ByteArray)

class EncryptedBlob(val nonce: ByteArray, val ciphertext: ByteArray)

/** Klucz nie pasuje albo dane są uszkodzone / z innego konta (zły tag GCM). */
class WrongKeyException(cause: Throwable? = null) : Exception("wrong key or corrupted data", cause)

private const val KEY_LEN = 32
private const val NONCE_LEN = 12
private const val TAG_BITS = 128

private val random = SecureRandom()

fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

fun blobAad(userId: String) = "$userId:1".toByteArray(Charsets.UTF_8)

fun passwordKeys(password: CharArray, salt: ByteArray, params: KdfParams): PasswordKeys {
    val pwBytes = nfcUtf8(password)
    val master = try {
        argon2id(pwBytes, salt, params)
    } finally {
        pwBytes.fill(0)
    }
    try {
        return PasswordKeys(hkdf(master, "meditracker-auth"), hkdf(master, "meditracker-kek"))
    } finally {
        master.fill(0)
    }
}

fun recoveryKeys(code: RecoveryCode): RecoveryKeys =
    RecoveryKeys(hkdf(code.bytes, "meditracker-recovery-auth"), hkdf(code.bytes, "meditracker-recovery-kek"))

/** Zwraca `nonce(12) || szyfrogram+tag`. */
fun wrap(kek: ByteArray, dek: ByteArray): ByteArray {
    val nonce = randomBytes(NONCE_LEN)
    return nonce + gcm(Cipher.ENCRYPT_MODE, kek, nonce, dek, aad = null)
}

fun unwrap(kek: ByteArray, wrapped: ByteArray): ByteArray {
    if (wrapped.size < NONCE_LEN + TAG_BITS / 8) throw WrongKeyException()
    val nonce = wrapped.copyOfRange(0, NONCE_LEN)
    return gcm(Cipher.DECRYPT_MODE, kek, nonce, wrapped.copyOfRange(NONCE_LEN, wrapped.size), aad = null)
}

fun encryptBlob(dek: ByteArray, plain: ByteArray, aad: ByteArray): EncryptedBlob {
    val nonce = randomBytes(NONCE_LEN)
    return EncryptedBlob(nonce, gcm(Cipher.ENCRYPT_MODE, dek, nonce, plain, aad))
}

fun decryptBlob(dek: ByteArray, blob: EncryptedBlob, aad: ByteArray): ByteArray {
    if (blob.nonce.size != NONCE_LEN) throw WrongKeyException()
    return gcm(Cipher.DECRYPT_MODE, dek, blob.nonce, blob.ciphertext, aad)
}

internal fun argon2id(
    password: ByteArray,
    salt: ByteArray,
    params: KdfParams,
    secret: ByteArray? = null,
    associatedData: ByteArray? = null,
): ByteArray {
    val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
        .withVersion(Argon2Parameters.ARGON2_VERSION_13)
        .withMemoryAsKB(params.m)
        .withIterations(params.t)
        .withParallelism(params.p)
        .withSalt(salt)
    if (secret != null) builder.withSecret(secret)
    if (associatedData != null) builder.withAdditional(associatedData)
    val out = ByteArray(KEY_LEN)
    Argon2BytesGenerator().apply { init(builder.build()) }.generateBytes(password, out)
    return out
}

/** HKDF-SHA256 z pustą solą (RFC 5869: równoważna soli z samych zer). */
internal fun hkdf(ikm: ByteArray, info: String, length: Int = KEY_LEN): ByteArray {
    val generator = HKDFBytesGenerator(SHA256Digest())
    generator.init(HKDFParameters(ikm, ByteArray(0), info.toByteArray(Charsets.UTF_8)))
    return ByteArray(length).also { generator.generateBytes(it, 0, length) }
}

// NFC, żeby to samo hasło wpisane różnymi klawiaturami (ż jako jeden znak albo z + kropka) dało ten sam klucz
private fun nfcUtf8(password: CharArray): ByteArray {
    val normalized = Normalizer.normalize(CharBuffer.wrap(password), Normalizer.Form.NFC)
    return normalized.toByteArray(Charsets.UTF_8)
}

private fun gcm(mode: Int, key: ByteArray, nonce: ByteArray, input: ByteArray, aad: ByteArray?): ByteArray {
    require(key.size == KEY_LEN) { "AES-256 key must be 32 bytes" }
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
    if (aad != null) cipher.updateAAD(aad)
    return try {
        cipher.doFinal(input)
    } catch (e: AEADBadTagException) {
        throw WrongKeyException(e)
    }
}
