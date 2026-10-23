package com.music.bitchord.data.net

import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The rule that keeps an artist tap from opening a same-name channel: a title
 * matches exactly or with a parenthetical alias, and the largest stated
 * audience wins when more than one does.
 */
class ArtistCandidateTest {

    private fun artist(id: String, title: String, subtitle: String = "") =
        BrowseItem(id, title, subtitle, null, BrowseType.ARTIST)

    @Test
    fun `a tiny exact name loses to the popular alias`() {
        val picked = bestArtistCandidate(
            listOf(
                artist("UC_TINY", "IVE", "73 subscribers"),
                artist("UC_REAL", "IVE (\uC544\uC774\uBE0C)", "4.1M monthly listeners"),
            ),
            "IVE",
        )
        assertEquals("UC_REAL", picked?.browseId)
    }

    @Test
    fun `the largest audience wins among exact names`() {
        val picked = bestArtistCandidate(
            listOf(
                artist("UC_SMALL", "Mate", "10K subscribers"),
                artist("UC_BIG", "Mate", "900K subscribers"),
            ),
            "Mate",
        )
        assertEquals("UC_BIG", picked?.browseId)
    }

    @Test
    fun `a name that is only a suffix is not that artist`() {
        assertNull(
            bestArtistCandidate(
                listOf(
                    artist("UC_WHITE", "White 2115", "1M subscribers"),
                    artist("UC_BEDOES", "Bedoes 2115", "900K subscribers"),
                ),
                "2115",
            ),
        )
    }

    @Test
    fun `without an audience the exact spelling still wins`() {
        val picked = bestArtistCandidate(
            listOf(
                artist("UC_ALIAS", "IVE (group)"),
                artist("UC_EXACT", "IVE"),
            ),
            "IVE",
        )
        assertEquals("UC_EXACT", picked?.browseId)
    }

    @Test
    fun `a parenthetical alias is read as the same artist`() {
        assertEquals(true, artistTitleMatches("NewJeans (\uB274\uC9C4\uC2A4)", "NewJeans"))
        assertEquals(true, artistTitleMatches("IVE", "IVE"))
        assertEquals(false, artistTitleMatches("IVE - topic", "IVE"))
        assertEquals(false, artistTitleMatches("White 2115", "2115"))
    }

    @Test
    fun `audience figures are read in Korean and English`() {
        assertEquals(73L, artistAudienceSize("73 subscribers"))
        assertEquals(41_200_000L, artistAudienceSize("\uC6D4\uAC04 \uC2DC\uCCAD\uC790 4120\uB9CC\uBA85"))
        assertEquals(4_100_000L, artistAudienceSize("4.1M monthly listeners"))
        assertEquals(1_200L, artistAudienceSize("1.2K subscribers"))
        assertEquals(0L, artistAudienceSize("Artist"))
    }
}
