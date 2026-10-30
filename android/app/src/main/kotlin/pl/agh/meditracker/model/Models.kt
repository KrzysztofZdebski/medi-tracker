package pl.agh.meditracker.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.LocalDateTime

enum class DoseStatus { Pending, Taken, Skipped, Snoozed }

enum class DoseUnit(val one: String, val few: String, val many: String) {
    Tablet("tabletka", "tabletki", "tabletek"),
    Capsule("kapsułka", "kapsułki", "kapsułek"),
    Sachet("saszetka", "saszetki", "saszetek"),
    Drop("kropla", "krople", "kropli"),
    Ml("ml", "ml", "ml");

    fun format(count: Int): String = "$count ${forCount(count)}"

    fun forCount(count: Int): String {
        val lastTwo = count % 100
        val last = count % 10
        return when {
            count == 1 -> one
            last in 2..4 && lastTwo !in 12..14 -> few
            else -> many
        }
    }
}

data class Schedule(
    val doseQty: Int,
    val days: Set<DayOfWeek>,
    val times: List<LocalTime>,
    val startDate: LocalDate,
    val endDate: LocalDate? = null,
) {
    val isDaily get() = days.size == 7

    fun appliesTo(date: LocalDate) =
        date.dayOfWeek in days && !date.isBefore(startDate) && (endDate == null || !date.isAfter(endDate))
}

data class Medication(
    val id: String = "",
    val name: String,
    val strength: String,
    val form: String,
    val unit: DoseUnit,
    val manufacturer: String? = null,
    val ean: String? = null,
    val packageSize: String? = null,
    val schedule: Schedule,
    val stock: Int,
    val refillThreshold: Int,
) {
    val dosesPerDay get() = schedule.doseQty * schedule.times.size * schedule.days.size / 7.0

    val daysLeft get() = if (dosesPerDay == 0.0) null else (stock / dosesPerDay).toInt()
}

data class DoseLog(
    val medicationId: String,
    val date: LocalDate,
    val time: LocalTime,
    val status: DoseStatus,
    val snoozedUntil: LocalTime? = null,
)

// Keep the identity, dose and stock as they were at the time of the event.
// Editing or removing a medication must not rewrite the journal.
data class HistoryEntry(
    val medication: Medication,
    val date: LocalDate,
    val time: LocalTime,
    val status: DoseStatus,
    val recordedAt: LocalDateTime,
    val stockAfter: Int,
)

data class Dose(
    val medication: Medication,
    val date: LocalDate,
    val time: LocalTime,
    val status: DoseStatus,
    val snoozedUntil: LocalTime? = null,
)

data class CatalogProduct(
    val name: String,
    val strength: String?,
    val form: String?,
    val manufacturer: String?,
    val packageSize: String?,
    val packageCount: Int?,
    val ean: String,
)
