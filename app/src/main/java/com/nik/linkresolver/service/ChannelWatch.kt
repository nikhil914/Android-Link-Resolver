package com.nik.linkresolver.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nik.linkresolver.R
import com.nik.linkresolver.data.db.Source
import com.nik.linkresolver.data.db.Status
import com.nik.linkresolver.engine.QueueWorker
import com.nik.linkresolver.graph
import com.nik.linkresolver.telegram.TelegramLinks
import com.nik.linkresolver.ui.MainActivity
import java.util.concurrent.TimeUnit

/**
 * Checks watched Telegram channels for posts newer than the last handled one, picks out supported links not seen
 * before, and (by default) resolves them right here — a background job may not start the foreground service — then
 * notifies with the final links. At most [MAX_LINKS_PER_RUN] links per run; the rest wait for the next run.
 */
class ChannelWatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as android.app.Application
        val g = app.graph
        val tg = g.telegram
        val watched = tg.prefs.watched()
        if (watched.isEmpty() || !tg.prefs.configured) return Result.success()
        if (!tg.awaitReady()) return Result.success() // not logged in; nothing to do

        var budget = MAX_LINKS_PER_RUN
        val batches = mutableListOf<Triple<Long, String, Long>>() // chat id, title, batch id
        for ((chatId, title) in watched) {
            if (budget <= 0) break
            val mark = tg.prefs.watchMark(chatId)
            val newest = runCatching { tg.history(chatId, 0, if (mark == 0L) 1 else SCAN) }.getOrElse { continue }
            if (newest.isEmpty()) continue
            if (mark == 0L) { // first run for this chat: start from now
                tg.prefs.setWatchMark(chatId, newest.first().id)
                continue
            }
            // Oldest new post first, so a capped run continues where it stopped next time.
            val fresh = newest.filter { it.id > mark }.sortedBy { it.id }
            val picked = mutableListOf<Pair<String, Long>>()
            var lastHandled = mark
            for (m in fresh) {
                val links = TelegramLinks.supportedLinks(m, g.registry).filter { !g.db.resolves().known(it) && picked.none { p -> p.first == it } }
                if (picked.size + links.size > budget && picked.isNotEmpty()) break
                picked += links.map { it to m.id }
                lastHandled = m.id
            }
            tg.prefs.setWatchMark(chatId, lastHandled)
            if (picked.isEmpty()) continue
            budget -= picked.size

            if (g.settings.watchAutoResolve) {
                val id = g.batches.enqueue(
                    urls = picked.map { it.first }, source = Source.WATCH, label = "$title: ${picked.size} new",
                    channel = title, messageIds = picked.toMap(), start = false,
                )
                batches += Triple(chatId, title, id)
            } else {
                notifyNew(chatId, title, picked.map { it.first })
            }
        }
        if (batches.isEmpty()) return Result.success()

        // Resolve them now, inside this job.
        val lease = if (g.settings.forceMobileData) g.networks.acquireCellular() else null
        try {
            val client = lease?.let { g.http.boundTo(it.network) } ?: g.http.base
            QueueWorker(g.runner, g.batches, { g.settings.concurrency }, onProgress = { id, e -> g.batches.onProgress(id, e) })
                .drain { client }
        } finally {
            lease?.close()
        }
        for ((chatId, title, batchId) in batches) {
            val rows = g.db.resolves().batchRows(batchId)
            ResultNotifier.notify(
                applicationContext, 3000 + (chatId.hashCode() and 0xfff), "New in $title",
                rows.filter { it.status == Status.DONE }.mapNotNull { it.finalUrl },
                rows.count { it.status == Status.FAILED }, batchId,
            )
        }
        return Result.success()
    }

    private fun notifyNew(chatId: Long, title: String, links: List<String>) {
        val ctx = applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "New channel links", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            ctx, 4000 + (chatId.hashCode() and 0xfff),
            MainActivity.openChannel(ctx, chatId, title),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val style = NotificationCompat.InboxStyle().also { st -> links.take(6).forEach { st.addLine(it) } }
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("${links.size} new link${if (links.size == 1) "" else "s"} in $title")
            .setContentText("Tap to pick which to resolve")
            .setStyle(style)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        nm.notify(4000 + (chatId.hashCode() and 0xfff), n)
    }

    companion object {
        const val MAX_LINKS_PER_RUN = 24
        private const val SCAN = 300
        private const val CHANNEL = "watch_new"
        private const val PERIODIC = "channel-watch"
        private const val NOW = "channel-watch-now"
        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** [intervalMin] 0 = off; Android runs periodic work at most every 15 minutes. */
        fun schedule(context: Context, intervalMin: Int) {
            val wm = WorkManager.getInstance(context)
            if (intervalMin <= 0) {
                wm.cancelUniqueWork(PERIODIC)
                return
            }
            val req = PeriodicWorkRequestBuilder<ChannelWatchWorker>(intervalMin.coerceAtLeast(15).toLong(), TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<ChannelWatchWorker>().setConstraints(constraints).build(),
            )
        }

        fun runningFlow(context: Context) = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(NOW)
    }
}
