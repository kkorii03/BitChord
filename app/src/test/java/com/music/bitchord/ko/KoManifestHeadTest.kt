package com.music.bitchord.ko

import com.music.bitchord.data.sources.SourceStream
import com.music.bitchord.data.sources.StreamFormat
import com.music.bitchord.playback.StreamContainer
import com.music.bitchord.playback.smart.HqAudioVerify
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections

/**
 * [KO] manifest-verify. Phone 11-16..18 (vc210test, ILLIT qsqvJpOgrWw and 52
 * others): every kkorii12 row the addon served as a DASH/HLS manifest logged
 * "match=0% offset=0 -> unmeasured" within 3 ms, because the sound check
 * refused manifests before fetching a byte, so no such row could be swapped in.
 * The check now fetches the manifest's opening segments (about 91 s) and
 * rejoins them as the download path does.
 */
class KoManifestHeadTest {

    private lateinit var server: MockWebServer
    private lateinit var root: File
    private val requested = Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath.orEmpty()
                requested += path
                return when {
                    path == "/dash/42" -> MockResponse().setBody(MPD)
                    path == "/dash/init.mp4" -> MockResponse().setBody(Buffer().write(init("fLaC")))
                    path.startsWith("/dash/seg-") -> MockResponse().setBody(Buffer().write(segment(frames, 4096, 0)))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        root = File(System.getProperty("java.io.tmpdir"), "bitchord-manifest-head-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        server.shutdown()
        root.deleteRecursively()
    }

    /** The kkorii12 shape: an extensionless path the addon declared as `dash`, with a FLAC format. */
    private fun kkoriiStream(): SourceStream {
        val url = server.url("/dash/42").toString()
        StreamContainer.declare(url, "dash")
        return SourceStream(url = url, format = StreamFormat(codec = "flac", sampleRateHz = 44_100, bitDepth = 24))
    }

    @Test
    fun `the sound check fetches a declared manifest's opening segments, not the index as audio`() = runBlocking {
        val directory = File(root, "hq_verify").apply { mkdirs() }

        // The JVM has no decoder, so no profile comes back; what matters is what was fetched.
        assertNull(HqAudioVerify.candidateFeatures(directory, kkoriiStream(), OkHttpClient()))

        assertTrue("the init segment must be fetched: $requested", "/dash/init.mp4" in requested)
        // 4 s segments: 23 of them reach the 91 s window, and no more are fetched.
        for (n in 1..23) assertTrue("segment $n must be fetched", "/dash/seg-$n.m4s" in requested)
        assertFalse("nothing past the window: $requested", "/dash/seg-24.m4s" in requested)
        assertEquals(25, requested.size)
    }

    @Test
    fun `the opening is rejoined as a native flac`() = runBlocking {
        val stream = kkoriiStream()
        val file = File(root, "candidate.bin")
        val bytes = KoManifestHead.fetchOpening(stream.url, emptyMap(), file, 1L shl 30, 91.0, OkHttpClient())

        assertEquals(file.length(), bytes)
        assertEquals("fLaC", String(file.readBytes(), 0, 4, Charsets.ISO_8859_1))
        assertEquals(listOf("candidate.bin"), root.list()?.toList()) // the frames file is cleaned up
    }

    @Test
    fun `a byte cap stops the segments early`() = runBlocking {
        val file = File(root, "candidate.bin")
        // The init alone passes a one-byte cap; a head with no audio is refused, not written.
        val outcome = runCatching { KoManifestHead.fetchOpening(kkoriiStream().url, emptyMap(), file, 1L, 91.0, OkHttpClient()) }
        assertTrue(outcome.exceptionOrNull() is java.io.IOException)
        assertEquals(listOf("/dash/42", "/dash/init.mp4"), requested)
    }

    @Test
    fun `an hls playlist is cut at the window by its EXTINF lengths`() {
        val playlist = buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-MAP:URI=\"init.mp4\"")
            repeat(60) { appendLine("#EXTINF:10.0,"); appendLine("seg-${it + 1}.m4s") }
            appendLine("#EXT-X-ENDLIST")
        }
        val plan = KoManifestHead.plan(playlist, dash = false, seconds = 91.0)
        assertEquals("init.mp4", plan.initialization)
        assertEquals((1..10).map { "seg-$it.m4s" }, plan.media)
    }

    @Test
    fun `an hls playlist without lengths keeps every segment for the byte cap to stop`() {
        val playlist = "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\na.m4s\nb.m4s\n"
        assertEquals(listOf("a.m4s", "b.m4s"), KoManifestHead.plan(playlist, dash = false, seconds = 1.0).media)
    }

    @Test(expected = java.io.IOException::class)
    fun `an unreadable manifest stays unmeasured`() {
        KoManifestHead.plan("<MPD><ContentProtection/></MPD>", dash = true, seconds = 91.0)
    }

    @Test
    fun `flac samples are rebuilt as flac and anything else stays mp4`() {
        assertEquals("flac", KoManifestHead.extensionFor(init("fLaC")))
        assertEquals("m4a", KoManifestHead.extensionFor(init("mp4a")))
        assertEquals("m4a", KoManifestHead.extensionFor(ByteArray(0)))
    }

    // CMAF builders, as in ManifestFileTest.
    private fun u32(v: Long) = ByteArray(4) { (v shr (8 * (3 - it))).toByte() }

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().also { o -> parts.forEach(o::write) }.toByteArray()
        return u32(8L + body.size) + type.toByteArray(Charsets.ISO_8859_1) + body
    }

    private val streamInfo = ByteArray(34).also {
        it[10] = 0x0A; it[11] = 0xC4.toByte(); it[12] = (0x40 or (1 shl 1)).toByte(); it[13] = (15 shl 4).toByte()
    }

    private fun init(entry: String): ByteArray {
        val dfla = box("dfLa", u32(0), byteArrayOf(0x80.toByte(), 0, 0, 34), streamInfo)
        val sampleEntry = box(entry, ByteArray(28), dfla)
        val stsd = box("stsd", u32(0), u32(1), sampleEntry)
        val mdhd = box("mdhd", u32(0), u32(0), u32(0), u32(44_100), u32(0), u32(0))
        val tkhd = box("tkhd", u32(0), u32(0), u32(0), u32(1), u32(0), u32(0))
        val trak = box("trak", tkhd, box("mdia", mdhd, box("minf", box("stbl", stsd))))
        val trex = box("trex", u32(0), u32(1), u32(1), u32(0), u32(0), u32(0))
        return box("ftyp", "iso8".toByteArray()) + box("moov", trak, box("mvex", trex))
    }

    private fun segment(frames: List<ByteArray>, frameTicks: Long, decodeTime: Long): ByteArray {
        val tfhd = box("tfhd", u32(0x20000), u32(1))
        val tfdt = box("tfdt", u32(0), u32(decodeTime))
        val entries = frames.flatMap { listOf(u32(frameTicks), u32(it.size.toLong())) }.toTypedArray()
        fun moof(offset: Long) = box("moof", box("traf", tfhd, tfdt, box("trun", u32(0x301), u32(frames.size.toLong()), u32(offset), *entries)))
        val moofSize = moof(0).size
        val mdat = box("mdat", *frames.toTypedArray())
        return box("styp", "msdh".toByteArray()) + box("sidx", ByteArray(32)) + moof(moofSize + 8L) + mdat
    }

    private val frames = List(3) { n -> ByteArray(100 + n) { (n * 7 + it).toByte() } }

    private companion object {
        /** One FLAC rendition, sixty 4-second segments, as Tidal-backed addons serve it. */
        val MPD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <MPD xmlns="urn:mpeg:dash:schema:mpd:2011" mediaPresentationDuration="PT4M0S" type="static">
              <Period id="0">
                <AdaptationSet contentType="audio" mimeType="audio/mp4">
                  <Representation id="FLAC,44100,24" codecs="flac" bandwidth="1411000" audioSamplingRate="44100">
                    <SegmentTemplate timescale="44100" initialization="init.mp4" media="seg-${'$'}Number${'$'}.m4s" startNumber="1">
                      <SegmentTimeline><S d="176400" r="59"/></SegmentTimeline>
                    </SegmentTemplate>
                  </Representation>
                </AdaptationSet>
              </Period>
            </MPD>
        """.trimIndent()
    }
}
