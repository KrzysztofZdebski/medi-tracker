package pl.agh.meditracker.data.account.crypto

// Kod odzyskiwania: 130 losowych bitów jako 26 znaków Crockford base32 (big-endian), np. K7QM-3VXA-…
// `bytes` to ta sama wartość w 17 bajtach (najstarsze 6 bitów zawsze zerowe) – wejście HKDF w recoveryKeys.
class RecoveryCode private constructor(val bytes: ByteArray) {

    fun display(): String = encode(bytes).chunked(4).joinToString("-")

    override fun equals(other: Any?) = other is RecoveryCode && bytes.contentEquals(other.bytes)

    override fun hashCode() = bytes.contentHashCode()

    // nigdy nie wypisujemy kodu do logów
    override fun toString() = "RecoveryCode(***)"

    companion object {
        private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
        private const val CHARS = 26
        private const val BYTES = 17
        private const val PAD_BITS = BYTES * 8 - CHARS * 5 // 6 zerowych bitów na początku bajtu 0

        fun generate(): RecoveryCode {
            val bytes = randomBytes(BYTES)
            bytes[0] = (bytes[0].toInt() and 0x03).toByte()
            return RecoveryCode(bytes)
        }

        /** Wybacza wielkość liter, spacje, myślniki i pomyłki O→0, I/L→1; null, gdy to nie jest 26 znaków alfabetu. */
        fun parse(input: String): RecoveryCode? {
            val chars = input.filterNot { it.isWhitespace() || it == '-' }
                .uppercase()
                .map {
                    when (it) {
                        'O' -> '0'
                        'I', 'L' -> '1'
                        else -> it
                    }
                }
            if (chars.size != CHARS) return null
            val bytes = ByteArray(BYTES)
            chars.forEachIndexed { i, c ->
                val v = ALPHABET.indexOf(c)
                if (v < 0) return null
                for (b in 0 until 5) {
                    if ((v shr (4 - b)) and 1 == 1) {
                        val bit = PAD_BITS + i * 5 + b
                        bytes[bit / 8] = (bytes[bit / 8].toInt() or (0x80 ushr (bit % 8))).toByte()
                    }
                }
            }
            // 26 znaków to dokładnie 130 bitów, więc każdy poprawny napis mieści się w zakresie
            return RecoveryCode(bytes)
        }

        private fun encode(bytes: ByteArray): String = buildString(CHARS) {
            for (i in 0 until CHARS) {
                var v = 0
                for (b in 0 until 5) {
                    val bit = PAD_BITS + i * 5 + b
                    v = (v shl 1) or ((bytes[bit / 8].toInt() shr (7 - bit % 8)) and 1)
                }
                append(ALPHABET[v])
            }
        }
    }
}
