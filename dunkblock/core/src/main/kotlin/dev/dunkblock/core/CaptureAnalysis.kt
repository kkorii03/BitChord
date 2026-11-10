package dev.dunkblock.core

import kotlin.math.abs
import kotlin.math.roundToInt

object TimebaseCheck {
    enum class Verdict { NO_TIMESTAMPS, PLAUSIBLY_SAME_CLOCK, MISMATCH }

    data class Result(val verdict: Verdict, val medianMs: Double, val p5Ms: Double, val p95Ms: Double, val n: Int) {
        fun text() = "$verdict (arrival-imageTs: median=${f(medianMs, 2)}ms p5=${f(p5Ms, 2)}ms p95=${f(p95Ms, 2)}ms n=$n)"
    }

    /**
     * Image.getTimestamp() has a producer-defined timebase; it is NOT assumed to equal System.nanoTime().
     * If both are CLOCK_MONOTONIC, arrival-imageTs is a small positive "buffer age". If they differ, the
     * difference is huge/odd. Only when the check passes are imageTs-derived latencies reported.
     */
    fun evaluate(log: FrameLog, from: Int): Result {
        val n = log.size - from
        if (n < 10) return Result(Verdict.NO_TIMESTAMPS, Double.NaN, Double.NaN, Double.NaN, n.coerceAtLeast(0))
        var nz = 0
        for (i in from until log.size) if (log.imageTsNs[i] != 0L) nz++
        if (nz < n / 2) return Result(Verdict.NO_TIMESTAMPS, Double.NaN, Double.NaN, Double.NaN, n)
        val d = DoubleArray(n) { (log.arrivalNs[from + it] - log.imageTsNs[from + it]) / 1e6 }
        d.sort()
        val med = Stats.percentileSorted(d, 50.0)
        val p5 = Stats.percentileSorted(d, 5.0)
        val p95 = Stats.percentileSorted(d, 95.0)
        val ok = med >= -2.0 && med <= 150.0 && (p95 - p5) <= 100.0
        return Result(if (ok) Verdict.PLAUSIBLY_SAME_CLOCK else Verdict.MISMATCH, med, p5, p95, n)
    }
}

data class LatencyReport(
    /** arrival - expected presentation of the display frame the captured frame shows (ESTIMATE). */
    val displayToArrivalMs: Summary,
    /** arrival - vsync start of that display frame (upper bound of the above, ESTIMATE). */
    val frameStartToArrivalMs: Summary,
    val negativeCount: Int,
    /** Colour-flip test: displayed flips, how many were found in the capture. */
    val flipsDisplayed: Int,
    val flipsCaptured: Int,
    val flipLatencyMs: Summary,
    /** How many display refreshes elapsed between the flip being drawn and the first captured frame showing it. */
    val flipRefreshesLate: Summary,
) {
    fun text(): String = buildString {
        appendLine("  [ESTIMATE] 표시(예상 프레젠테이션) → 앱 수신 지연: ${displayToArrivalMs.fmt("ms")}")
        appendLine("  [ESTIMATE] vsync 시작 → 앱 수신 지연(상한): ${frameStartToArrivalMs.fmt("ms")}")
        appendLine("  음수 지연 프레임(예상 프레젠테이션 시각이 실제보다 늦었다는 뜻): $negativeCount")
        appendLine("  색상 전환: 표시 $flipsDisplayed 회, 캡처에서 확인 $flipsCaptured 회")
        appendLine("  [ESTIMATE] 색상 전환 → 최초 수신 지연: ${flipLatencyMs.fmt("ms")}")
        appendLine("  색상 전환 후 새 색이 보이기까지 지난 표시 리프레시 수: ${flipRefreshesLate.fmt("", 1)}")
    }
}

data class CaptureReport(
    val frames: Int,
    val warmupSkippedFrames: Int,
    val durationSec: Double,
    val avgFps: Double,
    val perSecondFps: Summary,
    val producerDtMs: Summary,
    val arrivalDtMs: Summary,
    val nominalPeriodMs: Double,
    val nominalHz: Double,
    val rateClass: String,
    val nonMonotonicTs: Int,
    val gapsOver1p5: Int,
    val estimatedMissedRefreshes: Int,
    val dupSampled: Int,
    val burstFrames: Int,
    val counterDecoded: Int,
    val counterCoveragePct: Double,
    val displayedSpan: Int,
    val uniqueCounters: Int,
    val dupCounters: Int,
    val missingCounters: Int,
    val captureRatio: Double,
    val uniqueFramesEstimate: Int,
    val uniqueMethod: String,
    val timebase: TimebaseCheck.Result,
    val bufferAgeMs: Summary?,
    val blackFramePct: Double,
    val latency: LatencyReport?,
) {
    fun text(): String = buildString {
        appendLine("== ① 실효 캡처 FPS (측정값) ==")
        appendLine("프레임 수(워밍업 ${warmupSkippedFrames}개 제외): $frames, 구간 ${f(durationSec, 2)}s")
        appendLine("평균 수신 FPS(앱 도착 기준): ${f(avgFps, 2)}")
        appendLine("초당 수신 FPS 분포: ${perSecondFps.fmt("", 1)}")
        appendLine("생산자 프레임 간격(Image.timestamp 차이): ${producerDtMs.fmt("ms")}")
        appendLine("앱 도착 간격(System.nanoTime 차이):       ${arrivalDtMs.fmt("ms")}")
        appendLine("공칭 주기(중앙값) ${f(nominalPeriodMs, 3)}ms → ${f(nominalHz, 1)}Hz → $rateClass")
        appendLine("타임스탬프 비단조 ${nonMonotonicTs}건, 1.5주기 초과 간격 ${gapsOver1p5}건, 추정 누락 리프레시 ${estimatedMissedRefreshes}개")
        appendLine("  (주의: 화면이 계속 바뀌는 구간에서만 '누락'으로 해석 가능. 정지 화면은 프레임이 안 올 수 있음)")
        appendLine("샘플링 중복 프레임(희소 샘플 해시 동일): $dupSampled / 소비자 지연 버스트(한 콜백에 2장 이상): $burstFrames")
        appendLine("고유 프레임 추정: $uniqueFramesEstimate (방법: $uniqueMethod)")
        if (counterDecoded > 0) {
            appendLine("-- 패턴 카운터 기반 정확 계수 (LATENCY 테스트) --")
            appendLine("카운터 디코딩 성공 ${counterDecoded}장 (${f(counterCoveragePct, 1)}%), 표시된 프레임 구간 $displayedSpan")
            appendLine("고유 수신 $uniqueCounters, 중복 수신 $dupCounters, 미수신(누락) $missingCounters, 캡처 비율 ${f(captureRatio * 100, 1)}%")
        }
        appendLine("검은 화면 프레임 비율: ${f(blackFramePct, 1)}%" + if (blackFramePct > 50) "  ← FLAG_SECURE 등으로 캡처가 차단되었을 가능성" else "")
        appendLine()
        appendLine("== ② 타임베이스 점검 ==")
        appendLine(timebase.text())
        if (bufferAgeMs != null) appendLine("버퍼 나이(앱 도착 - Image.timestamp, 같은 시계로 판정된 경우만): ${bufferAgeMs.fmt("ms")}")
        else appendLine("Image.timestamp 기반 지연은 시계 동일성이 확인되지 않아 보고하지 않음")
        if (latency != null) { appendLine("== ② 화면 표시 → 캡처 수신 지연 =="); append(latency.text()) }
        else appendLine("(LATENCY 패턴 없음: 이 세션에서는 캡처 지연을 계산할 수 없음 — 프레임 간격은 지연이 아님)")
    }
}

object CaptureAnalysis {
    fun analyze(log: FrameLog, warmupSec: Double, display: DisplayLog?): CaptureReport {
        val total = log.size
        if (total < 3) return emptyReport(total)
        val t0 = log.arrivalNs[0]
        var from = 0
        while (from < total - 2 && (log.arrivalNs[from] - t0) / 1e9 < warmupSec) from++
        val n = total - from
        if (n < 3) return emptyReport(total)

        val durSec = (log.arrivalNs[total - 1] - log.arrivalNs[from]) / 1e9
        val avgFps = if (durSec > 0) (n - 1) / durSec else Double.NaN

        // per-second fps from arrival
        val secs = durSec.toInt().coerceAtLeast(0)
        val bins = IntArray(secs + 1)
        for (i in from until total) bins[((log.arrivalNs[i] - log.arrivalNs[from]) / 1_000_000_000L).toInt().coerceIn(0, secs)]++
        // the last bin is partial: drop it
        val perSec = if (secs >= 2) DoubleArray(secs) { bins[it].toDouble() } else DoubleArray(0)

        val arrDt = DoubleArray(n - 1) { (log.arrivalNs[from + it + 1] - log.arrivalNs[from + it]) / 1e6 }
        var nonMono = 0
        val prodList = DoubleArray(n - 1)
        var pc = 0
        for (i in from until total - 1) {
            val d = (log.imageTsNs[i + 1] - log.imageTsNs[i]) / 1e6
            if (d <= 0.0) nonMono++ else prodList[pc++] = d
        }
        val prodDt = prodList.copyOf(pc)
        val prodSum = Stats.summarize(prodDt)
        val nominal = if (prodSum.count > 0) prodSum.median else Stats.summarize(arrDt).median
        val hz = if (nominal > 0) 1000.0 / nominal else Double.NaN
        val rateClass = when {
            hz.isNaN() -> "판정불가"
            hz in 100.0..140.0 -> "약 120Hz 급 (60Hz가 아님)"
            hz in 80.0..100.0 -> "약 90Hz 급"
            hz in 50.0..70.0 -> "약 60Hz 급"
            hz in 25.0..40.0 -> "약 30Hz 급"
            else -> "비표준 (${f(hz, 1)}Hz)"
        }
        var gaps = 0
        var missed = 0
        for (d in prodDt) if (d > 1.5 * nominal) { gaps++; missed += (d / nominal).roundToInt() - 1 }

        var dup = 0; var burst = 0; var black = 0
        for (i in from until total) {
            if (log.dupSampled[i]) dup++
            if (log.burst[i] >= 2) burst++
            if (log.meanLuma100[i] < 100) black++
        }

        // ---- pattern counters
        val dispSize = display?.size ?: 0
        val idx = IntArray(n) { -1 }
        var decoded = 0
        var prevU = -1
        for (k in 0 until n) {
            val c = log.counter[from + k]
            if (c < 0) continue
            decoded++
            idx[k] = unwrap(c, prevU, dispSize)
            if (idx[k] >= 0) prevU = idx[k]
        }
        var span = 0; var uniq = 0; var dupC = 0; var missing = 0; var ratio = Double.NaN
        if (decoded > 0) {
            var mn = Int.MAX_VALUE; var mx = Int.MIN_VALUE
            val seen = HashSet<Int>()
            for (u in idx) if (u >= 0) { if (u < mn) mn = u; if (u > mx) mx = u; seen.add(u) }
            if (mx >= mn) {
                span = mx - mn + 1; uniq = seen.size
                val valid = idx.count { it >= 0 }
                dupC = valid - uniq; missing = span - uniq; ratio = uniq.toDouble() / span
            }
        }
        val coverage = 100.0 * decoded / n
        val uniqueEst: Int; val method: String
        if (decoded > 0 && coverage >= 90.0 && span > 0) { uniqueEst = uniq; method = "화면 카운터(정확)" }
        else { uniqueEst = n - dup; method = "희소 샘플 해시(근사: 작은 변화는 중복으로 오판 가능)" }

        val tb = TimebaseCheck.evaluate(log, from)
        val age = if (tb.verdict == TimebaseCheck.Verdict.PLAUSIBLY_SAME_CLOCK)
            Stats.summarize(DoubleArray(n) { (log.arrivalNs[from + it] - log.imageTsNs[from + it]) / 1e6 }) else null

        val latency = if (display != null && decoded > 0 && dispSize > 0) latency(log, from, n, idx, display, tb) else null

        return CaptureReport(
            frames = n, warmupSkippedFrames = from, durationSec = durSec, avgFps = avgFps,
            perSecondFps = Stats.summarize(perSec), producerDtMs = prodSum, arrivalDtMs = Stats.summarize(arrDt),
            nominalPeriodMs = nominal, nominalHz = hz, rateClass = rateClass, nonMonotonicTs = nonMono,
            gapsOver1p5 = gaps, estimatedMissedRefreshes = missed, dupSampled = dup, burstFrames = burst,
            counterDecoded = decoded, counterCoveragePct = coverage, displayedSpan = span, uniqueCounters = uniq,
            dupCounters = dupC, missingCounters = missing, captureRatio = ratio, uniqueFramesEstimate = uniqueEst,
            uniqueMethod = method, timebase = tb, bufferAgeMs = age, blackFramePct = 100.0 * black / n, latency = latency,
        )
    }

    /** Picks the display index whose low 16 bits equal [c16], closest at-or-after [prev] (or the smallest valid one first). */
    internal fun unwrap(c16: Int, prev: Int, dispSize: Int): Int {
        if (dispSize <= 0) return if (prev < 0) c16 else {
            // no display log: unwrap relative to prev only
            var u = c16 + (prev and 0xFFFF.inv())
            if (u < prev - 32768) u += 65536
            u
        }
        var best = -1
        var bestScore = Long.MAX_VALUE
        var k = c16
        while (k < dispSize) {
            val score = if (prev < 0) k.toLong() else abs((k - prev).toLong())
            if (score < bestScore) { bestScore = score; best = k }
            k += 65536
        }
        return best
    }

    private fun latency(log: FrameLog, from: Int, n: Int, idx: IntArray, d: DisplayLog, tb: TimebaseCheck.Result): LatencyReport {
        val toExpected = ArrayList<Double>(n)
        val toStart = ArrayList<Double>(n)
        var neg = 0
        for (k in 0 until n) {
            val u = idx[k]
            if (u < 0 || u >= d.size) continue
            val arr = log.arrivalNs[from + k]
            val e = (arr - d.expectedPresentNs[u]) / 1e6
            toExpected.add(e)
            toStart.add((arr - d.frameTimeNs[u]) / 1e6)
            if (e < 0) neg++
        }
        // colour flips — only those inside the analysed window [first, last captured display index];
        // a flip from the discarded warm-up would otherwise pair with the first analysed frame.
        val lat = ArrayList<Double>()
        val late = ArrayList<Double>()
        var flipsDisp = 0
        var p = 0
        var firstIdx = Int.MAX_VALUE; var lastIdx = -1
        for (u in idx) if (u >= 0) { if (u < firstIdx) firstIdx = u; if (u > lastIdx) lastIdx = u }
        for (fi in 0 until d.size) {
            val st = d.flipState[fi].toInt()
            if (st < 0 || fi <= firstIdx || fi > lastIdx) continue
            flipsDisp++
            while (p < n && (idx[p] < 0 || idx[p] < fi)) p++
            var q = p
            while (q < n && (idx[q] < 0 || idx[q] <= fi + 120)) {
                if (idx[q] >= 0 && log.patLuma[from + q] >= 0 &&
                    (if (log.patLuma[from + q] >= PatternCodec.LUMA_THRESHOLD) 1 else 0) == st) {
                    lat.add((log.arrivalNs[from + q] - d.expectedPresentNs[fi]) / 1e6)
                    late.add((idx[q] - fi).toDouble())
                    break
                }
                q++
            }
        }
        return LatencyReport(
            Stats.summarize(toExpected.toDoubleArray()), Stats.summarize(toStart.toDoubleArray()), neg,
            flipsDisp, lat.size, Stats.summarize(lat.toDoubleArray()), Stats.summarize(late.toDoubleArray()),
        )
    }

    private fun emptyReport(total: Int) = CaptureReport(
        total, 0, 0.0, Double.NaN, Summary.EMPTY, Summary.EMPTY, Summary.EMPTY, Double.NaN, Double.NaN, "데이터 부족",
        0, 0, 0, 0, 0, 0, 0.0, 0, 0, 0, 0, Double.NaN, 0, "-",
        TimebaseCheck.Result(TimebaseCheck.Verdict.NO_TIMESTAMPS, Double.NaN, Double.NaN, Double.NaN, 0), null, 0.0, null,
    )
}

/** Per-stage processing-time report (③). */
object TimingReport {
    fun text(log: FrameLog, warmupSec: Double, targetAnalysisMs: Double = 10.0): String {
        val total = log.size
        if (total < 3) return "데이터 부족"
        var from = 0
        while (from < total - 2 && (log.arrivalNs[from] - log.arrivalNs[0]) / 1e9 < warmupSec) from++
        fun s(a: IntArray) = Stats.summarizeInts(a, from, total, 1e-3)
        val analysis = s(log.tAnalysisUs)
        return buildString {
            appendLine("== ③ 영상 처리시간 (ms, 측정값, 워밍업 ${warmupSec}s 제외) ==")
            appendLine("버퍼 접근(acquire):      ${s(log.tCopyUs).fmt("", 3)}")
            appendLine("전처리(축소+그레이):     ${s(log.tPreUs).fmt("", 3)}")
            appendLine("ROI 추출:                ${s(log.tRoiUs).fmt("", 3)}")
            appendLine("움직임 분석:             ${s(log.tMotionUs).fmt("", 3)}")
            appendLine("분석 합계(전처리~검출):  ${analysis.fmt("", 3)}")
            appendLine("콜백 전체(로그 포함):    ${s(log.tTotalUs).fmt("", 3)}")
            var anyAlt = false
            for (i in from until total) if (log.tAltAnalysisUs[i] > 0) { anyAlt = true; break }
            if (anyAlt) appendLine("보조 백엔드 분석 합계:   ${s(log.tAltAnalysisUs).fmt("", 3)}")
            if (analysis.count > 0) {
                val over = (from until total).count { log.tAnalysisUs[it] / 1000.0 > targetAnalysisMs }
                appendLine("임시 목표 ${targetAnalysisMs}ms 초과 프레임: $over / ${analysis.count} (${f(100.0 * over / analysis.count, 2)}%), p95 ${if (analysis.p95 <= targetAnalysisMs) "충족" else "미충족"}")
            }
        }
    }
}
