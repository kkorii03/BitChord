package com.music.bitchord

import com.music.bitchord.playback.QualityUpgrade
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An accepted upgrade must survive every path that rebuilds the current item
 * from its song (download health, wrong-recording repair, song version, a
 * missing local file). Each of those loses the `q=hifi` marker, which is the
 * only thing that identifies the rendition the player agreed to.
 */
class UpgradeRenditionGuardTest {

    private fun watch(extra: String = "") = "bitchord://watch?v=KPM_BYl-EaQ&n=Watermelon%20Sugar$extra"

    @Test
    fun `an upgraded rendition is recognised`() {
        assertTrue(QualityUpgrade.isUpgradedRendition(watch("&q=hifi")))
        assertTrue(QualityUpgrade.isUpgradedRendition(watch("&q=hifi-2")))
    }

    @Test
    fun `a plain item and a revert are not upgrades`() {
        assertFalse(QualityUpgrade.isUpgradedRendition(watch()))
        assertFalse(QualityUpgrade.isUpgradedRendition(watch("&q=original")))
        assertFalse(QualityUpgrade.isUpgradedRendition(null))
    }

    @Test
    fun `rebuilding an upgraded item from its song would drop the upgrade`() {
        val upgraded = watch("&q=hifi")
        assertTrue(QualityUpgrade.replacementDropsUpgrade(upgraded, watch()))
        assertTrue(QualityUpgrade.replacementDropsUpgrade(upgraded, watch("&q=original")))
        assertTrue(QualityUpgrade.replacementDropsUpgrade(upgraded, watch("&q=hifi-2")))
    }

    @Test
    fun `a same rendition replacement keeps the upgrade`() {
        val upgraded = watch("&q=hifi")
        assertFalse(QualityUpgrade.replacementDropsUpgrade(upgraded, watch("&q=hifi")))
    }

    @Test
    fun `an ordinary item is never reported as an upgrade being dropped`() {
        assertFalse(QualityUpgrade.replacementDropsUpgrade(watch(), watch("&q=hifi")))
        assertFalse(QualityUpgrade.replacementDropsUpgrade(null, watch()))
    }
}
