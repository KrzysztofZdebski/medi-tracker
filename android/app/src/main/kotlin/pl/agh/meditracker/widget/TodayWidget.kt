package pl.agh.meditracker.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import pl.agh.meditracker.MainActivity
import pl.agh.meditracker.R
import pl.agh.meditracker.core.MediTrackerApp
import pl.agh.meditracker.data.repository.MedicationRepository
import pl.agh.meditracker.feature.today.dueTime
import pl.agh.meditracker.feature.today.hhmm
import pl.agh.meditracker.feature.today.nextDose
import pl.agh.meditracker.model.Dose
import pl.agh.meditracker.model.DoseStatus
import pl.agh.meditracker.reminder.ReminderReceiver
import pl.agh.meditracker.reminder.currentDay
import pl.agh.meditracker.reminder.reminderFor
import pl.agh.meditracker.core.SystemZoneClock
import java.time.Clock
import java.time.LocalDate

// Widżet „Dzisiejsze dawki”: postęp dnia i najbliższa dawka z przyciskiem „Przyjęte”.
// RemoteViews zamiast Glance, żeby nie dokładać zależności; przycisk idzie przez ten sam odbiornik co powiadomienie.

data class TodayWidgetState(
    val taken: Int,
    val total: Int,
    // najbliższa nieprzyjęta dawka na dziś (także zaległa albo odłożona)
    val next: Dose?,
    // pierwsza dawka jutro, gdy na dziś nic nie zostało
    val tomorrow: Dose?,
)

fun todayWidgetState(today: List<Dose>, tomorrow: List<Dose>) = TodayWidgetState(
    taken = today.count { it.status == DoseStatus.Taken },
    total = today.size,
    next = nextDose(today),
    tomorrow = tomorrow.minByOrNull { it.time },
)

private val Dose.label get() = "${medication.name} ${medication.strength}".trim()

fun renderTodayWidget(context: Context, state: TodayWidgetState): RemoteViews =
    RemoteViews(context.packageName, R.layout.widget_today).apply {
        val open = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE))
        setTextViewText(
            R.id.widget_progress,
            if (state.total == 0) "Brak dawek" else "${state.taken} z ${state.total} przyjęte",
        )

        val next = state.next
        setViewVisibility(R.id.widget_next, if (next == null) View.GONE else View.VISIBLE)
        setViewVisibility(R.id.widget_empty, if (next == null) View.VISIBLE else View.GONE)
        if (next == null) {
            setTextViewText(R.id.widget_empty_title, if (state.total == 0) "Brak dawek na dziś" else "Na dziś to wszystko")
            val tomorrow = state.tomorrow
            setViewVisibility(R.id.widget_empty_detail, if (tomorrow == null) View.GONE else View.VISIBLE)
            if (tomorrow != null) setTextViewText(R.id.widget_empty_detail, "Jutro o ${tomorrow.time.hhmm()} · ${tomorrow.label}")
            return@apply
        }

        val med = next.medication
        val enoughStock = med.stock >= med.schedule.doseQty
        setTextViewText(R.id.widget_next_time, next.dueTime.hhmm())
        // moc w drugiej linii, żeby przy wąskim widżecie nie zginęła w wielokropku po nazwie
        setTextViewText(R.id.widget_next_name, med.name)
        setTextViewText(
            R.id.widget_next_detail,
            listOfNotNull(
                med.strength.ifBlank { null },
                med.unit.format(med.schedule.doseQty),
                if (next.status == DoseStatus.Snoozed) "odłożona z ${next.time.hhmm()}" else null,
                if (!enoughStock) "brak zapasu" else null,
            ).joinToString(" · "),
        )
        setViewVisibility(R.id.widget_take, if (enoughStock) View.VISIBLE else View.GONE)
        if (enoughStock) {
            val reminder = reminderFor(next, next.date.atTime(next.time))
            setOnClickPendingIntent(R.id.widget_take, ReminderReceiver.pendingAction(context, ReminderReceiver.ACTION_TAKE, reminder))
        }
    }

// Odświeża widżety po każdej zmianie leków lub dawek i o północy, dopóki proces aplikacji żyje.
// Gdy nie żyje, dane i tak się nie zmieniają; dzień przestawia updatePeriodMillis z widget_today_info.xml.
@OptIn(ExperimentalCoroutinesApi::class)
class TodayWidgetUpdater(
    private val context: Context,
    private val repository: MedicationRepository,
    private val clock: Clock = SystemZoneClock,
) {
    // null na urządzeniach bez obsługi widżetów
    private val manager: AppWidgetManager? = context.getSystemService(AppWidgetManager::class.java)

    private fun states(day: LocalDate): Flow<TodayWidgetState> =
        combine(repository.dosesFor(day), repository.dosesFor(day.plusDays(1)), ::todayWidgetState)

    fun start(scope: CoroutineScope) = scope.launch {
        currentDay(clock).flatMapLatest(::states).collect { push(it) }
    }

    // natychmiastowe odświeżenie, np. z odbiornika przycisku, zanim system zamknie proces
    fun update(ids: IntArray = widgetIds()) {
        if (ids.isEmpty()) return
        push(runBlocking { states(LocalDate.now(clock)).first() }, ids)
    }

    private fun widgetIds(): IntArray =
        manager?.getAppWidgetIds(ComponentName(context, TodayWidgetProvider::class.java)) ?: IntArray(0)

    private fun push(state: TodayWidgetState, ids: IntArray = widgetIds()) {
        if (ids.isEmpty()) return
        manager?.updateAppWidget(ids, renderTodayWidget(context, state))
    }
}

class TodayWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        (context.applicationContext as MediTrackerApp).container.widgets.update(appWidgetIds)
    }
}
