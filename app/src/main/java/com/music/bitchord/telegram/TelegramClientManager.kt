package com.music.bitchord.telegram

import android.content.Context
import android.os.Build
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.startup.AppForeground
import com.music.bitchord.startup.StartupWork
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.ConcurrentHashMap
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

    /** Chats search may look in, as the settings screen toggles them. */
    private val _selectedChats = MutableStateFlow<Set<Long>>(emptySet())
    val selectedChats: StateFlow<Set<Long>> = _selectedChats.asStateFlow()

    private val gate = TelegramClientGate()
    private val generation = AtomicInteger()

    /**
     * Identical searches already running are shared, so a second track using
     * the same query does not open a second TDLib search. Completed answers
     * live for [SEARCH_REUSE_NANOS] only; this is not a catalogue cache.
     */
    private val searchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val searchFlights = ConcurrentHashMap<SearchKey, SearchFlight>()
    private val recentSearches = ConcurrentHashMap<SearchKey, RecentSearch>()

    /**
     * Test seam: lets the search paths run against a fake TDLib transport
     * without opening the native client. Both hooks are null in production.
     */
    @Volatile
    private var awaitReadyForTest: (suspend () -> Unit)? = null

    @Volatile
    private var requestForTest: ((TdApi.Function<*>, Long) -> TdApi.Object)? = null

    private data class SearchKey(val chats: List<Long>, val query: String, val limit: Int)
    private class RecentSearch(val result: SearchResult, val atNanos: Long)
    private class SearchResult(val tracks: List<TelegramTrack>, val mode: String)
    private class GlobalSearch(
        val tracks: List<TelegramTrack>,
        val exhausted: Boolean,
    )

    private class SearchFlight(val key: SearchKey, val deferred: CompletableDeferred<SearchResult>) {
        var job: Job? = null
        var waiters: Int = 0
        @Volatile var abandoned: Boolean = false
    }

    @Volatile
    private var context: Context? = null
    private var credentialStore: TelegramCredentialStore? = null
    private var databaseKeyStore: TelegramDatabaseKeyStore? = null
    private var chatSelection: TelegramChatSelection? = null
    private var sessionMarker: TelegramSessionMarker? = null
    @Volatile
    private var selectionLoaded = false
    @Volatile
    private var sessionState: TelegramSessionMarkerState? = null
    private val selectionLock = Any()
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

    /** True only while this process has seen an actual phone/code/password login. */
    private var interactiveLogin = false

    /** Called once from Application.onCreate; it only installs listeners. */
    fun init(context: Context) {
        if (this.context != null) return
        this.context = context.applicationContext
        // Cheap presence check only: the encrypted credential file is not
        // decrypted and no client is opened at startup. It lets Settings say
        // "connects when you search" instead of wrongly claiming a login is
        // missing for a session that is still on disk.
        if (File(context.noBackupFilesDir, "telegram-native/application.sealed").isFile) {
            _state.update { it.copy(configured = true) }
        }
        // The selected chats decide whether an automatic upgrade has anything
        // to search. Reading them at start is a small encrypted-prefs read, not
        // a TDLib connection: the client still opens only for a request.
        StartupWork.background("telegram-chats") {
            ensureChatSelectionLoaded()
            ensureSessionMarkerLoaded()
        }
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
     * Global playback (local, YouTube, add-ons) must not reach here. Streams,
     * metadata lookups and searches use the counted
     * [beginStream]/[streamMetadata]/[searchTracks] leases instead, so they
     * cannot close one another.
     */
    fun onTelegramPlayback(active: Boolean) {
        if (context == null) return
        work.execute {
            gate.onTelegramPlaybackActive(active)
            applyGate(if (active) "telegram playback started" else "telegram playback stopped")
        }
    }

    /**
     * A Telegram stream has opened. The first one also keeps the client alive
     * in the background; the last close gives it back, all as events.
     */
    fun beginStream(trackId: String) {
        if (context == null) return
        // Synchronous so the reader that is about to ask for bytes cannot get
        // ahead of the gate that keeps the client open for them.
        runOnWorker {
            acquireStreamLease("stream open")
        }
    }

    fun endStream(trackId: String) {
        if (context == null) return
        runOnWorker {
            releaseStreamLease("stream close")
        }
    }

    /** Runs on [work]. A metadata lookup is a lease just like an open stream. */
    private fun acquireStreamLease(reason: String) =
        acquireCountedLease(reason) { gate.beginStreamLease() }

    /** Runs on [work]. Only the last lease releases playback and the holder. */
    private fun releaseStreamLease(reason: String) =
        releaseCountedLease(reason) { gate.endStreamLease() }

    /** Runs on [work]. A search shares the counted lease with streams. */
    private fun acquireSearchLease(reason: String) =
        acquireCountedLease(reason) { gate.beginSearchLease() }

    /** Runs on [work]. One finished search must not close another's client. */
    private fun releaseSearchLease(reason: String) =
        releaseCountedLease(reason) { gate.endSearchLease() }

    /** Runs on [work]. The first lease opens; shared by stream, metadata and search. */
    private fun acquireCountedLease(reason: String, begin: () -> Boolean) {
        val first = begin()
        if (first) gate.acquire(STREAM_HOLDER)
        applyGate(reason)
    }

    /** Runs on [work]. Only the last lease releases playback and the holder. */
    private fun releaseCountedLease(reason: String, end: () -> Boolean) {
        val last = end()
        if (last) gate.release(STREAM_HOLDER)
        applyGate(reason)
    }

    private fun runOnWorker(block: () -> Unit) {
        val done = CompletableFuture<Unit>()
        work.execute {
            runCatching(block)
                .onSuccess { done.complete(Unit) }
                .onFailure { done.completeExceptionally(it) }
        }
        runCatching { done.get(10, TimeUnit.SECONDS) }
    }

    /** The chats search may look in. */
    fun selectedChatIds(): Set<Long> = _selectedChats.value

    fun setChatSelected(chatId: Long, selected: Boolean) {
        ensureCredentialsLoaded()
        synchronized(selectionLock) {
            val next = if (selected) _selectedChats.value + chatId else _selectedChats.value - chatId
            _selectedChats.value = next
            runCatching { chatSelection?.save(next) }
        }
        // The listener just changed what Telegram should look at, so a rest
        // earned by the old selection must not keep the new one switched off.
        clearTelegramRest()
    }

    fun setSelectedChatIds(ids: Set<Long>) {
        ensureCredentialsLoaded()
        synchronized(selectionLock) {
            _selectedChats.value = ids
            runCatching { chatSelection?.save(ids) }
        }
        clearTelegramRest()
    }

    /**
     * A Telegram settings change is new evidence: the old selection's rest
     * says nothing about the new one. Reads the already-published registry
     * list (no disk open) so this can run from the settings UI thread.
     */
    private fun clearTelegramRest() {
        SourceRegistry.configs.value
            .filter { it.kind == SourceKind.TELEGRAM }
            .forEach {
                SourceResolver.clearCooldown(it.id)
                // The old selection's empty answers must not keep the new
                // selection from being asked for up to SEARCH_MEMO_TTL_MS.
                SourceResolver.clearSearchAnswersForSource(it.id)
            }
        recentSearches.clear()
    }

    /** Reads the chosen chats off disk once; it neither decrypts credentials nor opens TDLib. */
    private fun ensureChatSelectionLoaded() {
        if (selectionLoaded) return
        synchronized(selectionLock) {
            if (selectionLoaded) return
            val app = context ?: return
            val selection = chatSelection ?: TelegramChatSelection(app).also { chatSelection = it }
            _selectedChats.value = runCatching { selection.load() }.getOrNull().orEmpty()
            selectionLoaded = true
        }
    }

    /**
     * Whether a login has reached `Ready` in this account, persisted across
     * launches. Read by [TelegramUpgradeGate] so a resting `CLOSED` client is
     * not mistaken for a signed-in one before the first login.
     */
    fun hasValidatedSession(): Boolean {
        ensureSessionMarkerLoaded()
        return synchronized(selectionLock) {
            sessionState?.isValidated() ?: false
        }
    }

    /**
     * Prepares the validated-session marker view without opening TDLib. The
     * first read or write loads it off disk; every later one reuses that value.
     */
    private fun ensureSessionMarkerLoaded() {
        if (sessionState != null) return
        synchronized(selectionLock) {
            if (sessionState != null) return
            val app = context ?: return
            val marker = sessionMarker ?: TelegramSessionMarker(app).also { sessionMarker = it }
            sessionState = TelegramSessionMarkerState(
                lock = selectionLock,
                load = { runCatching { marker.load() }.getOrDefault(false) },
                mark = { runCatching { marker.markValidated() } },
                clearPersisted = { runCatching { marker.clear() } },
            )
        }
    }

    /** Records that TDLib reported `Ready` for this account. */
    private fun markSessionValidated() {
        ensureSessionMarkerLoaded()
        synchronized(selectionLock) {
            sessionState?.markValidated()
        }
    }

    /** Forgets the login: logout, session reset, or a session that fell back to phone login. */
    private fun clearValidatedSession() {
        ensureSessionMarkerLoaded()
        synchronized(selectionLock) {
            sessionState?.clear()
        }
    }

    /**
     * Searches the selected chats for [query].
     *
     * There is no index and no background pass: this asks TDLib only because
     * someone submitted a search, opens the client for that request (through
     * [TelegramClientGate]) and releases it afterwards. The request holds the
     * same counted lease as [streamMetadata], so a screen-off queue transition
     * keeps the client open and concurrent searches cannot close one another.
     *
     * One global TDLib search per filter covers every chat, and its rows are
     * then kept only for the selected chats. The old per-chat loop cost
     * 6–12 seconds for fourteen chats; a global search pages through TDLib's
     * `nextOffset` until the selected chats have enough rows or the result is
     * exhausted. If that path fails, returns no selected rows while other
     * chats match, or hits the page cap, the per-chat search is used as a
     * bounded (four-at-a-time) fallback, not as the normal path.
     *
     * Identical searches in flight share one result; a just-finished answer is
     * reused for [SEARCH_REUSE_NANOS], because the same query arrives again
     * from the next track's upgrade pass. A flight nobody is waiting for any
     * more (every caller timed out) is cancelled so it cannot keep the lease,
     * the radio or TDLib busy.
     */
    suspend fun searchTracks(query: String, limit: Int): List<TelegramTrack> {
        // The credential store and Keystore are disk work. Load them on the
        // worker so the first search from the UI thread never does that I/O
        // itself, and read the selected chats only after they are loaded.
        withContext(Dispatchers.IO) { runOnWorker { ensureCredentialsLoaded() } }
        val chats = selectedChatIds().toList().sorted()
        if (chats.isEmpty()) return emptyList()
        val wanted = query.trim()
        if (wanted.isEmpty()) return emptyList()
        val key = SearchKey(chats, wanted, limit)
        recentSearches[key]?.let { recent ->
            if (System.nanoTime() - recent.atNanos < SEARCH_REUSE_NANOS) return recent.result.tracks
            recentSearches.remove(key, recent)
        }
        return awaitFlight(key).tracks
    }

    private suspend fun awaitFlight(key: SearchKey): SearchResult {
        while (true) {
            val flight = searchFlight(key)
            val joined = synchronized(flight) {
                if (flight.abandoned) {
                    false
                } else {
                    flight.waiters++
                    true
                }
            }
            if (!joined) {
                if (flight.abandoned) continue
                return flight.deferred.await()
            }
            try {
                return flight.deferred.await()
            } finally {
                leaveFlight(flight)
            }
        }
    }

    private fun searchFlight(key: SearchKey): SearchFlight {
        while (true) {
            val flight = searchFlights.compute(key) { _, existing ->
                existing?.takeUnless { it.abandoned }
                    ?: SearchFlight(key, CompletableDeferred())
            }!!
            if (flight.abandoned) continue
            synchronized(flight) {
                if (flight.job == null) {
                    flight.job = searchScope.launch(start = CoroutineStart.LAZY) {
                        runFlight(flight)
                    }
                    flight.job?.start()
                }
            }
            return flight
        }
    }

    private suspend fun runFlight(flight: SearchFlight) {
        try {
            val result = runSearch(flight.key)
            val now = System.nanoTime()
            recentSearches[flight.key] = RecentSearch(result, now)
            if (recentSearches.size > SEARCH_REUSE_MAX) {
                recentSearches.entries.removeIf {
                    now - it.value.atNanos >= SEARCH_REUSE_NANOS
                }
            }
            flight.deferred.complete(result)
        } catch (failure: Throwable) {
            flight.deferred.completeExceptionally(failure)
        } finally {
            searchFlights.remove(flight.key, flight)
        }
    }

    private fun leaveFlight(flight: SearchFlight) {
        val abandon = synchronized(flight) {
            if (flight.waiters > 0) flight.waiters--
            if (flight.waiters == 0 && !flight.deferred.isCompleted) {
                flight.abandoned = true
                true
            } else {
                false
            }
        }
        if (abandon) {
            searchFlights.remove(flight.key, flight)
            flight.job?.cancel(CancellationException("Telegram search abandoned"))
        }
    }

    private suspend fun runSearch(key: SearchKey): SearchResult {
        // Callers include main-thread prefetch: the wait for the worker happens
        // on IO, never on the caller's thread. The release must still run when
        // the search is cancelled, or the lease (and the client) would leak.
        // Not cancellable: a lease taken by the worker must reach the finally below.
        withContext(NonCancellable + Dispatchers.IO) { runOnWorker { acquireSearchLease("search open") } }
        val started = System.nanoTime()
        return try {
            awaitReady()
            val result = withContext(Dispatchers.IO) { searchSelected(key.chats, key.query, key.limit) }
            val millis = (System.nanoTime() - started) / 1_000_000
            TelegramLog.search(
                queryLength = key.query.length,
                chats = key.chats.size,
                results = result.tracks.size,
                millis = millis,
                mode = result.mode,
            )
            result
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runOnWorker { releaseSearchLease("search close") }
            }
        }
    }

    /**
     * Installs a fake transport for search tests, or clears it when both
     * values are null. Never used by production code.
     */
    internal fun installSearchTestHooks(
        awaitReady: (suspend () -> Unit)? = null,
        request: ((TdApi.Function<*>, Long) -> TdApi.Object)? = null,
    ) {
        awaitReadyForTest = awaitReady
        requestForTest = request
        if (awaitReady == null && request == null) {
            recentSearches.clear()
            searchFlights.clear()
        }
    }

    /**
     * Test seam: whether the gate still holds a client for the searches in
     * flight. A fake transport uses this to behave like the real [request],
     * which throws once the last lease closed the client.
     */
    internal fun searchGateOpenForTest(): Boolean = gate.shouldBeOpen

    /**
     * The account's chats, for the selection screen. Read only when that
     * screen asks; names are never written to the log.
     */
    suspend fun chats(): List<TelegramChat> {
        acquire(CHATS_HOLDER)
        return try {
            awaitReady()
            withContext(Dispatchers.IO) { loadChats() }
        } finally {
            release(CHATS_HOLDER)
        }
    }

    /**
     * The file details [TelegramSource][com.music.bitchord.data.sources.TelegramSource]
     * needs to describe a stream, resolved while holding the gate.
     *
     * A search has already released its client when its rows are played, so
     * looking this up without a lease finds no client. This takes the same
     * counted lease as [beginStream]: preparing a queue transition must work
     * while the screen is off, and concurrent lookups must not close the
     * client under one another. The lease ends before the DataSource opens;
     * [beginStream] takes its own and waits for the session again.
     */
    suspend fun streamMetadata(trackId: String): TelegramTrack {
        // Callers include main-thread prefetch: the wait for the worker happens
        // on IO, never on the caller's thread. The release must still run when
        // the lookup is cancelled, or the lease (and the client) would leak.
        // Not cancellable: a lease taken by the worker must reach the finally below.
        withContext(NonCancellable + Dispatchers.IO) { runOnWorker { acquireStreamLease("stream metadata open") } }
        return try {
            awaitReady()
            withContext(Dispatchers.IO) { trackForStreamWithRetry(trackId) }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                runOnWorker { releaseStreamLease("stream metadata close") }
            }
        }
    }

    /**
     * A deleted message, a channel the account left, or an expired file
     * reference all arrive as an [IOException]. Re-reading the message once is
     * what refreshes a file reference; when the message is really gone the
     * second failure is the clean one, never an endless wait.
     */
    private fun trackForStreamWithRetry(trackId: String): TelegramTrack =
        try {
            trackForStream(trackId)
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            if (failure !is IOException) throw failure
            trackForStream(trackId)
        }

    /** A file's current details for playback, re-checked against the selected chats. */
    internal fun trackForStream(trackId: String): TelegramTrack {
        val (chatId, messageId) = TelegramMessageMapper.parseId(trackId)
            ?: throw IOException("Invalid Telegram track id.")
        if (chatId !in selectedChatIds()) throw IOException("The Telegram music chat was deselected.")
        val chat = request(TdApi.GetChat(chatId), 15_000)
        if (!TelegramMessageMapper.chatAllowed(chat)) {
            throw IOException("Telegram does not allow this chat's files to be played.")
        }
        val message = request(TdApi.GetMessage(chatId, messageId), 15_000)
        if (message.chatId != chatId || message.id != messageId) {
            throw IOException("The Telegram message is no longer available.")
        }
        val properties = request(TdApi.GetMessageProperties(chatId, messageId), 15_000)
        if (properties == null || !properties.canBeSaved) {
            throw IOException("Telegram does not allow this message to be saved.")
        }
        return TelegramMessageMapper.from(message)
            ?: throw IOException("The Telegram file is no longer available.")
    }

    /**
     * Stops the download this process owns for [fileId]. Used when the last
     * reader closes, before its stream lease is released, so a background
     * range read cannot keep the radio busy after playback moved on.
     */
    internal fun cancelDownload(fileId: Int) {
        val target = client ?: return
        runCatching { target.send(TdApi.CancelDownloadFile(fileId, false), null) }
    }

    /** One TDLib request from a caller already holding this client (search or a stream). */
    internal fun <T : TdApi.Object> request(
        function: TdApi.Function<T>,
        timeoutMs: Long = 40_000,
    ): T {
        requestForTest?.let { fake ->
            @Suppress("UNCHECKED_CAST")
            return fake(function, timeoutMs) as T
        }
        val target = client ?: throw IOException("The Telegram client is not open.")
        return call(target, function, timeoutMs)
    }

    internal suspend fun awaitReady() {
        awaitReadyForTest?.let { it(); return }
        if (_state.value.stage == TelegramAuthStage.READY) return
        val reached = withTimeoutOrNull(45_000) {
            _state.first { state ->
                state.stage == TelegramAuthStage.READY ||
                    state.error != null ||
                    state.stage == TelegramAuthStage.WAIT_CREDENTIALS ||
                    state.stage == TelegramAuthStage.WAIT_PHONE ||
                    state.stage == TelegramAuthStage.WAIT_CODE ||
                    state.stage == TelegramAuthStage.WAIT_PASSWORD ||
                    state.stage == TelegramAuthStage.UNSUPPORTED
            }
        }
        if (reached?.stage != TelegramAuthStage.READY) {
            throw IOException("Telegram is not signed in.")
        }
    }

    /** Runs on IO, with the client held by a search. */
    private suspend fun searchSelected(
        chats: List<Long>,
        query: String,
        limit: Int,
    ): SearchResult {
        val wanted = query.trim()
        if (wanted.isEmpty()) return SearchResult(emptyList(), "global")
        val global = try {
            searchGlobal(chats.toSet(), wanted, limit)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (globalFailure: Throwable) {
            // A TDLib build without the global search, or one rejected request,
            // falls back to the selected chats directly. Still bounded to four
            // in-flight requests instead of the old one-at-a-time loop.
            return searchFallback(chats, wanted, limit, globalFailure)
        }
        // A global page is not the whole answer: selected chats can fill the
        // page while a selected chat's matches sit behind nextOffset. If the
        // page cap stopped us before TDLib exhausted the result, ask the
        // selected chats directly instead of reporting a partial answer. Once
        // TDLib says it is exhausted, no per-chat request can find more.
        // A full page is already the whole answer the caller asked for. The
        // per-chat rows are added to what the global pass found, never in its
        // place, and a failed walk keeps the global rows.
        if (!global.exhausted && global.tracks.size < limit) {
            val extra = try {
                searchFallback(chats, wanted, limit, null).tracks
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (global.tracks.isEmpty()) throw failure
                emptyList()
            }
            val merged = LinkedHashMap<String, TelegramTrack>()
            (global.tracks + extra).forEach { if (merged.size < limit) merged.putIfAbsent(it.id, it) }
            return SearchResult(merged.values.toList(), "global+perChat")
        }
        return SearchResult(global.tracks, "global")
    }

    private suspend fun searchFallback(
        chats: List<Long>,
        query: String,
        limit: Int,
        globalFailure: Throwable?,
    ): SearchResult = try {
        SearchResult(searchPerChat(chats, query, limit), "perChat")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (chatFailure: Throwable) {
        throw IOException(
            "Telegram search is unavailable.",
            globalFailure?.apply { addSuppressed(chatFailure) } ?: chatFailure,
        )
    }

    /**
     * Audio and documents across all chats, paging on [TdApi.FoundMessages.nextOffset]
     * until the selected chats have enough rows or there is no next page.
     */
    private suspend fun searchGlobal(
        selected: Set<Long>,
        query: String,
        limit: Int,
    ): GlobalSearch {
        val rows = LinkedHashMap<String, TelegramTrack>()
        var firstFailure: Throwable? = null
        var exhausted = true
        filters@ for (filter in SEARCH_FILTERS) {
            // An abandoned flight must stop between blocking TDLib requests;
            // request() cannot be interrupted, but no further page or filter
            // may start once every caller has gone.
            currentCoroutineContext().ensureActive()
            if (rows.size >= limit) break@filters
            var offset = ""
            var pages = 0
            while (rows.size < limit) {
                currentCoroutineContext().ensureActive()
                val found = try {
                    request(
                        TdApi.SearchMessages(
                            null,
                            query,
                            offset,
                            GLOBAL_SEARCH_LIMIT,
                            filter,
                            null,
                            0,
                            0,
                        ),
                        15_000,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Throwable) {
                    if (firstFailure == null) firstFailure = failure
                    break
                }
                found.messages?.forEach { message ->
                    if (message.chatId !in selected) return@forEach
                    if (rows.size < limit) {
                        TelegramMessageMapper.from(message)?.let { rows.putIfAbsent(it.id, it) }
                    }
                }
                val next = found.nextOffset.orEmpty()
                if (next.isBlank() || next == offset) break
                offset = next
                pages++
                if (pages >= MAX_GLOBAL_SEARCH_PAGES) {
                    // A cap reached with the rows already full is a complete answer.
                    if (rows.size < limit) exhausted = false
                    break
                }
            }
        }
        // A filter that failed after the other one already supplied rows is
        // not worth falling back to the slow per-chat walk for.
        if (rows.isEmpty() && firstFailure != null) {
            throw IOException("Telegram global search is unavailable.", firstFailure)
        }
        return GlobalSearch(rows.values.toList(), exhausted)
    }

    /** Fallback only: selected chats, at most [MAX_PARALLEL_CHAT_SEARCHES] at once. */
    private suspend fun searchPerChat(
        chats: List<Long>,
        query: String,
        limit: Int,
    ): List<TelegramTrack> = coroutineScope {
        val rows = LinkedHashMap<String, TelegramTrack>()
        var firstFailure: Throwable? = null
        for (chunk in chats.chunked(MAX_PARALLEL_CHAT_SEARCHES)) {
            currentCoroutineContext().ensureActive()
            if (synchronized(rows) { rows.size >= limit }) break
            chunk.map { chat ->
                async(Dispatchers.IO) {
                    try {
                        for (filter in SEARCH_FILTERS) {
                            // Awaiting children cannot interrupt a blocked
                            // TDLib call, so stop before starting another one.
                            currentCoroutineContext().ensureActive()
                            val found = request(
                                TdApi.SearchChatMessages(
                                    chat,
                                    null,
                                    query,
                                    null,
                                    0,
                                    0,
                                    100,
                                    filter,
                                ),
                                15_000,
                            )
                            synchronized(rows) {
                                found.messages?.forEach { message ->
                                    if (rows.size < limit) {
                                        TelegramMessageMapper.from(message)?.let { rows.putIfAbsent(it.id, it) }
                                    }
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Throwable) {
                        synchronized(rows) {
                            if (firstFailure == null) firstFailure = failure
                        }
                    }
                }
            }.awaitAll()
        }
        synchronized(rows) {
            if (rows.isEmpty() && firstFailure != null) {
                throw IOException("Telegram search is unavailable.", firstFailure)
            }
            return@coroutineScope rows.values.toList()
        }
    }

    /** Runs on IO, with the client held by the settings screen. */
    private suspend fun loadChats(): List<TelegramChat> = coroutineScope {
        val found = LinkedHashMap<Long, TelegramChat>()
        for (list in listOf<TdApi.ChatList>(TdApi.ChatListMain(), TdApi.ChatListArchive())) {
            for (page in 0 until MAX_CHAT_PAGES) {
                try {
                    request(TdApi.LoadChats(list, 100), 15_000)
                } catch (failure: Throwable) {
                    // TDLib answers 404 when a list has no more pages.
                    if (failure.message?.contains("404") == true) break
                    break
                }
            }
            val ids = runCatching { request(TdApi.GetChats(list, MAX_CHATS), 15_000).chatIds }
                .getOrNull()
                ?: continue
            // One GetChat per chat is a blocking round trip, and asking for them
            // one at a time left this screen on its spinner for the sum of every
            // chat's latency — up to MAX_CHATS of them. Bounded the way a
            // per-chat search already is, and read back in the ids' own order so
            // the list does not reshuffle. A chat that fails is still skipped,
            // not fatal.
            for (chunk in ids.asList().chunked(MAX_PARALLEL_CHAT_SEARCHES)) {
                currentCoroutineContext().ensureActive()
                if (found.size >= MAX_CHATS) break
                val fetched = chunk.map { id ->
                    async(Dispatchers.IO) {
                        runCatching { request(TdApi.GetChat(id), 15_000) }.getOrNull()
                    }
                }.awaitAll()
                for (chat in fetched) {
                    if (chat == null) continue
                    if (found.size >= MAX_CHATS) break
                    if (!TelegramMessageMapper.chatAllowed(chat)) continue
                    found.putIfAbsent(
                        chat.id,
                        TelegramChat(
                            id = chat.id,
                            title = chat.title.orEmpty().ifBlank { chat.id.toString() },
                            kind = chatKind(chat),
                        ),
                    )
                }
            }
        }
        found.values.toList()
    }

    private fun chatKind(chat: TdApi.Chat): String = when (chat.type) {
        is TdApi.ChatTypePrivate -> "private"
        is TdApi.ChatTypeBasicGroup -> "group"
        is TdApi.ChatTypeSupergroup ->
            if ((chat.type as TdApi.ChatTypeSupergroup).isChannel) "channel" else "group"
        else -> "chat"
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
            } catch (e: Throwable) {
                TelegramLog.failure("save credentials", e.javaClass.simpleName)
                update { it.copy(error = TelegramError.GENERIC) }
                return@execute
            }
            credentials = next
            update { it.copy(configured = true, error = null) }
            if (changed) clearTelegramRest()
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
                // Never delete files under an unconfirmed close. The handle is
                // kept and the late Closed update finishes the logout.
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
     * when the native client is really gone. On timeout nothing is dropped or
     * deleted (see [resetAfterCloseTimeout]); the client stays the only one
     * until TDLib's own Closed update arrives.
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
     * A Close that TDLib has not confirmed in time. The native client may
     * still be alive and holding its database, so its handle, its generation
     * and [closing] are all kept: [startClient] stays shut (no second client
     * on the same files), nothing is deleted, and [logoutRequested] survives.
     * When TDLib does report Closed, [onUpdate] runs [finishClosed] through the
     * normal path — still the same generation — and only then is the session
     * deleted (for a logout) and the gate re-applied. No retry or timer: the
     * late Closed update is the event. Runs on [work] only.
     */
    private fun resetAfterCloseTimeout(reason: String) {
        TelegramLog.clientClosed("$reason timeout; waiting for Closed")
        update {
            it.copy(
                stage = TelegramAuthStage.CLOSING,
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
        // A pending interactive login survives a close/reopen: the login may
        // complete after the client was released, and that Ready must still
        // clear the rest earned by the previous settings. deleteSession()
        // clears it when the login itself is abandoned or logged out.
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
        ensureChatSelectionLoaded()
        databaseKeyStore = TelegramDatabaseKeyStore(
            File(dir, "database.key.sealed"),
            { TelegramKeystore.key(app, "database") },
        ) { File(dir, "db").exists() || File(dir, "files").exists() }
        credentials = try {
            credentialStore?.load() ?: TelegramApplicationCredentials.EMPTY
        } catch (e: Throwable) {
            TelegramLog.failure("load credentials", e.javaClass.simpleName)
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

            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                interactiveLogin = true
                clearValidatedSession()
                update { it.copy(stage = TelegramAuthStage.WAIT_PHONE, error = null) }
            }

            is TdApi.AuthorizationStateWaitCode -> {
                interactiveLogin = true
                clearValidatedSession()
                update { it.copy(stage = TelegramAuthStage.WAIT_CODE, error = null) }
            }

            is TdApi.AuthorizationStateWaitPassword -> {
                interactiveLogin = true
                clearValidatedSession()
                update { it.copy(stage = TelegramAuthStage.WAIT_PASSWORD, error = null) }
            }

            is TdApi.AuthorizationStateReady -> {
                markSessionValidated()
                // Only an actual login changes the session. A cold client
                // start of the same account also reaches Ready, and wiping
                // that account's genuine-error rest on every reopen would
                // make it retry a source that is still failing.
                val loginChanged = interactiveLogin
                interactiveLogin = false
                if (loginChanged) clearTelegramRest()
                update {
                    it.copy(stage = TelegramAuthStage.READY, error = null)
                }
                if (loginChanged) enableSourceAfterLogin()
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
        } catch (e: Throwable) {
            // A Keystore or disk failure must become a visible error, not an
            // uncaught worker exception that can take the process down.
            TelegramLog.failure("database key", e.javaClass.simpleName)
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
            TelegramLog.failure("setTdlibParameters", describeFailure(e))
            update { it.copy(error = mapError(e)) }
        }
    }

    /**
     * A successful interactive login turns the Telegram source on, which is
     * the one moment the listener has clearly asked for it. Reopening an
     * existing session does not: their switch stays where they left it.
     * Runs on [work] only.
     */
    private fun enableSourceAfterLogin() {
        val config = telegramConfig() ?: return
        if (!config.enabled) SourceRegistry.setEnabled(config.id, true)
    }

    /**
     * The Telegram source config, opening the registry if login or logout got
     * here before its background open finished. Never opens the TDLib client.
     */
    private fun telegramConfig() = run {
        SourceRegistry.configs.value.firstOrNull { it.kind == SourceKind.TELEGRAM }
            ?: runCatching { SourceRegistry.active() }.let {
                SourceRegistry.configs.value.firstOrNull { it.kind == SourceKind.TELEGRAM }
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
        // No account is left to finish an interactive login for.
        interactiveLogin = false
        val dir = home ?: return
        val database = File(dir, "db")
        val files = File(dir, "files")
        runCatching { database.deleteRecursively() }
        runCatching { files.deleteRecursively() }
        // Chats were chosen for this account's session; a new login starts
        // with none rather than searching chats it may not belong to.
        synchronized(selectionLock) {
            ensureChatSelectionLoaded()
            _selectedChats.value = emptySet()
            runCatching { chatSelection?.clear() }
        }
        clearValidatedSession()
        // With no session the source has nothing to answer, so leave it off
        // until the next interactive login turns it back on.
        telegramConfig()?.takeIf { it.enabled }?.let { SourceRegistry.setEnabled(it.id, false) }
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

    /** TDLib's code and text for a TDLib error, the exception class otherwise. */
    private fun describeFailure(error: Throwable): String =
        if (error is TelegramFailure) {
            "code=${error.error.code} ${error.error.message.orEmpty()}"
        } else {
            error.javaClass.simpleName
        }

    private fun mapError(error: Throwable): TelegramError {
        TelegramLog.failure("request", describeFailure(error))
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

    private const val CHATS_HOLDER = "chats"
    private const val STREAM_HOLDER = "stream"
    private const val MAX_CHATS = 500
    private const val MAX_CHAT_PAGES = MAX_CHATS / 100

    private val SEARCH_FILTERS = listOf<TdApi.SearchMessagesFilter>(
        TdApi.SearchMessagesFilterAudio(),
        TdApi.SearchMessagesFilterDocument(),
    )
    private const val GLOBAL_SEARCH_LIMIT = 100
    private const val MAX_GLOBAL_SEARCH_PAGES = 5
    private const val MAX_PARALLEL_CHAT_SEARCHES = 4

    /** Only long enough to fold the next track's identical query into this answer. */
    private const val SEARCH_REUSE_NANOS = 2_000_000_000L
    private const val SEARCH_REUSE_MAX = 64
}

/** A chat the listener may choose as a search source. */
data class TelegramChat(
    val id: Long,
    val title: String,
    val kind: String,
)
