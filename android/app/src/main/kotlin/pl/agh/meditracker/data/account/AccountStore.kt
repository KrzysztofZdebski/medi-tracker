package pl.agh.meditracker.data.account

import android.annotation.SuppressLint
import android.content.SharedPreferences
import pl.agh.meditracker.data.account.crypto.RecoveryCode
import java.time.Instant
import java.util.Base64

/**
 * Rejestracja między wysłaniem a potwierdzeniem kodu odzyskiwania; przeżywa śmierć procesu (spec §6.1.3).
 * [authKey] jest potrzebny tylko do weryfikacji kodu z maila (R9), potem null.
 */
data class PendingRegistration(
    val email: String,
    val stage: Stage,
    val dek: ByteArray,
    val recoveryCode: RecoveryCode,
    val authKey: ByteArray?,
) {
    enum class Stage { Verify, ShowRecoveryCode }

    override fun toString() = "PendingRegistration(stage=$stage)"
}

/**
 * Stan konta na telefonie (spec §5.3) w pliku prefs `account`, wyłączonym z kopii systemu
 * (data_extraction_rules.xml, backup_rules.xml).
 * Token, DEK i sekrety oczekującej rejestracji są szyfrowane przez [SecretBox]; jeśli nie da się ich odszyfrować
 * (klucz Keystore zniknął), są traktowane jak nieobecne. Zapis przez commit: plik jest mały, a utrata tokenu albo
 * oczekującej rejestracji przy zabiciu procesu byłaby gorsza niż krótki zapis na dysk.
 */
class AccountStore(private val prefs: SharedPreferences, private val box: SecretBox) {

    var userId: String?
        get() = prefs.getString(USER_ID, null)
        set(value) = write { putOrRemove(USER_ID, value) }

    var email: String?
        get() = prefs.getString(EMAIL, null)
        set(value) = write { putOrRemove(EMAIL, value) }

    var token: String?
        get() = readSealed(TOKEN)?.toString(Charsets.UTF_8)
        set(value) = write { putSealed(TOKEN, value?.toByteArray(Charsets.UTF_8)) }

    var dek: ByteArray?
        get() = readSealed(DEK)
        set(value) = write { putSealed(DEK, value) }

    var epoch: Long?
        get() = readLong(EPOCH)
        set(value) = write { putOrRemove(EPOCH, value) }

    /** Ostatnia znana wersja kopii na serwerze (base_version następnego PUT). */
    var backupVersion: Long?
        get() = readLong(BACKUP_VERSION)
        set(value) = write { putOrRemove(BACKUP_VERSION, value) }

    /** Dane zmieniły się od ostatniej udanej kopii. */
    var dirty: Boolean
        get() = prefs.getBoolean(DIRTY, false)
        set(value) = write { putBoolean(DIRTY, value) }

    var lastBackupAt: Instant?
        get() = readLong(LAST_BACKUP_AT)?.let(Instant::ofEpochMilli)
        set(value) = write { putOrRemove(LAST_BACKUP_AT, value?.toEpochMilli()) }

    /** Z którego konta pochodzą dane na telefonie; przeżywa [clear] i [clearSession], kasuje go wylogowanie. */
    var dataOwnerUserId: String?
        get() = prefs.getString(DATA_OWNER, null)
        set(value) = write { putOrRemove(DATA_OWNER, value) }

    var pending: PendingRegistration?
        get() {
            val email = prefs.getString(P_EMAIL, null) ?: return null
            val stageName = prefs.getString(P_STAGE, null)
            val stage = PendingRegistration.Stage.entries.find { it.name == stageName } ?: return null
            val dek = readSealed(P_DEK) ?: return null
            val code = readSealed(P_CODE)?.toString(Charsets.UTF_8)?.let(RecoveryCode::parse) ?: return null
            val authKey = if (prefs.contains(P_AUTH)) readSealed(P_AUTH) ?: return null else null
            return PendingRegistration(email, stage, dek, code, authKey)
        }
        set(value) = write { putPending(value) }

    /**
     * Zapisuje nową sesję razem z [pending] jednym commitem (po weryfikacji rejestracji i po logowaniu), żeby
     * zabicie procesu w połowie nie zostawiło sesji bez etapu rejestracji albo odwrotnie. [claimData] ustawia
     * [dataOwnerUserId] na to konto (po rejestracji; po logowaniu dopiero po decyzji przywróć / zachowaj, R17).
     */
    fun startSession(
        email: String,
        userId: String,
        token: String,
        dek: ByteArray,
        epoch: Long,
        backupVersion: Long?,
        pending: PendingRegistration?,
        claimData: Boolean,
    ) = write {
        putString(EMAIL, email)
        putString(USER_ID, userId)
        putSealed(TOKEN, token.toByteArray(Charsets.UTF_8))
        putSealed(DEK, dek)
        putLong(EPOCH, epoch)
        putOrRemove(BACKUP_VERSION, backupVersion)
        if (claimData) putString(DATA_OWNER, userId)
        putPending(pending)
    }

    /** Wygaśnięcie sesji: znika token, DEK i epoch; e-mail, stan kopii i [dataOwnerUserId] zostają. */
    fun clearSession() = write { listOf(TOKEN, DEK, EPOCH).forEach { remove(it) } }

    /** Usuwa wszystko oprócz [dataOwnerUserId] (usunięcie konta, reset hasła, wylogowanie w Task 11). */
    fun clear() = write { prefs.all.keys.filter { it != DATA_OWNER }.forEach { remove(it) } }

    @SuppressLint("ApplySharedPref") // celowo commit, patrz opis klasy
    private fun write(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(block).commit()
    }

    private fun readLong(key: String): Long? = if (prefs.contains(key)) prefs.getLong(key, 0) else null

    private fun readSealed(key: String): ByteArray? {
        val stored = prefs.getString(key, null) ?: return null
        return try {
            box.open(Base64.getDecoder().decode(stored))
        } catch (e: Exception) {
            null
        }
    }

    private fun SharedPreferences.Editor.putSealed(key: String, value: ByteArray?) {
        if (value == null) remove(key) else putString(key, Base64.getEncoder().encodeToString(box.seal(value)))
    }

    private fun SharedPreferences.Editor.putPending(value: PendingRegistration?) {
        if (value == null) {
            PENDING_KEYS.forEach { remove(it) }
        } else {
            putString(P_EMAIL, value.email)
            putString(P_STAGE, value.stage.name)
            putSealed(P_DEK, value.dek)
            putSealed(P_CODE, value.recoveryCode.display().toByteArray(Charsets.UTF_8))
            putSealed(P_AUTH, value.authKey)
        }
    }

    private fun SharedPreferences.Editor.putOrRemove(key: String, value: String?) {
        if (value == null) remove(key) else putString(key, value)
    }

    private fun SharedPreferences.Editor.putOrRemove(key: String, value: Long?) {
        if (value == null) remove(key) else putLong(key, value)
    }

    private companion object {
        const val USER_ID = "user_id"
        const val EMAIL = "email"
        const val TOKEN = "token"
        const val DEK = "dek"
        const val EPOCH = "epoch"
        const val BACKUP_VERSION = "backup_version"
        const val DIRTY = "dirty"
        const val LAST_BACKUP_AT = "last_backup_at"
        const val DATA_OWNER = "data_owner_user_id"
        const val P_EMAIL = "pending_email"
        const val P_STAGE = "pending_stage"
        const val P_DEK = "pending_dek"
        const val P_CODE = "pending_code"
        const val P_AUTH = "pending_auth_key"
        val PENDING_KEYS = listOf(P_EMAIL, P_STAGE, P_DEK, P_CODE, P_AUTH)
    }
}
