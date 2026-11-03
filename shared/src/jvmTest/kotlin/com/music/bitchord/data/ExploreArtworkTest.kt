package com.music.bitchord.data

import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A tile borrows its cover from the first playlist inside its category. The
 * cover may only land on the exact tile it was fetched for, and a tile that
 * already has one must not ask again.
 */
class ExploreArtworkTest {

    private fun tile(
        title: String,
        browseId: String,
        params: String? = null,
        artwork: String? = null,
    ) = MoodGenre(title = title, browseId = browseId, params = params, thumbnailUrl = artwork)

    @Test
    fun `a tile without a cover asks for one`() {
        assertFalse(ExploreArtwork.hasCover(tile("발라드", "ytq:ballad")))
        assertFalse(ExploreArtwork.hasCover(tile("발라드", "ytq:ballad", artwork = "  ")))
        assertTrue(ExploreArtwork.hasCover(tile("발라드", "ytq:ballad", artwork = "https://i.ytimg.com/a.jpg")))
    }

    @Test
    fun `a cover lands only on the tile it belongs to`() {
        val target = tile("발라드", "ytq:ballad")
        val sameBrowseOtherParams = tile("발라드 (다른 탭)", "ytq:ballad", params = "other")
        val other = tile("록", "ytq:rock")
        val sections = listOf(
            MoodGenreSection("장르", listOf(target, sameBrowseOtherParams)),
            MoodGenreSection("분위기", listOf(other)),
        )

        val updated = ExploreArtwork.withCover(sections, "ytq:ballad", null, "https://i.ytimg.com/a.jpg")

        assertEquals("https://i.ytimg.com/a.jpg", updated[0].items[0].thumbnailUrl)
        assertNull(updated[0].items[1].thumbnailUrl)
        assertNull(updated[1].items[0].thumbnailUrl)
        assertEquals(sections[0].items[1], updated[0].items[1])
        assertEquals(sections[1], updated[1])
        assertEquals("장르", updated[0].title)
    }

    @Test
    fun `a cover for a params tile leaves the params-less tile alone`() {
        val plain = tile("발라드", "ytq:ballad")
        val parameterised = tile("발라드", "ytq:ballad", params = "p")
        val sections = listOf(MoodGenreSection("장르", listOf(plain, parameterised)))

        val updated = ExploreArtwork.withCover(sections, "ytq:ballad", "p", "https://i.ytimg.com/b.jpg")

        assertNull(updated[0].items[0].thumbnailUrl)
        assertEquals("https://i.ytimg.com/b.jpg", updated[0].items[1].thumbnailUrl)
    }
}
