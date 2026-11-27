package pl.agh.meditracker.calendar

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.CalendarContract.Events
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

// Atrapa dostawcy kalendarza: jedno wydarzenie cykliczne, zapamiętuje ostatnią zmianę.
class RecordingCalendarProvider : ContentProvider() {
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor =
        MatrixCursor(projection!!).apply {
            addRow(projection.map { column ->
                when (column) {
                    Events.DTSTART -> START
                    Events.DURATION -> "PT15M"
                    Events.EVENT_TIMEZONE -> "Europe/Warsaw"
                    Events.RRULE -> "FREQ=DAILY"
                    else -> null
                }
            })
        }
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?): Int {
        updates += values!!
        return 1
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun getType(uri: Uri): String? = null

    companion object {
        const val START = 1_795_762_800_000L
        val updates = mutableListOf<ContentValues>()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ContentResolverCalendarGatewayTest {
    // Android przelicza wystąpienia serii tylko, gdy zmiana zawiera też DTSTART (z DURATION i strefą).
    // Sama zmiana RRULE zostawiała w kalendarzu „zamkniętą” serię ze wszystkimi przyszłymi dawkami.
    @Test fun closingSeriesSendsStartDurationAndZoneWithRule() {
        RecordingCalendarProvider.updates.clear()
        Robolectric.setupContentProvider(RecordingCalendarProvider::class.java, "com.android.calendar")
        val gateway = ContentResolverCalendarGateway(RuntimeEnvironment.getApplication().contentResolver)

        assertTrue(gateway.updateRule(5, "FREQ=DAILY;UNTIL=20261127T111232Z"))

        val values = RecordingCalendarProvider.updates.single()
        assertEquals("FREQ=DAILY;UNTIL=20261127T111232Z", values.getAsString(Events.RRULE))
        assertEquals(RecordingCalendarProvider.START, values.getAsLong(Events.DTSTART))
        assertEquals("PT15M", values.getAsString(Events.DURATION))
        assertEquals("Europe/Warsaw", values.getAsString(Events.EVENT_TIMEZONE))
    }
}
