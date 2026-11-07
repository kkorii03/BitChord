package com.music.bitchord.data.lyrics

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * QQ Music's QRC cipher and its rows. The fixture is the ciphertext of a
 * synthetic document of made-up words, produced by the verified reference; the
 * rows themselves are read by the existing [KaraokeLrc].
 */
class QqMusicTest {

    /**
     * The synthetic document the fixture decrypts to. The newline inside the
     * attribute is what separates the two rows.
     *
     * ```text
     * <?xml version="1.0" encoding="utf-8"?>
     * <QrcInfos><QrcHeadInfo SaveTime="1" Version="100"/>
     * <LyricInfo LyricCount="1"><Lyric_1 LyricType="1" LyricContent="[1000,2000]zonk(1000,500,0) blip(1500,500) (2000,500,0)quax(2500,500)
     * [3000,1500]flim(3000,750,0) fram(3750,750)"/></LyricInfo></QrcInfos>
     * ```
     */
    private val fixture =
        "B96A3F55FE127A948E523DCC3F85296AE78AF7ADDD0BA19DB42B04E4DFA23FD911B1ECB36DEEF21F0552D34C83E905EB" +
            "5B29849406EF19C1EAF1182B65E775AFDF71DA0FAE2359A728D7D53035207F4C8AC2093DDDB7B9C2026D0280111256" +
            "F6896840402F80DEC0BEC13F042CCC778FEDB81BE7BD688EDEEB3D99B6367C94B8AF60936669EA124C74C56EE824A6F" +
            "A915B753E4EB9DF4EAFF8A1335FAC5088434F9DA5779371041DA297681310C90CEC495778BD4CE13E9B936BA4E3CFBB" +
            "7D79163E5C4B3A807493"

    /** QQ's search answer for one hit, as the endpoint returns it. */
    private val searchAnswer =
        """{"data":{"song":{"list":[{"songid":1,"songname":"zonk",""" +
            """"singer":[{"name":"Zed"}],"interval":200}]}}}"""

    @Test
    fun `the reference fixture decrypts to the document it was made from`() {
        val document = assertNotNull(QrcCipher.decryptHex(fixture))
        assertTrue(document.startsWith("<?xml"), document.take(40))
        assertTrue(document.contains("""LyricContent="[1000,2000]zonk"""))
        assertTrue(document.contains("[3000,1500]flim"))
    }

    @Test
    fun `qrc rows read the text before its stamp`() {
        val document = assertNotNull(QrcCipher.decryptHex(fixture))
        val lines = KaraokeLrc.parse(document)
        assertEquals(2, lines.size)

        assertEquals(1_000L, lines[0].timeMs)
        assertEquals("zonk blip quax", lines[0].text)
        assertEquals(listOf("zonk", "blip", "quax"), lines[0].words.map { it.text })
        assertEquals(listOf(1_000L, 1_500L, 2_500L), lines[0].words.map { it.startMs })
        assertEquals(listOf(1_500L, 2_000L, 3_000L), lines[0].words.map { it.endMs })

        assertEquals(3_000L, lines[1].timeMs)
        assertEquals("flim fram", lines[1].text)
        assertEquals(listOf(3_000L, 3_750L), lines[1].words.map { it.startMs })
    }

    @Test
    fun `corrupt input fails closed instead of throwing`() {
        assertNull(QrcCipher.decryptHex("zz"))
        assertNull(QrcCipher.decryptHex("ABC"))
        assertNull(QrcCipher.decryptHex(fixture.dropLast(2)))
        assertNull(QrcCipher.decryptHex("00".repeat(16)))
        assertNull(QrcCipher.decryptHex(""))
    }

    @Test
    fun `a translation or romanisation is never read as the original`() {
        val lrc = """<lyric><content type="file"><![CDATA[[00:01.00]zonk]]></content>""" +
            """<contentroma type="file"><![CDATA[$fixture]]></contentroma></lyric>"""
        assertNull(QqMusic.cdataHex(lrc))
        val qrc = """<lyric><content type="file"><![CDATA[$fixture]]></content>""" +
            """<contentts type="file"><![CDATA[[00:01.00]zonk]]></contentts></lyric>"""
        assertEquals(fixture, QqMusic.cdataHex(qrc))
    }

    @Test
    fun `a qrc answer becomes word synced lines`() = runBlocking {
        val xml = """<lyric><content type="file"><![CDATA[$fixture]]></content></lyric>"""
        val lines = assertNotNull(
            QqMusic.lyrics("zonk", "Zed", 200_000, get = { searchAnswer }, post = { _, _ -> xml }),
        )
        assertEquals(2, lines.size)
        assertEquals("zonk blip quax", lines[0].text)
        assertTrue(lines.all { it.isWordSynced })
    }

    @Test
    fun `an lrc answer is refused instead of shown as words`() = runBlocking {
        val xml = """<lyric><content type="file"><![CDATA[[00:01.00]zonk]]></content></lyric>"""
        assertNull(
            QqMusic.lyrics("zonk", "Zed", 200_000, get = { searchAnswer }, post = { _, _ -> xml }),
        )
    }

    @Test
    fun `the recording guard refuses a namesake and another length`() {
        val hit = QqMusic.Hit(
            songId = 1L,
            songName = "zonk",
            singers = listOf(QqMusic.Singer("Zed")),
            interval = 200,
        )
        assertNull(QqMusic.best(listOf(hit), "zonk", "Nope", 200_000))
        val short = QqMusic.Hit(
            songId = 1L,
            songName = "zonk",
            singers = listOf(QqMusic.Singer("Zed")),
            interval = 100,
        )
        assertNull(QqMusic.best(listOf(short), "zonk", "Zed", 200_000))
        assertEquals(hit, QqMusic.best(listOf(hit), "zonk", "Zed", 200_000))
    }
}