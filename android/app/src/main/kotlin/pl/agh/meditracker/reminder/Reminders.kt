package pl.agh.meditracker.reminder

import pl.agh.meditracker.model.Dose
import pl.agh.meditracker.model.DoseStatus
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val HHMM = DateTimeFormatter.ofPattern("HH:mm")

// Jedna dawka, o której trzeba przypomnieć. Kod jest deterministyczny (lek + data + godzina z harmonogramu),
// więc ten sam alarm i to samo powiadomienie da się nadpisać albo anulować bez osobnej tabeli.
data class Reminder(
    val medicationId: String,
    val date: LocalDate,
    val time: LocalTime,
    val at: LocalDateTime,
    val title: String,
    val text: String,
) {
    val code get() = reminderCode(medicationId, date, time)
}

fun reminderCode(medicationId: String, date: LocalDate, time: LocalTime) = "$medicationId|$date|$time".hashCode()

// Przyszłe przypomnienia: oczekujące dawki o swojej godzinie, odłożone o godzinie odłożenia.
fun remindersFor(doses: List<Dose>, now: LocalDateTime): List<Reminder> = doses.mapNotNull { dose ->
    val at = when (dose.status) {
        DoseStatus.Pending -> dose.date.atTime(dose.time)
        // odłożenie po 23:xx przechodzi na następny dzień
        DoseStatus.Snoozed -> dose.snoozedUntil?.let { until ->
            dose.date.plusDays(if (until < dose.time) 1 else 0).atTime(until)
        }
        DoseStatus.Taken, DoseStatus.Skipped -> null
    }
    if (at == null || !at.isAfter(now)) null else reminderFor(dose, at)
}.sortedBy { it.at }

fun reminderFor(dose: Dose, at: LocalDateTime): Reminder {
    val med = dose.medication
    return Reminder(
        medicationId = med.id,
        date = dose.date,
        time = dose.time,
        at = at,
        title = "Czas na ${med.name} ${med.strength}".trim(),
        text = "${med.unit.format(med.schedule.doseQty)} · zaplanowano na ${dose.time.format(HHMM)}",
    )
}
