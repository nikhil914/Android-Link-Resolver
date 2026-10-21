package com.nik.linkresolver

import android.app.Application
import com.nik.linkresolver.data.DebugStore
import com.nik.linkresolver.data.Settings
import com.nik.linkresolver.engine.ResolverRegistry
import com.nik.linkresolver.engine.ResolverRunner
import com.nik.linkresolver.net.NetworkHelper
import com.nik.linkresolver.net.OkHttpFactory

/** Manual object graph (no DI framework in v1). */
class AppGraph(app: Application) {
    val settings = Settings(app)
    val debugStore = DebugStore(app)
    val networks = NetworkHelper(app)
    val http = OkHttpFactory()
    val registry = ResolverRegistry.default()
    val runner = ResolverRunner(registry)
}

class LinkResolverApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}

val Application.graph: AppGraph get() = (this as LinkResolverApp).graph
