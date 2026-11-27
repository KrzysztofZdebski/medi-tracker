package pl.agh.meditracker.calendar

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import pl.agh.meditracker.core.SystemZoneClock
import pl.agh.meditracker.data.repository.MedicationRepository
import pl.agh.meditracker.data.settings.SettingsStore
import java.time.Clock
import java.time.ZonedDateTime

// Eksport dawek do kalendarza systemowego. Jak przypomnienia i widżet obserwuje repozytorium,
// więc zmiana harmonogramu, usunięcie leku i ukrycie go w kalendarzu same poprawiają wydarzenia.
// Zmiany są zbierane przez chwilę (debounce), a cała praca z ContentResolverem idzie poza wątkiem UI.
@OptIn(FlowPreview::class)
class CalendarSync(
    private val context: Context,
    private val repository: MedicationRepository,
    private val settings: SettingsStore,
    private val gateway: CalendarGateway = ContentResolverCalendarGateway(context.contentResolver),
    private val clock: Clock = SystemZoneClock,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val hasPermission: () -> Boolean = { hasCalendarPermission(context) },
) {
    private val refreshes = MutableStateFlow(0)
    private val mutex = Mutex()

    private data class Input(val enabled: Boolean, val calendarId: Long?, val events: List<CalendarEventSpec>, val revision: Int)

    fun start(scope: CoroutineScope) = scope.launch {
        combine(settings.calendar, repository.medications, refreshes) { prefs, meds, revision ->
            // strefa w każdym przeliczeniu, bo SystemZoneClock czyta bieżącą strefę telefonu
            val events = if (prefs.enabled) calendarEvents(meds, prefs.excluded, clock.zone) else emptyList()
            Input(prefs.enabled, prefs.calendarId, events, revision)
        }
            // zmiana zapasu albo statusu dawki nie zmienia wydarzeń
            .distinctUntilChanged()
            .debounce(DEBOUNCE_MILLIS)
            .collect { input -> withContext(io) { mutex.withLock { syncNow(input) } } }
    }

    // np. po zmianie strefy czasowej
    fun refresh() = refreshes.value++

    private fun syncNow(input: Input) {
        val state = loadState()
        if (!input.enabled) {
            // bez zgody nie da się nic usunąć; mapowanie zostaje na wypadek ponownego włączenia
            if (!state.isEmpty && hasPermission()) guarded(state) { removeEverything() }
            return
        }
        if (!hasPermission()) {
            // zgodę cofnięto w ustawieniach Androida: eksport wyłącza się bez błędu
            settings.calendarEnabled = false
            return
        }
        guarded(state) {
            // kalendarz z mapowania zniknął (usunięte konto, kalendarz skasowany poza aplikacją): jego wydarzeń już nie ma,
            // a zapisane id mogłyby wskazywać na nowy kalendarz o tym samym numerze i blokować dodanie wydarzeń
            forgetMissingCalendar()
            val calendarId = target(input.calendarId) ?: run {
                Log.w(TAG, "Nie udało się utworzyć kalendarza")
                return@guarded
            }
            sync(calendarId, input.events, ZonedDateTime.now(clock))
            // po przejściu na kalendarz użytkownika pusty kalendarz „mediTracker” nie jest już potrzebny; tylko gdy
            // faktycznie użyto wybranego kalendarza, bo gdy go zabrakło, „mediTracker” jest właśnie kalendarzem docelowym
            if (calendarId == input.calendarId && gateway.findLocalCalendar() != null) gateway.deleteLocalCalendar()
        }
    }

    // kalendarz wybrany przez użytkownika, a gdy go nie ma (lub nie wybrano) własny „mediTracker”
    private fun target(chosen: Long?): Long? {
        if (chosen != null) {
            if (gateway.calendarExists(chosen)) return chosen
            settings.calendarId = null
        }
        return gateway.findLocalCalendar() ?: gateway.createLocalCalendar()
    }

    // Usuwa wszystkie dodane wydarzenia i wyłącza eksport (przycisk w ustawieniach). false bez zgody lub po błędzie.
    suspend fun deleteAll(): Boolean {
        settings.calendarEnabled = false
        return withContext(io) {
            mutex.withLock {
                hasPermission() && guarded(loadState()) { removeEverything() }
            }
        }
    }

    // kalendarze do wyboru w ustawieniach; pusta lista bez zgody
    suspend fun calendars(): List<CalendarInfo> = withContext(io) {
        if (!hasPermission()) emptyList()
        else try { gateway.writableCalendars() } catch (e: Exception) { Log.w(TAG, "Nie udało się odczytać kalendarzy", e); emptyList() }
    }

    private fun CalendarReconciler.removeEverything() {
        removeAll()
        gateway.deleteLocalCalendar()
    }

    // Wykonuje operacje na kalendarzu i zapisuje stan także po błędzie w połowie. Nigdy nie rzuca.
    private fun guarded(state: CalendarSyncState, block: CalendarReconciler.() -> Unit): Boolean {
        val reconciler = CalendarReconciler(gateway, state)
        return try {
            reconciler.block()
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Brak zgody na kalendarz, eksport wyłączony", e)
            settings.calendarEnabled = false
            false
        } catch (e: Exception) {
            Log.e(TAG, "Synchronizacja kalendarza nie powiodła się", e)
            false
        } finally {
            saveState(reconciler.state)
        }
    }

    private fun loadState() = calendarStateFromJson(settings.calendarEvents)

    private fun saveState(state: CalendarSyncState) {
        settings.calendarEvents = if (state.isEmpty) null else state.toJson()
    }

    companion object {
        const val DEBOUNCE_MILLIS = 500L
        private const val TAG = "CalendarSync"
    }
}

fun CalendarSyncState.toJson(): String = JSONObject().apply {
    calendarId?.let { put("calendarId", it) }
    put("events", JSONArray().apply {
        events.forEach { (key, e) ->
            put(JSONObject().apply {
                put("key", key)
                put("eventId", e.eventId)
                put("medicationId", e.medicationId)
                put("contentHash", e.contentHash)
                put("ruleHash", e.ruleHash)
                put("startMillis", e.startMillis)
                put("rule", e.rule)
                e.untilMillis?.let { put("untilMillis", it) }
            })
        }
    })
    put("past", JSONArray().apply {
        past.forEach { put(JSONObject().put("eventId", it.eventId).put("medicationId", it.medicationId)) }
    })
}.toString()

// uszkodzony zapis traktujemy jak brak wydarzeń (lepiej dodać je od nowa niż przestać działać)
fun calendarStateFromJson(text: String?): CalendarSyncState {
    if (text.isNullOrBlank()) return CalendarSyncState()
    return try {
        val json = JSONObject(text)
        val events = json.getJSONArray("events").let { array ->
            (0 until array.length()).associate { i ->
                val e = array.getJSONObject(i)
                e.getString("key") to CalendarEntry(
                    eventId = e.getLong("eventId"),
                    medicationId = e.getString("medicationId"),
                    contentHash = e.getString("contentHash"),
                    ruleHash = e.getString("ruleHash"),
                    startMillis = e.getLong("startMillis"),
                    rule = e.getString("rule"),
                    untilMillis = if (e.has("untilMillis")) e.getLong("untilMillis") else null,
                )
            }
        }
        val past = json.getJSONArray("past").let { array ->
            (0 until array.length()).map { i -> array.getJSONObject(i).let { PastEvent(it.getLong("eventId"), it.getString("medicationId")) } }
        }
        CalendarSyncState(if (json.has("calendarId")) json.getLong("calendarId") else null, events, past)
    } catch (e: JSONException) {
        CalendarSyncState()
    }
}
