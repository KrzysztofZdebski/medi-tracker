package pl.agh.meditracker.data

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import pl.agh.meditracker.data.notifications.*
import pl.agh.meditracker.data.repository.MedicationRepository
import pl.agh.meditracker.data.settings.SettingsStore
import pl.agh.meditracker.model.*
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ReminderDeliveryTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private fun medication() = Medication(name = "Lek testowy", strength = "1 mg", form = "tabletki", unit = DoseUnit.Tablet,
        schedule = Schedule(1, DayOfWeek.entries.toSet(), listOf(LocalTime.now().plusMinutes(5).withSecond(0).withNano(0)), LocalDate.now()), stock = 30, refillThreshold = 5)

    @Test fun nextAlarmIsCancelledWhenRemindersAreDisabled() = runTest {
        val repository = MedicationRepository()
        repository.add(medication())
        val settings = SettingsStore(context).apply { remindersEnabled = true }
        shadowOf(context.getSystemService(NotificationManager::class.java)).setNotificationsEnabled(true)
        val scheduler = ReminderScheduler(context, repository, settings)
        scheduler.refresh()
        val alarms = shadowOf(context.getSystemService(AlarmManager::class.java))
        assertEquals(1, alarms.scheduledAlarms.size)
        assertTrue(requireNotNull(alarms.peekNextScheduledAlarm()).isAllowWhileIdle)
        settings.remindersEnabled = false
        scheduler.refresh()
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test fun alarmPostsOnceAndTakingDoseCancelsItsNotification() = runTest {
        val repository = MedicationRepository()
        val med = repository.add(medication())
        val settings = SettingsStore(context).apply { remindersEnabled = true }
        val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        val scheduler = ReminderScheduler(context, repository, settings)
        scheduler.refresh()
        val at = requireNotNull(shadowOf(context.getSystemService(AlarmManager::class.java)).peekNextScheduledAlarm()).triggerAtMs
        scheduler.dispatch(at)
        scheduler.dispatch(at)
        assertEquals(1, shadowOf(manager).size())
        val scheduledDate = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate()
        val dose = repository.dosesFor(scheduledDate).first().first()
        repository.setDoseStatus(med.id, dose.date, dose.time, DoseStatus.Taken)
        scheduler.refresh()
        assertEquals(0, shadowOf(manager).size())
    }

    @Test fun notificationControlOpensAndroidChannelSettings() {
        val controller = NotificationController(context)
        controller.openSettings(channel = true)
        val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(NotificationController.CHANNEL, intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test fun delayedAlarmAlsoDeliversOtherDosesThatBecameDueWhileIdle() = runTest {
        val now = LocalDateTime.now().withSecond(0).withNano(0)
        val repository = MedicationRepository()
        repository.add(medication().copy(schedule = Schedule(1, DayOfWeek.entries.toSet(), listOf(now.minusMinutes(3).toLocalTime(), now.minusMinutes(1).toLocalTime()), now.toLocalDate())))
        val settings = SettingsStore(context).apply { remindersEnabled = true }
        val manager = context.getSystemService(NotificationManager::class.java)
        shadowOf(manager).setNotificationsEnabled(true)
        val scheduler = ReminderScheduler(context, repository, settings)
        val trigger = now.minusMinutes(3).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        scheduler.dispatch(trigger)
        assertEquals(2, shadowOf(manager).size())
        scheduler.dispatch(trigger)
        assertEquals(2, shadowOf(manager).size())
    }
}
