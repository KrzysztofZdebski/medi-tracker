package pl.agh.meditracker.data.user

import pl.agh.meditracker.data.repository.MedicationStore
import pl.agh.meditracker.data.repository.StoredState
import pl.agh.meditracker.model.DoseLog
import pl.agh.meditracker.model.DoseStatus
import pl.agh.meditracker.model.DoseUnit
import pl.agh.meditracker.model.HistoryEntry
import pl.agh.meditracker.model.Medication
import pl.agh.meditracker.model.Schedule
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class RoomMedicationStore(private val dao: UserDao) : MedicationStore {
    override fun load(): StoredState {
        val schedules = dao.schedules().groupBy({ it.medicationId }) { row ->
            (row.validFrom?.let(LocalDateTime::parse) ?: LocalDateTime.MIN) to row.schedule.toSchedule()
        }
        return StoredState(
            medications = dao.medications().mapNotNull { row ->
                // bez harmonogramu lek nie ma sensu; nie powinno się zdarzyć, bo oba zapisy idą razem
                schedules[row.medication.id]?.last()?.second?.let { row.medication.toMedication(it) }
            },
            schedules = schedules,
            logs = dao.doseLogs().map {
                DoseLog(it.medicationId, LocalDate.parse(it.date), LocalTime.parse(it.time), DoseStatus.valueOf(it.status), it.snoozedUntil?.let(LocalTime::parse))
            },
            history = dao.history().map {
                HistoryEntry(
                    it.medication.toMedication(it.schedule.toSchedule()),
                    LocalDate.parse(it.date),
                    LocalTime.parse(it.time),
                    DoseStatus.valueOf(it.status),
                    LocalDateTime.parse(it.recordedAt),
                    it.stockAfter,
                )
            },
        )
    }

    override fun saveMedication(medication: Medication) = dao.upsertMedication(MedicationRow(medication.toColumns()))

    override fun archiveMedication(id: String, at: LocalDateTime) = dao.archiveMedication(id, at.toString())

    override fun saveSchedule(medicationId: String, version: Int, validFrom: LocalDateTime, schedule: Schedule) =
        dao.insertSchedule(ScheduleRow(medicationId, version, validFrom.takeIf { it != LocalDateTime.MIN }?.toString(), schedule.toColumns()))

    override fun saveDose(medication: Medication, date: LocalDate, time: LocalTime, log: DoseLog?, entry: HistoryEntry?) = dao.saveDose(
        MedicationRow(medication.toColumns()),
        log?.let { DoseLogRow(it.medicationId, it.date.toString(), it.time.toString(), it.status.name, it.snoozedUntil?.toString()) },
        Triple(medication.id, date.toString(), time.toString()),
        entry?.let {
            HistoryRow(
                medication = it.medication.toColumns(),
                schedule = it.medication.schedule.toColumns(),
                date = it.date.toString(),
                time = it.time.toString(),
                status = it.status.name,
                recordedAt = it.recordedAt.toString(),
                stockAfter = it.stockAfter,
            )
        },
    )
}

internal fun Schedule.toColumns() = ScheduleColumns(
    doseQty = doseQty,
    days = days.fold(0) { mask, day -> mask or (1 shl (day.value - 1)) },
    times = times.joinToString(","),
    startDate = startDate.toString(),
    endDate = endDate?.toString(),
)

internal fun ScheduleColumns.toSchedule() = Schedule(
    doseQty = doseQty,
    days = DayOfWeek.entries.filter { days and (1 shl (it.value - 1)) != 0 }.toSet(),
    times = times.split(",").filter { it.isNotEmpty() }.map(LocalTime::parse),
    startDate = LocalDate.parse(startDate),
    endDate = endDate?.let(LocalDate::parse),
)

internal fun Medication.toColumns() =
    MedicationColumns(id, name, strength, form, unit.name, manufacturer, ean, packageSize, stock, refillThreshold)

internal fun MedicationColumns.toMedication(schedule: Schedule) = Medication(
    id = id,
    name = name,
    strength = strength,
    form = form,
    unit = DoseUnit.valueOf(unit),
    manufacturer = manufacturer,
    ean = ean,
    packageSize = packageSize,
    schedule = schedule,
    stock = stock,
    refillThreshold = refillThreshold,
)
