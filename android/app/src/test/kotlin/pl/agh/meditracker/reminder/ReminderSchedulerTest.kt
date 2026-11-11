package pl.agh.meditracker.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import pl.agh.meditracker.core.AppContainer
import pl.agh.meditracker.core.MediTrackerApp
import pl.agh.meditracker.data.notifications.NotificationController
import pl.agh.meditracker.data.repository.MedicationRepository
import pl.agh.meditracker.data.settings.SettingsStore
import pl.agh.meditracker.model.Dose
import pl.agh.meditracker.model.DoseStatus
import pl.agh.meditracker.model.DoseUnit
import pl.agh.meditracker.model.Medication
import pl.agh.meditracker.model.Schedule
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

// bez synchronizacji katalogu z sieci i bez schedulera działającego w tle
class TestApp : MediTrackerApp() {
    override fun onCreate() {
        container = AppContainer(this)
    }
}

private fun medication(start: LocalDate) = Medication(
    name = "Metformina", strength = "500 mg", form = "tabletki", unit = DoseUnit.Tablet,
    schedule = Schedule(1, DayOfWeek.entries.toSet(), listOf(LocalTime.of(8, 0), LocalTime.of(20, 0)), start),
    stock = 30, refillThreshold = 5,
)

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class)
class ReminderSchedulerTest {
    private val app = RuntimeEnvironment.getApplication()
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 11, 6)
    private val clock = Clock.fixed(today.atTime(7, 0).atZone(zone).toInstant(), zone)
    private val alarms = shadowOf(app.getSystemService(AlarmManager::class.java))

    private fun millis(at: LocalDateTime) = at.atZone(zone).toInstant().toEpochMilli()
    private fun scheduled() = alarms.scheduledAlarms.map { it.triggerAtMs }.sorted()

    @Test fun keepsAlarmsInStepWithDosesAndSetting() = runTest {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val repository = MedicationRepository(clock)
        val settings = SettingsStore(app).apply { remindersEnabled = true }
        val med = repository.add(medication(today))
        ReminderScheduler(app, repository, settings, clock).start(backgroundScope)
        runCurrent()
        val tomorrow = today.plusDays(1)
        assertEquals(
            listOf(today.atTime(8, 0), today.atTime(20, 0), tomorrow.atTime(8, 0), tomorrow.atTime(20, 0)).map(::millis),
            scheduled(),
        )

        repository.setDoseStatus(med.id, today, LocalTime.of(8, 0), DoseStatus.Taken)
        runCurrent()
        assertEquals(3, scheduled().size)

        repository.setDoseStatus(med.id, today, LocalTime.of(20, 0), DoseStatus.Snoozed, LocalTime.of(20, 15))
        runCurrent()
        assertEquals(millis(today.atTime(20, 15)), scheduled().first())

        settings.remindersEnabled = false
        runCurrent()
        assertTrue(scheduled().isEmpty())
    }

    @Test fun removedMedicationLosesItsAlarms() = runTest {
        val repository = MedicationRepository(clock)
        val settings = SettingsStore(app).apply { remindersEnabled = true }
        val med = repository.add(medication(today))
        ReminderScheduler(app, repository, settings, clock).start(backgroundScope)
        runCurrent()
        repository.remove(med.id)
        runCurrent()
        assertTrue(scheduled().isEmpty())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class)
class ReminderReceiverTest {
    private val app = RuntimeEnvironment.getApplication() as TestApp
    private val container = app.container
    private val notifications = shadowOf(app.getSystemService(NotificationManager::class.java))
    private val today = LocalDate.now()
    private val eight = LocalTime.of(8, 0)

    private fun remind(med: Medication) = Intent(ReminderReceiver.ACTION_REMIND)
        .putExtras(ReminderReceiver.remind(reminderFor(Dose(med, today, eight, DoseStatus.Pending), today.atTime(eight))))

    private fun dose(med: Medication) = runBlocking { container.medications.dosesFor(today).first() }
        .first { it.medication.id == med.id && it.time == eight }

    private fun setUp(): Medication {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        container.settings.remindersEnabled = true
        return container.medications.add(medication(today))
    }

    @Test fun reminderShowsNotificationAndTakeMarksDose() {
        val med = setUp()
        ReminderReceiver().onReceive(app, remind(med))
        val notification = notifications.allNotifications.single()
        assertEquals("Czas na Metformina 500 mg", notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("1 tabletka · zaplanowano na 08:00", notification.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(listOf("Przyjęte", "Odłóż 15 min"), notification.actions.map { it.title.toString() })

        ReminderReceiver().onReceive(app, shadowOf(notification.actions[0].actionIntent).savedIntent)
        assertEquals(DoseStatus.Taken, dose(med).status)
        assertEquals(29, container.medications.medications.value.single().stock)
        assertTrue(notifications.allNotifications.isEmpty())
    }

    @Test fun snoozeActionPostponesDose() {
        val med = setUp()
        ReminderReceiver().onReceive(app, remind(med))
        val snooze = notifications.allNotifications.single().actions[1].actionIntent
        ReminderReceiver().onReceive(app, shadowOf(snooze).savedIntent)
        assertEquals(DoseStatus.Snoozed, dose(med).status)
        assertTrue(notifications.allNotifications.isEmpty())
    }

    @Test fun noNotificationWhenRemindersOffOrDoseClosed() {
        val med = setUp()
        container.settings.remindersEnabled = false
        ReminderReceiver().onReceive(app, remind(med))
        assertTrue(notifications.allNotifications.isEmpty())

        container.settings.remindersEnabled = true
        container.medications.setDoseStatus(med.id, today, eight, DoseStatus.Skipped)
        ReminderReceiver().onReceive(app, remind(med))
        assertTrue(notifications.allNotifications.isEmpty())
    }

    // alarm już zadziałał (kod nie jest „wanted”), więc powiadomienie znika tylko wraz z lekiem
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun deletingMedicationRemovesItsShownNotificationOnly() = runTest {
        val med = setUp()
        val other = container.medications.add(medication(today))
        ReminderReceiver().onReceive(app, remind(med))
        ReminderReceiver().onReceive(app, remind(other))
        assertEquals(2, notifications.size())
        ReminderScheduler(app, container.medications, container.settings).start(backgroundScope)
        runCurrent()
        assertEquals(2, notifications.size())

        container.medications.remove(med.id)
        runCurrent()
        val left = app.getSystemService(NotificationManager::class.java).activeNotifications.single()
        assertEquals(other.id, left.notification.extras.getString("medicationId"))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun notificationWithoutMedicationIdSurvivesScheduler() = runTest {
        setUp()
        NotificationController(app).show(1, "Test", "Test")
        ReminderScheduler(app, container.medications, container.settings).start(backgroundScope)
        runCurrent()
        assertEquals(1, notifications.size())
    }

    @Test fun noTakeActionWithoutStock() {
        val med = container.medications.add(medication(today).copy(stock = 0)).also {
            shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            container.settings.remindersEnabled = true
        }
        ReminderReceiver().onReceive(app, remind(med))
        assertEquals(listOf("Odłóż 15 min"), notifications.allNotifications.single().actions.map { it.title.toString() })
    }
}
