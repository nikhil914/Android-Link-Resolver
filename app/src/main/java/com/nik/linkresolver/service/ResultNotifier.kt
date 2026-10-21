package com.nik.linkresolver.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.nik.linkresolver.R
import com.nik.linkresolver.ui.MainActivity
import com.nik.linkresolver.ui.viewIntent

/** Copies the text passed in the intent (notification "Copy" action). */
class CopyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Final link", text))
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
        intent.getIntExtra(EXTRA_NOTIFICATION, 0).takeIf { it != 0 }?.let {
            context.getSystemService(NotificationManager::class.java).cancel(it)
        }
    }

    companion object {
        const val EXTRA_TEXT = "text"
        const val EXTRA_NOTIFICATION = "notification"
    }
}

/** Result notifications with the final links themselves and Open / Copy actions. */
object ResultNotifier {
    private const val CHANNEL = "resolve_results"

    fun ensureChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Resolve results", NotificationManager.IMPORTANCE_DEFAULT))
    }

    /**
     * [finals] = final links in order; [failed] = how many failed. One link → Open + Copy; several → the list,
     * Copy all, and a tap opens [batchId] (or History).
     */
    fun notify(context: Context, id: Int, title: String, finals: List<String>, failed: Int, batchId: Long? = null) {
        if (finals.isEmpty() && failed == 0) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, id,
            (if (batchId != null) MainActivity.openBatch(context, batchId) else Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_HISTORY))
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setAutoCancel(true)
            .setContentIntent(open)
        val failText = if (failed > 0) " · $failed failed" else ""
        when {
            finals.size == 1 -> {
                val url = finals[0]
                b.setContentTitle(title + failText).setContentText(url)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(url))
                    .addAction(0, "Open", PendingIntent.getActivity(context, id + 1, context.viewIntent(url), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
                    .addAction(0, "Copy", copyIntent(context, id + 2, url, id))
            }
            finals.isNotEmpty() -> {
                val style = NotificationCompat.InboxStyle()
                finals.take(6).forEach { style.addLine(it) }
                if (finals.size > 6) style.setSummaryText("+${finals.size - 6} more")
                b.setContentTitle("$title: ${finals.size} links$failText").setContentText(finals[0]).setStyle(style)
                    .addAction(0, "Copy all", copyIntent(context, id + 2, finals.joinToString("\n"), id))
            }
            else -> b.setContentTitle("$failed link${if (failed == 1) "" else "s"} failed").setContentText("Open the app for the log")
        }
        context.getSystemService(NotificationManager::class.java).notify(id, b.build())
    }

    private fun copyIntent(context: Context, req: Int, text: String, notificationId: Int) = PendingIntent.getBroadcast(
        context, req,
        Intent(context, CopyReceiver::class.java).putExtra(CopyReceiver.EXTRA_TEXT, text).putExtra(CopyReceiver.EXTRA_NOTIFICATION, notificationId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
