package com.nik.linkresolver.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Site rules: the tunables of each resolver (hosts, waits, form ids, paths) as JSON, so site changes like a new
 * step timer or a renamed form need no new APK. A new kind of step still needs a release.
 *
 * Unknown keys are ignored; missing keys keep the built-in defaults; out-of-range values are rejected.
 */
data class SiteRules(
    val version: Int,
    val minAppVersion: Int,
    val updated: String,
    val sites: List<Pair<String, LinkShortXConfig>>,
    val generic: LinkShortXConfig,
    /** Extra shortener domains handled by the generic resolver. */
    val domains: Set<String>,
) {
    fun registry(learned: DomainStore): ResolverRegistry = ResolverRegistry(
        sites.map { (name, cfg) -> LinkShortXResolver(cfg, name) },
        LinkShortXResolver(generic, "AdLinkFly (generic)"),
        learned,
        domains,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val HOST = Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$")

        /** Parses and validates; throws [IllegalArgumentException] with the reason when the file is not usable. */
        fun parse(text: String): SiteRules {
            val root = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull()
                ?: throw IllegalArgumentException("not a JSON object")
            val version = root.int("version") ?: throw IllegalArgumentException("missing version")
            require(version >= 1) { "version must be ≥ 1" }
            val sitesJson = root["sites"] as? JsonArray ?: throw IllegalArgumentException("missing sites")
            require(sitesJson.isNotEmpty()) { "no sites" }
            val sites = sitesJson.mapIndexed { i, el ->
                val o = el as? JsonObject ?: throw IllegalArgumentException("sites[$i] is not an object")
                val hosts = o.hosts("hosts")
                require(hosts.isNotEmpty()) { "sites[$i] has no hosts" }
                (o.str("name") ?: hosts.first()) to config(o, LinkShortXConfig(entryHosts = hosts), "sites[$i]")
            }
            val generic = (root["generic"] as? JsonObject)?.let {
                config(it, LinkShortXConfig(entryHosts = emptySet(), matchAnyHost = true), "generic")
            } ?: LinkShortXConfig(entryHosts = emptySet(), matchAnyHost = true)
            return SiteRules(
                version = version,
                minAppVersion = root.int("minAppVersion") ?: 0,
                updated = root.str("updated").orEmpty(),
                sites = sites,
                generic = generic.copy(matchAnyHost = true, entryHosts = emptySet()),
                domains = root.hosts("domains"),
            )
        }

        private fun config(o: JsonObject, base: LinkShortXConfig, where: String): LinkShortXConfig {
            fun ms(key: String, def: Long, max: Long = 120_000) = o.long(key)?.also {
                require(it in 0..max) { "$where.$key out of range (0–$max)" }
            } ?: def
            fun n(key: String, def: Int, max: Int) = o.int(key)?.also {
                require(it in 0..max) { "$where.$key out of range (0–$max)" }
            } ?: def
            fun id(key: String, def: String) = o.str(key)?.also {
                require(it.matches(Regex("^[A-Za-z0-9_-]{1,40}$"))) { "$where.$key is not a valid id" }
            } ?: def
            fun path(key: String, def: String) = o.str(key)?.also {
                require(it.startsWith("/") && it.length <= 100 && !it.contains("..")) { "$where.$key is not a path" }
            } ?: def
            val armPattern = o.str("armUrlPattern")?.let {
                runCatching { Regex(it) }.getOrNull()?.takeIf { r -> it.contains('(') } ?: throw IllegalArgumentException("$where.armUrlPattern is not a regex with a group")
            } ?: base.armUrlPattern
            return base.copy(
                flowHosts = if (o.containsKey("flowHosts")) o.hosts("flowHosts") else base.flowHosts,
                destinationHosts = if (o.containsKey("destinationHosts")) o.hosts("destinationHosts") else base.destinationHosts,
                stepWaitMs = ms("stepWaitMs", base.stepWaitMs),
                firstStepWaitMs = ms("firstStepWaitMs", base.firstStepWaitMs),
                rejectExtraWaitMs = ms("rejectExtraWaitMs", base.rejectExtraWaitMs, 10_000),
                retryDelayMs = ms("retryDelayMs", base.retryDelayMs, 30_000),
                maxRetries = n("maxRetries", base.maxRetries, 20),
                stepFormId = id("stepFormId", base.stepFormId),
                getLinkFormId = id("getLinkFormId", base.getLinkFormId),
                getLinkAnchorClass = id("getLinkAnchorClass", base.getLinkAnchorClass),
                preFormId = id("preFormId", base.preFormId),
                getLinkDefaultCounterS = n("getLinkDefaultCounterS", base.getLinkDefaultCounterS, 120),
                getLinkExtraMs = ms("getLinkExtraMs", base.getLinkExtraMs, 30_000),
                getLinkAttempts = n("getLinkAttempts", base.getLinkAttempts, 10).coerceAtLeast(1),
                getLinkRetryDelayMs = ms("getLinkRetryDelayMs", base.getLinkRetryDelayMs, 30_000),
                armUrlPattern = armPattern,
                armFallbackPath = path("armFallbackPath", base.armFallbackPath),
                armAttempts = n("armAttempts", base.armAttempts, 10).coerceAtLeast(1),
                armRetryDelayMs = ms("armRetryDelayMs", base.armRetryDelayMs, 30_000),
                maxHops = n("maxHops", base.maxHops, 100).coerceAtLeast(5),
                maxProbes = n("maxProbes", base.maxProbes, 5),
            )
        }

        private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
        private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
        @Suppress("unused") private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull
        private fun JsonObject.hosts(k: String): Set<String> {
            val arr = this[k] as? JsonArray ?: return emptySet()
            return arr.map { el ->
                val h = (el as? JsonPrimitive)?.content?.trim()?.lowercase()?.removePrefix("www.") ?: ""
                require(HOST.matches(h)) { "\"$h\" in $k is not a host name" }
                h
            }.toSet()
        }
    }
}
