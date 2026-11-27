package com.music.bitchord.ko

/**
 * [KO] native-crash: the `exit-native:` lines for one tombstone, as Android
 * hands it to [android.app.ApplicationExitInfo.getTraceInputStream] for a
 * native crash (protobuf, AOSP `system/core/debuggerd/proto/tombstone.proto`).
 *
 * The signal, the abort message, the crashing thread's frames, then one line
 * per other thread with its top frames: a crash that followed a stall (11-27:
 * 37 s of no log, then CRASH_NATIVE) is explained as much by where the other
 * threads sat as by the one that went down.
 *
 * Only the fields read here are decoded; everything else is skipped by wire
 * type. A tombstone this cannot read still gives the libraries named in it.
 */
internal fun nativeCrashLines(bytes: ByteArray, maxLines: Int = 60): List<String> {
    val stone = runCatching { readTombstone(bytes) }.getOrNull()
    if (stone == null || (stone.signal == null && stone.threads.isEmpty() && stone.abort == null)) {
        return listOf("exit-native: unparsed tombstone bytes=${bytes.size} libs=${librariesNamedIn(bytes)}")
    }
    val out = ArrayList<String>()
    val signal = stone.signal
    val fault = signal?.fault?.let { " fault=0x" + java.lang.Long.toHexString(it) } ?: ""
    out += "exit-native: ${signal?.name ?: "signal?"} ${signal?.code ?: "-"}$fault tid=${stone.tid}"
    stone.abort?.let { out += "exit-native: abort ${oneLine(it, ABORT_CHARS)}" }
    stone.causes.take(3).forEach { out += "exit-native: cause ${oneLine(it, ABORT_CHARS)}" }
    val crashed = stone.threads.firstOrNull { it.id == stone.tid }
    if (crashed != null) {
        out += "exit-native: crashed thread '${crashed.name}' (${crashed.id})"
        crashed.frames.take(CRASH_FRAMES).forEachIndexed { i, frame ->
            out += "exit-native: #%02d %s".format(i, frame.describe())
        }
    }
    // App libraries first: a thread parked in the kernel tells less than one inside our own code.
    val others = stone.threads.filter { it.id != stone.tid }
        .sortedByDescending { thread -> thread.frames.any { APP_LIB in it.file } }
    for (thread in others) {
        if (out.size >= maxLines) break
        val top = thread.frames.take(OTHER_FRAMES).joinToString(" < ") { it.describe() }
        out += "exit-native: thread '${thread.name}' (${thread.id}): ${top.ifEmpty { "-" }}"
    }
    return out.take(maxLines)
}

private const val ABORT_CHARS = 400
private const val CRASH_FRAMES = 24
private const val OTHER_FRAMES = 4
private const val APP_LIB = "/data/app/"

private fun oneLine(text: String, max: Int): String =
    text.lineSequence().map(String::trim).filter(String::isNotEmpty).joinToString(" / ").take(max)

internal class TombstoneFrame(val relPc: Long, val function: String, val offset: Long, val file: String) {
    fun describe(): String {
        val lib = file.substringAfterLast('/').ifEmpty { "?" }
        val where = if (function.isNotEmpty()) "$function+$offset" else "pc 0x" + java.lang.Long.toHexString(relPc)
        return "$lib $where"
    }
}

internal class TombstoneThread(val id: Long, val name: String, val frames: List<TombstoneFrame>)

internal class TombstoneSignal(val name: String?, val code: String?, val fault: Long?)

internal class Tombstone(
    val tid: Long,
    val signal: TombstoneSignal?,
    val abort: String?,
    val causes: List<String>,
    val threads: List<TombstoneThread>,
)

/** Reads what it can; a cut-off tail keeps the fields before it. */
internal fun readTombstone(bytes: ByteArray): Tombstone {
    var tid = 0L
    var signal: TombstoneSignal? = null
    var abort: String? = null
    val causes = ArrayList<String>()
    val threads = ArrayList<TombstoneThread>()
    proto(bytes, 0, bytes.size) { field, value, from, to ->
        when (field) {
            6 -> tid = value
            10 -> signal = readSignal(bytes, from, to)
            14 -> abort = text(bytes, from, to)
            15 -> proto(bytes, from, to) { f, _, a, b -> if (f == 1) causes += text(bytes, a, b) }
            16 -> proto(bytes, from, to) { f, _, a, b -> if (f == 2) threads += readThread(bytes, a, b) }
        }
    }
    return Tombstone(tid, signal, abort?.takeIf(String::isNotBlank), causes, threads)
}

private fun readSignal(bytes: ByteArray, from: Int, to: Int): TombstoneSignal {
    var name: String? = null
    var code: String? = null
    var hasFault = false
    var fault = 0L
    proto(bytes, from, to) { field, value, a, b ->
        when (field) {
            2 -> name = text(bytes, a, b)
            4 -> code = text(bytes, a, b)
            8 -> hasFault = value != 0L
            9 -> fault = value
        }
    }
    return TombstoneSignal(name, code, fault.takeIf { hasFault })
}

private fun readThread(bytes: ByteArray, from: Int, to: Int): TombstoneThread {
    var id = 0L
    var name = ""
    val frames = ArrayList<TombstoneFrame>()
    proto(bytes, from, to) { field, value, a, b ->
        when (field) {
            1 -> id = value
            2 -> name = text(bytes, a, b)
            4 -> frames += readFrame(bytes, a, b)
        }
    }
    return TombstoneThread(id, name, frames)
}

private fun readFrame(bytes: ByteArray, from: Int, to: Int): TombstoneFrame {
    var relPc = 0L
    var function = ""
    var offset = 0L
    var file = ""
    proto(bytes, from, to) { field, value, a, b ->
        when (field) {
            1 -> relPc = value
            4 -> function = text(bytes, a, b)
            5 -> offset = value
            6 -> file = text(bytes, a, b)
        }
    }
    return TombstoneFrame(relPc, function, offset, file)
}

private fun text(bytes: ByteArray, from: Int, to: Int) = String(bytes, from, to - from, Charsets.UTF_8)

/**
 * Walks one message's fields. [onField] gets the field number, the value of a
 * varint or fixed field, and the byte range of a length-delimited one. Stops
 * quietly at the first field that runs past [end], keeping what came before.
 */
private inline fun proto(bytes: ByteArray, start: Int, end: Int, onField: (Int, Long, Int, Int) -> Unit) {
    var pos = start
    while (pos < end) {
        var key = 0L
        var shift = 0
        while (true) {
            if (pos >= end || shift > 63) return
            val b = bytes[pos++].toInt() and 0xff
            key = key or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        val field = (key ushr 3).toInt()
        when ((key and 7).toInt()) {
            0 -> {
                var value = 0L
                shift = 0
                while (true) {
                    if (pos >= end || shift > 63) return
                    val b = bytes[pos++].toInt() and 0xff
                    value = value or ((b and 0x7f).toLong() shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                onField(field, value, 0, 0)
            }
            1 -> {
                if (pos + 8 > end) return
                var value = 0L
                for (i in 7 downTo 0) value = (value shl 8) or (bytes[pos + i].toLong() and 0xff)
                pos += 8
                onField(field, value, 0, 0)
            }
            2 -> {
                var length = 0L
                shift = 0
                while (true) {
                    if (pos >= end || shift > 63) return
                    val b = bytes[pos++].toInt() and 0xff
                    length = length or ((b and 0x7f).toLong() shl shift)
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                if (length < 0 || length > end - pos) return
                val from = pos
                pos += length.toInt()
                onField(field, 0L, from, pos)
            }
            5 -> {
                if (pos + 4 > end) return
                var value = 0L
                for (i in 3 downTo 0) value = (value shl 8) or (bytes[pos + i].toLong() and 0xff)
                pos += 4
                onField(field, value, 0, 0)
            }
            else -> return
        }
    }
}

/** Distinct `lib*.so` names in the printable runs of [bytes], for a tombstone that would not parse. */
internal fun librariesNamedIn(bytes: ByteArray, max: Int = 12): List<String> {
    val found = LinkedHashSet<String>()
    val run = StringBuilder()
    fun flush() {
        if (run.length >= 4) {
            Regex("""[A-Za-z0-9_.+-]+\.so""").findAll(run).forEach { found += it.value }
        }
        run.setLength(0)
    }
    for (b in bytes) {
        val c = b.toInt() and 0xff
        if (c in 0x20..0x7e) run.append(c.toChar()) else flush()
        if (found.size >= max) break
    }
    flush()
    return found.take(max)
}
