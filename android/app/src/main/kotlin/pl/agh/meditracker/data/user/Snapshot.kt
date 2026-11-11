package pl.agh.meditracker.data.user

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import pl.agh.meditracker.data.settings.AppTheme
import pl.agh.meditracker.model.DoseStatus
import pl.agh.meditracker.model.DoseUnit
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

// Ustawienia, które jadą razem z kopią (przypomnienia zależą od uprawnień urządzenia, więc zostają lokalne).
data class SyncedSettings(val theme: AppTheme = AppTheme.System, val snoozeMinutes: Int = 15)

// Surowy zrzut user.db razem z lekami zarchiwizowanymi (StoredState ich nie zawiera).
data class Snapshot(
    val medications: List<MedicationRow>,
    val schedules: List<ScheduleRow>,
    val doseLogs: List<DoseLogRow>,
    val history: List<HistoryRow>,
    val settings: SyncedSettings,
)

class UnsupportedSnapshotException(message: String, cause: Throwable? = null) : Exception(message, cause)

// gzip(JSON {format, exportedAt, medications, schedules, doseLogs, history, settings}); klucze = nazwy kolumn Room.
object SnapshotCodec {
    const val FORMAT = 1

    fun encode(s: Snapshot, exportedAt: Instant): ByteArray {
        val json = JSONObject()
            .put("format", FORMAT)
            .put("exportedAt", exportedAt.toString())
            .put("medications", JSONArray(s.medications.map { medication(it) }))
            .put("schedules", JSONArray(s.schedules.map { schedule(it) }))
            .put("doseLogs", JSONArray(s.doseLogs.map { doseLog(it) }))
            .put("history", JSONArray(s.history.map { history(it) }))
            .put("settings", JSONObject().put("theme", s.settings.theme.name).put("snoozeMinutes", s.settings.snoozeMinutes))
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    // Każdy błąd (nie-gzip, zły JSON, brak kluczy, zły format) kończy się UnsupportedSnapshotException, zanim cokolwiek trafi do bazy.
    fun decode(bytes: ByteArray): Snapshot = try {
        val text = GZIPInputStream(bytes.inputStream()).use { String(it.readBytes(), Charsets.UTF_8) }
        val json = JSONObject(text)
        val format = json.getInt("format")
        if (format != FORMAT) throw UnsupportedSnapshotException("Nieznany format kopii: $format")
        val settings = json.getJSONObject("settings")
        Snapshot(
            medications = json.getJSONArray("medications").objects().map { medication(it) },
            schedules = json.getJSONArray("schedules").objects().map { schedule(it) },
            doseLogs = json.getJSONArray("doseLogs").objects().map { doseLog(it) },
            history = json.getJSONArray("history").objects().map { history(it) },
            settings = SyncedSettings(AppTheme.valueOf(settings.getString("theme")), settings.getInt("snoozeMinutes")),
        ).also(::validate)
    } catch (e: UnsupportedSnapshotException) {
        throw e
    } catch (e: Exception) {
        throw UnsupportedSnapshotException("Nie można odczytać kopii", e)
    }

    // wartości, których load() nie umiałby potem sparsować, wychodzą tu, a nie po podmianie danych
    private fun validate(s: Snapshot) {
        fun schedule(c: ScheduleColumns) {
            LocalDate.parse(c.startDate)
            c.endDate?.let(LocalDate::parse)
            c.times.split(",").filter { it.isNotEmpty() }.forEach(LocalTime::parse)
        }
        s.medications.forEach { DoseUnit.valueOf(it.medication.unit); it.archivedAt?.let(LocalDateTime::parse) }
        s.schedules.forEach { it.validFrom?.let(LocalDateTime::parse); schedule(it.schedule) }
        s.doseLogs.forEach {
            LocalDate.parse(it.date); LocalTime.parse(it.time); DoseStatus.valueOf(it.status); it.snoozedUntil?.let(LocalTime::parse)
        }
        s.history.forEach {
            DoseUnit.valueOf(it.medication.unit); schedule(it.schedule)
            LocalDate.parse(it.date); LocalTime.parse(it.time); DoseStatus.valueOf(it.status); LocalDateTime.parse(it.recordedAt)
        }
    }

    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    private fun JSONObject.putN(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)
    private fun JSONObject.str(key: String): String = if (isNull(key)) throw JSONException("Brak $key") else getString(key)
    private fun JSONObject.strOrNull(key: String): String? = if (isNull(key)) null else getString(key)

    private fun putMedication(o: JSONObject, m: MedicationColumns, p: String) = o
        .put("${p}id", m.id).put("${p}name", m.name).put("${p}strength", m.strength).put("${p}form", m.form).put("${p}unit", m.unit)
        .putN("${p}manufacturer", m.manufacturer).putN("${p}ean", m.ean).putN("${p}package_size", m.packageSize)
        .put("${p}stock", m.stock).put("${p}refill_threshold", m.refillThreshold)

    private fun readMedication(o: JSONObject, p: String) = MedicationColumns(
        id = o.str("${p}id"), name = o.str("${p}name"), strength = o.str("${p}strength"), form = o.str("${p}form"), unit = o.str("${p}unit"),
        manufacturer = o.strOrNull("${p}manufacturer"), ean = o.strOrNull("${p}ean"), packageSize = o.strOrNull("${p}package_size"),
        stock = o.getInt("${p}stock"), refillThreshold = o.getInt("${p}refill_threshold"),
    )

    private fun putSchedule(o: JSONObject, c: ScheduleColumns, p: String) = o
        .put("${p}dose_qty", c.doseQty).put("${p}days", c.days).put("${p}times", c.times)
        .put("${p}start_date", c.startDate).putN("${p}end_date", c.endDate)

    private fun readSchedule(o: JSONObject, p: String) = ScheduleColumns(
        doseQty = o.getInt("${p}dose_qty"), days = o.getInt("${p}days"), times = o.str("${p}times"),
        startDate = o.str("${p}start_date"), endDate = o.strOrNull("${p}end_date"),
    )

    private fun medication(r: MedicationRow) = putMedication(JSONObject(), r.medication, "").putN("archived_at", r.archivedAt)
    private fun medication(o: JSONObject) = MedicationRow(readMedication(o, ""), o.strOrNull("archived_at"))

    private fun schedule(r: ScheduleRow) =
        putSchedule(JSONObject().put("medication_id", r.medicationId).put("version", r.version).putN("valid_from", r.validFrom), r.schedule, "")
    private fun schedule(o: JSONObject) =
        ScheduleRow(o.str("medication_id"), o.getInt("version"), o.strOrNull("valid_from"), readSchedule(o, ""))

    private fun doseLog(r: DoseLogRow) = JSONObject()
        .put("medication_id", r.medicationId).put("scheduled_date", r.date).put("scheduled_time", r.time)
        .put("status", r.status).putN("snoozed_until", r.snoozedUntil)
    private fun doseLog(o: JSONObject) = DoseLogRow(
        o.str("medication_id"), o.str("scheduled_date"), o.str("scheduled_time"), o.str("status"), o.strOrNull("snoozed_until"),
    )

    private fun history(r: HistoryRow): JSONObject {
        val o = JSONObject().put("rowId", r.rowId)
        putMedication(o, r.medication, "med_")
        putSchedule(o, r.schedule, "sch_")
        return o.put("scheduled_date", r.date).put("scheduled_time", r.time).put("status", r.status)
            .put("recorded_at", r.recordedAt).put("stock_after", r.stockAfter)
    }
    private fun history(o: JSONObject) = HistoryRow(
        rowId = o.getLong("rowId"),
        medication = readMedication(o, "med_"),
        schedule = readSchedule(o, "sch_"),
        date = o.str("scheduled_date"), time = o.str("scheduled_time"), status = o.str("status"),
        recordedAt = o.str("recorded_at"), stockAfter = o.getInt("stock_after"),
    )
}
