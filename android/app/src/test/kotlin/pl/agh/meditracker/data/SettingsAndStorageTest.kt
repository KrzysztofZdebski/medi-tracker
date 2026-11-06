package pl.agh.meditracker.data

import android.app.Application
import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import pl.agh.meditracker.data.repository.*
import pl.agh.meditracker.data.settings.*
import pl.agh.meditracker.model.*
import java.io.File
import java.time.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SettingsAndStorageTest {
    @get:Rule val temp = TemporaryFolder()
    private val today = LocalDate.of(2026, 11, 6)
    private val clock = Clock.fixed(Instant.parse("2026-11-06T23:50:00Z"), ZoneOffset.UTC)
    private fun medication() = Medication(name = "Metformina", strength = "500 mg", form = "tabletki", unit = DoseUnit.Tablet,
        schedule = Schedule(1, DayOfWeek.entries.toSet(), listOf(LocalTime.of(8, 0)), today), stock = 30, refillThreshold = 5)

    @Test fun settingsSurviveRecreationAndPublishChanges() {
        val context = RuntimeEnvironment.getApplication() as Context
        val settings = SettingsStore(context)
        settings.remindersEnabled = true
        settings.snoozeMinutes = 30
        settings.theme = AppTheme.Dark
        assertEquals(AppSettings(true, 30, AppTheme.Dark), settings.state.value)
        assertEquals(settings.state.value, SettingsStore(context).state.value)
    }

    @Test fun malformedPreferenceUsesKnownDefault() {
        val context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("theme", "unknown").putInt("snooze_minutes", -1).commit()
        assertEquals(AppSettings(), SettingsStore(context).state.value)
    }

    @Test fun medicationsHistoryAndStockSurviveProcessRecreation() = runTest {
        val file = File(temp.root, "medications.json")
        val repo = MedicationRepository(clock, MedicationStore(file))
        val med = repo.add(medication())
        repo.setDoseStatus(med.id, today, LocalTime.of(8, 0), DoseStatus.Taken)
        repo.refill(med.id, 10)
        repo.update(repo.medications.value.single().copy(name = "Zmieniona nazwa", schedule = med.schedule.copy(doseQty = 2)))
        val restored = MedicationRepository(clock, MedicationStore(file))
        assertEquals(39, restored.medications.value.single().stock)
        assertEquals("Zmieniona nazwa", restored.medications.value.single().name)
        assertEquals("Metformina", restored.history.value.single().medication.name)
        assertEquals(29, restored.history.value.single().stockAfter)
        assertEquals(1, restored.dosesFor(today).first().single().medication.schedule.doseQty)
        assertEquals(2, restored.dosesFor(today.plusDays(1)).first().single().medication.schedule.doseQty)
        restored.remove(med.id)
        val afterDelete = MedicationRepository(clock, MedicationStore(file))
        assertTrue(afterDelete.medications.value.isEmpty())
        assertEquals(1, afterDelete.history.value.size)
    }

    @Test fun snoozeAcrossMidnightRestoresFullDateAndTime() {
        val file = File(temp.root, "medications.json")
        val repo = MedicationRepository(clock, MedicationStore(file))
        val med = repo.add(medication())
        repo.setDoseStatus(med.id, today, LocalTime.of(8, 0), DoseStatus.Snoozed, LocalTime.of(0, 20))
        val restored = MedicationRepository(clock, MedicationStore(file))
        assertEquals(today.plusDays(1).atTime(0, 20), restored.snoozedDoses().single().snoozedUntilAt)
        assertEquals(30, restored.medications.value.single().stock)
        assertTrue(restored.history.value.isEmpty())
    }
}
