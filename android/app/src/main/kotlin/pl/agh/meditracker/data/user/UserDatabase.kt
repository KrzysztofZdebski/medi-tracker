package pl.agh.meditracker.data.user

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert

// Dane użytkownika (user.db), osobno od katalogu leków, żeby podmiana katalogu nie mogła ich dotknąć.
// Nazwy tabel jak w docs/baza-danych.md; na razie tylko to, co obsługuje aplikacja.

data class ScheduleColumns(
    @ColumnInfo(name = "dose_qty") val doseQty: Int,
    // dni tygodnia jako maska bitowa: bit 0 = poniedziałek
    val days: Int,
    // "08:00,20:00"
    val times: String,
    @ColumnInfo(name = "start_date") val startDate: String,
    @ColumnInfo(name = "end_date") val endDate: String?,
)

data class MedicationColumns(
    val id: String,
    val name: String,
    val strength: String,
    val form: String,
    val unit: String,
    val manufacturer: String?,
    val ean: String?,
    @ColumnInfo(name = "package_size") val packageSize: String?,
    val stock: Int,
    @ColumnInfo(name = "refill_threshold") val refillThreshold: Int,
)

@Entity(tableName = "medication", primaryKeys = ["id"])
data class MedicationRow(
    @Embedded val medication: MedicationColumns,
    // usunięty lek zostaje w bazie, bo historia może się do niego odwoływać
    @ColumnInfo(name = "archived_at") val archivedAt: String? = null,
)

// Kolejne wersje harmonogramu leku; wersja obowiązuje od valid_from (null = od początku).
@Entity(tableName = "schedule", primaryKeys = ["medication_id", "version"])
data class ScheduleRow(
    @ColumnInfo(name = "medication_id") val medicationId: String,
    val version: Int,
    @ColumnInfo(name = "valid_from") val validFrom: String?,
    @Embedded val schedule: ScheduleColumns,
)

@Entity(tableName = "dose_log", primaryKeys = ["medication_id", "scheduled_date", "scheduled_time"])
data class DoseLogRow(
    @ColumnInfo(name = "medication_id") val medicationId: String,
    @ColumnInfo(name = "scheduled_date") val date: String,
    @ColumnInfo(name = "scheduled_time") val time: String,
    val status: String,
    @ColumnInfo(name = "snoozed_until") val snoozedUntil: String?,
)

// Wpis dziennika z kopią leku i dawkowania z chwili zdarzenia (edycja leku nie zmienia historii).
@Entity(tableName = "history_entry")
data class HistoryRow(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    @Embedded(prefix = "med_") val medication: MedicationColumns,
    @Embedded(prefix = "sch_") val schedule: ScheduleColumns,
    @ColumnInfo(name = "scheduled_date") val date: String,
    @ColumnInfo(name = "scheduled_time") val time: String,
    val status: String,
    @ColumnInfo(name = "recorded_at") val recordedAt: String,
    @ColumnInfo(name = "stock_after") val stockAfter: Int,
)

@Dao
interface UserDao {
    @Query("SELECT * FROM medication WHERE archived_at IS NULL ORDER BY rowid")
    fun medications(): List<MedicationRow>

    @Query("SELECT * FROM schedule ORDER BY medication_id, version")
    fun schedules(): List<ScheduleRow>

    @Query("SELECT * FROM dose_log")
    fun doseLogs(): List<DoseLogRow>

    @Query("SELECT * FROM history_entry ORDER BY rowId")
    fun history(): List<HistoryRow>

    @Upsert
    fun upsertMedication(row: MedicationRow)

    @Query("UPDATE medication SET archived_at = :at WHERE id = :id")
    fun archiveMedication(id: String, at: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSchedule(row: ScheduleRow)

    @Query("DELETE FROM dose_log WHERE medication_id = :medicationId AND scheduled_date = :date AND scheduled_time = :time")
    fun deleteDoseLog(medicationId: String, date: String, time: String)

    @Upsert
    fun upsertDoseLog(row: DoseLogRow)

    @Query("DELETE FROM history_entry WHERE med_id = :medicationId AND scheduled_date = :date AND scheduled_time = :time")
    fun deleteHistory(medicationId: String, date: String, time: String)

    @Insert
    fun insertHistory(row: HistoryRow)

    // zmiana statusu dawki: dziennik, historia i zapas razem albo wcale
    @Transaction
    fun saveDose(medication: MedicationRow, log: DoseLogRow?, key: Triple<String, String, String>, history: HistoryRow?) {
        upsertMedication(medication)
        deleteDoseLog(key.first, key.second, key.third)
        log?.let(::upsertDoseLog)
        deleteHistory(key.first, key.second, key.third)
        history?.let(::insertHistory)
    }
}

@Database(
    entities = [MedicationRow::class, ScheduleRow::class, DoseLogRow::class, HistoryRow::class],
    version = 1,
)
abstract class UserDatabase : RoomDatabase() {
    abstract fun dao(): UserDao

    companion object {
        fun open(context: Context): UserDatabase =
            Room.databaseBuilder(context, UserDatabase::class.java, "user.db").build()
    }
}
