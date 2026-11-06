package pl.agh.meditracker.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import pl.agh.meditracker.MainActivity
import pl.agh.meditracker.R
import pl.agh.meditracker.core.AppContainer
import pl.agh.meditracker.core.MediTrackerApp
import pl.agh.meditracker.data.notifications.NotificationController
import pl.agh.meditracker.model.Dose
import pl.agh.meditracker.model.DoseStatus
import java.time.LocalDate
import java.time.LocalTime


// Alarm przypomnienia i przyciski z powiadomienia: „Przyjęte” i „Odłóż”.
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as MediTrackerApp).container
        val medicationId = intent.getStringExtra(EXTRA_MEDICATION) ?: return
        val date = intent.getStringExtra(EXTRA_DATE)?.let(LocalDate::parse) ?: return
        val time = intent.getStringExtra(EXTRA_TIME)?.let(LocalTime::parse) ?: return
        val code = reminderCode(medicationId, date, time)
        // lek mógł zostać usunięty, a dawka oznaczona w aplikacji od czasu ustawienia alarmu
        val dose = container.openDose(medicationId, date, time)
        when (intent.action) {
            ACTION_REMIND -> if (dose != null && container.settings.remindersEnabled) show(context, dose, code)
            ACTION_TAKE -> {
                if (dose == null || dose.medication.stock < dose.medication.schedule.doseQty) return
                container.medications.setDoseStatus(medicationId, date, time, DoseStatus.Taken)
                NotificationManagerCompat.from(context).cancel(code)
                finishAfterWrites(container)
            }
            ACTION_SNOOZE -> {
                if (dose != null) {
                    val until = LocalTime.now().plusMinutes(container.settings.snoozeMinutes.toLong())
                    container.medications.setDoseStatus(medicationId, date, time, DoseStatus.Snoozed, until)
                }
                NotificationManagerCompat.from(context).cancel(code)
                finishAfterWrites(container)
            }
        }
    }

    // proces odbiorcy może zostać zamknięty zaraz po onReceive; zmiana dawki musi najpierw trafić do bazy
    private fun finishAfterWrites(container: AppContainer) {
        val pending: PendingResult? = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            container.medications.awaitWrites()
            pending?.finish()
        }
    }

    private fun AppContainer.openDose(medicationId: String, date: LocalDate, time: LocalTime): Dose? =
        runBlocking { medications.dosesFor(date).first() }
            .find { it.medication.id == medicationId && it.time == time }
            ?.takeIf { it.status == DoseStatus.Pending || it.status == DoseStatus.Snoozed }

    private fun show(context: Context, dose: Dose, code: Int) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val reminder = reminderFor(dose, dose.date.atTime(dose.time))
        NotificationController(context).ensureChannel()
        val open = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        val builder = NotificationCompat.Builder(context, NotificationController.CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(reminder.title)
            .setContentText(reminder.text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, code, open, PendingIntent.FLAG_IMMUTABLE))
        val med = dose.medication
        if (med.stock >= med.schedule.doseQty) builder.addAction(0, "Przyjęte", action(context, ACTION_TAKE, reminder))
        else builder.setSubText("Brak zapasu")
        builder.addAction(0, "Odłóż ${(context.applicationContext as MediTrackerApp).container.settings.snoozeMinutes} min", action(context, ACTION_SNOOZE, reminder))
        NotificationManagerCompat.from(context).notify(code, builder.build())
    }

    private fun action(context: Context, action: String, reminder: Reminder) = PendingIntent.getBroadcast(
        context,
        reminder.code,
        Intent(context, ReminderReceiver::class.java).setAction(action).putExtras(remind(reminder)),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val ACTION_REMIND = "pl.agh.meditracker.REMIND"
        const val ACTION_TAKE = "pl.agh.meditracker.TAKE"
        const val ACTION_SNOOZE = "pl.agh.meditracker.SNOOZE"
        private const val EXTRA_MEDICATION = "medication"
        private const val EXTRA_DATE = "date"
        private const val EXTRA_TIME = "time"

        fun remind(reminder: Reminder) = Intent()
            .putExtra(EXTRA_MEDICATION, reminder.medicationId)
            .putExtra(EXTRA_DATE, reminder.date.toString())
            .putExtra(EXTRA_TIME, reminder.time.toString())
    }
}

// Po restarcie telefonu, zmianie czasu lub strefy i aktualizacji aplikacji alarmy trzeba ustawić od nowa.
class RescheduleReceiver : BroadcastReceiver() {
    private val actions = setOf(
        Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_TIME_CHANGED,
        Intent.ACTION_TIMEZONE_CHANGED,
        Intent.ACTION_MY_PACKAGE_REPLACED,
        AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
    )

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in actions) (context.applicationContext as MediTrackerApp).container.reminders.refresh()
    }
}
