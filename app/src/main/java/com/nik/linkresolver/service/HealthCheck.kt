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
import com.nik.linkresolver.data.SiteHealth
import com.nik.linkresolver.engine.InMemoryDebugRecorder
import com.nik.linkresolver.engine.ResolveResult
import com.nik.linkresolver.graph
import com.nik.linkresolver.ui.MainActivity
import java.util.concurrent.TimeUnit

/**
 * Once a day, re-resolves the most recent successful link of every site (fresh session) and records whether the
 * site still works. A site that starts failing gets a notification, and its pages are kept in the debug bundle.
 */
class HealthCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as android.app.Application
        val g = app.graph
        val dao = g.db.resolves()
        val sites = (g.registry.builtInHosts() + g.sites.domainsFlow.value).sorted()
        val lease = if (g.settings.forceMobileData) g.networks.acquireCellular() else null
        try {
            val client = lease?.let { g.http.boundTo(it.network) } ?: g.http.base
            for (site in sites) {
                val test = dao.lastSuccessForSite(site) ?: continue
                val rec = InMemoryDebugRecorder()
                rec.log("health check for $site with ${test.sourceUrl}")
                val result = g.runner.run(test.sourceUrl, client, rec) {}
                val previous = g.sites.health.value.firstOrNull { it.site == site }
                val h = when (result) {
                    is ResolveResult.Success -> SiteHealth(
                        site, System.currentTimeMillis(), true,
                        "OK in ${result.elapsedMs / 1000} s" + if (result.finalUrl != test.finalUrl) " (final link changed)" else "",
                        test.sourceUrl,
                    )
                    is ResolveResult.Failure -> SiteHealth(site, System.currentTimeMillis(), false, result.message, test.sourceUrl)
                }
                g.sites.putHealth(h)
                if (!h.ok) {
                    runCatching { g.debugStore.save(rec, "health check: $site\nresult: $result") }
                    if (previous == null || previous.ok) notifyBroken(h)
                }
            }
        } finally {
            lease?.close()
        }
        return Result.success()
    }

    private fun notifyBroken(h: SiteHealth) {
        val ctx = applicationContext
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Site health", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            ctx, 10,
            Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_link)
            .setContentTitle("${h.site} stopped resolving")
            .setContentText(h.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${h.message}\n\nThe debug bundle in Settings has the pages for a fix."))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        nm.notify(2000 + (h.site.hashCode() and 0xfff), n)
    }

    companion object {
        private const val CHANNEL = "site_health"
        private const val DAILY = "site-health-daily"
        private const val NOW = "site-health-now"

        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Turns the daily check on or off. */
        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (!enabled) {
                wm.cancelUniqueWork(DAILY)
                return
            }
            val req = PeriodicWorkRequestBuilder<HealthCheckWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setInitialDelay(1, TimeUnit.HOURS)
                .build()
            wm.enqueueUniquePeriodicWork(DAILY, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun runNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<HealthCheckWorker>().setConstraints(constraints).build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
        }

        fun runningFlow(context: Context) = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(NOW)
    }
}
