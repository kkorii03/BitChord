package com.music.bitchord.data.net

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every branch of the primary/backup route decision. The route only moves on a
 * request's answer, so the retry window is passed in rather than slept through.
 */
class ProxyRoutePolicyEdgeTest {

    @Test
    fun `a failure with a backup always names the backup`() {
        assertEquals(ProxyRoute.BACKUP, ProxyRoutePolicy.nextRouteAfterFailure(hasBackup = true))
    }

    @Test
    fun `a failure without a backup keeps the old primary route`() {
        assertEquals(ProxyRoute.PRIMARY, ProxyRoutePolicy.nextRouteAfterFailure(hasBackup = false))
    }

    @Test
    fun `without a backup every success returns to the primary`() {
        for (current in ProxyRoute.entries) {
            for (elapsed in listOf(-1L, 0L, 1L, ProxyRoutePolicy.PRIMARY_RETRY_AFTER_MS, Long.MAX_VALUE)) {
                assertEquals(
                    ProxyRoute.PRIMARY,
                    ProxyRoutePolicy.nextRouteAfterSuccess(
                        current = current,
                        hasBackup = false,
                        msSinceSwitch = elapsed,
                    ),
                    "current=$current elapsed=$elapsed",
                )
            }
        }
    }

    @Test
    fun `a success on the primary keeps the primary whatever the window`() {
        for (elapsed in listOf(-1L, 0L, 1L, ProxyRoutePolicy.PRIMARY_RETRY_AFTER_MS - 1, Long.MAX_VALUE)) {
            assertEquals(
                ProxyRoute.PRIMARY,
                ProxyRoutePolicy.nextRouteAfterSuccess(
                    current = ProxyRoute.PRIMARY,
                    hasBackup = true,
                    msSinceSwitch = elapsed,
                ),
                "elapsed=$elapsed",
            )
        }
    }

    @Test
    fun `a success on the backup stays there until the retry window opens`() {
        val before = ProxyRoutePolicy.PRIMARY_RETRY_AFTER_MS - 1L
        assertEquals(
            ProxyRoute.BACKUP,
            ProxyRoutePolicy.nextRouteAfterSuccess(
                current = ProxyRoute.BACKUP,
                hasBackup = true,
                msSinceSwitch = before,
            ),
        )
        assertEquals(
            ProxyRoute.BACKUP,
            ProxyRoutePolicy.nextRouteAfterSuccess(
                current = ProxyRoute.BACKUP,
                hasBackup = true,
                msSinceSwitch = 0L,
            ),
        )
        assertEquals(
            ProxyRoute.BACKUP,
            ProxyRoutePolicy.nextRouteAfterSuccess(
                current = ProxyRoute.BACKUP,
                hasBackup = true,
                msSinceSwitch = -1L,
            ),
        )
    }

    @Test
    fun `the retry window boundary is inclusive`() {
        assertEquals(
            ProxyRoute.PRIMARY,
            ProxyRoutePolicy.nextRouteAfterSuccess(
                current = ProxyRoute.BACKUP,
                hasBackup = true,
                msSinceSwitch = ProxyRoutePolicy.PRIMARY_RETRY_AFTER_MS,
            ),
        )
        assertEquals(
            ProxyRoute.PRIMARY,
            ProxyRoutePolicy.nextRouteAfterSuccess(
                current = ProxyRoute.BACKUP,
                hasBackup = true,
                msSinceSwitch = ProxyRoutePolicy.PRIMARY_RETRY_AFTER_MS + 1L,
            ),
        )
    }

    @Test
    fun `the retry window is fifteen minutes`() {
        assertEquals(900_000L, ProxyRoutePolicy.PRIMARY_RETRY_AFTER_MS)
    }
}
