package dev.dunkblock.core

/**
 * Ground truth for the synthetic MOTION test: the test activity knows exactly at which display frame each
 * scripted "attack" (JUMP) or "fake" (FAKE: short rise then abort) started, and that frame's expected
 * presentation time (System.nanoTime base). Matching detector events against it gives a TRUE
 * start-to-detection latency for the synthetic scene — the one place where "attack start" is known exactly.
 * It says nothing about real game footage, which needs manually labelled start frames.
 */
class TruthEvent(val displayIdx: Int, val startNs: Long, val kind: String) {
    var matchedEventId = -1
    var detectDelayMs = Double.NaN
    var onsetDelayMs = Double.NaN
}

data class TruthReport(
    val jumps: Int,
    val jumpsDetected: Int,
    val fakes: Int,
    val fakesTriggered: Int,
    val unmatchedEvents: Int,
    /** Event confirmation time - true start (expected presentation of the first moving frame). */
    val detectDelayMs: Summary,
    val onsetDelayMs: Summary,
    val fakeDetectDelayMs: Summary,
) {
    fun text() = buildString {
        appendLine("== ④ 합성 공격 정답 대비 검출 성능 (합성 장면 한정, 실제 게임 성능 아님) ==")
        appendLine("점프(공격) ${jumps}회 중 검출 $jumpsDetected 회 (${f(if (jumps > 0) 100.0 * jumpsDetected / jumps else Double.NaN, 1)}%)")
        appendLine("페이크 ${fakes}회 중 이벤트 발생 $fakesTriggered 회 ← 이 값이 크면 '페이크에 블록'하게 됨")
        appendLine("정답과 매칭되지 않은 이벤트(오탐) $unmatchedEvents 건")
        appendLine("[측정값*] 실제 시작(예상 표시시각) → 검출 확정: ${detectDelayMs.fmt("ms")}")
        appendLine("[측정값*] 실제 시작 → 검출기가 판단한 시작(onset): ${onsetDelayMs.fmt("ms")}")
        appendLine("페이크에서 이벤트가 확정되기까지: ${fakeDetectDelayMs.fmt("ms")}")
        appendLine("  * 시작 시각은 Choreographer 예상 표시시각(±1 리프레시)을 기준으로 함")
    }
}

object TruthMatcher {
    fun match(truth: List<TruthEvent>, events: List<AttackEvent>, earlyToleranceMs: Double = 30.0, windowMs: Double = 600.0): TruthReport {
        val used = BooleanArray(events.size)
        for (t in truth) {
            for ((k, e) in events.withIndex()) {
                if (used[k]) continue
                val d = (e.onsetNs - t.startNs) / 1e6
                if (d >= -earlyToleranceMs && d <= windowMs) {
                    used[k] = true
                    t.matchedEventId = e.id
                    t.onsetDelayMs = d
                    t.detectDelayMs = (e.detectNs - t.startNs) / 1e6
                    break
                }
            }
        }
        val jumps = truth.filter { it.kind == "JUMP" }
        val fakes = truth.filter { it.kind == "FAKE" }
        return TruthReport(
            jumps = jumps.size,
            jumpsDetected = jumps.count { it.matchedEventId >= 0 },
            fakes = fakes.size,
            fakesTriggered = fakes.count { it.matchedEventId >= 0 },
            unmatchedEvents = used.count { !it },
            detectDelayMs = Stats.summarize(jumps.filter { it.matchedEventId >= 0 }.map { it.detectDelayMs }.toDoubleArray()),
            onsetDelayMs = Stats.summarize(jumps.filter { it.matchedEventId >= 0 }.map { it.onsetDelayMs }.toDoubleArray()),
            fakeDetectDelayMs = Stats.summarize(fakes.filter { it.matchedEventId >= 0 }.map { it.detectDelayMs }.toDoubleArray()),
        )
    }

    const val CSV_HEADER = "display_idx,start_ns,kind,matched_event_id,onset_delay_ms,detect_delay_ms"

    fun csv(truth: List<TruthEvent>, out: Appendable) {
        out.append(CSV_HEADER).append('\n')
        for (t in truth) out.append("${t.displayIdx},${t.startNs},${t.kind},${t.matchedEventId},${f(t.onsetDelayMs, 3)},${f(t.detectDelayMs, 3)}\n")
    }
}

/** Display-side (what the test activity achieved): refresh cadence and UI-thread jank. */
object DisplayReport {
    fun text(d: DisplayLog): String {
        if (d.size < 3) return "표시 로그 없음\n"
        val dt = Stats.deltas(d.frameTimeNs, d.size, 1e-6)
        val s = Stats.summarize(dt)
        val jank = dt.count { it > 1.5 * s.median }
        return buildString {
            appendLine("== 테스트 화면 표시 측 (Choreographer, 측정값) ==")
            appendLine("표시 프레임 ${d.size}개, 프레임 간격 ${s.fmt("ms")} → ${f(1000.0 / s.median, 1)}Hz")
            appendLine("1.5주기 초과(테스트 화면 자체 끊김) ${jank}회 — 이 프레임들의 지연 추정은 부정확할 수 있음")
        }
    }
}
