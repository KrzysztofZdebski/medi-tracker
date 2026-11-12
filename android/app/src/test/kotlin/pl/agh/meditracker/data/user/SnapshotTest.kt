package pl.agh.meditracker.data.user

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import pl.agh.meditracker.data.repository.MedicationRepository
import pl.agh.meditracker.data.repository.StoredState
import pl.agh.meditracker.data.settings.AppTheme
import pl.agh.meditracker.data.settings.SettingsStore
import pl.agh.meditracker.model.DoseStatus
import pl.agh.meditracker.model.DoseUnit
import pl.agh.meditracker.model.Medication
import pl.agh.meditracker.model.Schedule
import pl.agh.meditracker.reminder.TestApp
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class)
class SnapshotTest {
    private val app = RuntimeEnvironment.getApplication()
    private val db = inMemoryUserDatabase(app)
    private val other = inMemoryUserDatabase(app)
    private val store = RoomMedicationStore(db.dao())
    private val otherStore = RoomMedicationStore(other.dao())
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 11, 6)
    private val clock = Clock.fixed(today.atTime(9, 0).atZone(zone).toInstant(), zone)
    private val morning = LocalTime.of(8, 0)
    private val evening = LocalTime.of(20, 0)
    private val settings = SyncedSettings(AppTheme.Dark, 30)
    private val metformina = Medication(
        name = "Metformina", strength = "500 mg", form = "tabletki", unit = DoseUnit.Tablet,
        manufacturer = "Producent", ean = null, packageSize = null,
        schedule = Schedule(1, setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), listOf(morning, evening), today, null),
        stock = 30, refillThreshold = 5,
    )

    @After fun close() { db.close(); other.close() }

    private fun gzip(text: String) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(text.toByteArray()) } }.toByteArray()

    // dwa leki (jeden zarchiwizowany), kilka wersji harmonogramu, dziennik i historia
    private fun seed(): MedicationRepository {
        val repo = MedicationRepository(clock, store)
        val med = repo.add(metformina)
        val removed = repo.add(metformina.copy(name = "Apap", ean = "5901234123457"))
        repo.update(med.copy(schedule = med.schedule.copy(doseQty = 2, endDate = today.plusDays(30))))
        repo.setDoseStatus(med.id, today, morning, DoseStatus.Taken)
        repo.setDoseStatus(med.id, today, evening, DoseStatus.Snoozed, LocalTime.of(20, 15))
        repo.setDoseStatus(removed.id, today, morning, DoseStatus.Skipped)
        repo.remove(removed.id)
        runBlocking { repo.awaitWrites() }
        return repo
    }

    @Test fun exportImportRoundTrip() {
        seed()
        val original = store.dump(settings)
        assertEquals(2, original.medications.size)
        assertTrue(original.medications.any { it.archivedAt != null })

        val bytes = SnapshotCodec.encode(original, Instant.parse("2026-11-11T10:00:00Z"))
        val decoded = SnapshotCodec.decode(bytes)
        assertEquals(original, decoded)

        otherStore.replaceAll(decoded)
        assertEquals(store.load(), otherStore.load())
        assertEquals(original.medications.toSet(), other.dao().allMedications().toSet())
        assertEquals(original, otherStore.dump(settings))
    }

    @Test fun decodeRejectsUnknownFormat() {
        val valid = JSONObject(
            """{"format":2,"exportedAt":"2026-11-11T10:00:00Z","medications":[],"schedules":[],"doseLogs":[],"history":[],
            "settings":{"theme":"System","snoozeMinutes":15}}""",
        )
        assertThrowsUnsupported { SnapshotCodec.decode(gzip(valid.toString())) }
        valid.put("format", 1).remove("history")
        assertThrowsUnsupported { SnapshotCodec.decode(gzip(valid.toString())) }
        assertThrowsUnsupported { SnapshotCodec.decode(gzip("{not json")) }
        assertThrowsUnsupported { SnapshotCodec.decode(gzip("""{"medications":[]}""")) }
        assertThrowsUnsupported { SnapshotCodec.decode(ByteArray(64) { (it * 7).toByte() }) }
        assertThrowsUnsupported { SnapshotCodec.decode(ByteArray(0)) }
    }

    @Test fun decodeRejectsUnparsableValues() {
        seed()
        val text = String(java.util.zip.GZIPInputStream(SnapshotCodec.encode(store.dump(settings), Instant.EPOCH).inputStream()).readBytes())
        assertThrowsUnsupported { SnapshotCodec.decode(gzip(text.replace("2026-11-06", "not-a-date"))) }
    }

    @Test fun replaceAllKeepsOldDataOnFailure() {
        seed()
        val before = store.load()
        val snapshot = store.dump(settings)
        val clash = snapshot.schedules.first()
        val broken = snapshot.copy(
            medications = snapshot.medications.map { it.copy(medication = it.medication.copy(name = "ZMIENIONY")) },
            schedules = snapshot.schedules + clash,
        )
        runCatching { store.replaceAll(broken) }.also { assertTrue(it.isFailure) }
        assertEquals(before, store.load())
        assertEquals(snapshot, store.dump(settings))
    }

    @Test fun wipeEmptiesAllTables() {
        seed()
        store.wipe()
        val dump = store.dump(settings)
        assertTrue(dump.medications.isEmpty() && dump.schedules.isEmpty() && dump.doseLogs.isEmpty() && dump.history.isEmpty())
        assertEquals(StoredState(), store.load())
    }

    @Test fun reloadUpdatesFlows() = runBlocking {
        val repo = seed()
        val target = MedicationRepository(clock, otherStore)
        assertTrue(target.medications.value.isEmpty())
        target.replaceAllFrom(store.dump(settings))
        assertEquals(repo.medications.value, target.medications.value)
        assertEquals(repo.history.value, target.history.value)
        assertEquals(repo.dosesFor(today).first(), target.dosesFor(today).first())
        target.wipeAll()
        assertTrue(target.medications.value.isEmpty() && target.history.value.isEmpty())
        assertTrue(target.dosesFor(today).first().isEmpty())
    }

    @Test fun changesEmitsOnWrite() = runBlocking {
        val repo = MedicationRepository(clock, store)
        val emissions = mutableListOf<Unit>()
        val job = launch(Dispatchers.Unconfined) { repo.changes.collect { emissions += it } }
        repo.add(metformina)
        assertEquals(1, emissions.size)
        repo.wipeAll()
        assertEquals(1, emissions.size)
        job.cancel()
        repo.awaitWrites()
    }

    @Test fun queuedWriteBeforeWipeDoesNotResurrect() = runBlocking {
        val repo = MedicationRepository(clock, store)
        repeat(20) { repo.add(metformina.copy(name = "Lek $it")) }
        repo.wipeAll()
        assertEquals(StoredState(), store.load())
        assertTrue(store.dump(settings).medications.isEmpty())
        assertTrue(repo.medications.value.isEmpty())
        assertTrue(repo.dosesFor(today).first().isEmpty())
    }

    @Test fun queuedWriteBeforeReplaceAllDoesNotLeak() = runBlocking {
        seed()
        val snapshot = store.dump(settings)
        val repo = MedicationRepository(clock, otherStore)
        repeat(20) { repo.add(metformina.copy(name = "Lek $it")) }
        repo.replaceAllFrom(snapshot)
        assertEquals(snapshot, otherStore.dump(settings))
        assertEquals(otherStore.load().medications, repo.medications.value)
        assertEquals(2, snapshot.medications.size)
    }

    @Test fun dosesForNeverThrowsDuringConcurrentReload() = runBlocking {
        seed()
        val full = store.dump(settings)
        val repo = MedicationRepository(clock, otherStore)
        var failure: Throwable? = null
        var last: List<pl.agh.meditracker.model.Dose> = emptyList()
        val collector = launch(Dispatchers.Default) {
            try { repo.dosesFor(today).collect { last = it } } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { failure = e }
        }
        withContext(Dispatchers.Default) {
            repeat(200) { if (it % 2 == 0) repo.replaceAllFrom(full) else repo.wipeAll() }
            repo.replaceAllFrom(full)
        }
        val expected = repo.dosesFor(today).first()
        kotlinx.coroutines.delay(200)
        collector.cancel()
        assertNull(failure)
        assertEquals(expected, last)
        assertTrue(expected.isNotEmpty())
    }

    @Test fun syncedSettingsRoundTripAndReset() = runBlocking {
        val settingsStore = SettingsStore(app)
        val seen = mutableListOf<SyncedSettings>()
        val job = launch(Dispatchers.Unconfined) { settingsStore.syncedChanges.collect { seen += it } }
        settingsStore.remindersEnabled = true
        assertTrue(seen.isEmpty())
        settingsStore.applySynced(SyncedSettings(AppTheme.Dark, 60))
        assertEquals(listOf(SyncedSettings(AppTheme.Dark, 60)), seen)
        assertTrue(settingsStore.remindersEnabled)
        settingsStore.resetSynced()
        assertEquals(SyncedSettings(AppTheme.System, 15), seen.last())
        assertEquals(AppTheme.System, settingsStore.theme)
        assertEquals(15, settingsStore.snoozeMinutes)
        assertEquals(2, seen.size)
        job.cancel()
    }

    private fun assertThrowsUnsupported(block: () -> Unit) {
        try {
            block()
            throw AssertionError("expected UnsupportedSnapshotException")
        } catch (_: UnsupportedSnapshotException) {
        }
    }
}
