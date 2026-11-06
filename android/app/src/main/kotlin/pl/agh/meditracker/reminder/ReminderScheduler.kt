package pl.agh.meditracker.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import pl.agh.meditracker.data.repository.MedicationRepository
import pl.agh.meditracker.data.settings.SettingsStore
import pl.agh.meditracker.model.DoseStatus
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

// Utrzymuje alarmy na dziś i jutro zgodne z harmonogramem: po każdej zmianie leków lub dawek,
// po przełączeniu przypomnień w ustawieniach i o północy.
@OptIn(ExperimentalCoroutinesApi::class)
class ReminderScheduler(
    private val context: Context,
    private val repository: MedicationRepository,
    private val settings: SettingsStore,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val refreshes = MutableStateFlow(0)

    val canScheduleExact: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    fun start(scope: CoroutineScope) = scope.launch {
        val today = flow {
            while (true) {
                emit(LocalDate.now(clock))
                delay(60_000)
            }
        }.distinctUntilChanged()
        val doses = today.flatMapLatest { day ->
            combine(repository.dosesFor(day), repository.dosesFor(day.plusDays(1))) { a, b -> a + b }
        }
        combine(settings.remindersFlow, doses, refreshes) { enabled, doses, _ -> enabled to doses }
            .collect { (enabled, doses) ->
                // zamknięte dawki nie potrzebują już powiadomienia, nawet jeśli zostały oznaczone w aplikacji
                val done = doses.filter { it.status == DoseStatus.Taken || it.status == DoseStatus.Skipped }
                val notifications = NotificationManagerCompat.from(context)
                done.forEach { notifications.cancel(reminderCode(it.medication.id, it.date, it.time)) }
                apply(if (enabled) remindersFor(doses, LocalDateTime.now(clock)) else emptyList())
            }
    }

    // np. po przyznaniu zgody na dokładne alarmy albo po zmianie strefy czasowej
    fun refresh() = refreshes.value++

    private fun apply(reminders: List<Reminder>) {
        val wanted = reminders.associateBy { it.code.toString() }
        (settings.scheduledAlarms - wanted.keys).forEach { alarms.cancel(pendingIntent(it.toInt(), Intent())) }
        val exact = canScheduleExact
        reminders.forEach { r ->
            val pi = pendingIntent(r.code, ReminderReceiver.remind(r))
            val millis = r.at.atZone(clock.zone).toInstant().toEpochMilli()
            if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
        }
        settings.scheduledAlarms = wanted.keys
    }

    // PendingIntent rozpoznaje się po akcji, komponencie i request code; extras nie mają znaczenia przy anulowaniu
    private fun pendingIntent(code: Int, extras: Intent) = PendingIntent.getBroadcast(
        context,
        code,
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_REMIND).putExtras(extras),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
