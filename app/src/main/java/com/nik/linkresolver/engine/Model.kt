package com.nik.linkresolver.engine

import okhttp3.HttpUrl

/** Live progress emitted while a link resolves. UI turns these into text. */
sealed class ResolveEvent {
    data class Fetching(val url: String) : ResolveEvent()
    data class StepSubmitting(val step: Int, val total: String) : ResolveEvent()
    data class StepWaiting(val step: Int, val total: String, val secondsLeft: Int) : ResolveEvent()
    data class Retrying(val attempt: Int, val max: Int, val reason: String) : ResolveEvent()
    data class GetLinkWaiting(val secondsLeft: Int) : ResolveEvent()
    data object Finalizing : ResolveEvent()
}

/** One HTTP request/response in the flow, for the debug log. */
data class Hop(
    val method: String,
    val url: String,
    val status: Int,
    val title: String,
    val elapsedMs: Long,
)

enum class ErrorKind {
    /** Site served its "Access Denied … proxy or VPN" page. */
    ACCESS_DENIED,

    /** Connect/read timeout or host unreachable on this network. */
    UNREACHABLE,

    /** Other I/O failure. */
    NETWORK,

    /** We got a page we don't know how to handle (site changed?). */
    UNRECOGNISED_PAGE,

    /** The Get Link step never produced a URL. */
    GET_LINK_FAILED,

    TOO_MANY_HOPS,
    UNSUPPORTED_URL,
}

sealed class ResolveResult {
    data class Success(val finalUrl: String, val hops: List<Hop>, val elapsedMs: Long) : ResolveResult()
    data class Failure(val kind: ErrorKind, val message: String, val hops: List<Hop>, val elapsedMs: Long) : ResolveResult()
}

/** Thrown inside resolvers; [ResolverRunner] turns it into a [ResolveResult.Failure]. */
class ResolveException(val kind: ErrorKind, message: String, cause: Throwable? = null) : Exception(message, cause)

/** A site-specific resolver. Implementations must not touch Android APIs. */
interface Resolver {
    val name: String
    fun canHandle(url: HttpUrl): Boolean

    /** Returns the final destination URL (never requested), or throws [ResolveException]. */
    suspend fun resolve(url: HttpUrl, ctx: ResolveContext): String
}

/** Human readable text for an error kind, as shown in the app. */
fun ErrorKind.userMessage(): String = when (this) {
    ErrorKind.ACCESS_DENIED -> "Site blocked this network (VPN/proxy/datacenter IP)"
    ErrorKind.UNREACHABLE -> "Can't reach site on this network — try mobile data"
    ErrorKind.NETWORK -> "Network error"
    ErrorKind.UNRECOGNISED_PAGE -> "Unrecognised page — the site may have changed its flow"
    ErrorKind.GET_LINK_FAILED -> "The final \"Get Link\" step didn't return a link"
    ErrorKind.TOO_MANY_HOPS -> "Too many redirects, gave up"
    ErrorKind.UNSUPPORTED_URL -> "This link isn't from a supported site"
}
