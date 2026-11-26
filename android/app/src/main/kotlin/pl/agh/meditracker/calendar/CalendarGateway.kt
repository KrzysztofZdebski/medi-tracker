package pl.agh.meditracker.calendar

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.core.content.ContextCompat
import java.time.ZoneId

val CALENDAR_PERMISSIONS = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)

fun hasCalendarPermission(context: Context) =
    CALENDAR_PERMISSIONS.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

// kalendarz użytkownika, do którego można dopisywać wydarzenia
data class CalendarInfo(val id: Long, val name: String, val account: String)

// Cały dostęp do CalendarContract w jednym miejscu, żeby uzgadnianie dało się testować bez dostawcy kalendarza.
// Każda metoda może rzucić SecurityException, gdy użytkownik cofnie zgodę.
interface CalendarGateway {
    // kalendarze z prawem zapisu, bez kalendarza aplikacji
    fun writableCalendars(): List<CalendarInfo>

    fun calendarExists(id: Long): Boolean

    // id lokalnego kalendarza „mediTracker”, jeśli istnieje
    fun findLocalCalendar(): Long?

    // tworzy lokalny kalendarz „mediTracker”; null, gdy dostawca odmówił
    fun createLocalCalendar(): Long?

    // usuwa lokalny kalendarz razem z jego wydarzeniami
    fun deleteLocalCalendar()

    fun insert(calendarId: Long, event: CalendarEventValues): Long?

    // false, gdy wydarzenia już nie ma (np. usunięte ręcznie w aplikacji kalendarza)
    fun update(eventId: Long, event: CalendarEventValues): Boolean

    // zmienia tylko RRULE (zamknięcie serii); false, gdy wydarzenia już nie ma
    fun updateRule(eventId: Long, rrule: String): Boolean

    fun delete(eventId: Long)
}

const val LOCAL_CALENDAR_ACCOUNT = "mediTracker"
private const val LOCAL_CALENDAR_COLOR = 0xFF176B5B.toInt()

// Zgodę sprawdza CalendarSync przed każdą synchronizacją i łapie SecurityException, gdy zostanie cofnięta w trakcie.
@SuppressLint("MissingPermission")
class ContentResolverCalendarGateway(private val resolver: ContentResolver) : CalendarGateway {
    private val localSelection = "${Calendars.ACCOUNT_NAME} = ? AND ${Calendars.ACCOUNT_TYPE} = ?"
    private val localArgs = arrayOf(LOCAL_CALENDAR_ACCOUNT, CalendarContract.ACCOUNT_TYPE_LOCAL)

    // Kalendarz konta lokalnego może utworzyć tylko „adapter synchronizacji”, stąd parametry w URI.
    private fun asSyncAdapter(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, LOCAL_CALENDAR_ACCOUNT)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()

    override fun writableCalendars(): List<CalendarInfo> {
        val projection = arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE)
        val selection = "${Calendars.CALENDAR_ACCESS_LEVEL} >= ? AND ${Calendars.SYNC_EVENTS} = 1"
        val args = arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
        return resolver.query(Calendars.CONTENT_URI, projection, selection, args, "${Calendars.CALENDAR_DISPLAY_NAME} ASC")?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    val account = c.getString(2).orEmpty()
                    if (account == LOCAL_CALENDAR_ACCOUNT && c.getString(3) == CalendarContract.ACCOUNT_TYPE_LOCAL) continue
                    add(CalendarInfo(c.getLong(0), c.getString(1) ?: account, account))
                }
            }
        }.orEmpty()
    }

    override fun calendarExists(id: Long): Boolean =
        resolver.query(ContentUris.withAppendedId(Calendars.CONTENT_URI, id), arrayOf(Calendars._ID), null, null, null)
            ?.use { it.moveToFirst() } ?: false

    override fun findLocalCalendar(): Long? =
        resolver.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID), localSelection, localArgs, null)
            ?.use { if (it.moveToFirst()) it.getLong(0) else null }

    override fun createLocalCalendar(): Long? {
        val values = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, LOCAL_CALENDAR_ACCOUNT)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, LOCAL_CALENDAR_ACCOUNT)
            put(Calendars.CALENDAR_DISPLAY_NAME, "mediTracker")
            put(Calendars.CALENDAR_COLOR, LOCAL_CALENDAR_COLOR)
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, LOCAL_CALENDAR_ACCOUNT)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, ZoneId.systemDefault().id)
        }
        return resolver.insert(asSyncAdapter(Calendars.CONTENT_URI), values)?.let(ContentUris::parseId)
    }

    override fun deleteLocalCalendar() {
        resolver.delete(asSyncAdapter(Calendars.CONTENT_URI), localSelection, localArgs)
    }

    private fun CalendarEventValues.toContentValues() = ContentValues().apply {
        put(Events.TITLE, title)
        put(Events.DESCRIPTION, description)
        put(Events.DTSTART, startMillis)
        put(Events.DURATION, duration)
        put(Events.EVENT_TIMEZONE, timeZone)
        put(Events.RRULE, rrule)
        // przypomina aplikacja, nie kalendarz
        put(Events.HAS_ALARM, 0)
        // dawka nie blokuje czasu w kalendarzu
        put(Events.AVAILABILITY, Events.AVAILABILITY_FREE)
    }

    override fun insert(calendarId: Long, event: CalendarEventValues): Long? {
        val values = event.toContentValues().apply { put(Events.CALENDAR_ID, calendarId) }
        return resolver.insert(Events.CONTENT_URI, values)?.let(ContentUris::parseId)
    }

    override fun update(eventId: Long, event: CalendarEventValues): Boolean =
        resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), event.toContentValues(), null, null) > 0

    override fun updateRule(eventId: Long, rrule: String): Boolean {
        val values = ContentValues().apply { put(Events.RRULE, rrule) }
        return resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), values, null, null) > 0
    }

    override fun delete(eventId: Long) {
        resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, eventId), null, null)
    }
}
