package pl.agh.meditracker.data.catalog

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import pl.agh.meditracker.model.CatalogProduct
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val BASE = "https://github.com/KrzysztofZdebski/medi-tracker/releases/download/katalog"
private const val SCHEMA = 1

class CatalogRepository(private val dir: File) {
    private val db = File(dir, "leki.sqlite")
    private val localVersion = File(dir, "version.json")
    private val mutex = Mutex()

    private fun localStan(): String? =
        runCatching { JSONObject(localVersion.readText()).getString("stan_na_dzien") }.getOrNull()

    fun dataDate(): String? = localStan()

    private fun open(url: String) = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 60_000
    }

    suspend fun sync() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val vc = open("$BASE/version.json")
            val versionText = try {
                if (vc.responseCode != 200) throw IOException("Katalog: HTTP ${vc.responseCode}")
                vc.inputStream.bufferedReader().use { it.readText() }
            } finally { vc.disconnect() }
            val remote = JSONObject(versionText)
            if (remote.getInt("schema") != SCHEMA) throw IOException("Nieobsługiwany format katalogu")
            if (db.exists() && remote.getString("stan_na_dzien") == localStan()) return@withLock
            dir.mkdirs()
            val gc = open("$BASE/leki.sqlite.gz")
            val tmp = File(dir, "leki.sqlite.tmp")
            try {
                if (gc.responseCode != 200) throw IOException("Katalog: HTTP ${gc.responseCode}")
                val sha = MessageDigest.getInstance("SHA-256")
                DigestInputStream(GZIPInputStream(gc.inputStream), sha).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                val hash = sha.digest().joinToString("") { "%02x".format(it) }
                if (hash != remote.getString("sha256") || tmp.length() != remote.getLong("size")) throw IOException("Nieprawidłowa suma kontrolna katalogu")
                Files.move(tmp.toPath(), db.toPath(), StandardCopyOption.REPLACE_EXISTING)
                localVersion.writeText(versionText)
            } finally { gc.disconnect(); tmp.delete() }
        }
    }

    suspend fun findByEan(input: String): CatalogProduct? = withContext(Dispatchers.IO) {
        if (!input.matches(Regex("[0-9]{8,14}"))) return@withContext null
        if (!db.exists()) sync()
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
