package pl.agh.meditracker.data.repository

import org.json.JSONArray
import org.json.JSONObject
import pl.agh.meditracker.model.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.time.*

internal data class MedicationSnapshot(
    val medications: List<Medication> = emptyList(),
    val logs: List<DoseLog> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
    val schedules: Map<String, List<Pair<LocalDateTime, Schedule>>> = emptyMap(),
)

/** Private local storage, including historical schedules needed after process death. */
class MedicationStore(private val file: File) {

    internal fun load(): MedicationSnapshot {
        val text = try { file.readText() } catch (_: java.io.FileNotFoundException) { return MedicationSnapshot() }
        val root = JSONObject(text)
        check(root.getInt("schema") == 1) { "Nieobsługiwana wersja danych lokalnych" }
        return MedicationSnapshot(
            root.getJSONArray("medications").objects().map(::medication),
            root.getJSONArray("logs").objects().map {
                DoseLog(it.getString("id"), LocalDate.parse(it.getString("date")), LocalTime.parse(it.getString("time")), DoseStatus.valueOf(it.getString("status")),
                    it.optional("snooze")?.let(LocalTime::parse), it.optional("snoozeAt")?.let(LocalDateTime::parse))
            },
            root.getJSONArray("history").objects().map {
                HistoryEntry(medication(it.getJSONObject("medication")), LocalDate.parse(it.getString("date")), LocalTime.parse(it.getString("time")),
                    DoseStatus.valueOf(it.getString("status")), LocalDateTime.parse(it.getString("recordedAt")), it.getInt("stockAfter"))
            },
            root.getJSONArray("schedules").objects().associate { row ->
                row.getString("id") to row.getJSONArray("versions").objects().map { LocalDateTime.parse(it.getString("from")) to schedule(it.getJSONObject("schedule")) }
            },
        )
    }

    internal fun save(snapshot: MedicationSnapshot) {
        val root = JSONObject().put("schema", 1)
            .put("medications", JSONArray(snapshot.medications.map(::json)))
            .put("logs", JSONArray(snapshot.logs.map {
                JSONObject().put("id", it.medicationId).put("date", it.date).put("time", it.time).put("status", it.status.name)
                    .put("snooze", it.snoozedUntil).put("snoozeAt", it.snoozedUntilAt)
            }))
            .put("history", JSONArray(snapshot.history.map {
                JSONObject().put("medication", json(it.medication)).put("date", it.date).put("time", it.time).put("status", it.status.name)
                    .put("recordedAt", it.recordedAt).put("stockAfter", it.stockAfter)
            }))
            .put("schedules", JSONArray(snapshot.schedules.map { (id, versions) ->
                JSONObject().put("id", id).put("versions", JSONArray(versions.map { (from, schedule) -> JSONObject().put("from", from).put("schedule", json(schedule)) }))
            }))
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.new")
        temporary.outputStream().use { stream -> stream.write(root.toString().toByteArray(Charsets.UTF_8)); stream.fd.sync() }
        try { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        catch (_: AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
    }

    private fun json(value: Schedule) = JSONObject().put("qty", value.doseQty).put("days", JSONArray(value.days.map { it.value }))
        .put("times", JSONArray(value.times.map { it.toString() })).put("start", value.startDate).put("end", value.endDate)

    private fun schedule(value: JSONObject) = Schedule(value.getInt("qty"), value.getJSONArray("days").let { array -> (0 until array.length()).map { DayOfWeek.of(array.getInt(it)) }.toSet() },
        value.getJSONArray("times").let { array -> (0 until array.length()).map { LocalTime.parse(array.getString(it)) } }, LocalDate.parse(value.getString("start")), value.optional("end")?.let(LocalDate::parse))

    private fun json(value: Medication) = JSONObject().put("id", value.id).put("name", value.name).put("strength", value.strength).put("form", value.form)
        .put("unit", value.unit.name).put("manufacturer", value.manufacturer).put("ean", value.ean).put("package", value.packageSize)
        .put("schedule", json(value.schedule)).put("stock", value.stock).put("threshold", value.refillThreshold)

    private fun medication(value: JSONObject) = Medication(value.getString("id"), value.getString("name"), value.getString("strength"), value.getString("form"), DoseUnit.valueOf(value.getString("unit")),
        value.optional("manufacturer"), value.optional("ean"), value.optional("package"), schedule(value.getJSONObject("schedule")), value.getInt("stock"), value.getInt("threshold"))
}

private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }
private fun JSONObject.optional(key: String): String? = if (isNull(key)) null else getString(key)
