package pl.agh.meditracker.widget

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.View.MeasureSpec
import android.widget.FrameLayout
import android.widget.TextView
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import pl.agh.meditracker.MainActivity
import pl.agh.meditracker.R
import pl.agh.meditracker.data.repository.MedicationRepository
import pl.agh.meditracker.data.user.DrainAppWritesRule
import pl.agh.meditracker.model.Dose
import pl.agh.meditracker.model.DoseStatus
import pl.agh.meditracker.model.DoseUnit
import pl.agh.meditracker.model.Medication
import pl.agh.meditracker.model.Schedule
import pl.agh.meditracker.reminder.ReminderReceiver
import pl.agh.meditracker.reminder.RescheduleReceiver
import pl.agh.meditracker.reminder.TestApp
import pl.agh.meditracker.reminder.reminderFor
import java.io.File
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone
import kotlin.math.roundToInt

private val eight = LocalTime.of(8, 0)
private val evening = LocalTime.of(20, 0)

private fun metformin(start: LocalDate, times: List<LocalTime> = listOf(eight, evening), doseQty: Int = 1, stock: Int = 30) = Medication(
    name = "Metformina", strength = "500 mg", form = "tabletki", unit = DoseUnit.Tablet,
    schedule = Schedule(doseQty, DayOfWeek.entries.toSet(), times, start),
    stock = stock, refillThreshold = 5,
)

class TodayWidgetStateTest {
    private val date = LocalDate.of(2026, 11, 6)
    private val med = metformin(date).copy(id = "m")

    private fun dose(h: Int, m: Int = 0, status: DoseStatus = DoseStatus.Pending, snoozedUntil: LocalTime? = null, day: LocalDate = date) =
        Dose(med, day, LocalTime.of(h, m), status, snoozedUntil)

    @Test fun countsTakenOutOfAllTodayDoses() {
        val today = listOf(dose(6, status = DoseStatus.Taken), dose(7, status = DoseStatus.Skipped), dose(12, status = DoseStatus.Taken), dose(20))
        val state = todayWidgetState(today, emptyList())
        assertEquals(2, state.taken)
        assertEquals(4, state.total)
    }

    @Test fun nextIsEarliestOpenDoseIncludingOverdue() {
        // lista nieposortowana; zaległa poranna dawka wyprzedza późniejsze
        val state = todayWidgetState(listOf(dose(20), dose(12), dose(8)), emptyList())
        assertEquals(dose(8), state.next)
    }

    @Test fun snoozedDoseIsOrderedBySnoozedUntil() {
        val toNoon = dose(8, status = DoseStatus.Snoozed, snoozedUntil = LocalTime.of(12, 0))
        val toNight = dose(8, status = DoseStatus.Snoozed, snoozedUntil = LocalTime.of(21, 0))
        assertEquals(toNoon, todayWidgetState(listOf(dose(20), toNoon), emptyList()).next)
        assertEquals(dose(20), todayWidgetState(listOf(toNight, dose(20)), emptyList()).next)
        // odłożenie po północy liczy się jako następny dzień
        val pastMidnight = dose(22, status = DoseStatus.Snoozed, snoozedUntil = LocalTime.of(0, 10))
        assertEquals(dose(23), todayWidgetState(listOf(pastMidnight, dose(23)), emptyList()).next)
    }

    @Test fun closedDosesAreNeverNext() {
        assertNull(todayWidgetState(listOf(dose(6, status = DoseStatus.Taken), dose(8, status = DoseStatus.Skipped)), emptyList()).next)
        val today = listOf(dose(6, status = DoseStatus.Taken), dose(7, status = DoseStatus.Skipped), dose(20))
        assertEquals(dose(20), todayWidgetState(today, emptyList()).next)
    }

    @Test fun tomorrowIsEarliestDoseOfTomorrow() {
        val tomorrow = date.plusDays(1)
        val state = todayWidgetState(
            listOf(dose(8, status = DoseStatus.Taken)),
            listOf(dose(20, day = tomorrow), dose(7, 30, day = tomorrow), dose(12, day = tomorrow)),
        )
        assertEquals(dose(7, 30, day = tomorrow), state.tomorrow)
        assertNull(todayWidgetState(listOf(dose(8)), emptyList()).tomorrow)
    }

    @Test fun emptyListsGiveEmptyState() {
        assertEquals(TodayWidgetState(taken = 0, total = 0, next = null, tomorrow = null), todayWidgetState(emptyList(), emptyList()))
    }
}

// zegar, który test przestawia ręcznie (np. przez północ)
private class MovableClock(var now: Instant, private val zone: ZoneId) : Clock() {
    override fun getZone() = zone
    override fun withZone(zone: ZoneId) = MovableClock(now, zone)
    override fun instant() = now
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = TestApp::class)
class TodayWidgetTest {
    @get:Rule val drainWrites = DrainAppWritesRule()
    private val app = RuntimeEnvironment.getApplication() as TestApp
    private val container = app.container
    private val widgets = shadowOf(AppWidgetManager.getInstance(app))
    // container.widgets liczy dzień z zegara systemowego
    private val today = LocalDate.now()

    // createWidget woła TodayWidgetProvider.onUpdate, a ten container.widgets.update(ids)
    private fun createWidget() = widgets.createWidget(TodayWidgetProvider::class.java, R.layout.widget_today)
    private fun text(widget: Int, view: Int) = widgets.getViewFor(widget).findViewById<TextView>(view).text.toString()
    private fun visible(widget: Int, view: Int) = widgets.getViewFor(widget).findViewById<View>(view).visibility == View.VISIBLE

    private fun dose(med: Medication, time: LocalTime) = runBlocking { container.medications.dosesFor(today).first() }
        .first { it.medication.id == med.id && it.time == time }

    @Test fun showsProgressAndNextDoseWithTakeButton() {
        container.medications.add(metformin(today))
        val id = createWidget()
        assertEquals("0 z 2 przyjęte", text(id, R.id.widget_progress))
        assertTrue(visible(id, R.id.widget_next))
        assertFalse(visible(id, R.id.widget_empty))
        assertEquals("08:00", text(id, R.id.widget_next_time))
        assertEquals("Metformina", text(id, R.id.widget_next_name))
        assertEquals("500 mg · 1 tabletka", text(id, R.id.widget_next_detail))
        assertTrue(visible(id, R.id.widget_take))
        assertEquals("Przyjęte", text(id, R.id.widget_take))

        widgets.getViewFor(id).findViewById<View>(R.id.widget_root).performClick()
        assertEquals(ComponentName(app, MainActivity::class.java), shadowOf(app).nextStartedActivity.component)
    }

    @Test fun updateShowsNextDoseAfterOneIsTaken() {
        val med = container.medications.add(metformin(today))
        val id = createWidget()
        assertEquals("08:00", text(id, R.id.widget_next_time))

        container.medications.setDoseStatus(med.id, today, eight, DoseStatus.Taken)
        container.widgets.update()
        assertEquals("20:00", text(id, R.id.widget_next_time))
        assertEquals("1 z 2 przyjęte", text(id, R.id.widget_progress))
    }

    @Test fun takeButtonMarksDoseAndRefreshesWidget() {
        val med = container.medications.add(metformin(today))
        val id = createWidget()
        widgets.getViewFor(id).findViewById<View>(R.id.widget_take).performClick()
        val intent = shadowOf(app).broadcastIntents.single { it.action == ReminderReceiver.ACTION_TAKE }
        assertEquals(ComponentName(app, ReminderReceiver::class.java), intent.component)

        ReminderReceiver().onReceive(app, intent)
        assertEquals(DoseStatus.Taken, dose(med, eight).status)
        assertEquals(29, container.medications.medications.value.single().stock)
        assertEquals("1 z 2 przyjęte", text(id, R.id.widget_progress))
        assertEquals("20:00", text(id, R.id.widget_next_time))
    }

    @Test fun snoozeFromNotificationRefreshesWidget() {
        val med = container.medications.add(metformin(today, times = listOf(eight)))
        val id = createWidget()
        val reminder = reminderFor(Dose(med, today, eight, DoseStatus.Pending), today.atTime(eight))
        ReminderReceiver().onReceive(app, shadowOf(ReminderReceiver.pendingAction(app, ReminderReceiver.ACTION_SNOOZE, reminder)).savedIntent)
        assertEquals(DoseStatus.Snoozed, dose(med, eight).status)
        assertEquals("500 mg · 1 tabletka · odłożona z 08:00", text(id, R.id.widget_next_detail))
    }

    @Test fun timeChangeRefreshesWidget() {
        val id = createWidget()
        assertEquals("Brak dawek", text(id, R.id.widget_progress))
        container.medications.add(metformin(today))
        RescheduleReceiver().onReceive(app, Intent(Intent.ACTION_TIMEZONE_CHANGED))
        assertEquals("0 z 2 przyjęte", text(id, R.id.widget_progress))
    }

    // zegar aplikacji (SystemZoneClock) bierze strefę przy każdym odczycie, więc widżet przechodzi na nowy dzień
    @Test fun timeZoneChangeMovesWidgetToNewDay() {
        val original = TimeZone.getDefault()
        try {
            // jedna z dwóch stref (UTC+14, UTC-11) zawsze ma inną datę niż strefa startowa
            val zone = listOf("Pacific/Kiritimati", "Pacific/Pago_Pago").map(ZoneId::of)
                .first { LocalDate.now(it) != LocalDate.now(original.toZoneId()) }
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            val newDay = LocalDate.now()
            container.medications.add(metformin(newDay).let { it.copy(schedule = it.schedule.copy(endDate = newDay)) })
            val id = createWidget()
            RescheduleReceiver().onReceive(app, Intent(Intent.ACTION_TIMEZONE_CHANGED))
            assertEquals("0 z 2 przyjęte", text(id, R.id.widget_progress))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test fun allDosesClosedShowsTomorrowsFirstDose() {
        val med = container.medications.add(metformin(today))
        container.medications.setDoseStatus(med.id, today, eight, DoseStatus.Taken)
        container.medications.setDoseStatus(med.id, today, evening, DoseStatus.Skipped)
        val id = createWidget()
        assertFalse(visible(id, R.id.widget_next))
        assertTrue(visible(id, R.id.widget_empty))
        assertEquals("1 z 2 przyjęte", text(id, R.id.widget_progress))
        assertEquals("Na dziś to wszystko", text(id, R.id.widget_empty_title))
        assertTrue(visible(id, R.id.widget_empty_detail))
        assertEquals("Jutro o 08:00 · Metformina 500 mg", text(id, R.id.widget_empty_detail))
    }

    @Test fun noDosesTodayShowsEmptyDay() {
        val id = createWidget()
        assertEquals("Brak dawek", text(id, R.id.widget_progress))
        assertFalse(visible(id, R.id.widget_next))
        assertTrue(visible(id, R.id.widget_empty))
        assertEquals("Brak dawek na dziś", text(id, R.id.widget_empty_title))
        assertFalse(visible(id, R.id.widget_empty_detail))

        // lek zaczyna się jutro: dziś pusto, ale widać jutrzejszą dawkę
        container.medications.add(metformin(today.plusDays(1)))
        container.widgets.update()
        assertEquals("Brak dawek na dziś", text(id, R.id.widget_empty_title))
        assertEquals("Jutro o 08:00 · Metformina 500 mg", text(id, R.id.widget_empty_detail))
    }

    @Test fun notEnoughStockHidesTakeButton() {
        val med = container.medications.add(metformin(today, doseQty = 2, stock = 1))
        val id = createWidget()
        assertFalse(visible(id, R.id.widget_take))
        assertEquals("500 mg · 2 tabletki · brak zapasu", text(id, R.id.widget_next_detail))

        // zapas równy dawce wystarcza
        container.medications.refill(med.id, 1)
        container.widgets.update()
        assertTrue(visible(id, R.id.widget_take))
        assertEquals("500 mg · 2 tabletki", text(id, R.id.widget_next_detail))
    }

    @Test fun snoozedDoseShowsSnoozeTime() {
        val med = container.medications.add(metformin(today))
        container.medications.setDoseStatus(med.id, today, eight, DoseStatus.Snoozed, LocalTime.of(8, 15))
        val id = createWidget()
        assertEquals("08:15", text(id, R.id.widget_next_time))
        assertEquals("500 mg · 1 tabletka · odłożona z 08:00", text(id, R.id.widget_next_detail))
        assertTrue(visible(id, R.id.widget_take))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun updaterFollowsRepositoryChanges() = runTest {
        val zone = ZoneId.systemDefault()
        val day = LocalDate.of(2026, 11, 6)
        val clock = Clock.fixed(day.atTime(7, 0).atZone(zone).toInstant(), zone)
        val repository = MedicationRepository(clock)
        val med = repository.add(metformin(day))
        // widżet z pustego kontenera, dopóki updater nie ruszy
        val id = createWidget()
        assertEquals("Brak dawek", text(id, R.id.widget_progress))

        TodayWidgetUpdater(app, repository, clock).start(backgroundScope)
        runCurrent()
        assertEquals("0 z 2 przyjęte", text(id, R.id.widget_progress))
        assertEquals("08:00", text(id, R.id.widget_next_time))

        repository.setDoseStatus(med.id, day, eight, DoseStatus.Taken)
        runCurrent()
        assertEquals("1 z 2 przyjęte", text(id, R.id.widget_progress))
        assertEquals("20:00", text(id, R.id.widget_next_time))

        repository.add(metformin(day, times = listOf(LocalTime.of(12, 0))).copy(name = "Witamina D", strength = "2000 j.m."))
        runCurrent()
        assertEquals("1 z 3 przyjęte", text(id, R.id.widget_progress))
        assertEquals("Witamina D", text(id, R.id.widget_next_name))

        repository.remove(med.id)
        runCurrent()
        assertEquals("0 z 1 przyjęte", text(id, R.id.widget_progress))
        assertEquals("12:00", text(id, R.id.widget_next_time))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun updaterSwitchesToNextDayAfterMidnight() = runTest {
        val zone = ZoneId.systemDefault()
        val day = LocalDate.of(2026, 11, 6)
        val clock = MovableClock(day.atTime(23, 59).atZone(zone).toInstant(), zone)
        val repository = MedicationRepository(clock)
        val med = repository.add(metformin(day))
        repository.setDoseStatus(med.id, day, eight, DoseStatus.Taken)
        repository.setDoseStatus(med.id, day, evening, DoseStatus.Taken)
        val id = createWidget()
        TodayWidgetUpdater(app, repository, clock).start(backgroundScope)
        runCurrent()
        assertEquals("Na dziś to wszystko", text(id, R.id.widget_empty_title))
        assertEquals("Jutro o 08:00 · Metformina 500 mg", text(id, R.id.widget_empty_detail))

        clock.now = day.plusDays(1).atTime(0, 0, 30).atZone(zone).toInstant()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("0 z 2 przyjęte", text(id, R.id.widget_progress))
        assertTrue(visible(id, R.id.widget_next))
        assertEquals("08:00", text(id, R.id.widget_next_time))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "xhdpi", application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TodayWidgetScreensTest {
    private val app = RuntimeEnvironment.getApplication()
    private val date = LocalDate.now()
    private val state = TodayWidgetState(
        taken = 1, total = 3,
        next = Dose(metformin(date).copy(id = "test"), date, evening, DoseStatus.Pending),
        tomorrow = null,
    )

    // widżet 320×130 dp na szarym „tapecie” z marginesem, żeby było widać zaokrąglone rogi
    private fun screenshot(name: String): Bitmap {
        val density = app.resources.displayMetrics.density
        val width = (320 * density).roundToInt()
        val height = (130 * density).roundToInt()
        val margin = (16 * density).roundToInt()
        val host = FrameLayout(app)
        host.addView(renderTodayWidget(app, state).apply(app, host))
        host.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        host.layout(0, 0, width, height)
        assertEquals("1 z 3 przyjęte", host.findViewById<TextView>(R.id.widget_progress).text.toString())

        val image = Bitmap.createBitmap(width + 2 * margin, height + 2 * margin, Bitmap.Config.ARGB_8888)
        Canvas(image).apply {
            drawColor(Color.rgb(0x7A, 0x86, 0x90))
            translate(margin.toFloat(), margin.toFloat())
            host.draw(this)
        }
        val directory = File("build/reports/design-screens").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return image
    }

    // piksel tła przy lewej krawędzi, w połowie wysokości (w paddingu, poza tekstem)
    private fun Bitmap.surface() = getPixel((24 * app.resources.displayMetrics.density).roundToInt(), height / 2)

    @Test fun widgetLight() {
        assertEquals(Color.parseColor("#F7FAF8"), screenshot("widget_today_light").surface())
    }

    @Config(qualifiers = "+night")
    @Test fun widgetDark() {
        assertEquals(Color.parseColor("#18241E"), screenshot("widget_today_dark").surface())
    }
}
