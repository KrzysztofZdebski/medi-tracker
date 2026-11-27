package pl.agh.meditracker.data.repository

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.agh.meditracker.data.user.Snapshot
import pl.agh.meditracker.data.user.SyncedSettings
import pl.agh.meditracker.model.Dose
import pl.agh.meditracker.model.DoseLog
import pl.agh.meditracker.model.DoseStatus
import pl.agh.meditracker.model.Medication
import pl.agh.meditracker.model.HistoryEntry
import pl.agh.meditracker.model.Schedule
import java.time.LocalDateTime
import pl.agh.meditracker.core.SystemZoneClock
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

// Stan w pamięci (StateFlow dla UI) z zapisem do bazy: każda zmiana trafia do store po kolei, poza wątkiem UI.
// Bez store (testy) repozytorium działa tylko w pamięci. [writeDispatcher] musi wykonywać jedno zadanie naraz
// (testy podają dyspozytor z wirtualnym czasem).
class MedicationRepository(
    private val clock: Clock = SystemZoneClock,
    private val store: MedicationStore? = null,
    initial: StoredState = StoredState(),
    writeDispatcher: CoroutineDispatcher = serialIo(),
) {
    // leki i wersje ich harmonogramów w jednym stanie, żeby nikt nie zobaczył niespójnej pary;
    // [removed] to leki usunięte w tej sesji, które „Cofnij” może jeszcze przywrócić
    private data class Core(
        val medications: List<Medication>,
        val schedules: Map<String, List<Pair<LocalDateTime, Schedule>>>,
        val removed: Map<String, Removed> = emptyMap(),
    )

    // Usunięcie zdejmuje z pamięci tylko sam lek: wersje harmonogramu, dziennik dawek i historia zostają,
    // więc do cofnięcia wystarczy lek (z zapasem z chwili usunięcia) i jego miejsce na liście.
    // [before] to leki stojące przed nim; przywrócony wraca za ostatnim z nich, który nadal jest na liście.
    private data class Removed(val medication: Medication, val before: List<String>)

    private val core = MutableStateFlow(Core(initial.medications, initial.schedules))

    // widok samych leków nad stanem core; value zawsze zgodne z core
    val medications: StateFlow<List<Medication>> = object : StateFlow<List<Medication>> {
        override val value: List<Medication> get() = core.value.medications
        override val replayCache: List<List<Medication>> get() = listOf(value)
        override suspend fun collect(collector: FlowCollector<List<Medication>>): Nothing {
            core.map { it.medications }.distinctUntilChanged().collect(collector)
            throw IllegalStateException("StateFlow collect nie kończy się")
        }
    }

    private val logs = MutableStateFlow(initial.logs)
    private val _history = MutableStateFlow(initial.history)
    val history: StateFlow<List<HistoryEntry>> = _history

    private fun schedulesOf(id: String) = core.value.schedules[id]

    // jeden wątek naraz, więc zapisy wykonują się w kolejności wywołań
    private val writes = CoroutineScope(SupervisorJob() + writeDispatcher)

    // sygnał po każdej zaplanowanej zmianie (kopia zapasowa); reload() go nie wysyła
    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 64)
    val changes: SharedFlow<Unit> = _changes

    // zastępuje cały stan w pamięci; wołane tylko z kolejki zapisów, po zmianie bazy
    private fun reload(state: StoredState) {
        logs.value = state.logs
        _history.value = state.history
        core.value = Core(state.medications, state.schedules)
    }

    // Operacje na całej bazie idą tą samą kolejką co zapisy: wykonują się po wszystkich wcześniejszych
    // i przed późniejszymi, więc zaległy zapis nie wskrzesi starych wierszy.
    private suspend fun <T> serialized(block: MedicationStore.() -> T): T {
        val store = checkNotNull(store) { "Brak bazy" }
        return writes.async { store.block() }.await()
    }

    suspend fun dumpSnapshot(settings: SyncedSettings): Snapshot = serialized { dump(settings) }

    suspend fun replaceAllFrom(s: Snapshot) = serialized {
        replaceAll(s)
        reload(load())
    }

    suspend fun wipeAll() = serialized {
        wipe()
        reload(StoredState())
    }

    private fun persist(write: MedicationStore.() -> Unit) {
        val store = store ?: return
        writes.launch {
            runCatching { store.write() }.onFailure { Log.e("MedicationRepository", "Zapis do bazy nie powiódł się", it) }
        }
        // sygnał dopiero po zakolejkowaniu zapisu: zrzut zlecony przez odbiorcę sygnału na pewno go zawiera
        _changes.tryEmit(Unit)
    }

    // czeka, aż wszystkie dotychczasowe zmiany będą w bazie (np. zanim system zamknie proces po akcji z powiadomienia)
    suspend fun awaitWrites() = writes.launch {}.join()

    fun update(medication: Medication) {
        val previous = medications.value.find { it.id == medication.id } ?: return
        if (previous.schedule != medication.schedule) {
            val versions = schedulesOf(medication.id).orEmpty() + (LocalDateTime.now(clock) to medication.schedule)
            core.update { it.copy(schedules = it.schedules + (medication.id to versions)) }
            persist { saveSchedule(medication.id, versions.lastIndex, versions.last().first, medication.schedule) }
        }
        core.update { c -> c.copy(medications = c.medications.map { if (it.id == medication.id) medication else it }) }
        persist { saveMedication(medication) }
    }

    // usunięty lek zostaje w bazie z archived_at (odwołuje się do niego historia); zwraca usunięty lek
    fun remove(id: String): Medication? = removeAll(listOf(id)).firstOrNull()

    // usuwa kilka leków jedną zmianą stanu i jednym zapisem; zwraca te, które faktycznie były na liście
    fun removeAll(ids: Collection<String>): List<Medication> {
        val wanted = ids.toSet()
        var taken = emptyList<Medication>()
        core.update { c ->
            val order = c.medications.map { it.id }
            taken = c.medications.filter { it.id in wanted }
            val stash = taken.associate { med -> med.id to Removed(med, order.subList(0, order.indexOf(med.id))) }
            c.copy(medications = c.medications.filterNot { it.id in wanted }, removed = c.removed + stash)
        }
        if (taken.isEmpty()) return taken
        val at = LocalDateTime.now(clock)
        val removedIds = taken.map { it.id }
        persist { archiveMedications(removedIds, at) }
        return taken
    }

    // „Cofnij”: lek wraca z tym samym id, harmonogramem, zapasem i miejscem na liście
    fun restore(id: String): Medication? = restoreAll(listOf(id)).firstOrNull()

    // Przywraca leki usunięte w tej sesji (po przeładowaniu bazy, np. przywróceniu kopii, nie ma już czego cofać).
    // Lek, który jest już na liście, zostaje pominięty. Zwraca przywrócone leki.
    fun restoreAll(ids: Collection<String>): List<Medication> {
        val wanted = ids.toSet()
        var restored = emptyList<Medication>()
        core.update { c ->
            val present = c.medications.map { it.id }.toSet()
            // od ostatnio usuniętego: każde przywrócenie odwraca jedno usunięcie, więc [before] wskazuje właściwe miejsce
            val back = c.removed.values.filter { it.medication.id in wanted && it.medication.id !in present }.reversed()
            restored = back.map { it.medication }
            val meds = c.medications.toMutableList()
            back.forEach { r ->
                val after = r.before.maxOfOrNull { id -> meds.indexOfFirst { it.id == id } } ?: -1
                meds.add(after + 1, r.medication)
            }
            c.copy(medications = meds, removed = c.removed - wanted)
        }
        if (restored.isEmpty()) return restored
        val restoredIds = restored.map { it.id }
        persist { unarchiveMedications(restoredIds) }
        return restored
    }

    // leki usunięte w tej sesji, które da się jeszcze przywrócić (np. do treści komunikatu po powrocie ze szczegółów)
    fun recentlyRemoved(ids: Collection<String>): List<Medication> {
        val removed = core.value.removed
        return ids.mapNotNull { removed[it]?.medication }
    }

    fun dosesFor(date: LocalDate): Flow<List<Dose>> = combine(core, logs) { state, logs ->
        state.medications.flatMap { med ->
            val versions = state.schedules[med.id] ?: return@flatMap emptyList()
            val times = versions.flatMap { it.second.times }.distinct()
            times.mapNotNull { time ->
                val schedule = versions.last { !it.first.isAfter(LocalDateTime.of(date, time)) }.second
                val log = logs.find { it.medicationId == med.id && it.date == date && it.time == time }
                if (log == null && (!schedule.appliesTo(date) || time !in schedule.times)) null
                else Dose(med.copy(schedule = schedule), date, time, log?.status ?: DoseStatus.Pending, log?.snoozedUntil)
            }
        }.sortedBy { it.time }
    }

    fun add(medication: Medication): Medication {
        val saved = medication.copy(id = UUID.randomUUID().toString())
        core.update { it.copy(medications = it.medications + saved, schedules = it.schedules + (saved.id to listOf(LocalDateTime.MIN to saved.schedule))) }
        persist {
            saveMedication(saved)
            saveSchedule(saved.id, 0, LocalDateTime.MIN, saved.schedule)
        }
        return saved
    }

    fun refill(medicationId: String, amount: Int) {
        if (amount <= 0) return
        changeStock(medicationId, amount)
        val med = medications.value.find { it.id == medicationId } ?: return
        persist { saveMedication(med) }
    }

    fun setDoseStatus(
        medicationId: String,
        date: LocalDate,
        time: LocalTime,
        status: DoseStatus,
        snoozedUntil: LocalTime? = null,
    ) {
        val previous = logs.value.find { it.medicationId == medicationId && it.date == date && it.time == time }
        val med = medications.value.find { it.id == medicationId } ?: return
        if (previous?.status == status && previous.snoozedUntil == snoozedUntil) return
        val oldEntry = _history.value.find { it.medication.id == medicationId && it.date == date && it.time == time }
        val schedule = (schedulesOf(med.id) ?: return).last { !it.first.isAfter(LocalDateTime.of(date, time)) }.second
        val qty = schedule.doseQty
        if (previous?.status == DoseStatus.Taken) changeStock(medicationId, oldEntry?.medication?.schedule?.doseQty ?: qty)
        if (status == DoseStatus.Taken) changeStock(medicationId, -qty)
        // przeładowanie po przywróceniu/wyczyszczeniu mogło usunąć lek od czasu poprzedniego odczytu
        val updated = medications.value.firstOrNull { it.id == medicationId } ?: return
        val entry = if (status == DoseStatus.Taken || status == DoseStatus.Skipped) {
            HistoryEntry(med.copy(schedule = schedule), date, time, status, LocalDateTime.now(clock), updated.stock)
        } else null
        val log = if (status == DoseStatus.Pending) null else DoseLog(medicationId, date, time, status, snoozedUntil)
        _history.update { entries ->
            entries.filterNot { it.medication.id == medicationId && it.date == date && it.time == time } + listOfNotNull(entry)
        }
        logs.update { list -> list - setOfNotNull(previous) + listOfNotNull(log) }
        persist { saveDose(updated, date, time, log, entry) }
    }

    private fun changeStock(medicationId: String, delta: Int) = core.update { c ->
        c.copy(medications = c.medications.map { if (it.id == medicationId) it.copy(stock = (it.stock + delta).coerceAtLeast(0)) else it })
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private fun serialIo(): CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
