package pl.agh.meditracker.data

import android.app.Application
import android.content.Context
import android.provider.Settings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import pl.agh.meditracker.data.notifications.NotificationController
import pl.agh.meditracker.data.settings.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SettingsAndStorageTest {

    @Test fun settingsSurviveRecreationAndPublishChanges() {
        val context = RuntimeEnvironment.getApplication() as Context
        val settings = SettingsStore(context)
        settings.remindersEnabled = true
        settings.snoozeMinutes = 30
        settings.theme = AppTheme.Dark
        assertEquals(AppSettings(true, 30, AppTheme.Dark), settings.state.value)
        assertEquals(settings.state.value, SettingsStore(context).state.value)
    }

    @Test fun malformedPreferenceUsesKnownDefault() {
        val context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("theme", "unknown").putInt("snooze_minutes", -1).commit()
        assertEquals(AppSettings(), SettingsStore(context).state.value)
    }

    @Test fun notificationControlOpensAndroidChannelSettings() {
        val context = RuntimeEnvironment.getApplication() as Context
        NotificationController(context).openSettings(channel = true)
        val intent = shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(NotificationController.CHANNEL, intent.getStringExtra(Settings.EXTRA_CHANNEL_ID))
        assertEquals(context.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }
}
