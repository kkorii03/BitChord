package dev.dunkblock.bench

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

/**
 * Used ONLY to test whether an injected tap (dispatchGesture) disturbs a finger that is already holding a
 * joystick. It does not read screen content.
 */
class BlockAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /**
     * Dispatches a single-point tap. [done] runs on the main thread with (completed, System.nanoTime()).
     * Returns false if the system refused to dispatch.
     */
    fun tap(x: Float, y: Float, durationMs: Long, done: (Boolean, Long) -> Unit): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1)))
            .build()
        return dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) = done(true, System.nanoTime())
            override fun onCancelled(gestureDescription: GestureDescription?) = done(false, System.nanoTime())
        }, null)
    }

    companion object {
        @Volatile var instance: BlockAccessibilityService? = null
    }
}
