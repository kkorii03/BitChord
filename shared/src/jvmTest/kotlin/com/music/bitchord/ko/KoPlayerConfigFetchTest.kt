package com.music.bitchord.ko

import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.models.response.PlayerResponse
import com.music.bitchord.data.innertube.InnerTubeXResolver
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import java.nio.file.Files
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * The library's real cipher service, wired to the app's player-config source:
 * a song's solve may ask YouTube for the player and nobody else. Before, it
 * asked jsDelivr and GitHub for a file that no longer exists, on every song,
 * while holding the one cipher lock.
 */
class KoPlayerConfigFetchTest {

    private val dir = Files.createTempDirectory("bitchord-itx-config").toFile()
    private val hosts = Collections.synchronizedList(mutableListOf<String>())

    private val store = object : InnerTubeXResolver.ConfigStore {
        override fun getString(key: String): String? = null
        override fun getLong(key: String): Long? = null
        override fun putString(key: String, value: String) = Unit
        override fun putLong(key: String, value: Long) = Unit
    }

    @AfterTest
    fun tearDown() {
        InnerTubeXResolver.resetWarmForTest()
        dir.deleteRecursively()
    }

    @Test
    fun `solving a song asks nobody but YouTube`() {
        InnerTubeXResolver.init(dir, store, null)
        val ok = OkHttpClient.Builder().addInterceptor { chain ->
            hosts += chain.request().url.host
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(404)
                .message("Not Found")
                .body("".toResponseBody())
                .build()
        }.build()
        val http = HttpClient(OkHttp) { engine { preconfigured = ok } }
        val cipher = YouTubeCipherService(http, RemotePlayerConfigStore(http, InnerTubeXResolver.playerConfigsForTest()))

        runBlocking {
            cipher.processFormats(
                playerUrl = "https://www.youtube.com/s/player/b485e332/player_es6.vflset/ko_KR/base.js",
                formats = listOf(
                    PlayerResponse.StreamingData.Format(
                        itag = 251,
                        url = "https://rr1---sn-test.googlevideo.com/videoplayback?itag=251&n=abcdefgh",
                        mimeType = "audio/webm; codecs=\"opus\"",
                    ),
                ),
            )
        }

        // The player script itself, so the solve did get past the config lookup.
        assertEquals(setOf("www.youtube.com"), hosts.toSet())
    }
}