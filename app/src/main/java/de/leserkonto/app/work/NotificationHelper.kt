package de.leserkonto.app.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import de.leserkonto.app.MainActivity
import de.leserkonto.app.R
import de.leserkonto.app.data.model.Loan

/** Builds and posts the local "due soon" reminder notifications. */
class NotificationHelper(private val context: Context) {

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = context.getString(R.string.notif_channel_desc) }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun canPost(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    /** Posts one summary notification for all items that are due soon/overdue. */
    fun notifyDueSoon(dueSoon: List<Loan>) {
        if (dueSoon.isEmpty() || !canPost()) return
        ensureChannel()

        val title = context.getString(R.string.notif_due_title, dueSoon.size)
        val lines = dueSoon.take(6).map { loan ->
            val days = loan.daysUntilDue() ?: 0
            val whenText = when {
                days < 0 -> context.getString(R.string.due_overdue, -days)
                days == 0L -> context.getString(R.string.due_today)
                else -> context.getString(R.string.due_in_days, days)
            }
            "• ${loan.title} – $whenText"
        }

        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        lines.forEach { style.addLine(it) }

        val intent = Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(lines.firstOrNull().orEmpty())
            .setStyle(style)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIF_ID, notification)
    }

    /** Posts a short confirmation after an automatic renewal ran. */
    fun notifyAutoRenewed(count: Int) {
        if (count <= 0 || !canPost()) return
        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.notif_autorenew_title))
            .setContentText(context.getString(R.string.notif_autorenew_text, count))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIF_ID + 1, notification)
    }

    companion object {
        const val CHANNEL_ID = "due_reminders"
        const val NOTIF_ID = 1001
    }
}
