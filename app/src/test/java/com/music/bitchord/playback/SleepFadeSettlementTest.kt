package com.music.bitchord.playback

import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A sleep fade is a transition for as long as it runs, so callers that parked
 * work on the settled event (the output-reconfigure gate) must be woken when
 * it returns to IDLE — not only when an ordinary crossfade does.
 */
class SleepFadeSettlementTest {

    @After
    fun resetTimer() {
        SleepTimer.cancel()
    }

    @Test
    fun `a route change parked during a sleep fade runs when it ends`() {
        val gate = OutputReconfigureGate()
        var applied = false
        SleepTimer.startAfterTrack()
        val controller = controller(player(), onSettled = {
            if (gate.settled()) applied = true
        })

        invokeLong(controller, "beginSleepFadeOut", 1_000L)
        assertFalse("the route change waits for the fade", gate.request(transitioning = true))
        invoke(controller, "finishSleepFade")

        assertTrue("the fade's settled event releases the parked rebuild", applied)
    }

    @Test
    fun `a finished sleep fade reports settled exactly once`() {
        var settled = 0
        SleepTimer.startAfterTrack()
        val controller = controller(player(), onSettled = { settled++ })

        invokeLong(controller, "beginSleepFadeOut", 1_000L)
        invoke(controller, "finishSleepFade")

        assertEquals("the parked rebuild is woken by the sleep fade exit", 1, settled)
        assertFalse(controller.isTransitioning())
    }

    @Test
    fun `turning the timer off mid fade also reports settled`() {
        var settled = 0
        SleepTimer.startAfterTrack()
        val controller = controller(player(), onSettled = { settled++ })

        invokeLong(controller, "beginSleepFadeOut", 1_000L)
        SleepTimer.cancel()
        invoke(controller, "driveSleepFade")

        assertEquals("the timer-off exit is a settled transition too", 1, settled)
        assertFalse(controller.isTransitioning())
    }

    private fun controller(player: ExoPlayer, onSettled: () -> Unit) =
        CrossfadeController(
            scope = CoroutineScope(Dispatchers.Unconfined),
            active = { player },
            standby = { player },
            onHandoff = { _, _ -> },
            onSettled = onSettled,
        )

    private fun invoke(target: Any, name: String) {
        val method = target.javaClass.getDeclaredMethod(name)
        method.isAccessible = true
        method.invoke(target)
    }

    private fun invokeLong(target: Any, name: String, value: Long) {
        val method = target.javaClass.getDeclaredMethod(name, Long::class.javaPrimitiveType)
        method.isAccessible = true
        method.invoke(target, value)
    }

    private fun player(): ExoPlayer {
        val handler = java.lang.reflect.InvocationHandler { proxy, method, args ->
            when (method.name) {
                "getCurrentPosition" -> 0L
                "getVolume" -> 1f
                "getPlaybackState" -> Player.STATE_READY
                "toString" -> "sleep-fade-test-player"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> defaultValue(method.returnType)
            }
        }
        return Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java),
            handler,
        ) as ExoPlayer
    }

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> '\u0000'
        else -> null
    }
}
