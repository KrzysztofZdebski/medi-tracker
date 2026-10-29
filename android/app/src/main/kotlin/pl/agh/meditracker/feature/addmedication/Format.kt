package pl.agh.meditracker.feature.addmedication

import pl.agh.meditracker.model.DoseUnit
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private val shortDate = DateTimeFormatter.ofPattern("dd.MM.yyyy")
private val clock = DateTimeFormatter.ofPattern("HH:mm")
private val weekdays = listOf("Pon.", "Wt.", "Śr.", "Czw.", "Pt.", "Sob.", "Niedz.")
private val dayShort = listOf("Pn", "Wt", "Śr", "Cz", "Pt", "So", "Nd")
private val months = listOf(
    "stycznia", "lutego", "marca", "kwietnia", "maja", "czerwca",
    "lipca", "sierpnia", "września", "października", "listopada", "grudnia",
)

fun formatDate(date: LocalDate): String = date.format(shortDate)

fun formatLongDate(date: LocalDate) =
    "${weekdays[date.dayOfWeek.ordinal]}, ${date.dayOfMonth} ${months[date.monthValue - 1]}"

fun formatTime(time: LocalTime): String = time.format(clock)

fun formatTimes(times: List<LocalTime>): String {
    val labels = times.map(::formatTime)
    return if (labels.size < 2) labels.joinToString() else labels.dropLast(1).joinToString(", ") + " i " + labels.last()
}

fun formatDays(days: Set<DayOfWeek>): String =
    if (days.size == 7) "codziennie" else days.sorted().joinToString(", ") { dayShort[it.ordinal] }

fun formatDaysLeft(days: Int) = if (days == 1) "Około 1 dnia stosowania" else "Około $days dni stosowania"

val DoseUnit.label
    get() = when (this) {
        DoseUnit.Tablet -> "Tabletki"
        DoseUnit.Capsule -> "Kapsułki"
        DoseUnit.Sachet -> "Saszetki"
        DoseUnit.Drop -> "Krople"
        DoseUnit.Ml -> "Płyn"
    }

val DoseUnit.pickerLabel get() = "$label · $one"
