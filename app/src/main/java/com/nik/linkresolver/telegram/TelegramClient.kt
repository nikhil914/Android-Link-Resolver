package com.nik.linkresolver.telegram

import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.core.content.edit
import com.nik.linkresolver.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.security.SecureRandom
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Where the login stands. */
sealed interface TgAuth {
    /** No API ID/hash saved yet. */
    data object NotConfigured : TgAuth
    data object Starting : TgAuth
    data object WaitPhone : TgAuth
    data class WaitCode(val sentTo: String) : TgAuth
    data class WaitPassword(val hint: String) : TgAuth
    data object Ready : TgAuth
    data class Unsupported(val what: String) : TgAuth
    data object LoggingOut : TgAuth
}

class TdException(val code: Int, message: String) : Exception(message)

data class TgChat(val id: Long, val title: String, val kind: String, val lastMessageDate: Int)

/** API credentials (from my.telegram.org) and the local database key. Stored only on this phone. */
class TelegramPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("telegram", Context.MODE_PRIVATE)

    val apiId: Int get() = prefs.getInt("api_id", 0)
    val apiHash: String get() = prefs.getString("api_hash", "").orEmpty()
    val configured: Boolean get() = apiId > 0 && apiHash.isNotBlank()

    fun saveCredentials(id: Int, hash: String) = prefs.edit { putInt("api_id", id); putString("api_hash", hash.trim()) }

    /** Random key that encrypts TDLib's local database. */
    val databaseKey: ByteArray
        get() {
            prefs.getString("db_key", null)?.let { return Base64.decode(it, Base64.NO_WRAP) }
            val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            prefs.edit { putString("db_key", Base64.encodeToString(key, Base64.NO_WRAP)) }
            return key
        }

    /** Recently opened chats, newest first: id → title. */
    fun recentChats(): List<Pair<Long, String>> =
        prefs.getString("recent", "").orEmpty().split('\n').filter { it.isNotBlank() }.mapNotNull { line ->
            val id = line.substringBefore('\t').toLongOrNull() ?: return@mapNotNull null
            id to line.substringAfter('\t')
        }

    fun addRecent(id: Long, title: String) {
        val list = (listOf(id to title) + recentChats().filter { it.first != id }).take(8)
        prefs.edit { putString("recent", list.joinToString("\n") { "${it.first}\t${it.second.replace('\n', ' ')}" }) }
    }

    /** Watched channels (background check for new posts): id → title. */
    fun watched(): List<Pair<Long, String>> =
        prefs.getString("watched", "").orEmpty().split('\n').filter { it.isNotBlank() }.mapNotNull { line ->
            val id = line.substringBefore('\t').toLongOrNull() ?: return@mapNotNull null
            id to line.substringAfter('\t')
        }

    fun isWatched(id: Long) = watched().any { it.first == id }

    /** Starts watching [id]; posts up to [fromMessageId] count as already handled. */
    fun watch(id: Long, title: String, fromMessageId: Long) {
        val list = watched().filter { it.first != id } + (id to title)
        prefs.edit {
            putString("watched", list.joinToString("\n") { "${it.first}\t${it.second.replace('\n', ' ')}" })
            putLong("watch_mark_$id", fromMessageId)
        }
    }

    fun unwatch(id: Long) {
        val list = watched().filter { it.first != id }
        prefs.edit {
            putString("watched", list.joinToString("\n") { "${it.first}\t${it.second}" })
            remove("watch_mark_$id")
        }
    }

    /** Newest message id the watch has handled for [id]. */
    fun watchMark(id: Long): Long = prefs.getLong("watch_mark_$id", 0)
    fun setWatchMark(id: Long, messageId: Long) = prefs.edit { putLong("watch_mark_$id", messageId) }

    /** Newest message id seen per chat, to mark new posts. */
    fun lastSeen(chatId: Long): Long = prefs.getLong("seen_$chatId", 0)
    fun setLastSeen(chatId: Long, messageId: Long) = prefs.edit { putLong("seen_$chatId", messageId) }
}

/**
 * Thin coroutine wrapper around TDLib. Read-only use: we only log in, list chats and read history.
 * One instance per process; the TDLib client is created on first [start].
 */
class TelegramClient(private val context: Context, val prefs: TelegramPrefs) {
    private val _auth = MutableStateFlow<TgAuth>(if (prefs.configured) TgAuth.Starting else TgAuth.NotConfigured)
    val auth: StateFlow<TgAuth> = _auth

    private val _error = MutableStateFlow<String?>(null)
    /** Last error from a login step, for the UI. */
    val error: StateFlow<String?> = _error

    @Volatile private var client: Client? = null

    @Synchronized
    fun start() {
        if (client != null || !prefs.configured) return
        Client.execute(TdApi.SetLogVerbosityLevel(1))
        _auth.value = TgAuth.Starting
        client = Client.create({ onUpdate(it) }, null, null)
    }

    fun clearError() { _error.value = null }

    /** Starts TDLib if needed and waits until logged in. False if not logged in (or not within [timeoutMs]). */
    suspend fun awaitReady(timeoutMs: Long = 30_000): Boolean {
        start()
        val st = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
            auth.first { it !is TgAuth.Starting && it !is TgAuth.LoggingOut }
        }
        return st is TgAuth.Ready
    }

    private fun onUpdate(obj: TdApi.Object) {
        if (obj !is TdApi.UpdateAuthorizationState) return
        when (val st = obj.authorizationState) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> sendParameters()
            is TdApi.AuthorizationStateWaitPhoneNumber -> _auth.value = TgAuth.WaitPhone
            is TdApi.AuthorizationStateWaitCode -> _auth.value = TgAuth.WaitCode(describeCode(st.codeInfo))
            is TdApi.AuthorizationStateWaitPassword -> _auth.value = TgAuth.WaitPassword(st.passwordHint.orEmpty())
            is TdApi.AuthorizationStateReady -> _auth.value = TgAuth.Ready
            is TdApi.AuthorizationStateLoggingOut, is TdApi.AuthorizationStateClosing -> _auth.value = TgAuth.LoggingOut
            is TdApi.AuthorizationStateClosed -> {
                // After log out the client is closed; make a fresh one for the next login.
                synchronized(this) { client = null }
                if (prefs.configured) start() else _auth.value = TgAuth.NotConfigured
            }
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation,
            is TdApi.AuthorizationStateWaitRegistration,
            is TdApi.AuthorizationStateWaitEmailAddress,
            is TdApi.AuthorizationStateWaitEmailCode,
            is TdApi.AuthorizationStateWaitPremiumPurchase ->
                _auth.value = TgAuth.Unsupported(st.javaClass.simpleName.removePrefix("AuthorizationState"))
            else -> Unit
        }
    }

    private fun sendParameters() {
        val dir = File(context.filesDir, "tdlib").apply { mkdirs() }
        val p = TdApi.SetTdlibParameters().apply {
            useTestDc = false
            databaseDirectory = dir.absolutePath
            filesDirectory = File(dir, "files").absolutePath
            databaseEncryptionKey = prefs.databaseKey
            useFileDatabase = false
            useChatInfoDatabase = true
            useMessageDatabase = true
            useSecretChats = false
            apiId = prefs.apiId
            apiHash = prefs.apiHash
            systemLanguageCode = "en"
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
            systemVersion = "Android ${Build.VERSION.RELEASE}"
            applicationVersion = "Link Resolver ${BuildConfig.VERSION_NAME}"
        }
        client?.send(p) { r -> if (r is TdApi.Error) _error.value = "Telegram rejected the API ID/hash: ${r.message}" }
    }

    private fun describeCode(info: TdApi.AuthenticationCodeInfo?): String {
        val t = when (info?.type) {
            is TdApi.AuthenticationCodeTypeTelegramMessage -> "in Telegram (on your other devices)"
            is TdApi.AuthenticationCodeTypeSms -> "by SMS"
            is TdApi.AuthenticationCodeTypeCall -> "by phone call"
            else -> "to your account"
        }
        return "${info?.phoneNumber ?: ""} $t".trim()
    }

    /** Sends a TDLib request and suspends for its result. Throws [TdException] on an error result. */
    @Suppress("UNCHECKED_CAST")
    suspend fun <R : TdApi.Object> send(function: TdApi.Function<R>): R {
        val c = client ?: throw TdException(-1, "Telegram is not started")
        return suspendCancellableCoroutine { cont ->
            c.send(function) { r ->
                if (!cont.isActive) return@send
                if (r is TdApi.Error) cont.resumeWithException(TdException(r.code, r.message)) else cont.resume(r as R)
            }
        }
    }

    // ---- login

    fun configure(apiId: Int, apiHash: String) {
        prefs.saveCredentials(apiId, apiHash)
        start()
    }

    suspend fun submitPhone(phone: String) = step { send(TdApi.SetAuthenticationPhoneNumber(phone.trim(), TdApi.PhoneNumberAuthenticationSettings())) }
    suspend fun submitCode(code: String) = step { send(TdApi.CheckAuthenticationCode(code.trim())) }
    suspend fun submitPassword(password: String) = step { send(TdApi.CheckAuthenticationPassword(password)) }
    suspend fun logOut() = step { send(TdApi.LogOut()) }

    private suspend fun step(block: suspend () -> Unit) {
        _error.value = null
        try {
            block()
        } catch (e: TdException) {
            _error.value = e.message
        }
    }

    // ---- reading

    /** All chats in the main list (channels, groups, private chats), most recent first. */
    suspend fun chats(): List<TgChat> {
        // Make TDLib load the whole main list (it answers 404 once everything is loaded).
        repeat(20) {
            try {
                send(TdApi.LoadChats(TdApi.ChatListMain(), 100))
            } catch (e: TdException) {
                if (e.code == 404) return@repeat else throw e
            }
        }
        val ids = send(TdApi.GetChats(TdApi.ChatListMain(), 2000)).chatIds
        return ids.map { id ->
            val c = send(TdApi.GetChat(id))
            val kind = when (val t = c.type) {
                is TdApi.ChatTypeSupergroup -> if (t.isChannel) "Channel" else "Group"
                is TdApi.ChatTypeBasicGroup -> "Group"
                is TdApi.ChatTypePrivate -> "Private"
                else -> "Chat"
            }
            TgChat(c.id, c.title.ifBlank { "(no title)" }, kind, c.lastMessage?.date ?: 0)
        }
    }

    suspend fun chat(id: Long): TdApi.Chat = send(TdApi.GetChat(id))

    /**
     * Up to [count] messages older than [fromMessageId] (0 = newest), newest first. TDLib may return fewer per call,
     * so this keeps asking until it has [count] or the history ends.
     */
    suspend fun history(chatId: Long, fromMessageId: Long, count: Int): List<TdApi.Message> {
        val out = mutableListOf<TdApi.Message>()
        var from = fromMessageId
        var emptyInARow = 0
        while (out.size < count) {
            val page = send(TdApi.GetChatHistory(chatId, from, 0, minOf(100, count - out.size), false)).messages
            if (page.isEmpty()) {
                if (++emptyInARow >= 2) break // end of history
                continue
            }
            emptyInARow = 0
            out += page.filter { m -> out.none { it.id == m.id } }
            from = page.last().id
        }
        return out
    }
}
