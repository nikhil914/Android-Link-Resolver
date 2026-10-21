package com.nik.linkresolver

import android.app.Application
import com.nik.linkresolver.data.BatchManager
import com.nik.linkresolver.data.CollectionManager
import com.nik.linkresolver.data.DebugStore
import com.nik.linkresolver.data.db.AppDatabase
import com.nik.linkresolver.data.Settings
import com.nik.linkresolver.data.SiteStore
import com.nik.linkresolver.engine.ResolverRegistry
import com.nik.linkresolver.engine.ResolverRunner
import com.nik.linkresolver.net.NetworkHelper
import com.nik.linkresolver.net.OkHttpFactory
import com.nik.linkresolver.service.ChannelWatchWorker
import com.nik.linkresolver.service.HealthCheckWorker
import kotlinx.coroutines.launch
import com.nik.linkresolver.engine.TelegramUri
import com.nik.linkresolver.telegram.BotStarter
import com.nik.linkresolver.telegram.TelegramClient
import com.nik.linkresolver.telegram.TelegramPrefs

/** Manual object graph (no DI framework in v1). */
class AppGraph(app: Application) {
    /** Work that must outlive a screen (e.g. queueing a link from an activity that closes at once). */
    val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    val settings = Settings(app)
    val debugStore = DebugStore(app)
    val networks = NetworkHelper(app)
    val http = OkHttpFactory()
    val sites = SiteStore(app)
    val registry = ResolverRegistry.default(sites.domains)
    val rules = com.nik.linkresolver.data.RulesStore(app, registry, sites.domains).also { it.load() }
    val runner = ResolverRunner(registry, tuning = sites.tuning)
    val db = AppDatabase.create(app)
    val batches = BatchManager(app, db.resolves(), registry)
    val telegram by lazy { TelegramClient(app, TelegramPrefs(app)) }
    val bots = BotStarter({ telegram }, db.botStarts(), settings, appScope)
    val collections = CollectionManager(db.collections(), { telegram }, batches, registry, appScope)
}

class LinkResolverApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        HealthCheckWorker.schedule(this, graph.settings.healthCheck)
        ChannelWatchWorker.schedule(this, graph.settings.watchIntervalMin)
        graph.batches.onResolved = { url ->
            if (graph.settings.autoBotStart && TelegramUri.botStart(url) != null) graph.appScope.launch { graph.bots.request(url) }
        }
        if (graph.rules.dueForCheck()) graph.appScope.launch { graph.rules.refresh() }
        // A collection save cut off by a killed process is no longer running.
        graph.appScope.launch { graph.db.collections().clearStaleSaving() }
    }
}

val Application.graph: AppGraph get() = (this as LinkResolverApp).graph
