package com.nik.linkresolver

import android.app.Application
import com.nik.linkresolver.data.BatchManager
import com.nik.linkresolver.data.DebugStore
import com.nik.linkresolver.data.db.AppDatabase
import com.nik.linkresolver.data.Settings
import com.nik.linkresolver.data.SiteStore
import com.nik.linkresolver.engine.ResolverRegistry
import com.nik.linkresolver.engine.ResolverRunner
import com.nik.linkresolver.net.NetworkHelper
import com.nik.linkresolver.net.OkHttpFactory
import com.nik.linkresolver.service.HealthCheckWorker
import com.nik.linkresolver.telegram.TelegramClient
import com.nik.linkresolver.telegram.TelegramPrefs

/** Manual object graph (no DI framework in v1). */
class AppGraph(app: Application) {
    val settings = Settings(app)
    val debugStore = DebugStore(app)
    val networks = NetworkHelper(app)
    val http = OkHttpFactory()
    val sites = SiteStore(app)
    val registry = ResolverRegistry.default(sites.domains)
    val runner = ResolverRunner(registry, tuning = sites.tuning)
    val db = AppDatabase.create(app)
    val batches = BatchManager(app, db.resolves(), registry)
    val telegram by lazy { TelegramClient(app, TelegramPrefs(app)) }
}

class LinkResolverApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        HealthCheckWorker.schedule(this, graph.settings.healthCheck)
    }
}

val Application.graph: AppGraph get() = (this as LinkResolverApp).graph
