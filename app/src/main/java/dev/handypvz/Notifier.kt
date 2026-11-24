package dev.handypvz

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifier {

    private const val CHANNEL_ID = "handypvz_problems"
    private const val NOTIF_ID = 1001

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel),
            NotificationManager.IMPORTANCE_HIGH
        )
        channel.description = context.getString(R.string.notif_channel_desc)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun sessionExpired(context: Context) =
        show(context, context.getString(R.string.notif_session_title), context.getString(R.string.notif_session_text))

    fun fetchFailed(context: Context) =
        show(context, context.getString(R.string.notif_fail_title), context.getString(R.string.notif_fail_text))

    fun orderReady(context: Context) =
        show(context, context.getString(R.string.notif_order_title), context.getString(R.string.notif_order_text))

    private fun show(context: Context, title: String, text: String) {
        val intent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_barcode)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIF_ID, notification)
        } catch (_: SecurityException) {
        }
    }
}
