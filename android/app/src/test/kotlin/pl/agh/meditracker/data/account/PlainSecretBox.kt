package pl.agh.meditracker.data.account

// SecretBox bez Keystore (Robolectric go nie ma); failOpen udaje klucz utracony po przywróceniu kopii systemu
class PlainSecretBox : SecretBox {
    var failOpen = false

    override fun seal(plain: ByteArray): ByteArray = MARK + plain

    override fun open(sealed: ByteArray): ByteArray {
        if (failOpen || sealed.size < MARK.size || !sealed.copyOfRange(0, MARK.size).contentEquals(MARK)) {
            throw IllegalStateException("cannot open")
        }
        return sealed.copyOfRange(MARK.size, sealed.size)
    }

    private companion object {
        val MARK = byteArrayOf(0x53, 0x42)
    }
}
