package com.music.bitchord

import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.webdav.update
import coil3.network.NetworkHeaders
import com.music.bitchord.data.webdav.WebDavAuth
import com.music.bitchord.data.webdav.WebDavClient
import com.music.bitchord.data.webdav.WebDavCoilAuth
import com.music.bitchord.data.webdav.WebDavConfig
import com.music.bitchord.data.webdav.WebDavRepository
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavTest {

    @Test
    fun normalizesUrl() {
        assertEquals("", WebDavConfig.normalizeUrl("  "))
        assertEquals(
            "https://cloud.example.com/remote.php/dav/files/user/Music",
            WebDavConfig.normalizeUrl("cloud.example.com/remote.php/dav/files/user/Music/"),
        )
        assertEquals(
            "http://192.168.1.10:8080/music",
            WebDavConfig.normalizeUrl("http://192.168.1.10:8080/music"),
        )
    }

    @Test
    fun detectsConfiguration() {
        assertFalse(WebDavConfig.isConfigured(""))
        assertFalse(WebDavConfig.isConfigured("not a url"))
        assertTrue(WebDavConfig.isConfigured("https://cloud.example.com/music"))
    }

    @Test
    fun buildsBasicAuthHeader() {
        assertNull(WebDavConfig.basicAuthHeader("", ""))
        assertEquals("Basic dXNlcjpwYXNz", WebDavConfig.basicAuthHeader("user", "pass"))
    }

    @Test
    fun detectsAudioFiles() {
        assertTrue(WebDavConfig.isAudioFile("song.mp3"))
        assertTrue(WebDavConfig.isAudioFile("Song.FLAC"))
        assertTrue(WebDavConfig.isAudioFile("take.opus"))
        assertTrue(WebDavConfig.isAudioFile("take.WAV"))
        assertTrue(WebDavConfig.isAudioFile("master.dsf"))
        assertTrue(WebDavConfig.isAudioFile("master.dff"))
        // A bare listing has no content type, so a container that can hold
        // video is not assumed to be an audio track.
        assertFalse(WebDavConfig.isAudioFile("clip.3gp"))
        assertFalse(WebDavConfig.isAudioFile("clip.3gpp"))
        assertFalse(WebDavConfig.isAudioFile("movie.mp4"))
        assertFalse(WebDavConfig.isAudioFile("clip.webm"))
        // Media3 has no extractor for these.
        assertFalse(WebDavConfig.isAudioFile("old.wma"))
        assertFalse(WebDavConfig.isAudioFile("mix.aif"))
        assertFalse(WebDavConfig.isAudioFile("master.aiff"))
        assertFalse(WebDavConfig.isAudioFile("cover.jpg"))
        assertFalse(WebDavConfig.isAudioFile("notes.txt"))
    }

    @Test
    fun `content type decides ambiguous containers`() {
        assertTrue(WebDavConfig.isAudioFile("album.mp4", "audio/mp4"))
        assertFalse(WebDavConfig.isAudioFile("movie.mp4", "video/mp4"))
        assertTrue(WebDavConfig.isAudioFile("take.webm", "audio/webm"))
        assertFalse(WebDavConfig.isAudioFile("concert.webm", "video/webm"))
        // A server that calls a normal audio file video is believed.
        assertFalse(WebDavConfig.isAudioFile("song.mp3", "video/mp4"))
    }

    @Test
    fun mapsFilenameToSong() {
        val song = WebDavConfig.songFor("https://cloud.example.com/Music/Artist%20-%20Title.mp3", "Album")
        assertTrue(song.videoId.startsWith("webdav:"))
        assertEquals("https://cloud.example.com/Music/Artist%20-%20Title.mp3", song.localUri)
        assertEquals(WebDavConfig.BROWSE_ID, song.playbackSourceId)
    }

    @Test
    fun parsesMultistatus() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
  <d:response>
    <d:href>/remote.php/dav/files/user/Music/</d:href>
    <d:propstat><d:prop>
      <d:displayname>Music</d:displayname>
      <d:resourcetype><d:collection/></d:resourcetype>
    </d:prop></d:propstat>
  </d:response>
  <d:response>
    <d:href>/remote.php/dav/files/user/Music/Artist%20-%20Title.mp3</d:href>
    <d:propstat><d:prop>
      <d:displayname>Artist - Title.mp3</d:displayname>
      <d:resourcetype/>
      <d:getcontenttype>audio/mpeg</d:getcontenttype>
    </d:prop></d:propstat>
  </d:response>
  <d:response>
    <d:href>/remote.php/dav/files/user/Music/cover.jpg</d:href>
    <d:propstat><d:prop>
      <d:displayname>cover.jpg</d:displayname>
      <d:resourcetype/>
      <d:getcontenttype>image/jpeg</d:getcontenttype>
    </d:prop></d:propstat>
  </d:response>
  <d:response>
    <d:href>/remote.php/dav/files/user/Music/Live/</d:href>
    <d:propstat><d:prop>
      <d:displayname>Live</d:displayname>
      <d:resourcetype><d:collection/></d:resourcetype>
    </d:prop></d:propstat>
  </d:response>
</d:multistatus>
""".trimIndent()
        val entries = WebDavClient.parseMultistatus(xml, "https://cloud.example.com/remote.php/dav/files/user/Music")
        // The directory itself is dropped.
        assertEquals(3, entries.size)
        val audio = entries.first { it.displayName == "Artist - Title.mp3" }
        assertFalse(audio.isCollection)
        assertEquals("audio/mpeg", audio.contentType)
        assertTrue(audio.url.endsWith("Artist%20-%20Title.mp3"))
        val folder = entries.first { it.displayName == "Live" }
        assertTrue(folder.isCollection)
    }

    @Test
    fun resolvesRelativeHrefs() {
        assertEquals(
            "https://cloud.example.com/remote.php/dav/files/user/Music/a.mp3",
            WebDavClient.resolveHref(
                "https://cloud.example.com/remote.php/dav/files/user/Music",
                "/remote.php/dav/files/user/Music/a.mp3",
            ),
        )
        assertEquals(
            "https://other.example.com/a.mp3",
            WebDavClient.resolveHref(
                "https://cloud.example.com/music",
                "https://other.example.com/a.mp3",
            ),
        )
    }

    @Test
    fun coilAuth_attachesCredentialOnlyForTheServer() {
        WebDavAuth.update("https://cloud.example.com/music", "user", "pass")
        try {
            assertEquals(
                "Basic dXNlcjpwYXNz",
                WebDavCoilAuth.authHeaderFor("https://cloud.example.com/Music/Album/cover.jpg"),
            )
            // Anything else passes through untouched.
            assertNull(WebDavCoilAuth.authHeaderFor("https://other.example.com/cover.jpg"))
            assertNull(WebDavCoilAuth.authHeaderFor("not a url"))
            assertNull(WebDavCoilAuth.authHeaderFor(null))
            assertNull(WebDavCoilAuth.authHeaderFor(42))
            // A request carrying its own credential is never overridden.
            assertNull(
                WebDavCoilAuth.authHeaderFor(
                    "https://cloud.example.com/Music/Album/cover.jpg",
                    NetworkHeaders.Builder().set("Authorization", "Bearer x").build(),
                ),
            )
        } finally {
            WebDavAuth.update("", "", "")
        }
    }

    @Test
    fun coilAuth_staysQuietWhenUnconfigured() {
        WebDavAuth.update("", "", "")
        assertNull(WebDavCoilAuth.authHeaderFor("https://cloud.example.com/Music/Album/cover.jpg"))
    }

    @Test
    fun authorizesOnlyConfiguredHost() {
        WebDavAuth.update("https://cloud.example.com/music", "user", "pass")
        assertTrue(WebDavAuth.shouldAuthorize("cloud.example.com"))
        assertFalse(WebDavAuth.shouldAuthorize("other.example.com"))
        assertNotNull(WebDavAuth.authHeader)
        WebDavAuth.update("", "", "")
        assertFalse(WebDavAuth.shouldAuthorize("cloud.example.com"))
    }

    @Test
    fun derivesParentFolder() {
        assertEquals(
            "Album",
            WebDavRepository.parentFolderName("https://cloud.example.com/Music/Artist/Album/song.mp3"),
        )
    }

    @Test
    fun uploadFileName_prefersArtistTitle() {
        assertEquals(
            "Artist - Title.mp3",
            WebDavClient.uploadFileName("Title", "Artist", "mp3"),
        )
    }

    @Test
    fun uploadFileName_dropsUnknownArtistAndSanitizes() {
        assertEquals(
            "Title _ Live.flac",
            WebDavClient.uploadFileName("Title / Live", "Unknown Artist", "FLAC"),
        )
    }

    @Test
    fun uploadFileName_blankFallsBack() {
        assertEquals("track.mp3", WebDavClient.uploadFileName("  ", "", ""))
    }

    @Test
    fun resolveNumberedName_incrementsCaseInsensitively() {
        assertEquals(
            "Title.mp3",
            WebDavClient.resolveNumberedName("Title", "mp3", emptySet()),
        )
        assertEquals(
            "Title (2).mp3",
            WebDavClient.resolveNumberedName(
                "Title", "mp3", setOf("title.MP3", "Title (1).mp3"),
            ),
        )
    }

    @Test
    fun joinUrl_encodesSegments() {
        assertEquals(
            "https://cloud.example.com/Music/Artist%20-%20Title.mp3",
            WebDavClient.joinUrl("https://cloud.example.com/Music/", "Artist - Title.mp3"),
        )
    }

    @Test
    fun `exists maps 200 and 404, put honors If-None-Match`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/Music").toString().trimEnd('/')
            val target = "$base/Artist%20-%20Title.mp3"

            server.enqueue(MockResponse().setResponseCode(404))
            assertEquals(false, WebDavClient.exists(target, "", "").getOrThrow())

            server.enqueue(MockResponse().setResponseCode(201))
            var progress = -1L
            assertEquals(
                WebDavClient.PutResult.Uploaded,
                WebDavClient.putFile(
                    fileUrl = target,
                    stream = "hello".byteInputStream(),
                    contentLength = 5,
                    mimeType = "audio/mpeg",
                    username = "", password = "",
                    overwrite = false,
                    onProgress = { progress = it },
                ).getOrThrow(),
            )
            assertEquals(5L, progress)
            server.takeRequest() // HEAD from exists() above
            val put = server.takeRequest() // the PUT itself
            assertEquals("PUT", put.method)
            assertEquals("*", put.getHeader("If-None-Match"))

            server.enqueue(MockResponse().setResponseCode(412))
            assertEquals(
                WebDavClient.PutResult.AlreadyExists,
                WebDavClient.putFile(
                    fileUrl = target,
                    stream = "hello".byteInputStream(),
                    contentLength = 5,
                    mimeType = "audio/mpeg",
                    username = "", password = "",
                    overwrite = false,
                ).getOrThrow(),
            )
            server.takeRequest() // the refused PUT

            server.enqueue(MockResponse().setResponseCode(204))
            assertEquals(
                WebDavClient.PutResult.Uploaded,
                WebDavClient.putFile(
                    fileUrl = target,
                    stream = "hello".byteInputStream(),
                    contentLength = 5,
                    mimeType = "audio/mpeg",
                    username = "", password = "",
                    overwrite = true,
                ).getOrThrow(),
            )
            assertNull(server.takeRequest().getHeader("If-None-Match"))
        }
    }

    @Test
    fun detectsImageFiles() {
        assertTrue(WebDavClient.isImageFile("cover.jpg"))
        assertTrue(WebDavClient.isImageFile("Folder.PNG"))
        assertTrue(WebDavClient.isImageFile("art.webp"))
        assertFalse(WebDavClient.isImageFile("song.mp3"))
        assertFalse(WebDavClient.isImageFile("notes.txt"))
    }

    @Test
    fun artworkFor_prefersConventionalCovers() {
        fun img(name: String) = WebDavClient.Entry(
            url = "https://cloud.example.com/Music/Album/$name",
            displayName = name,
            isCollection = false,
            contentType = "image/jpeg",
        )
        val siblings = listOf(img("IMG_1234.jpg"), img("cover.jpg"), img("back.jpg"))
        assertEquals(
            "https://cloud.example.com/Music/Album/cover.jpg",
            WebDavRepository.artworkFor("https://cloud.example.com/Music/Album/song.mp3", siblings),
        )
    }

    @Test
    fun artworkFor_fallsBackToFirstAlphabetical() {
        fun img(name: String) = WebDavClient.Entry(
            url = "https://cloud.example.com/Music/Album/$name",
            displayName = name,
            isCollection = false,
            contentType = "image/jpeg",
        )
        assertEquals(
            "https://cloud.example.com/Music/Album/a.jpg",
            WebDavRepository.artworkFor(
                "https://cloud.example.com/Music/Album/song.mp3",
                listOf(img("z.jpg"), img("a.jpg")),
            ),
        )
        assertNull(
            WebDavRepository.artworkFor("https://cloud.example.com/Music/Album/song.mp3", emptyList()),
        )
    }

    @Test
    fun `listLibrary collects audio and sibling images`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val rootXml = multistatus(
                collection("Music"),
                file("song.mp3", "audio/mpeg"),
                file("cover.jpg", "image/jpeg"),
                collection("Music/Live"),
            )
            val liveXml = multistatus(file("Music/Live/take.flac", "audio/flac"))
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                    MockResponse().setResponseCode(207).setBody(
                        if (request.path?.contains("Live") == true) liveXml else rootXml,
                    )
            }
            val listing = WebDavClient.listLibrary(server.url("/Music").toString(), "", "").getOrThrow()
            assertEquals(2, listing.audio.size)
            assertEquals(1, listing.images.size)
            assertEquals("cover.jpg", listing.images.single().displayName)
        }
    }

    @Test
    fun `listLibrary enforces maxFiles inside one large directory`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setResponseCode(207).setBody(
                    multistatus(
                        file("Music/a.mp3", "audio/mpeg"),
                        file("Music/b.mp3", "audio/mpeg"),
                        file("Music/cover.jpg", "image/jpeg"),
                        file("Music/c.mp3", "audio/mpeg"),
                    ),
                ),
            )
            val listing = WebDavClient.listLibrary(
                server.url("/Music").toString(),
                "",
                "",
                maxFiles = 2,
            ).getOrThrow()
            assertEquals(2, listing.audio.size)
            // Reaching the audio cap must not skip the cover filed beside the
            // tracks that were collected.
            assertEquals("cover.jpg", listing.images.single().displayName)
        }
    }

    @Test
    fun `listLibrary leaves video files out of the audio rows`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse().setResponseCode(207).setBody(
                    multistatus(
                        file("Music/song.flac", "audio/flac"),
                        file("Music/movie.mp4", "video/mp4"),
                        file("Music/clip.3gp", "video/3gpp"),
                        file("Music/audio-only.mp4", "audio/mp4"),
                        file("Music/cover.jpg", "image/jpeg"),
                    ),
                ),
            )
            val listing = WebDavClient.listLibrary(server.url("/Music").toString(), "", "").getOrThrow()
            assertEquals(
                listOf("song.flac", "audio-only.mp4"),
                listing.audio.map { it.displayName },
            )
            assertEquals("cover.jpg", listing.images.single().displayName)
        }
    }

    private fun multistatus(vararg rows: String): String = """<?xml version="1.0" encoding="utf-8"?>
<d:multistatus xmlns:d="DAV:">
${rows.joinToString("\n")}
</d:multistatus>
""".trimIndent()

    private fun collection(href: String): String = """
  <d:response>
    <d:href>/$href/</d:href>
    <d:propstat><d:prop>
      <d:displayname>${href.substringAfterLast('/')}</d:displayname>
      <d:resourcetype><d:collection/></d:resourcetype>
    </d:prop></d:propstat>
  </d:response>""".trimIndent()

    private fun file(href: String, mime: String): String = """
  <d:response>
    <d:href>/$href</d:href>
    <d:propstat><d:prop>
      <d:displayname>${href.substringAfterLast('/')}</d:displayname>
      <d:resourcetype/>
      <d:getcontenttype>$mime</d:getcontenttype>
    </d:prop></d:propstat>
  </d:response>""".trimIndent()

    @Test
    fun `ensureCollection tolerates an existing folder`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val dir = server.url("/Music").toString()
            server.enqueue(MockResponse().setResponseCode(405))
            WebDavClient.ensureCollection(dir, "", "").getOrThrow()
            assertEquals("MKCOL", server.takeRequest().method)
        }
    }

    @Test
    fun `malformed multistatus is a failure, not an empty library`() {
        val failure = runCatching {
            WebDavClient.parseMultistatus("<d:multistatus><broken", "https://cloud.example.com/Music")
        }
        assertTrue(failure.isFailure)
    }

    @Test
    fun `multistatus refuses doctype and external entities`() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE d:multistatus [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
<d:multistatus xmlns:d="DAV:">
  <d:response>
    <d:href>/Music/secret.mp3</d:href>
    <d:propstat><d:prop>
      <d:displayname>&xxe;</d:displayname>
      <d:resourcetype/>
    </d:prop></d:propstat>
  </d:response>
</d:multistatus>
"""
        val failure = runCatching {
            WebDavClient.parseMultistatus(xml, "https://cloud.example.com/Music")
        }
        assertTrue(failure.isFailure)
    }

    @Test
    fun `repository surfaces a listing failure instead of an empty library`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(500))
            val previous = AppSettings.webdavUrl.value
            AppSettings.webdavUrl.value = server.url("/Music").toString().trimEnd('/')
            try {
                val result = runCatching { WebDavRepository.getSongs() }
                assertTrue(result.isFailure)
            } finally {
                AppSettings.webdavUrl.value = previous
            }
        }
    }
}
