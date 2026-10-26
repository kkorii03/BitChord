package com.music.bitchord.data.net

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The primary -> backup -> primary decision, and the selector actually
 * following it. The switch is only ever made at a request's answer, so these
 * cases need no clock of their own: the retry window is passed in.
 */
class ProxyRoutePolicyTest {

    private val musicUri = URI("https://music.youtube.com/youtubei/v1/browse")

    private val withBackup = ProxyConfig(
        mode = ProxyMode.HTTP,
        host = "primary.example",
        port = 8080,
        backupHost = "backup.example",
        backupPort = 9090,
    )

    @AfterTest
    fun tearDown() {
        // update() only resets the route when the configuration really moves,
        // so a non-default config goes in first.
        YouTubeProxy.update(ProxyConfig(mode = ProxyMode.HTTP, host = "cleanup.invalid", port = 1))
        YouTubeProxy.update(ProxyConfig())
        ProxyHealthMonitor.clear()
    }

    @Test
    fun `a failed primary moves to the backup`() {
        assertEquals(ProxyRoute.BACKUP, ProxyRoutePolicy.nextRouteAfterFailure(hasBackup = true))
    }

    @Test
    fun `a failed backup leaves the route there for the caller to bypass`() {
        assertEquals(ProxyRoute.BACKUP, ProxyRoutePolicy.nextRouteAfterFailure(hasBackup = true))
    }

    @Test
    fun `without a backup a failure keeps the old direct-retry behaviour`() {
        assertEquals(ProxyRoute.PRIMARY, ProxyRoutePolicy.nextRouteAfterFailure(hasBackup = false))
    }

    @Test
    fun `a success on the backup keeps it inside the retry window`() {
        assertEquals(
            ProxyRoute.BACKUP,
            ProxyRoutePolicy.nextRouteAfterSuccess(
                current = ProxyRoute.BACKUP,
                hasBackup = true,
                msSinceSwitch = ProxyRoutePolicy.PRIMARY_RETRY_AFTER_MS - 1,
            ),
        )
    }

    @Test
    fun `a success on the backup after the window returns to the primary`() {
        assertEquals(
            ProxyRoute.PRIMARY,
            ProxyRoutePolicy.nextRouteAfterSuccess(
                current = ProxyRoute.BACKUP,
                hasBackup = true,
                msSinceSwitch = ProxyRoutePolicy.PRIMARY_RETRY_AFTER_MS,
            ),
        )
    }

    @Test
    fun `a success on the primary keeps the primary`() {
        assertEquals(
            ProxyRoute.PRIMARY,
            ProxyRoutePolicy.nextRouteAfterSuccess(
                current = ProxyRoute.PRIMARY,
                hasBackup = true,
                msSinceSwitch = 0,
            ),
        )
    }

    @Test
    fun `the whole walk primary to backup and back is driven by request answers`() {
        YouTubeProxy.update(withBackup)
        assertTrue(YouTubeProxy.config.backupUsable)
        assertEquals(ProxyRoute.PRIMARY, YouTubeProxy.activeRoute.value)

        // A proxy request fails: the selector is now addressed to the backup.
        ProxyHealthMonitor.recordUnreachable("test")
        assertEquals(ProxyRoute.BACKUP, YouTubeProxy.activeRoute.value)
        assertTrue(YouTubeProxy.usingBackup)
        assertEquals(9090, selectedPort())

        // The backup answers, and the policy's window decides the way back.
        ProxyHealthMonitor.recordSuccess()
        assertEquals(ProxyRoute.BACKUP, YouTubeProxy.activeRoute.value)
        assertEquals(9090, selectedPort())

        YouTubeProxy.switchRoute(ProxyRoute.PRIMARY, "test")
        assertEquals(ProxyRoute.PRIMARY, YouTubeProxy.activeRoute.value)
        assertFalse(YouTubeProxy.usingBackup)
        assertEquals(8080, selectedPort())
    }

    @Test
    fun `when the backup also fails the route prefers the primary once the bypass lapses`() {
        YouTubeProxy.update(withBackup)
        ProxyHealthMonitor.recordUnreachable("test")
        assertEquals(ProxyRoute.BACKUP, YouTubeProxy.activeRoute.value)

        ProxyHealthMonitor.recordBackupUnreachable()

        assertEquals(ProxyRoute.PRIMARY, YouTubeProxy.activeRoute.value)
        assertTrue(ProxyHealthMonitor.isMarkedUnreachable())
        assertEquals(Proxy.NO_PROXY, YouTubeProxy.proxySelector.select(musicUri).single())
    }

    @Test
    fun `with no backup a failure still goes direct instead of switching`() {
        YouTubeProxy.update(ProxyConfig(mode = ProxyMode.HTTP, host = "primary.example", port = 8080))
        assertFalse(YouTubeProxy.config.backupUsable)

        ProxyHealthMonitor.recordUnreachable("test")

        assertEquals(ProxyRoute.PRIMARY, YouTubeProxy.activeRoute.value)
        assertFalse(YouTubeProxy.usingBackup)
        assertTrue(ProxyHealthMonitor.isMarkedUnreachable())
        assertEquals(Proxy.NO_PROXY, YouTubeProxy.proxySelector.select(musicUri).single())
    }

    @Test
    fun `a malformed backup is ignored rather than disabling the primary`() {
        val config = ProxyConfig(
            mode = ProxyMode.HTTP,
            host = "primary.example",
            port = 8080,
            backupHost = "backup.example:9090",
            backupPort = 9090,
        )
        assertTrue(config.usable)
        assertFalse(config.backupUsable)
        assertEquals(ProxyConfigError.HOST_INVALID, ProxyValidation.validateBackup(config))
    }

    @Test
    fun `a backup port without a backup host is not a configuration`() {
        val normalized = ProxyConfig(
            mode = ProxyMode.HTTP,
            host = "primary.example",
            port = 8080,
            backupPort = 9090,
        ).normalized()

        assertEquals(0, normalized.backupPort)
        assertFalse(normalized.backupUsable)
        assertNull(ProxyValidation.validateBackup(normalized))
    }

    @Test
    fun `without a backup a success on the backup still returns to the primary`() {
        assertEquals(
            ProxyRoute.PRIMARY,
            ProxyRoutePolicy.nextRouteAfterSuccess(
                current = ProxyRoute.BACKUP,
                hasBackup = false,
                msSinceSwitch = 0,
            ),
        )
    }

    @Test
    fun `the primary is retried after fifteen minutes on the backup`() {
        assertEquals(15L * 60L * 1000L, ProxyRoutePolicy.PRIMARY_RETRY_AFTER_MS)
        assertEquals(
            ProxyRoute.BACKUP,
            ProxyRoutePolicy.nextRouteAfterSuccess(
                current = ProxyRoute.BACKUP,
                hasBackup = true,
                msSinceSwitch = ProxyRoutePolicy.PRIMARY_RETRY_AFTER_MS - 1,
            ),
        )
        assertEquals(
            ProxyRoute.PRIMARY,
            ProxyRoutePolicy.nextRouteAfterSuccess(
                current = ProxyRoute.BACKUP,
                hasBackup = true,
                msSinceSwitch = ProxyRoutePolicy.PRIMARY_RETRY_AFTER_MS,
            ),
        )
    }

    private fun selectedPort(): Int =
        (YouTubeProxy.proxySelector.select(musicUri).single().address() as InetSocketAddress).port
}
