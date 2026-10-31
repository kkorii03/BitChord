package com.music.bitchord.telegram

import android.content.Context
import android.os.Build
import com.music.bitchord.startup.AppForeground
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the TDLib client for the login stage of the Telegram source.
 *
 * Ported from Hexclyde/bitchord-morphe-patches (GPL-3.0-or-later) and reduced to
 * the pieces this stage needs: credentials, phone/code/2FA login and logout.
 * Unlike the upstream bridge, nothing is opened at app start; [TelegramClientGate]
 * decides from events whether the client should be running.
 */
object TelegramClientManager {

    private const val WORKER = "Telegram client"

    private val work = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, WORKER).apply { isDaemon = true }
    }

    private val _state = MutableStateFlow(TelegramState())
    val state: StateFlow<TelegramState> = _state.asStateFlow()

    private val gate = TelegramClientGate()
    private val generation = AtomicInteger()

    @Volatile
    private var context: Context? = null
    private var credentialStore: TelegramCredentialStore? = null
    private var databaseKeyStore: TelegramDatabaseKeyStore? = null
    private var home: File? = null

    private var credentials = TelegramApplicationCredentials.EMPTY
    private var credentialsLoaded = false
    private var appliedCredentials = TelegramApplicationCredentials.EMPTY

    private var client: Client? = null
    private var auth: TdApi.AuthorizationState? = null
    private var clientClosed: CompletableFuture<Unit>? = null
    private var closing = false
    private var paramsSent = false
    private var logoutRequested = false
    private var librariesLoaded = false

    /** Called once from Application.onCreate; it only installs listeners. */
    fun init(context: Context) {
        if (this.context != null) return
        this.context = context.applicationContext
        gate.onForeground(AppForeground.isForeground)
        AppForeground.onForeground { sync("foreground") }
        AppForeground.onBackground { sync("background") }
    }

    /** The settings screen holds the client only while it is open. */
    fun acquire(reason: String) {
        work.execute {
            ensureCredentialsLoaded()
            gate.acquire(reason)
            applyGate("open: $reason")
        }
    }

    fun release(reason: String) {
        work.execute {
            gate.release(reason)
            applyGate("close: $reason")
        }
    }

    /**
     * The Telegram source calls this when its own playback starts or stops.
     * Global playback (local, YouTube, add-ons) must not reach here: this stage
     * has no Telegram playback yet, so no caller is wired.
     */
    fun onTelegramPlayback(active: Boolean) {
        if (context == null) return
        work.execute {
            gate.onTelegramPlaybackActive(active)
            applyGate(if (active) "telegram playback started" else "telegram playback stopped")
        }
    }

    fun saveCredentials(apiId: String, apiHash: String) {
        work.execute {
            val next = try {
                TelegramApplicationCredentials.parse(apiId, apiHash)
            } catch (_: IllegalArgumentException) {
                update { it.copy(error = TelegramError.INVALID_CREDENTIALS) }
                return@execute
            }
            val store = credentialStore ?: return@execute
            val changed = credentials != next
            try {
                store.save(next)
            } catch (_: Throwable) {
                update { it.copy(error = TelegramError.GENERIC) }
                return@execute
            }
            credentials = next
            update { it.copy(configured = true, error = null) }
            if (client != null && changed && auth !is TdApi.AuthorizationStateWaitTdlibParameters) {
                closeNow("credentials changed")
            }
            if (client != null && auth is TdApi.AuthorizationStateWaitTdlibParameters) configure()
            applyGate("credentials saved")
        }
    }

    fun submitPhone(number: String) = submit(number.trim()) { TdApi.SetAuthenticationPhoneNumber(it, null) }

    fun submitCode(code: String) = submit(code.trim()) { TdApi.CheckAuthenticationCode(it) }

    fun submitPassword(password: String) = submit(password) { TdApi.CheckAuthenticationPassword(it) }

    fun logOut() {
        work.execute {
            ensureCredentialsLoaded()
            val target = client
            if (target == null) {
                deleteSession()
                update {
                    it.copy(
                        stage = TelegramAuthStage.CLOSED,
                        clientOpen = false,
                        accountName = null,
                        error = null,
                    )
                }
                applyGate("logged out")
                return@execute
            }
            logoutRequested = true
            update { it.copy(stage = TelegramAuthStage.CLOSING, error = null) }
            TelegramLog.authState("loggingOut")
            val sent = try {
                call(target, TdApi.LogOut())
                true
            } catch (_: Throwable) {
                // Closing the client below is still the right cleanup.
                false
            }
            if (!sent) runCatching { target.send(TdApi.Close(), null) }
            val closed = clientClosed
            val confirmed = runCatching { closed?.get(20, TimeUnit.SECONDS) }.isSuccess
            if (confirmed) {
                // Only drop the native handle and delete the session once TDLib
                // has confirmed Closed. Deleting under an open client corrupts it.
                finishClosed(deleteLocalSession = true)
                TelegramLog.authState("closed")
            } else {
                // Never delete files under an unconfirmed close. Drop the stale
                // handle and generation after the timeout so startClient is not
                // latched shut forever; a retry can then reset the session.
                resetAfterCloseTimeout("logout")
            }
            applyGate("logged out")
        }
    }

    /**
     * Explicit recovery for a session that cannot be opened (for example a
     * database key that no longer decrypts). The client is closed first, then
     * db/files and only after that the key are removed, so the next open mints
     * a fresh key for a session that no longer exists.
     */
    fun resetSession() {
        work.execute {
            ensureCredentialsLoaded()
            val target = client
            if (target != null && !closeNow("session reset")) {
                // The close timed out; files stay in place for a later retry.
                applyGate("session reset deferred")
                return@execute
            }
            deleteSession()
            logoutRequested = false
            update {
                it.copy(
                    stage = TelegramAuthStage.CLOSED,
                    clientOpen = false,
                    accountName = null,
                    error = null,
                )
            }
            TelegramLog.authState("session reset")
            applyGate("session reset")
        }
    }

    private fun <T : TdApi.Object> submit(
        value: String,
        request: (String) -> TdApi.Function<T>,
    ) {
        work.execute {
            val target = client ?: return@execute
            update { it.copy(error = null) }
            try {
                call(target, request(value))
            } catch (e: Throwable) {
                update { it.copy(error = mapError(e)) }
            }
        }
    }

    private fun sync(reason: String) {
        if (context == null) return
        work.execute {
            gate.onForeground(AppForeground.isForeground)
            applyGate(reason)
        }
    }

    /** Runs on [work] only. */
    private fun applyGate(reason: String) {
        if (gate.shouldBeOpen) {
            if (client == null) {
                val reasons = gate.openReasons.toMutableList()
                if (gate.telegramPlaybackActive) reasons += "telegram playback"
                startClient(reasons.joinToString(","))
            }
        } else {
            if (client != null) closeNow(reason)
        }
    }

    /** Runs on [work] only. */
    private fun startClient(reason: String) {
        if (client != null || closing) return
        val app = context ?: return
        ensureCredentialsLoaded()
        if (!ensureLibraries()) return
        val current = generation.incrementAndGet()
        val closed = CompletableFuture<Unit>()
        clientClosed = closed
        auth = null
        paramsSent = false
        closing = false
        appliedCredentials = TelegramApplicationCredentials.EMPTY
        logoutRequested = false
        try {
            val created = Client.create(
                { obj ->
                    if (obj is TdApi.UpdateAuthorizationState &&
                        obj.authorizationState is TdApi.AuthorizationStateClosed
                    ) {
                        closed.complete(Unit)
                    }
                    if (current == generation.get()) {
                        // TDLib callbacks arrive on their own thread. Serialize all
                        // auth/state mutations through the worker; re-check the
                        // generation there so a stale client cannot touch a newer one.
                        work.execute {
                            if (current == generation.get()) onUpdate(obj)
                        }
                    }
                },
                { TelegramLog.authState("updateFailed") },
                { TelegramLog.authState("callbackFailed") },
            )
            client = created
            update {
                it.copy(
                    stage = if (credentials.configured) {
                        TelegramAuthStage.STARTING
                    } else {
                        TelegramAuthStage.WAIT_CREDENTIALS
                    },
                    configured = credentials.configured,
                    clientOpen = true,
                    accountName = null,
                    error = null,
                )
            }
            TelegramLog.clientOpened(reason)
        } catch (_: Throwable) {
            client = null
            update {
                it.copy(
                    stage = TelegramAuthStage.CLOSED,
                    clientOpen = false,
                    error = TelegramError.GENERIC,
                )
            }
        }
    }

    /**
     * Sends Close and waits for TDLib's Closed confirmation. Returns true only
     * when the native client is really gone. On timeout the session files stay
     * in place so nothing is deleted under an open client, but the stale handle
     * and its generation are dropped: otherwise `closing`/`client` would latch
     * [startClient] shut forever after one hung Close. Stale callbacks from the
     * abandoned client fail the generation check.
     *
     * Runs on [work] only.
     */
    private fun closeNow(reason: String): Boolean {
        val target = client ?: return true
        if (!closing) {
            closing = true
            auth = null
            paramsSent = false
            TelegramLog.clientClosed(reason)
            update {
                it.copy(
                    stage = TelegramAuthStage.CLOSED,
                    clientOpen = false,
                    accountName = null,
                )
            }
        }
        val closed = clientClosed
        runCatching { target.send(TdApi.Close(), null) }
        val confirmed = runCatching { closed?.get(5, TimeUnit.SECONDS) }.isSuccess
        if (confirmed) {
            finishClosed(deleteLocalSession = logoutRequested)
            return true
        }
        resetAfterCloseTimeout(reason)
        return false
    }

    /**
     * Drops a client that did not confirm Closed in time without touching its
     * files. Bumping the generation makes queued updates from that client
     * unusable, and clearing [closing]/[client] lets a later gate event start
     * again instead of staying latched. Runs on [work] only.
     */
    private fun resetAfterCloseTimeout(reason: String) {
        generation.incrementAndGet()
        client = null
        clientClosed = null
        closing = false
        auth = null
        paramsSent = false
        appliedCredentials = TelegramApplicationCredentials.EMPTY
        logoutRequested = false
        TelegramLog.clientClosed("$reason timeout")
        update {
            it.copy(
                stage = TelegramAuthStage.CLOSED,
                clientOpen = false,
                accountName = null,
                error = TelegramError.GENERIC,
            )
        }
    }

    /**
     * Drops the native handle after TDLib confirmed Closed, and optionally
     * clears the local session. Runs on [work] only, never while the client is open.
     */
    private fun finishClosed(deleteLocalSession: Boolean) {
        // Advance first: any update already queued from this client must be
        // dropped, or a trailing Closing could overwrite CLOSED and re-enter
        // auth handlers after cleanup.
        generation.incrementAndGet()
        client = null
        clientClosed = null
        closing = false
        auth = null
        paramsSent = false
        appliedCredentials = TelegramApplicationCredentials.EMPTY
        if (deleteLocalSession) deleteSession()
        logoutRequested = false
        update {
            it.copy(
                stage = TelegramAuthStage.CLOSED,
                clientOpen = false,
                accountName = null,
                error = null,
            )
        }
    }

    private fun ensureLibraries(): Boolean {
        if (librariesLoaded) return true
        return try {
            // Explicit order also resolves OpenSSL and the C++ runtime dependencies.
            System.loadLibrary("c++_shared")
            System.loadLibrary("cryptox")
            System.loadLibrary("sslx")
            System.loadLibrary("tdjni")
            Client.execute(TdApi.SetLogVerbosityLevel(0))
            librariesLoaded = true
            true
        } catch (_: Throwable) {
            update { it.copy(error = TelegramError.GENERIC) }
            false
        }
    }

    private fun ensureCredentialsLoaded() {
        if (credentialsLoaded) return
        val app = context ?: return
        val dir = File(app.noBackupFilesDir, "telegram-native")
        home = dir
        credentialStore = TelegramCredentialStore(app)
        databaseKeyStore = TelegramDatabaseKeyStore(
            File(dir, "database.key.sealed"),
            { TelegramKeystore.key(app, "database") },
        ) { File(dir, "db").exists() || File(dir, "files").exists() }
        credentials = try {
            credentialStore?.load() ?: TelegramApplicationCredentials.EMPTY
        } catch (_: Throwable) {
            TelegramApplicationCredentials.EMPTY
        }
        credentialsLoaded = true
        update { it.copy(configured = credentials.configured) }
    }

    private fun onUpdate(obj: TdApi.Object) {
        if (obj !is TdApi.UpdateAuthorizationState) return
        val next = obj.authorizationState
        auth = next
        TelegramLog.authState(authName(next))
        when (next) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                update {
                    it.copy(
                        stage = if (credentials.configured) {
                            TelegramAuthStage.STARTING
                        } else {
                            TelegramAuthStage.WAIT_CREDENTIALS
                        },
                        error = null,
                    )
                }
                configure()
            }

            is TdApi.AuthorizationStateWaitPhoneNumber -> update {
                it.copy(stage = TelegramAuthStage.WAIT_PHONE, error = null)
            }

            is TdApi.AuthorizationStateWaitCode -> update {
                it.copy(stage = TelegramAuthStage.WAIT_CODE, error = null)
            }

            is TdApi.AuthorizationStateWaitPassword -> update {
                it.copy(stage = TelegramAuthStage.WAIT_PASSWORD, error = null)
            }

            is TdApi.AuthorizationStateReady -> {
                update {
                    it.copy(stage = TelegramAuthStage.READY, error = null)
                }
                loadAccountName()
            }

            is TdApi.AuthorizationStateLoggingOut,
            is TdApi.AuthorizationStateClosing,
            -> update { it.copy(stage = TelegramAuthStage.CLOSING) }

            is TdApi.AuthorizationStateClosed -> {
                finishClosed(deleteLocalSession = logoutRequested)
                TelegramLog.authState("closed")
                applyGate("client closed")
            }

            else -> update {
                it.copy(stage = TelegramAuthStage.UNSUPPORTED, error = TelegramError.UNSUPPORTED)
            }
        }
    }

    /** Runs on [work] only. */
    private fun configure() {
        val target = client ?: return
        if (auth !is TdApi.AuthorizationStateWaitTdlibParameters || paramsSent) return
        val applying = credentials
        if (!applying.configured) {
            update { it.copy(stage = TelegramAuthStage.WAIT_CREDENTIALS) }
            return
        }
        val app = context ?: return
        val dir = home ?: File(app.noBackupFilesDir, "telegram-native")
        val databaseKey = try {
            databaseKeyStore?.loadOrCreate()
                ?: throw IllegalStateException("Telegram storage is not ready.")
        } catch (_: Throwable) {
            // A Keystore or disk failure must become a visible error, not an
            // uncaught worker exception that can take the process down.
            update { it.copy(error = TelegramError.GENERIC) }
            return
        }
        paramsSent = true
        try {
            call(
                target,
                TdApi.SetTdlibParameters(
                    false,
                    File(dir, "db").absolutePath,
                    File(dir, "files").absolutePath,
                    databaseKey,
                    true,
                    true,
                    true,
                    false,
                    applying.apiId,
                    applying.apiHash,
                    Locale.getDefault().toLanguageTag(),
                    Build.MODEL,
                    Build.VERSION.RELEASE,
                    "BitChord",
                ),
            )
            databaseKey.fill(0)
            appliedCredentials = applying
            update { it.copy(stage = TelegramAuthStage.STARTING, error = null) }
        } catch (e: Throwable) {
            databaseKey.fill(0)
            paramsSent = false
            update { it.copy(error = mapError(e)) }
        }
    }

    /** Runs on [work] only. */
    private fun loadAccountName() {
        val target = client ?: return
        try {
            val me = call(target, TdApi.GetMe())
            val name = listOf(me.firstName, me.lastName)
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .ifBlank { me.usernames?.editableUsername?.takeIf { it.isNotBlank() }?.let { "@$it" }.orEmpty() }
            update { it.copy(accountName = name.ifBlank { null }) }
        } catch (_: Throwable) {
            // The login still succeeded; a missing display name is not an error.
        }
    }

    private fun deleteSession() {
        val dir = home ?: return
        val database = File(dir, "db")
        val files = File(dir, "files")
        runCatching { database.deleteRecursively() }
        runCatching { files.deleteRecursively() }
        // Keep the key if anything survived: a keyless leftover session could
        // never be opened again.
        if (!database.exists() && !files.exists()) databaseKeyStore?.clear()
    }

    private fun <T : TdApi.Object> call(target: Client, fn: TdApi.Function<T>, timeoutMs: Long = 40_000): T {
        val future = CompletableFuture<T>()
        target.send(fn, { obj ->
            if (obj is TdApi.Error) {
                future.completeExceptionally(TelegramFailure(obj))
            } else {
                @Suppress("UNCHECKED_CAST")
                future.complete(obj as T)
            }
        }, null)
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: ExecutionException) {
            val cause = e.cause
            if (cause is Throwable) throw cause
            throw e
        } catch (e: TimeoutException) {
            throw IllegalStateException("Telegram request timed out.", e)
        }
    }

    private fun mapError(error: Throwable): TelegramError {
        if (error !is TelegramFailure) return TelegramError.GENERIC
        val message = error.error.message.orEmpty().uppercase(Locale.ROOT)
        return when {
            error.error.code == 429 || message.contains("FLOOD_WAIT") -> TelegramError.RATE_LIMITED
            message.contains("PHONE_NUMBER_INVALID") -> TelegramError.INVALID_PHONE
            message.contains("PHONE_CODE_EXPIRED") -> TelegramError.CODE_EXPIRED
            message.contains("PHONE_CODE_INVALID") ||
                message.contains("PHONE_CODE_EMPTY") -> TelegramError.INVALID_CODE
            message.contains("PASSWORD_HASH_INVALID") -> TelegramError.INVALID_PASSWORD
            message.contains("UNSUPPORTED") -> TelegramError.UNSUPPORTED
            else -> TelegramError.GENERIC
        }
    }

    private fun authName(value: TdApi.AuthorizationState): String = when (value) {
        is TdApi.AuthorizationStateWaitTdlibParameters -> "waitTdlibParameters"
        is TdApi.AuthorizationStateWaitPhoneNumber -> "waitPhoneNumber"
        is TdApi.AuthorizationStateWaitCode -> "waitCode"
        is TdApi.AuthorizationStateWaitPassword -> "waitPassword"
        is TdApi.AuthorizationStateReady -> "ready"
        is TdApi.AuthorizationStateLoggingOut -> "loggingOut"
        is TdApi.AuthorizationStateClosing -> "closing"
        is TdApi.AuthorizationStateClosed -> "closed"
        else -> value.javaClass.simpleName.replace("AuthorizationState", "").replaceFirstChar {
            it.lowercase(Locale.ROOT)
        }
    }

    private fun update(transform: (TelegramState) -> TelegramState) {
        _state.update(transform)
    }

    private class TelegramFailure(val error: TdApi.Error) : java.io.IOException() {
        override val message: String get() = "Telegram ${error.code}"
    }
}
