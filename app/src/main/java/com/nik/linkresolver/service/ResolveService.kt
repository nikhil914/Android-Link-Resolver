package com.nik.linkresolver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.nik.linkresolver.R
import com.nik.linkresolver.engine.QueueWorker
import com.nik.linkresolver.graph
import com.nik.linkresolver.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that drains the resolve queue (dataSync type) so batches keep going when the app is closed.
 * Stops itself when nothing is queued or running.
 */
class ResolveService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var firstRun = true
    private var since = 0L
    @Volatile private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        ensureChannels(this)
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, progressNotification("Starting…", null),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (intent?.action == ACTION_CANCEL_ALL) {
            scope.launch { application.graph.batches.cancelBatch(null) }
        }
        if (job?.isActive == true) {
            application.graph.batches.worker?.wake()
        } else {
            job = scope.launch { runQueue() }
        }
        return START_NOT_STICKY
    }

    private suspend fun runQueue() {
        val g = application.graph
        val dao = g.db.resolves()
        if (firstRun) {
            dao.requeueRunning() // links left RUNNING by a killed process
            firstRun = false
            since = System.currentTimeMillis()
        }
        val lease = if (g.settings.forceMobileData) g.networks.acquireCellular() else null
        val client = lease?.let { g.http.boundTo(it.network) } ?: g.http.base
        val network = if (lease != null) "mobile data" else g.networks.describe()
        try {
            do {
                val worker = QueueWorker(
                    runner = g.runner,
                    store = g.batches,
                    concurrency = { g.settings.concurrency },
                    onProgress = { id, e -> g.batches.onProgress(id, e) },
                    onFinished = { _, _ -> scope.launch { updateProgress(network) } },
                )
                g.batches.worker = worker
                updateProgress(network)
                worker.drain { client }
            } while (dao.queuedCount() > 0)
        } finally {
            g.batches.worker = null
            lease?.close()
        }
        val c = dao.counts(since)
        notifyDone(c.done ?: 0, c.failed ?: 0)
        // A start that arrived while we were finishing means new links: run again instead of stopping.
        if (dao.queuedCount() > 0 || !stopSelfResult(lastStartId)) {
            job = scope.launch { runQueue() }
        } else {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    private suspend fun updateProgress(network: String) {
        val c = application.graph.db.resolves().counts(since)
        val running = c.running ?: 0
        val queued = c.queued ?: 0
        val done = c.done ?: 0
        val failed = c.failed ?: 0
        val total = running + queued + done + failed
        val text = "$running running · $queued queued · $done done" + (if (failed > 0) " · $failed failed" else "") + " — $network"
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, progressNotification(text, if (total > 0) (done + failed) to total else null))
    }

    private fun progressNotification(text: String, progress: Pair<Int, Int>?): Notification {
        val cancel = PendingIntent.getService(
            this, 1, intent(this).setAction(ACTION_CANCEL_ALL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Resolving links")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setContentIntent(openApp(null))
            .addAction(0, "Cancel all", cancel)
            .apply { if (progress != null) setProgress(progress.second, progress.first, false) }
            .build()
    }

    private fun notifyDone(done: Int, failed: Int) {
        if (done + failed == 0) return
        val n = NotificationCompat.Builder(this, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("Links resolved")
            .setContentText("$done done" + if (failed > 0) ", $failed failed" else "")
            .setAutoCancel(true)
            .setContentIntent(openApp(MainActivity.TAB_HISTORY))
            .build()
        getSystemService(NotificationManager::class.java).notify(DONE_NOTIFICATION_ID, n)
    }

    private fun openApp(tab: String?): PendingIntent = PendingIntent.getActivity(
        this, if (tab == null) 2 else 3,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { if (tab != null) putExtra(MainActivity.EXTRA_TAB, tab) },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** Android 15+: dataSync services get a time limit. Stop cleanly; queued links stay queued for next time. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        application.graph.batches.worker?.cancelAll()
        stopSelf()
    }

    override fun onDestroy() {
        application.graph.batches.worker?.cancelAll()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_PROGRESS = "resolve_progress"
        private const val CHANNEL_RESULTS = "resolve_results"
        private const val NOTIFICATION_ID = 1001
        private const val DONE_NOTIFICATION_ID = 1002
        private const val ACTION_CANCEL_ALL = "com.nik.linkresolver.CANCEL_ALL"

        fun intent(context: Context) = Intent(context, ResolveService::class.java)

        fun ensureChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_PROGRESS, "Resolving progress", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(CHANNEL_RESULTS, "Resolve results", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }
}
