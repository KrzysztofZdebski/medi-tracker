package pl.agh.meditracker.data.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.agh.meditracker.core.MediTrackerApp
import pl.agh.meditracker.data.repository.MedicationRepository
import pl.agh.meditracker.data.settings.SettingsStore
import pl.agh.meditracker.feature.addmedication.formatTime
import pl.agh.meditracker.model.Dose
import pl.agh.meditracker.model.DoseStatus
import java.time.*

data class PlannedReminder(val dose: Dose, val at: Instant) {
    val key get() = "${dose.medication.id}|${dose.date}|${dose.time}|${at.toEpochMilli()}"
}

fun planReminders(doses: List<Dose>, zone: ZoneId): List<PlannedReminder> = doses
    .filter { it.status == DoseStatus.Pending || it.status == DoseStatus.Snoozed }
    .map { dose -> PlannedReminder(dose, (dose.snoozedUntilAt ?: dose.date.atTime(dose.snoozedUntil ?: dose.time)).atZone(zone).toInstant()) }
    .distinctBy { it.key }.sortedBy { it.at }

/** One wakeup for the next batch. Replanned on every medication or preference change. */
class ReminderScheduler(private val context: Context, private val repository: MedicationRepository, private val settings: SettingsStore) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = NotificationController(context)
    private val prefs = context.getSharedPreferences("reminder_delivery", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    private suspend fun candidates(): List<PlannedReminder> {
        val today = LocalDate.now()
        val dates = (0L..7L).map { today.plusDays(it) }.toMutableSet()
        repository.medications.value.forEach { med ->
            if (med.schedule.startDate.isAfter(today)) (0L..7L).forEach { dates += med.schedule.startDate.plusDays(it) }
        }
        val doses = dates.flatMap { repository.dosesFor(it).first() } + repository.snoozedDoses()
        return planReminders(doses, ZoneId.systemDefault())
    }

    private fun pending(at: Long = 0) = PendingIntent.getBroadcast(context, 0,
        Intent(context, ReminderReceiver::class.java).setAction(ACTION).putExtra("at", at), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    suspend fun refresh() = mutex.withLock { scheduleNext() }

    private suspend fun scheduleNext() {
        alarms.cancel(pending())
        if (!settings.remindersEnabled || !notifications.access().allowed) { notifications.cancelAll(); return }
        val candidates = candidates()
        notifications.cancelObsolete(candidates.map { it.key.hashCode() }.toSet())
        val delivered = prefs.getStringSet("delivered", emptySet()).orEmpty()
        val next = candidates.firstOrNull { it.at.isAfter(Instant.now()) && it.key !in delivered } ?: return
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.at.toEpochMilli(), pending(next.at.toEpochMilli()))
    }

    suspend fun dispatch(at: Long) = mutex.withLock {
        if (settings.remindersEnabled && notifications.access().allowed) {
            val delivered = prefs.getStringSet("delivered", emptySet()).orEmpty().toMutableSet()
            val until = maxOf(at, Instant.now().toEpochMilli())
            candidates().filter { it.at.toEpochMilli() in at..until && it.key !in delivered }.forEach { planned ->
                val dose = planned.dose
                val text = "${dose.medication.name} ${dose.medication.strength} · ${dose.medication.unit.format(dose.medication.schedule.doseQty)} · planowana godzina ${formatTime(dose.time)}"
                if (notifications.show(planned.key.hashCode(), "Przypomnienie o dawce", text)) delivered += planned.key
            }
            // Old receipts are only needed to prevent duplicate delivery of recent alarms.
            val cutoff = Instant.now().minus(Duration.ofDays(14)).toEpochMilli()
            prefs.edit().putStringSet("delivered", delivered.filter { (it.substringAfterLast('|').toLongOrNull() ?: 0) >= cutoff }.toSet()).apply()
        }
        scheduleNext()
    }
    companion object { const val ACTION = "pl.agh.meditracker.REMIND_DOSE" }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(ReminderScheduler.ACTION, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val app = context.applicationContext as MediTrackerApp
        val result = goAsync()
        app.applicationScope.launch {
            try {
                val at = intent.getLongExtra("at", -1)
                if (at >= 0) app.container.reminders.dispatch(at) else app.container.reminders.refresh()
            } finally { result.finish() }
        }
    }
}
