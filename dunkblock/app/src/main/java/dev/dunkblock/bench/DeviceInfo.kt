package dev.dunkblock.bench

import android.content.ComponentName
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Display

/** Phase 0: what this device actually is and allows. Everything here is read from the OS, not assumed. */
object DeviceInfo {
    fun short(ctx: Context): String {
        val d = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        return "기기: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
            "현재 표시 ${d.mode.physicalWidth}x${d.mode.physicalHeight}@${"%.1f".format(d.refreshRate)}Hz"
    }

    fun a11yEnabled(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(ctx, BlockAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun full(ctx: Context): String = buildString {
        val d = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val pm = ctx.getSystemService(PowerManager::class.java)
        appendLine("== Phase 0: 기기/OS 확인 (OS에서 읽은 값) ==")
        appendLine("제조사/모델: ${Build.MANUFACTURER} ${Build.MODEL} (device=${Build.DEVICE}, product=${Build.PRODUCT})")
        if (Build.VERSION.SDK_INT >= 31) appendLine("SoC: ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
        appendLine("Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT} / 보안패치 ${Build.VERSION.SECURITY_PATCH}")
        appendLine("빌드: ${Build.DISPLAY}")
        appendLine("CPU 코어 수(앱에서 보이는): ${Runtime.getRuntime().availableProcessors()}")
        appendLine("현재 표시 모드: ${d.mode.physicalWidth}x${d.mode.physicalHeight} @ ${"%.2f".format(d.mode.refreshRate)}Hz (display.refreshRate=${"%.2f".format(d.refreshRate)})")
        appendLine("지원 표시 모드:")
        for (m in d.supportedModes) appendLine("  id=${m.modeId} ${m.physicalWidth}x${m.physicalHeight} @ ${"%.2f".format(m.refreshRate)}Hz")
        appendLine("열 상태: ${pm.currentThermalStatus} (0=없음 … 6=셧다운), 헤드룸(10s): ${try { pm.getThermalHeadroom(10) } catch (_: Throwable) { Float.NaN }}")
        appendLine("접근성 서비스(제스처) 활성화: ${a11yEnabled(ctx)}")
        appendLine("OpenCV: ${OpenCv.status}")
    }
}
