package pl.agh.meditracker.data.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import pl.agh.meditracker.MainActivity
import pl.agh.meditracker.R

data class NotificationAccess(
    val allowed: Boolean = false,
    val sound: Boolean = true,
    val vibration: Boolean = true,
) {
    val soundSummary get() = when {
        sound && vibration -> "Dźwięk i wibracje włączone"
        sound -> "Tylko dźwięk"
        vibration -> "Tylko wibracje"
        else -> "Wyciszone"
    }
}

class NotificationController(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun ensureChannel() {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Przypomnienia o dawkach", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Przypomnienia o zaplanowanych i odroczonych dawkach"
            enableVibration(true)
        })
    }

    fun access(): NotificationAccess {
        ensureChannel()
        val channel = manager.getNotificationChannel(CHANNEL)
        return NotificationAccess(
            NotificationManagerCompat.from(context).areNotificationsEnabled() && channel.importance != NotificationManager.IMPORTANCE_NONE,
            channel.sound != null && channel.importance >= NotificationManager.IMPORTANCE_DEFAULT,
            channel.shouldVibrate(),
        )
    }

    fun openSettings(channel: Boolean = false) {
        ensureChannel()
        val intent = if (channel) Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL)
        else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: android.content.ActivityNotFoundException) {
            try { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            catch (_: android.content.ActivityNotFoundException) { Toast.makeText(context, "Otwórz ustawienia telefonu i wybierz mediTracker → Powiadomienia.", Toast.LENGTH_LONG).show() }
        }
    }

    fun show(id: Int, title: String, body: String): Boolean {
        if (!access().allowed) return false
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pending).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
        return try { manager.notify(id, notification); true } catch (_: SecurityException) { false }
    }

    companion object { const val CHANNEL = "doses" }
}
