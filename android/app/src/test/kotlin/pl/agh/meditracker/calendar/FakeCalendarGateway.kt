package pl.agh.meditracker.calendar

// Dostawca kalendarza w pamięci: wiersze Events, jeden lokalny kalendarz i kalendarze użytkownika.
class FakeCalendarGateway : CalendarGateway {
    data class Row(val calendarId: Long, val values: CalendarEventValues)

    val rows = linkedMapOf<Long, Row>()
    val calendars = mutableListOf(CalendarInfo(7, "Praca", "anna@example.com"))
    var local: Long? = null
    // liczba zapisów do dostawcy (wstawienia, zmiany, usunięcia)
    var writes = 0
    // true: każde wywołanie rzuca SecurityException jak po cofnięciu zgody
    var denied = false
    private var nextId = 100L

    private fun guard() {
        if (denied) throw SecurityException("Brak zgody na kalendarz")
    }

    fun inCalendar(calendarId: Long) = rows.filterValues { it.calendarId == calendarId }

    override fun writableCalendars(): List<CalendarInfo> { guard(); return calendars.toList() }

    override fun calendarExists(id: Long): Boolean { guard(); return id == local || calendars.any { it.id == id } }

    override fun findLocalCalendar(): Long? { guard(); return local }

    override fun createLocalCalendar(): Long? { guard(); writes++; local = LOCAL_ID; return LOCAL_ID }

    override fun deleteLocalCalendar() {
        guard()
        val id = local ?: return
        writes++
        rows.entries.removeAll { it.value.calendarId == id }
        local = null
    }

    override fun insert(calendarId: Long, event: CalendarEventValues): Long? {
        guard()
        writes++
        val id = nextId++
        rows[id] = Row(calendarId, event)
        return id
    }

    override fun update(eventId: Long, event: CalendarEventValues): Boolean {
        guard()
        writes++
        val row = rows[eventId] ?: return false
        rows[eventId] = row.copy(values = event)
        return true
    }

    override fun updateRule(eventId: Long, rrule: String): Boolean {
        guard()
        writes++
        val row = rows[eventId] ?: return false
        rows[eventId] = row.copy(values = row.values.copy(rrule = rrule))
        return true
    }

    override fun delete(eventId: Long) {
        guard()
        writes++
        rows.remove(eventId)
    }

    companion object { const val LOCAL_ID = 1L }
}
