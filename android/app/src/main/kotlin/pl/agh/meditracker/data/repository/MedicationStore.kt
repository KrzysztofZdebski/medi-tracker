package pl.agh.meditracker.data.repository

import pl.agh.meditracker.model.DoseLog
import pl.agh.meditracker.model.HistoryEntry
import pl.agh.meditracker.model.Medication
import pl.agh.meditracker.model.Schedule
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

// Stan repozytorium wczytany z bazy przy starcie aplikacji.
data class StoredState(
    val medications: List<Medication> = emptyList(),
    // wersje harmonogramu: od kiedy obowiązuje (LocalDateTime.MIN = od początku) i jaki jest
    val schedules: Map<String, List<Pair<LocalDateTime, Schedule>>> = emptyMap(),
    val logs: List<DoseLog> = emptyList(),
    val history: List<HistoryEntry> = emptyList(),
)

// Trwały zapis repozytorium. Metody są blokujące; repozytorium woła je po kolei poza wątkiem UI.
interface MedicationStore {
    fun load(): StoredState

    // sam lek, bez harmonogramu (ten ma osobne wersje)
    fun saveMedication(medication: Medication)

    fun archiveMedication(id: String, at: LocalDateTime)

    fun saveSchedule(medicationId: String, version: Int, validFrom: LocalDateTime, schedule: Schedule)

    // jedna transakcja: zapas leku, wpis w dzienniku dawek (null = oczekująca) i wpis historii (null = brak)
    fun saveDose(medication: Medication, date: LocalDate, time: LocalTime, log: DoseLog?, entry: HistoryEntry?)
}
