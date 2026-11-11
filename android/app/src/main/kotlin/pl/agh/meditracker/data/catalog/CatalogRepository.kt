package pl.agh.meditracker.data.catalog

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import pl.agh.meditracker.BuildConfig
import pl.agh.meditracker.model.CatalogProduct
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

private const val SCHEMA = 1

// Unsupported: nowszy schemat katalogu wymaga nowszej wersji aplikacji
enum class CatalogSync { Updated, UpToDate, Unsupported, Failed }

class CatalogRepository(
    private val dir: File,
    private val baseUrl: String = "${BuildConfig.SERVER_URL}/catalog",
) {
    private val db = File(dir, "leki.sqlite")
    private val localVersion = File(dir, "version.json")

    private fun localStan(): String? =
        runCatching { JSONObject(localVersion.readText()).getString("stan_na_dzien") }.getOrNull()

    // data stanu RPL lokalnego katalogu; null, dopóki pierwsza synchronizacja się nie uda
    val asOf: String? get() = localStan()?.takeIf { db.exists() }

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 60_000
    }

    // synchronizacja przy starcie i ręczna z ustawień nie mogą pisać naraz do tego samego pliku tymczasowego
    private val syncing = Mutex()

    suspend fun sync(): CatalogSync = syncing.withLock { download() }

    private suspend fun download(): CatalogSync = withContext(Dispatchers.IO) {
        val vc = open("$baseUrl/version.json")
        if (vc.responseCode != 200) return@withContext CatalogSync.Failed
        val versionText = vc.inputStream.use { it.readBytes() }.decodeToString()
        val remote = JSONObject(versionText)
        if (remote.getInt("schema") != SCHEMA) return@withContext CatalogSync.Unsupported
        if (db.exists() && remote.getString("stan_na_dzien") == localStan()) return@withContext CatalogSync.UpToDate

        val gc = open("$baseUrl/leki.sqlite.gz")
        if (gc.responseCode != 200) return@withContext CatalogSync.Failed
        val tmp = File(dir, "leki.sqlite.tmp")
        val sha = MessageDigest.getInstance("SHA-256")
        DigestInputStream(GZIPInputStream(gc.inputStream), sha).use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }
        val hash = sha.digest().joinToString("") { "%02x".format(it) }
        if (hash != remote.getString("sha256") || tmp.length() != remote.getLong("size")) {
            tmp.delete()
            return@withContext CatalogSync.Failed
        }
        tmp.renameTo(db)
        localVersion.writeText(versionText)
        CatalogSync.Updated
    }

    suspend fun findByEan(input: String): CatalogProduct? = withContext(Dispatchers.IO) {
        if (!input.matches(Regex("[0-9]{8,14}"))) return@withContext null
        // ponowna próba bez katalogu najpierw go pobiera
        if (!db.exists()) sync()
        check(db.exists()) { "Katalog leków jest niedostępny. Spróbuj ponownie po synchronizacji." }
        SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
            d.rawQuery(
                """SELECT p.nazwa, p.moc, p.postac, p.podmiot, o.zawartosc, o.ilosc FROM kod k
                   JOIN opakowanie o ON o.id = k.opakowanie_id
                   JOIN produkt p ON p.id = o.produkt_id
                   WHERE k.gtin = ?""", arrayOf(input.padStart(14, '0'))
            ).use { c ->
                if (!c.moveToFirst()) return@withContext null
                CatalogProduct(
                    name = c.getString(0),
                    strength = c.getString(1),
                    form = c.getString(2),
                    manufacturer = c.getString(3),
                    packageSize = c.getString(4),
                    packageCount = if (c.isNull(5)) null else c.getDouble(5).toInt(),
                    ean = input,
                )
            }
        }
    }
}
