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
        /** adb extra only, never saved: unattended bot-game tests (nobody touches, the game's 자동 진행 plays), so
         *  InGameProxy.fireBlock skips its "a finger touched in the last 30 s" check. 0-4z54: bot game 021923 skipped all 4. */
        val unattended: Boolean = false,
        /** Experimental: also press when the ball handler lifts ball and hands to the marker (ShotWatcher.checkUpPending). */
        val upPress: Boolean = false,
        /** Experimental, for quick-release shooters only: press when the standing ball handler bends his knees (ShotWatcher.crouch). */
        val crouchPress: Boolean = false,
        /**
         * Experimental (vc1143): press when the d2 marker starts its shot climb (ShotWatcher.kin). Offline on 42 match
         * shots: fit games 9/35 in the 205-296 ms window, held-out game 3/7, ~10 false fires per minute of defence.
         * While on, the other shot rules (all late in matches, 0/17) only log would_press.
         */
        val kinPress: Boolean = false,
        /** Experimental: press when the ring-checked ball disc rises fast (ShotWatcher.rise2Press). Recorded matches only, never run on the phone. */
        val rise2Press: Boolean = false,
        /**
         * Experimental, off (0) by default: same triggers, but the tap leaves earlier (ShotWatcher.preArm). 1 = open the
         * block tile ahead of a likely trigger so the press skips the 40 ms pass-through wait; 2 = also drop the 25 ms
         * hold of early-only presses. Replayed on recorded bundles only (HANDOFF 0-4z8), never run on the phone.
         */
        val fastPress: Int = 0,
        /**
         * Experimental, off by default (HANDOFF 0-4z10): drop a shot trigger whose ball is not next to my own player
         * (core NearGate on the matchup shadow's ring; ShotWatcher.notMine), or when my player is not found. Separated
         * all 20 auto presses of two recorded phone matches; never run on the phone.
         */
        val nearOnly: Boolean = false,
        /** Calibration, PRACTICE COURT ONLY: press on the practice rep's own clock at stepped leads before the release (ShotWatcher.sweep). */
        val sweepPress: Boolean = false,
        /**
         * With [sweepPress]: one fixed lead (ms before the predicted release) instead of stepping SWEEP_LEADS; 0 = step.
         * 240 = middle of the measured practice block window 211-265 ms (tap clock, vc1056/vc1035 sweeps, HANDOFF 0-4f).
         * Replayed on the 11-12 16:13 practice bundle (not used to set it): 14/21 shots inside 190-290 ms, the gap
         * trigger 0/21. Practice court only: a match has no rep clock (3/78 on the six other bundles, whose repT is not constant). HANDOFF 0-4z27.
         * Since vc1129 it presses only on a steady beat (ShotWatcher.SWEEP_TOL_NS) and no longer holds the shot rules back.
         */
        val sweepLeadMs: Int = 0,
        /**
         * Experimental (vc1067): press darkDelayMs after a signature skill starts to darken the screen (dark2DelayMs for the
         * dark after a fake). ShotWatcher.skillDark. 215: measured 11-11 on Nowitzki "위닝 슛" only (practice court, ~60
         * presses at 0-500 ms): blocked at 150-300 ms (22 of 25), never at <= 100 or >= 325. Other skills differ (his
         * "위닝 페이드어웨이" was not blocked at any delay 0-750; 아일라's dark is a jump with the release ~0.8 s later).
         */
        val darkPress: Boolean = false,
        val darkDelayMs: Int = 215,
        val dark2DelayMs: Int = 380,
        /**
         * Delay for a dark that starts as a jump (아일라 "기습 스텝백"). 540: measured 11-11 by the game's own verdict
         * text, 21 presses at 323-808 ms: "타이밍 빠름" up to 425, "타이밍 늦음" from 656, neither at 442-638 (there
         * "블록 위치 부족" / "컨테스트 실패": the standing defender was too far, so no block was seen at any delay).
         */
        val darkJumpDelayMs: Int = 540,
        /** Calibration, PRACTICE COURT ONLY: press at stepped delays (+ darkAddMs) after the onset of dark number darkNth; every other press is held back. */
        val darkSweep: Boolean = false,
        val darkNth: Int = 1,
        val darkAddMs: Int = 0,
        /** With darkSweep: from the onset hold the joystick this way (degrees, 0 = screen right, 90 = up; -1 = off) for darkJoyMs (0 = until 300 ms after the press). */
        val darkJoyDeg: Int = -1,
        val darkJoyMs: Int = 0,
        /**
         * Experiment, PRACTICE COURT ONLY, never tried on the phone (11-12): push the joystick towards my matchup
         * (core FollowRule on the record-only matchup scan). 0 = off, 1 = only while the user's thumb rests on the
         * joystick pad (moving the thumb takes over), 2 = nobody holds the phone (adb self-test), 3 = only while the
         * thumb is OFF the pad (putting it down takes over), 4 = 1 + 3 + the steering thumb's direction turned up to
         * 30 degrees towards the matchup, 5 = hold the game's "수비" button through the defence (joystick
         * untouched, no player recognition). InGameProxy.steer / defHold.
         */
        val follow: Int = 0,
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
