package com.music.bitchord.ko

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The per-output lyric offset: route keys and the stored map (pure logic). */
class LyricsRouteOffsetSettingsForkRegressionTest {
    @Test
    fun speakerAndUnknownShareTheDefaultKey() {
        assertEquals("speaker", KoLyricsRouteKey.routeKey("phone", null, null))
        assertEquals("speaker", KoLyricsRouteKey.routeKey("", "AA:BB", "x"))
    }

    @Test
    fun bluetoothIsKeyedByAddressThenName() {
        val byAddress = KoLyricsRouteKey.routeKey("bluetooth", "AA:BB:CC:DD:EE:FF", "JBL Tour Pro 3")
        assertEquals("bluetooth:aa:bb:cc:dd:ee:ff", byAddress)
        // A rename keeps the key; a second pair of the same model gets its own.
        assertEquals(byAddress, KoLyricsRouteKey.routeKey("bluetooth", "aa:bb:cc:dd:ee:ff", "Renamed"))
        assertEquals(
            "bluetooth:11:22:33:44:55:66",
            KoLyricsRouteKey.routeKey("bluetooth", "11:22:33:44:55:66", "JBL Tour Pro 3"),
        )
        assertEquals("bluetooth:jbl tour pro 3", KoLyricsRouteKey.routeKey("bluetooth", "", " JBL Tour Pro 3 "))
        assertEquals("bluetooth:unknown", KoLyricsRouteKey.routeKey("bluetooth", null, null))
    }

    @Test
    fun wiredSharesOneKeyWhateverTheReportedName() {
        assertEquals("wired", KoLyricsRouteKey.routeKey("wired", "", "SM-S928N"))
    }

    @Test
    fun labelNamesTheDeviceAndIsAbsentOnTheSpeaker() {
        assertNull(KoLyricsRouteKey.label("speaker", "phone", "x"))
        assertEquals(
            "이 기기(JBL Tour Pro 3) 전용",
            KoLyricsRouteKey.label("bluetooth:aa", "bluetooth", "JBL Tour Pro 3"),
        )
        assertEquals("이 기기(유선 이어폰) 전용", KoLyricsRouteKey.label("wired", "wired", ""))
    }

    @Test
    fun mapRoundTripsAndNewRouteStartsAtZero() {
        val map = KoLyricsRouteKey.withOffset(emptyMap(), "bluetooth:aa", -300)
        val back = KoLyricsRouteKey.parse(KoLyricsRouteKey.encode(map))
        assertEquals(-300, KoLyricsRouteKey.offsetFor(back, "bluetooth:aa"))
        assertEquals(0, KoLyricsRouteKey.offsetFor(back, "bluetooth:never-seen"))
    }

    @Test
    fun speakerIsNeverWrittenIntoTheMap() {
        assertTrue(KoLyricsRouteKey.withOffset(emptyMap(), "speaker", 200).isEmpty())
        assertTrue(KoLyricsRouteKey.parse("""{"speaker": 900}""").isEmpty())
    }

    @Test
    fun valuesAreClampedAndGarbageIsIgnored() {
        assertEquals(5_000, KoLyricsRouteKey.withOffset(emptyMap(), "usb:x", 99_999)["usb:x"])
        val parsed = KoLyricsRouteKey.parse("""{"a": 90000, "b": "text", "c": -20}""")
        assertEquals(mapOf("a" to 5_000, "c" to -20), parsed)
        assertTrue(KoLyricsRouteKey.parse("not json").isEmpty())
        assertTrue(KoLyricsRouteKey.parse(null).isEmpty())
        assertTrue(KoLyricsRouteKey.parse("[1,2]").isEmpty())
    }

    @Test
    fun oldestRoutesDropPastTheCap() {
        var map: Map<String, Int> = emptyMap()
        for (i in 1..(KoLyricsRouteKey.MAX_ROUTES + 3)) map = KoLyricsRouteKey.withOffset(map, "usb:$i", i)
        assertEquals(KoLyricsRouteKey.MAX_ROUTES, map.size)
        assertNull(map["usb:1"])
        assertEquals(KoLyricsRouteKey.MAX_ROUTES + 3, map["usb:${KoLyricsRouteKey.MAX_ROUTES + 3}"])
        // Re-setting an old route makes it the newest.
        val refreshed = KoLyricsRouteKey.withOffset(map, "usb:4", 7)
        assertEquals("usb:4", refreshed.keys.last())
    }
}
