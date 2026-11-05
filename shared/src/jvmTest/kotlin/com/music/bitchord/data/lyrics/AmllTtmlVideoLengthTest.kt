package com.music.bitchord.data.lyrics

import com.music.bitchord.data.TrackLog
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Why the AMLL lookup missed on nine of the ten songs of the 11-05 emulator
 * run, pinned to the answers iTunes actually gave for them.
 *
 * Every fixture below is the live `itunes.apple.com/search` response for the
 * title and artist the log shows the app asking under, cut down to the fields
 * this source reads. The durations are the videos' own `lengthSeconds`.
 */
class AmllTtmlVideoLengthTest {

    /** `Youngblood 5 Seconds of Summer`, video -RJSbO8UZVY at 230 s. */
    private val youngblood = """
        {"resultCount":10,"results":[
          {"trackId":1369109665,"artistName":"5 Seconds of Summer","trackName":"Youngblood","trackTimeMillis":203418},
          {"trackId":1369109851,"artistName":"5 Seconds of Summer","trackName":"Youngblood","trackTimeMillis":203418},
          {"trackId":1369109322,"artistName":"5 Seconds of Summer","trackName":"Youngblood","trackTimeMillis":203418},
          {"trackId":1475816324,"artistName":"5 Seconds of Summer & Charlie Puth","trackName":"Easier (Remix)","trackTimeMillis":157326},
          {"trackId":1446260670,"artistName":"5 Seconds of Summer","trackName":"Youngblood (Live)","trackTimeMillis":390623},
          {"trackId":1388856681,"artistName":"5 Seconds of Summer","trackName":"Youngblood (Acoustic)","trackTimeMillis":220996},
          {"trackId":1676159149,"artistName":"5 Seconds of Summer","trackName":"Youngblood (Live from The Royal Albert Hall)","trackTimeMillis":235928},
          {"trackId":1475004487,"artistName":"5 Seconds of Summer","trackName":"Easier (Live From The Vault)","trackTimeMillis":206927},
          {"trackId":1369078102,"artistName":"5 Seconds of Summer","trackName":"Youngblood","trackTimeMillis":203418},
          {"trackId":1471381796,"artistName":"5 Seconds of Summer","trackName":"Easier (Seeb Remix)","trackTimeMillis":165767}
        ]}
    """.trimIndent()

    /** `When I Was Your Man Bruno Mars`, video ekzHIouo8Q4 at 234 s. */
    private val whenIWasYourMan = """
        {"resultCount":4,"results":[
          {"trackId":573962555,"artistName":"Bruno Mars","trackName":"When I Was Your Man","trackTimeMillis":213827},
          {"trackId":619672090,"artistName":"Twinkle Twinkle Little Rock Star","trackName":"When I Was Your Man","trackTimeMillis":187000},
          {"trackId":581806441,"artistName":"Bruno Mars","trackName":"When I Was Your Man","trackTimeMillis":213827},
          {"trackId":1367259151,"artistName":"Boyce Avenue","trackName":"When I Was Your Man (feat. Fifth Harmony)","trackTimeMillis":218473}
        ]}
    """.trimIndent()

    /** `7 rings Ariana Grande`, video QYh6mYIJG2Y at 185 s. */
    private val sevenRings = """
        {"resultCount":10,"results":[
          {"trackId":1450334168,"artistName":"Ariana Grande","trackName":"7 rings","trackTimeMillis":178631},
          {"trackId":1451154052,"artistName":"Ariana Grande","trackName":"7 rings (Remix) [feat. 2 Chainz]","trackTimeMillis":178631},
          {"trackId":1500952424,"artistName":"Lady Gaga & Ariana Grande","trackName":"Rain On Me","trackTimeMillis":182203},
          {"trackId":1745100726,"artistName":"Lullaby Rock!","trackName":"7 Rings","trackTimeMillis":203333},
          {"trackId":1563812775,"artistName":"The Weeknd & Ariana Grande","trackName":"Save Your Tears (Remix)","trackTimeMillis":191014},
          {"trackId":1846394909,"artistName":"Rockabye Baby!","trackName":"7 rings","trackTimeMillis":179240},
          {"trackId":1450561360,"artistName":"HQ INSTRUMENTALS","trackName":"7 Rings (Karaoke Instrumental) [Originally Performed by Ariana Grande]","trackTimeMillis":180472},
          {"trackId":1450647725,"artistName":"B Lou","trackName":"7 Rings (Originally Performed by Ariana Grande) Karaoke Version","trackTimeMillis":179371},
          {"trackId":1849247939,"artistName":"Our Last Night","trackName":"7 Rings (feat. Derek DiScanio)","trackTimeMillis":181821},
          {"trackId":1457886509,"artistName":"KIDZ BOP Kids","trackName":"7 Rings","trackTimeMillis":176393}
        ]}
    """.trimIndent()

    @Test
    fun `a music video runs longer than its track and is still the same recording`() {
        val young = AmllTtml.parseSearch(youngblood)!!
        assertEquals(1369109665L, AmllTtml.best(young, "Youngblood", "5 Seconds of Summer", 230_000)?.trackId)

        val bruno = AmllTtml.parseSearch(whenIWasYourMan)!!
        assertEquals(573962555L, AmllTtml.best(bruno, "When I Was Your Man", "Bruno Mars", 234_000)?.trackId)

        val rings = AmllTtml.parseSearch(sevenRings)!!
        assertEquals(1450334168L, AmllTtml.best(rings, "7 rings", "Ariana Grande", 185_000)?.trackId)
    }

    @Test
    fun `the length test still refuses a row longer than the song or far shorter`() {
        val young = AmllTtml.parseSearch(youngblood)!!
        // The video is shorter than this, so the row is longer than the song:
        // our audio would be a cut of it.
        assertNull(AmllTtml.best(young, "Youngblood", "5 Seconds of Summer", 200_000))
        assertNull(AmllTtml.best(young, "Youngblood", "5 Seconds of Summer", 190_000))
        // 40 s shorter than the song is past any intro a video adds.
        assertNull(AmllTtml.best(young, "Youngblood", "5 Seconds of Summer", 250_000))
        assertEquals(
            "no-match count=10 closest=length",
            AmllTtml.missReason(young, "Youngblood", "5 Seconds of Summer", 190_000),
        )
    }

    @Test
    fun `a miss says whether nothing came back or every row was refused`() {
        assertEquals("no-match count=0", AmllTtml.missReason(emptyList(), "Song", "Artist", 200_000))

        val noLength = listOf(
            AmllTtml.Hit(trackId = 1, trackName = "Song", artistName = "Artist", trackTimeMillis = null),
        )
        assertEquals("no-match count=1 closest=no-length", AmllTtml.missReason(noLength, "Song", "Artist", 200_000))

        val otherTitle = listOf(
            AmllTtml.Hit(trackId = 2, trackName = "Other Song", artistName = "Artist", trackTimeMillis = 200_000),
        )
        assertEquals("no-match count=1 closest=core", AmllTtml.missReason(otherTitle, "Song", "Artist", 200_000))
    }

    @Test
    fun `an unanswered search and an empty answer are told apart in the log`() = runBlocking {
        val logged = mutableListOf<String>()
        val wasEchoing = TrackLog.echo
        TrackLog.echo = { _, tag, message, _ -> if (tag == "Lyrics") logged += message }
        try {
            // The request itself fails: nothing was learned about the song.
            assertNull(AmllTtml.lyrics("Silent Song", "Silent Artist", 200_000, "video-http") { null })
            assertTrue(logged.any { it == "lyrics source ttml: miss id=unknown lines=0 reason=http" }, "$logged")

            // iTunes answered, and said it has no such song.
            logged.clear()
            assertNull(
                AmllTtml.lyrics("Missing Song", "Missing Artist", 200_000, "video-empty") {
                    """{"resultCount":0,"results":[]}"""
                },
            )
            assertTrue(
                logged.any { it == "lyrics source ttml: miss id=unknown lines=0 reason=no-match count=0" },
                "$logged",
            )
        } finally {
            TrackLog.echo = wasEchoing
        }
    }
}
