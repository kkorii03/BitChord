package com.music.bitchord.data.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.music.bitchord.BuildConfig
import com.music.bitchord.data.net.ProxyConfig
import com.music.bitchord.data.net.ProxyMode
import com.music.bitchord.data.net.ProxyScope
import com.music.bitchord.data.net.ProxyValidation
import com.music.bitchord.data.sources.SourceConfig
import com.music.bitchord.data.sources.SourceKind
import com.music.bitchord.data.sources.SourceRegistry
import com.music.bitchord.data.stats.Backup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/** Which Settings page a transferred key belongs to, for the import preview. */
internal enum class TransferCategory {
    QUALITY,
    AUTOMIX,
    LYRICS,
    PLAYBACK,
    DISPLAY,
}

/** The Android preference type a transferred key is allowed to carry. */
internal enum class TransferType {
    BOOLEAN,
    INT,
    LONG,
    FLOAT,
    STRING,
    STRING_LIST,
    STRING_SET,
    FLOAT_LIST,
}

/**
 * One whitelisted setting and the shape it must arrive in.
 *
 * [allowed] is for values that name an enum. A file from another build may
 * contain a rung this build has never heard of, and the right answer is to
 * leave that one key alone rather than write something [AppSettings] cannot
 * read back.
 */
internal data class TransferSpec(
    val type: TransferType,
    val allowed: Set<String>? = null,
    /** Finite values outside this are clamped; non-finite values are skipped. */
    val floatRange: ClosedFloatingPointRange<Float>? = null,
    /** Values outside this are clamped. */
    val intRange: IntRange? = null,
)

internal data class TransferKey(
    val category: TransferCategory,
    val spec: TransferSpec,
)

internal data class TransferApplyResult(
    val applied: Int,
    val skipped: Int,
)

internal data class ParsedSettings(
    val values: Map<String, Any?>,
    val skipped: Int,
)

internal data class ParsedSources(
    val values: List<SourceConfig>,
    val skipped: Int,
)

internal data class ParsedProxy(
    val value: ProxyConfig?,
    val skipped: Int,
)

/**
 * Settings that can travel to another phone, without the things that belong to
 * this one.
 *
 * Deliberately a whitelist rather than [AppSettings.exportPrefs]'s "everything
 * except secrets". A full backup is a copy of this device; this file is a
 * short errand — the proxy, the sources, and the sound and screen choices
 * somebody wants their sibling to have. A setting added next month is not part
 * of that errand until it is named here, so it cannot leave by accident.
 *
 * The exclusions are therefore structural: account cookies live in the
 * encrypted store and never reach SharedPreferences, while listening history,
 * likes, playlists, downloads, diagnostics and device-local values are simply
 * not keys this object knows.
 */
object SettingsTransfer {

    const val APP_TAG = "bitchord-settings"
    const val SCHEMA_VERSION = 1
    const val FILE_EXTENSION = "bitchord-settings"

    private const val MIN_SCHEMA_VERSION = 1
    private const val SHARE_FOLDER = "shared"
    private const val MIME_TYPE = "application/json"

    /**
     * A settings file is a short document — proxy, sources and a few dozen
     * choices. Reading an arbitrarily large shared file whole would let another
     * app hand this process a file big enough to run it out of memory before
     * the schema check can refuse it.
     */
    internal const val MAX_IMPORT_BYTES = 1_000_000

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /**
     * The versioned document written to disk.
     *
     * Settings and sources are kept as [JsonElement]s so one malformed row can
     * be skipped on its own. Decoding them as a typed map or list would fail
     * the whole file over a single unknown enum or a value that is not the type
     * it claims to be, which is the opposite of "apply what you can".
     */
    @Serializable
    data class File(
        /**
         * No default tag: an app key that was never written cannot be the
         * same as ours, and [decode] must refuse the document.
         */
        val app: String = "",
        val version: Int = SCHEMA_VERSION,
        val versionName: String = "",
        val exportedAt: String = "",
        val settings: Map<String, JsonElement> = emptyMap(),
        val sources: List<JsonElement> = emptyList(),
        val proxy: JsonElement? = null,
    )

    /**
     * The proxy as it travels, credentials included.
     *
     * Not [ProxyConfig] itself: this file is a text document, and the password
     * has to be written down here because the receiving phone has no other way
     * to learn it. That is exactly what the warning before sharing says.
     */
    @Serializable
    data class ProxyTransfer(
        val mode: String = ProxyMode.OFF.name,
        val host: String = "",
        val port: Int = 0,
        val backupHost: String = "",
        val backupPort: Int = 0,
        val username: String = "",
        val password: String = "",
        val scope: String = ProxyScope.MUSIC_ONLY.name,
    ) {
        fun toConfig(): ProxyConfig? {
            val parsedMode = ProxyMode.entries.firstOrNull { it.name == mode } ?: return null
            val parsedScope = ProxyScope.entries.firstOrNull { it.name == scope } ?: return null
            if (port !in 0..65535 || backupPort !in 0..65535) return null
            val config = ProxyConfig(
                mode = parsedMode,
                host = host,
                port = port,
                backupHost = backupHost,
                backupPort = backupPort,
                username = username,
                password = password,
                scope = parsedScope,
            ).normalized()
            return config.takeIf { ProxyValidation.validate(it) == null }
        }

        companion object {
            fun from(config: ProxyConfig): ProxyTransfer = ProxyTransfer(
                mode = config.mode.name,
                host = config.host,
                port = config.port,
                backupHost = config.backupHost,
                backupPort = config.backupPort,
                username = config.username,
                password = config.password,
                scope = config.scope.name,
            )
        }
    }

    /** What an import will change, shown before anything is written. */
    data class Preview(
        val file: File,
        val settings: Map<String, Any?>,
        val changedSettings: Map<String, Any?>,
        val sources: List<SourceConfig>,
        val sourcesChanged: Boolean,
        val proxy: ProxyConfig?,
        val proxyChanged: Boolean,
        val skipped: Int,
    ) {
        internal val changedCategoryCounts: Map<TransferCategory, Int>
            get() = changedSettings.keys
                .mapNotNull { AppSettings.TRANSFERABLE_KEYS[it]?.category }
                .groupingBy { it }
                .eachCount()

        val sourceNames: List<String> get() = sources.map { it.displayName }

        val hasChanges: Boolean
            get() = changedSettings.isNotEmpty() || sourcesChanged || proxyChanged
    }

    /** What an import actually did, for the line shown afterwards. */
    data class Summary(
        val settings: Int,
        val sources: Int,
        val proxy: Boolean,
        val skipped: Int,
    )

    fun suggestedName(): String =
        "bitchord-settings-${DateTimeFormatter.ofPattern("yyyy-MM-dd").format(
            Instant.now().atZone(ZoneId.systemDefault()),
        )}.$FILE_EXTENSION"

    /**
     * Writes the current configuration to the cache and returns a share intent.
     *
     * The file goes through the app's existing [FileProvider] so the receiving
     * app gets a read grant for this one document and nothing else. The cache
     * copy is a hand-off point, not a record: it is overwritten on the next
     * send and can be reclaimed by Android at any time.
     */
    suspend fun share(context: Context): Result<Intent> = withContext(Dispatchers.IO) {
        runCatching {
            val file = buildFile(
                rawSettings = AppSettings.transferablePrefs(),
                sources = SourceRegistry.transferSnapshot(),
                proxy = ProxyTransfer.from(AppSettings.currentProxyConfig()),
                versionName = BuildConfig.VERSION_NAME,
            )
            val text = encode(file)
            val folder = File(context.cacheDir, SHARE_FOLDER).apply { mkdirs() }
            val target = File(folder, suggestedName())
            target.writeText(text)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                target,
            )
            Intent(Intent.ACTION_SEND).apply {
                type = MIME_TYPE
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, suggestedName())
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    /** Reads and validates a file without writing anything. */
    suspend fun importFrom(context: Context, source: Uri): Result<Preview> =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = context.contentResolver.openInputStream(source)
                    ?.use { it.readTextBounded() }
                    ?: error("파일을 열 수 없습니다")
                preview(decode(text).getOrThrow())
            }
        }

    /**
     * Reads at most [MAX_IMPORT_BYTES] of UTF-8 text, refusing the document
     * rather than growing the buffer without bound.
     */
    internal fun InputStream.readTextBounded(maxBytes: Int = MAX_IMPORT_BYTES): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            require(total <= maxBytes) { "설정 파일이 너무 큽니다" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray().decodeToString()
    }

    /** Applies a preview that the listener has already seen. */
    suspend fun apply(preview: Preview): Result<Summary> = withContext(Dispatchers.IO) {
        runCatching {
            val settings = AppSettings.applyTransferablePrefs(preview.settings)
            val sources = if (preview.sources.isEmpty()) {
                0
            } else {
                SourceRegistry.replaceFromTransfer(preview.sources)
            }
            preview.proxy?.let(AppSettings::setProxyConfig)
            Summary(
                settings = preview.changedSettings.size,
                sources = if (preview.sourcesChanged) sources else 0,
                proxy = preview.proxyChanged,
                // The preview already counted every bad setting, source and
                // proxy field; apply only sees the values it accepted.
                skipped = preview.skipped,
            )
        }
    }

    internal fun buildFile(
        rawSettings: Map<String, Any?>,
        sources: List<SourceConfig>,
        proxy: ProxyTransfer?,
        now: Instant = Instant.now(),
        versionName: String = "",
    ): File {
        val settings = rawSettings
            .filterKeys { it in AppSettings.TRANSFERABLE_KEYS }
            .mapNotNull { (key, value) ->
                Backup.PrefValue.of(value)?.let { pref ->
                    key to json.encodeToJsonElement(Backup.PrefValue.serializer(), pref)
                }
            }
            .toMap()
        return File(
            app = APP_TAG,
            version = SCHEMA_VERSION,
            versionName = versionName,
            exportedAt = now.toString(),
            settings = settings,
            sources = sources.map { json.encodeToJsonElement(SourceConfig.serializer(), it) },
            proxy = proxy?.let { json.encodeToJsonElement(ProxyTransfer.serializer(), it) },
        )
    }

    internal fun encode(file: File): String =
        json.encodeToString(File.serializer(), file)

    internal fun decode(text: String): Result<File> = runCatching {
        val file = json.decodeFromString(File.serializer(), text)
        require(file.app == APP_TAG) { "BitChord 설정 파일이 아닙니다" }
        require(file.version >= MIN_SCHEMA_VERSION) { "이 설정 파일의 버전을 읽을 수 없습니다" }
        require(file.version <= SCHEMA_VERSION) { "더 최신 버전의 BitChord에서 만든 설정입니다" }
        file
    }

    internal fun readSettings(file: File): ParsedSettings {
        val accepted = linkedMapOf<String, Any?>()
        var skipped = 0
        for ((key, element) in file.settings) {
            val spec = AppSettings.TRANSFERABLE_KEYS[key]?.spec
            if (spec == null) {
                skipped++
                continue
            }
            val pref = runCatching {
                json.decodeFromJsonElement(Backup.PrefValue.serializer(), element)
            }.getOrNull()
            val value = pref?.decoded()
            val normalized = spec.normalize(value)
            if (normalized == null) {
                skipped++
            } else {
                accepted[key] = normalized
            }
        }
        return ParsedSettings(accepted, skipped)
    }

    internal fun readSources(file: File): ParsedSources {
        val accepted = mutableListOf<SourceConfig>()
        var skipped = 0
        for (element in file.sources) {
            val config = runCatching {
                json.decodeFromJsonElement(SourceConfig.serializer(), element)
            }.getOrNull()
            if (config == null || config.kind == SourceKind.MODULE || !config.isComplete) {
                skipped++
                continue
            }
            accepted += config.copy(
                id = config.id.ifBlank { UUID.randomUUID().toString() },
                baseUrl = config.baseUrl.trim(),
                label = config.label.trim(),
            )
        }
        return ParsedSources(
            values = accepted.distinctBy { if (it.kind.isUserAdded) it.id else it.kind },
            skipped = skipped,
        )
    }

    internal fun readProxy(file: File): ParsedProxy {
        val element = file.proxy ?: return ParsedProxy(null, 0)
        val transfer = runCatching {
            json.decodeFromJsonElement(ProxyTransfer.serializer(), element)
        }.getOrNull()
        val config = transfer?.toConfig()
        return if (config == null) ParsedProxy(null, 1) else ParsedProxy(config, 0)
    }

    fun preview(file: File): Preview = previewOf(
        file = file,
        currentSettings = AppSettings.transferablePrefs(),
        currentSources = SourceRegistry.transferSnapshot(),
        currentProxy = AppSettings.currentProxyConfig(),
    )

    internal fun previewOf(
        file: File,
        currentSettings: Map<String, Any?>,
        currentSources: List<SourceConfig>,
        currentProxy: ProxyConfig,
    ): Preview {
        val settings = readSettings(file)
        val sources = readSources(file)
        val proxy = readProxy(file)
        val changed = settings.values.filter { (key, value) ->
            !sameValue(currentSettings[key], value)
        }
        val sourcesChanged = sources.values.isNotEmpty() &&
            sources.values != currentSources
        return Preview(
            file = file,
            settings = settings.values,
            changedSettings = changed,
            sources = sources.values,
            sourcesChanged = sourcesChanged,
            proxy = proxy.value,
            proxyChanged = proxy.value != null && proxy.value != currentProxy,
            skipped = settings.skipped + sources.skipped + proxy.skipped,
        )
    }

    private fun sameValue(current: Any?, incoming: Any?): Boolean = when {
        current is Set<*> && incoming is Set<*> -> current == incoming
        current is Number && incoming is Number -> current.toDouble() == incoming.toDouble()
        else -> current == incoming
    }

}

/** Accepts only the declared shape; anything else is skipped by the caller. */
internal fun TransferSpec.normalize(value: Any?): Any? = when (type) {
    TransferType.BOOLEAN -> value as? Boolean
    TransferType.INT -> (value as? Int)?.let { intRange?.let { range -> it.coerceIn(range) } ?: it }
    TransferType.LONG -> value as? Long
    TransferType.FLOAT -> (value as? Float)
        ?.takeIf { it.isFinite() }
        ?.let { floatRange?.let { range -> it.coerceIn(range) } ?: it }
    TransferType.STRING -> (value as? String)?.takeIf { allowed == null || it in allowed }
    TransferType.STRING_LIST -> {
        val text = value as? String ?: return null
        val parts = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val kept = if (allowed == null) parts else parts.filter { it in allowed }
        if (parts.isNotEmpty() && kept.isEmpty()) null else kept.joinToString(",")
    }
    TransferType.STRING_SET -> {
        val values = (value as? Set<*>)?.filterIsInstance<String>() ?: return null
        val kept = if (allowed == null) values else values.filter { it in allowed }
        if (values.isNotEmpty() && kept.isEmpty()) null else kept.toSet()
    }
    TransferType.FLOAT_LIST -> {
        val text = value as? String ?: return null
        val parts = text.split(',').map { it.trim() }
        val parsed = parts.map { part ->
            part.toFloatOrNull()?.takeIf { it.isFinite() } ?: return null
        }
        if (floatRange == null) {
            parts.joinToString(",")
        } else {
            parsed.joinToString(",") { it.coerceIn(floatRange).toString() }
        }
    }
}

/**
 * A settings file handed to BitChord from the share sheet or a file manager.
 *
 * The same relay [com.music.bitchord.playback.MusicLink] and
 * [com.music.bitchord.data.listentogether.JamInviteLink] are: the activity sees
 * the intent, the composition opens Settings and reads it. Held until Settings
 * has actually read the file, because a cold launch composes the activity
 * before the Settings page exists.
 */
object SettingsTransferInbox {

    private const val EXTRA_CONSUMED = "bitchord.settingsTransferConsumed"

    private val _pending = MutableStateFlow<Uri?>(null)
    val pending: StateFlow<Uri?> = _pending.asStateFlow()

    fun consume(intent: Intent?): Boolean {
        if (intent == null || intent.getBooleanExtra(EXTRA_CONSUMED, false)) return false
        val uri = when (intent.action) {
            Intent.ACTION_SEND -> streamOf(intent)
            Intent.ACTION_VIEW -> intent.data
            else -> null
        } ?: return false
        if (!looksLikeSettings(intent, uri)) return false
        intent.putExtra(EXTRA_CONSUMED, true)
        _pending.value = uri
        return true
    }

    fun handled() {
        _pending.value = null
    }

    @Suppress("DEPRECATION")
    private fun streamOf(intent: Intent): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }

    private fun looksLikeSettings(intent: Intent, uri: Uri): Boolean {
        val name = uri.lastPathSegment.orEmpty().lowercase()
        val mime = intent.type.orEmpty().lowercase()
        return name.endsWith(".${SettingsTransfer.FILE_EXTENSION}") ||
            mime == "application/json" ||
            mime == "application/octet-stream"
    }
}
