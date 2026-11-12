package pl.agh.meditracker.data.user

import android.content.Context
import androidx.room.Room
import java.util.concurrent.Executor

/**
 * In-memory UserDatabase whose query and transaction work runs on the calling thread.
 * With Room's default executors the invalidation-tracker refresh runs on the shared
 * ArchTaskExecutor IO pool and can outlive db.close() / Robolectric teardown, then throws
 * into whichever runTest happens to be running next (UncaughtExceptionsBeforeTest).
 */
fun inMemoryUserDatabase(context: Context): UserDatabase {
    val direct = Executor { it.run() }
    return Room.inMemoryDatabaseBuilder(context, UserDatabase::class.java)
        .allowMainThreadQueries()
        .setQueryExecutor(direct)
        .setTransactionExecutor(direct)
        .build()
}

/**
 * Same idea for the AppContainer's file-backed user.db opened by the test Applications:
 * makes Room's default executors (ArchTaskExecutor disk IO) run inline for this JVM.
 */
@Suppress("RestrictedApi")
fun useDirectRoomExecutors() {
    androidx.arch.core.executor.ArchTaskExecutor.getInstance().setDelegate(object : androidx.arch.core.executor.TaskExecutor() {
        override fun executeOnDiskIO(runnable: Runnable) = runnable.run()
        override fun postToMainThread(runnable: Runnable) = runnable.run()
        override fun isMainThread(): Boolean = true
    })
}

/**
 * Robolectric tears the app down without waiting for MedicationRepository's queued writes. A write that
 * lands afterwards runs against a DB whose SQLite connection Robolectric already reset, and its
 * invalidation-tracker refresh throws into whichever runTest runs next. Draining the queue here
 * (and the inline executors above) keeps every write inside the test that made it.
 */
class DrainAppWritesRule : org.junit.rules.TestWatcher() {
    override fun finished(description: org.junit.runner.Description?) {
        val app = org.robolectric.RuntimeEnvironment.getApplication() as? pl.agh.meditracker.core.MediTrackerApp ?: return
        val container = try { app.container } catch (_: UninitializedPropertyAccessException) { return }
        kotlinx.coroutines.runBlocking { container.medications.awaitWrites() }
    }
}
