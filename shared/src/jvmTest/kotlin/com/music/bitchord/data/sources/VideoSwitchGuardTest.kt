package com.music.bitchord.data.sources

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The v1.9.1 merge replaced the fork's resolveVideo with upstream's body, which
 * searched for a row that had lost its header. A blank title or artist has to
 * be refused before any lookup: the empty query can only find something else.
 */
class VideoSwitchGuardTest {

    @Test
    fun `a row without title or artist is refused without a lookup`() = runBlocking {
        val blank = Song(videoId = "blankHeader1", title = "", artist = "", thumbnailUrl = null)
        val noArtist = Song(videoId = "blankHeader2", title = "Hype Boy", artist = " ", thumbnailUrl = null)
        // Short deadline: the guard answers at once; a lookup would go to the network.
        withTimeout(1_000) {
            assertNull(YtMusicRepository.resolveVideo(blank))
            assertNull(YtMusicRepository.resolveVideo(noArtist))
        }
    }
}
