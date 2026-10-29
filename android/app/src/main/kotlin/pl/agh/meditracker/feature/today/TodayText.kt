package pl.agh.meditracker.feature.today

import pl.agh.meditracker.model.Dose
import pl.agh.meditracker.model.DoseStatus
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val dateFormat = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.forLanguageTag("pl"))
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

val Dose.dueTime: LocalTime get() = if (status == DoseStatus.Snoozed) snoozedUntil ?: time else time

fun nextDose(doses: List<Dose>): Dose? =
    doses.filter { it.status == DoseStatus.Pending || it.status == DoseStatus.Snoozed }.minByOrNull { it.dueTime }

fun relativeTimeText(now: LocalTime, target: LocalTime): String {
    val minutes = Duration.between(now.truncatedTo(ChronoUnit.MINUTES), target).toMinutes()
    return when {
        minutes <= 0 -> "Teraz"
        minutes < 60 -> "Za $minutes ${plural(minutes, "minutę", "minuty", "minut")}"
        minutes % 60 == 0L -> "Za ${minutes / 60} godz."
        else -> "Za ${minutes / 60} godz. ${minutes % 60} min"
    }
}

fun dateCaption(date: LocalDate): String = dateFormat.format(date).replaceFirstChar { it.uppercase() }

fun daysText(days: Int): String = "$days ${plural(days.toLong(), "dzień", "dni", "dni")}"

fun LocalTime.hhmm(): String = timeFormat.format(this)

private fun plural(n: Long, one: String, few: String, many: String): String = when {
    n == 1L -> one
    n % 10 in 2..4 && n % 100 !in 12..14 -> few
    else -> many
}
