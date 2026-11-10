package dev.dunkblock.bench

import dev.dunkblock.core.AttackEvent
import dev.dunkblock.core.AttackEventDetector
import dev.dunkblock.core.Csv
import dev.dunkblock.core.DisplayLog
import dev.dunkblock.core.FrameLog
import dev.dunkblock.core.TruthEvent
import java.util.concurrent.CopyOnWriteArrayList

/** Process-wide shared state between the control UI, the capture service and the test screens. */
object Bench {
    enum class Mode(val label: String) {
        GAME("게임 캡처"), LATENCY("지연·FPS 패턴"), MOTION("합성 모션 검출")
    }

    enum class Backend(val label: String) {
        KOTLIN("Kotlin(무의존)"), OPENCV("OpenCV"), BOTH("둘 다(비교용, 부하↑)"), NONE("캡처만(분석 없음)")
    }

    data class Config(
        val mode: Mode = Mode.GAME,
        val captureScale: Float = 0.5f,
        val analysisWidth: Int = 192,
        val backend: Backend = Backend.KOTLIN,
        val confirmFrames: Int = 2,
        val diffThreshold: Int = 24,
        val excludeHud: Boolean = true,
        val evidence: Boolean = true,
        /** GAME mode: save a colour frame every [sampleEveryMs] for designing the recognition step (offline). */
        val sampleFrames: Boolean = true,
        val sampleEveryMs: Long = 2000,
        val sampleWidth: Int = 960,
        /** Block button / joystick centre as fractions of the landscape screen (from ⑦ calibration). */
        val blockFx: Float = 0.889f,
        val blockFy: Float = 0.835f,
        val joyFx: Float = 0.207f,
        val joyFy: Float = 0.769f,
        /** GAME mode: when the block button visibly changes (the user pressed it), save a 60 fps clip around it. */
        val pressClips: Boolean = true,
        /** ⑧ auto-block draft: see AutoBlocker. */
        val autoBlock: Boolean = false,
        val autoBlockLive: Boolean = true,
        val ultDelayMs: Int = 600,
        /** Regular-shot auto-block (ShotWatcher trigger v1). Sensitivity 0..2, extra delay after detection. */
        val shotBlock: Boolean = true,
        val shotSens: Int = 1,
        val shotDelayMs: Int = 0,
        val recalibrate: Boolean = false,
        val opencvAreaResize: Boolean = false,
        val opencvThreads: Int = 1,
        val warmupSec: Double = 5.0,
        val patternSeconds: Int = 30,
        val motionTrials: Int = 24,
        val cameraAck: Boolean = false,
    )

    @Volatile var config = Config()

    /** One capture session; created by the service, read by the UI and test screens. */
    class Session(val config: Config, val startedWallMs: Long, val startedNs: Long) {
        val frames = FrameLog(FRAME_CAPACITY)
        val display: DisplayLog? = if (config.mode != Mode.GAME) DisplayLog(DISPLAY_CAPACITY) else null
        val truth = CopyOnWriteArrayList<TruthEvent>()
        val seconds = CopyOnWriteArrayList<Csv.SecondRow>()
        @Volatile var detector: AttackEventDetector? = null
        @Volatile var lastEvent: AttackEvent? = null
        @Volatile var eventCount = 0
        @Volatile var captureW = 0
        @Volatile var captureH = 0
        @Volatile var notes = StringBuilder()
        @Volatile var error: String? = null
        fun note(s: String) = synchronized(notes) { notes.append(s).append('\n') }
    }

    @Volatile var session: Session? = null
    @Volatile var running = false
    @Volatile var lastSummary: String = ""
    /** True between "stop" and "results written" (a few seconds), so the UI can say so. */
    @Volatile var saving = false
    /** Input-side report of the last ⑧ run (overlay/joystick statistics), merged into the session summary. */
    @Volatile var autoReport: String = ""
    /** One-line live state of the auto-blocker for the UI. */
    @Volatile var autoLive: String = ""
    /** Ask the running capture session to save a clip around now: (tag, secondsBefore, secondsAfter). Null when not capturing. */
    @Volatile var clipRequester: ((String, Double, Double) -> Unit)? = null
    /** ShotWatcher's latest reading of the block-button slot: offence / defence / unknown (ShotWatcher.SIDE_*). */
    @Volatile var shotSide: Int = 0
    /** One-line live state of the regular-shot trigger for the UI. */
    @Volatile var shotLive: String = ""
    @Volatile var lastExportDir: String = ""

    /** Live numbers for the control screen (written by the 1 Hz sampler). */
    @Volatile var liveText: String = ""

    /** Capture thread → test screen: the colour-flip box was seen in a captured frame (for the camera ACK square). */
    @Volatile var flipSeenListener: ((Int) -> Unit)? = null

    /** Test screen → service: finish the session when the scripted test is over. */
    @Volatile var stopRequester: (() -> Unit)? = null

    const val FRAME_CAPACITY = 200_000      // ~27 min at 120 fps, ~14 MB
    const val DISPLAY_CAPACITY = 120_000
}
