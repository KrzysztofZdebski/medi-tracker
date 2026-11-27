package pl.agh.meditracker.calendar

import pl.agh.meditracker.model.Medication
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

// Wydarzenia kalendarza wyliczane z harmonogramów: jedno wydarzenie cykliczne na każdą godzinę dawki leku.
// Ten plik nie używa klas Androida, więc logikę można testować zwykłym JUnitem.

const val EVENT_MINUTES = 15

// Docelowy wygląd jednej serii wydarzeń. Zależy tylko od leku i strefy, nie od bieżącej chwili,
// więc ten sam stan leków zawsze daje te same skróty (powtórna synchronizacja niczego nie zmienia).
data class CalendarEventSpec(
    val medicationId: String,
    val title: String,
    val description: String,
    val time: LocalTime,
    val days: Set<DayOfWeek>,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val zone: ZoneId,
) {
    val key: String get() = eventKey(medicationId, time)

    // RRULE bez UNTIL; koniec serii dokłada [untilMillis] albo zamknięcie przy zmianie harmonogramu
    val rule: String
        get() = if (days.size == 7) "FREQ=DAILY"
        else "FREQ=WEEKLY;WKST=MO;BYDAY=" + days.sortedBy { it.value }.joinToString(",") { it.name.take(2) }

    // ostatnia dawka w dniu zakończenia harmonogramu (UNTIL obejmuje to wystąpienie)
    val untilMillis: Long? get() = endDate?.atTime(time)?.atZone(zone)?.toInstant()?.toEpochMilli()

    // skrót samej reguły powtarzania: jego zmiana oznacza nową wersję harmonogramu
    val ruleHash: String get() = shortHash("$time|$rule|$startDate|$endDate|${zone.id}")

    // skrót całej treści: zmiana samej nazwy lub dawki poprawia wydarzenie w miejscu
    val contentHash: String get() = shortHash("$ruleHash|$title|$description")

    // Pierwsze wystąpienie nie wcześniej niż data rozpoczęcia i (gdy podano [after]) później niż [after].
    // DTSTART musi pasować do BYDAY, bo inaczej kalendarz pokazałby dodatkowe wystąpienie w złym dniu.
    fun firstStart(after: LocalDateTime? = null): ZonedDateTime? {
        var date = startDate
        if (after != null) {
            val candidate = if (time.isAfter(after.toLocalTime())) after.toLocalDate() else after.toLocalDate().plusDays(1)
            if (candidate.isAfter(date)) date = candidate
        }
        repeat(7) {
            if (endDate != null && date.isAfter(endDate)) return null
            if (date.dayOfWeek in days) return date.atTime(time).atZone(zone)
            date = date.plusDays(1)
        }
        return null
    }

    fun values(startMillis: Long, untilMillis: Long?) = CalendarEventValues(
        title = title,
        description = description,
        startMillis = startMillis,
        duration = "PT${EVENT_MINUTES}M",
        timeZone = zone.id,
        rrule = rrule(rule, untilMillis),
    )
}

// Wartości wiersza Events (bez id kalendarza), które zapisuje CalendarGateway.
data class CalendarEventValues(
    val title: String,
    val description: String,
    val startMillis: Long,
    // format RFC 5545, np. PT15M; wydarzenia cykliczne w CalendarContract wymagają DURATION zamiast DTEND
    val duration: String,
    val timeZone: String,
    val rrule: String,
)

fun eventKey(medicationId: String, time: LocalTime) = "$medicationId@$time"

private val untilFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

// UNTIL w UTC, bo DTSTART ma strefę (EVENT_TIMEZONE); tak wymaga RFC 5545
fun rrule(rule: String, untilMillis: Long?): String =
    if (untilMillis == null) rule else "$rule;UNTIL=${untilFormat.format(Instant.ofEpochMilli(untilMillis))}"

private fun shortHash(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

fun calendarTitle(medication: Medication) = "${medication.name} ${medication.strength}".trim()

fun calendarDescription(medication: Medication) =
    "Dawka: ${medication.unit.format(medication.schedule.doseQty)}\nPrzypomnienia wysyła aplikacja mediTracker."

// Wszystkie serie, które powinny być w kalendarzu: leki z listy (usunięte już na niej nie są) bez wykluczonych.
fun calendarEvents(medications: List<Medication>, excluded: Set<String>, zone: ZoneId): List<CalendarEventSpec> =
    medications.filter { it.id !in excluded && it.schedule.days.isNotEmpty() }.flatMap { med ->
        med.schedule.times.distinct().sorted().map { time ->
            CalendarEventSpec(
                medicationId = med.id,
                title = calendarTitle(med),
                description = calendarDescription(med),
                time = time,
                days = med.schedule.days,
                startDate = med.schedule.startDate,
                endDate = med.schedule.endDate,
                zone = zone,
            )
        }
    }

// Seria dodana przez aplikację, która nadal odpowiada godzinie dawki.
data class CalendarEntry(
    val eventId: Long,
    val medicationId: String,
    val contentHash: String,
    val ruleHash: String,
    val startMillis: Long,
    // RRULE bez UNTIL
    val rule: String,
    val untilMillis: Long?,
)

// Seria zamknięta przy zmianie harmonogramu: zostaje w kalendarzu jako przeszłość, dopóki lek jest pokazywany.
data class PastEvent(val eventId: Long, val medicationId: String)

data class CalendarSyncState(
    // kalendarz, w którym są wydarzenia z [events] i [past]; null: jeszcze nic nie dodano
    val calendarId: Long? = null,
    val events: Map<String, CalendarEntry> = emptyMap(),
    val past: List<PastEvent> = emptyList(),
) {
    val isEmpty get() = calendarId == null && events.isEmpty() && past.isEmpty()
}
