package pl.agh.meditracker

import android.app.Activity
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

private const val BASE = "https://github.com/KrzysztofZdebski/medi-tracker/releases/download/katalog"
private const val SCHEMA = 1

class MainActivity : Activity() {
    private lateinit var log: TextView
    private val db by lazy { File(filesDir, "leki.sqlite") }
    private val localVersion by lazy { File(filesDir, "version.json") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sync = Button(this).apply { text = "Synchronizuj" }
        val gtin = EditText(this).apply {
            hint = "GTIN / EAN z pudełka"
            inputType = InputType.TYPE_CLASS_NUMBER
        }
        val search = Button(this).apply { text = "Szukaj" }
        log = TextView(this).apply { setTextIsSelectable(true) }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(sync)
            addView(gtin)
            addView(search)
            addView(ScrollView(context).apply { addView(log) })
        })

        sync.setOnClickListener {
            sync.isEnabled = false
            thread {
                try {
                    syncCatalog()
                } catch (e: Exception) {
                    say("Błąd: $e")
                }
                runOnUiThread { sync.isEnabled = true }
            }
        }
        search.setOnClickListener { lookup(gtin.text.toString().trim()) }
        say(if (db.exists()) "Katalog lokalny: ${localStan()}" else "Brak katalogu, kliknij Synchronizuj")
    }

    private fun say(line: String) = runOnUiThread { log.append(line + "\n") }

    private fun localStan(): String? =
        if (localVersion.exists()) JSONObject(localVersion.readText()).getString("stan_na_dzien") else null

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 60_000
        return c
    }

    private fun describe(name: String, c: HttpURLConnection, started: Long, bytes: Long) {
        say("GET $name → HTTP ${c.responseCode} ${c.responseMessage}")
        say("  host: ${c.url.host}, typ: ${c.contentType}, ${bytes} B, ${System.currentTimeMillis() - started} ms")
    }

    private fun syncCatalog() {
        say("--- synchronizacja ---")
        var t = System.currentTimeMillis()
        val vc = open("$BASE/version.json")
        if (vc.responseCode != 200) return describe("version.json", vc, t, 0)
        val versionText = vc.inputStream.use { it.readBytes() }.decodeToString()
        describe("version.json", vc, t, versionText.length.toLong())

        val remote = JSONObject(versionText)
        val stan = remote.getString("stan_na_dzien")
        say("  zdalny stan: $stan, schema ${remote.getInt("schema")}, lokalny stan: ${localStan()}")
        if (remote.getInt("schema") != SCHEMA) return say("Nieobsługiwana wersja schematu, pomijam")
        if (db.exists() && stan == localStan()) return say("Katalog aktualny")

        t = System.currentTimeMillis()
        val gc = open("$BASE/leki.sqlite.gz")
        if (gc.responseCode != 200) return describe("leki.sqlite.gz", gc, t, 0)
        val tmp = File(filesDir, "leki.sqlite.tmp")
        val sha = MessageDigest.getInstance("SHA-256")
        val counted = CountingStream(gc.inputStream)
        DigestInputStream(GZIPInputStream(counted), sha).use { input ->
            tmp.outputStream().use { input.copyTo(it) }
        }
        describe("leki.sqlite.gz", gc, t, counted.count)

        val hash = sha.digest().joinToString("") { "%02x".format(it) }
        if (hash != remote.getString("sha256") || tmp.length() != remote.getLong("size")) {
            tmp.delete()
            return say("  suma kontrolna się NIE zgadza, zostaje stary katalog")
        }
        tmp.renameTo(db)
        localVersion.writeText(versionText)
        say("  sha256 OK, rozpakowane ${db.length()} B, katalog zapisany")
    }

    private fun lookup(input: String) {
        if (!input.matches(Regex("\\d{8,14}"))) return say("Podaj 8–14 cyfr")
        if (!db.exists()) return say("Brak katalogu, najpierw Synchronizuj")
        val gtin14 = input.padStart(14, '0')
        SQLiteDatabase.openDatabase(db.path, null, SQLiteDatabase.OPEN_READONLY).use { d ->
            d.rawQuery(
                """SELECT p.nazwa, p.moc, o.zawartosc, k.gtin FROM kod k
                   JOIN opakowanie o ON o.id = k.opakowanie_id
                   JOIN produkt p ON p.id = o.produkt_id
                   WHERE k.gtin = ?""", arrayOf(gtin14)
            ).use { c ->
                if (!c.moveToFirst()) return say("$gtin14: nie znaleziono")
                do {
                    say("${c.getString(3)}: ${c.getString(0)} ${c.getString(1) ?: ""}, ${c.getString(2)}")
                } while (c.moveToNext())
            }
        }
    }
}

// liczy bajty faktycznie pobrane z sieci (przed rozpakowaniem)
private class CountingStream(inner: java.io.InputStream) : java.io.FilterInputStream(inner) {
    var count = 0L

    override fun read(): Int = super.read().also { if (it >= 0) count++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int =
        super.read(b, off, len).also { if (it > 0) count += it }
}
