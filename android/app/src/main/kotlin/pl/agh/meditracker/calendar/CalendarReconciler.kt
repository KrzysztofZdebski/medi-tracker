package pl.agh.meditracker.calendar

import java.time.LocalDateTime
import java.time.ZonedDateTime

// Uzgadnia wydarzenia w kalendarzu z oczekiwanymi seriami i zmienia tylko to, co się zmieniło.
//
// Wersje harmonogramu: gdy zmienia się reguła serii (dni, daty, strefa), a seria ma już przeszłe wystąpienia,
// stara seria dostaje UNTIL = teraz i zostaje jako przeszłość, a od najbliższej dawki po „teraz” rusza nowa.
// Tak samo liczy repozytorium: nowy harmonogram obowiązuje od chwili zapisu, a przeszłe dawki zostają po staremu.
// Zmiana samej nazwy lub dawki poprawia serię w miejscu (także przeszłe wystąpienia), bo to ta sama seria.
// Seria, która jeszcze się nie zaczęła, jest po prostu poprawiana albo usuwana.
//
// [state] jest aktualizowany po każdej operacji, więc po wyjątku w połowie można zapisać to, co już zrobiono.
class CalendarReconciler(private val gateway: CalendarGateway, initial: CalendarSyncState) {
    var state: CalendarSyncState = initial
        private set

    fun sync(calendarId: Long, desired: List<CalendarEventSpec>, now: ZonedDateTime) {
        if (state.calendarId != calendarId) {
            // inny kalendarz docelowy: wydarzenia ze starego znikają, w nowym powstają od początku
            removeAll()
            state = CalendarSyncState(calendarId = calendarId)
        }
        val nowMillis = now.toInstant().toEpochMilli()
        val wanted = desired.associateBy { it.key }
        val wantedMeds = desired.mapTo(HashSet()) { it.medicationId }
        // lek, który już był w kalendarzu, zmienia harmonogram od teraz; nowy (albo znów pokazywany) od daty rozpoczęcia
        val knownMeds = state.events.values.mapTo(HashSet()) { it.medicationId } + state.past.map { it.medicationId }

        for ((key, entry) in state.events) {
            if (key in wanted) continue
            // lek usunięty lub ukryty: znika cały; usunięta godzina leku: kończy się teraz
            if (entry.medicationId in wantedMeds) endAt(key, entry, nowMillis) else deleteEntry(key, entry)
        }
        for (past in state.past) {
            if (past.medicationId in wantedMeds) continue
            gateway.delete(past.eventId)
            state = state.copy(past = state.past - past)
        }

        for (spec in desired) {
            val entry = state.events[spec.key]
            val after = if (spec.medicationId in knownMeds) now.toLocalDateTime() else null
            when {
                entry == null -> insert(calendarId, spec, after)
                entry.contentHash == spec.contentHash -> Unit
                entry.ruleHash == spec.ruleHash -> {
                    val values = spec.values(entry.startMillis, entry.untilMillis)
                    if (gateway.update(entry.eventId, values)) state = state.withEntry(spec.key, entry.copy(contentHash = spec.contentHash))
                    else { dropEntry(spec.key); insert(calendarId, spec, now.toLocalDateTime()) }
                }
                entry.startMillis > nowMillis -> {
                    // seria bez przeszłych wystąpień: wystarczy ją poprawić
                    val start = spec.firstStart(after)
                    if (start == null) { deleteEntry(spec.key, entry); continue }
                    val startMillis = start.toInstant().toEpochMilli()
                    if (gateway.update(entry.eventId, spec.values(startMillis, spec.untilMillis))) {
                        state = state.withEntry(spec.key, spec.entry(entry.eventId, startMillis))
                    } else { dropEntry(spec.key); insert(calendarId, spec, after) }
                }
                else -> {
                    endAt(spec.key, entry, nowMillis)
                    insert(calendarId, spec, now.toLocalDateTime())
                }
            }
        }
    }

    // zapomina wydarzenia z kalendarza, którego już nie ma (zniknęły razem z nim)
    fun forgetMissingCalendar() {
        val id = state.calendarId ?: return
        if (!gateway.calendarExists(id)) state = CalendarSyncState()
    }

    // usuwa wszystko, co aplikacja dodała do kalendarza
    fun removeAll() {
        for ((key, entry) in state.events) deleteEntry(key, entry)
        for (past in state.past) {
            gateway.delete(past.eventId)
            state = state.copy(past = state.past - past)
        }
        state = CalendarSyncState()
    }

    private fun insert(calendarId: Long, spec: CalendarEventSpec, after: LocalDateTime?) {
        // brak przyszłych wystąpień (np. harmonogram już się skończył): nie ma czego dodać
        val start = spec.firstStart(after) ?: return
        val startMillis = start.toInstant().toEpochMilli()
        val id = gateway.insert(calendarId, spec.values(startMillis, spec.untilMillis)) ?: return
        state = state.withEntry(spec.key, spec.entry(id, startMillis))
    }

    // kończy serię na chwili [nowMillis]; przeszłe wystąpienia zostają w kalendarzu
    private fun endAt(key: String, entry: CalendarEntry, nowMillis: Long) {
        if (entry.startMillis > nowMillis) return deleteEntry(key, entry)
        val stillRunning = entry.untilMillis == null || entry.untilMillis > nowMillis
        val exists = !stillRunning || gateway.updateRule(entry.eventId, rrule(entry.rule, nowMillis))
        dropEntry(key)
        if (exists) state = state.copy(past = state.past + PastEvent(entry.eventId, entry.medicationId))
    }

    private fun deleteEntry(key: String, entry: CalendarEntry) {
        gateway.delete(entry.eventId)
        dropEntry(key)
    }

    private fun dropEntry(key: String) {
        state = state.copy(events = state.events - key)
    }
}

private fun CalendarSyncState.withEntry(key: String, entry: CalendarEntry) = copy(events = events + (key to entry))

private fun CalendarEventSpec.entry(eventId: Long, startMillis: Long) =
    CalendarEntry(eventId, medicationId, contentHash, ruleHash, startMillis, rule, untilMillis)
