package dev.dunkblock.core

/** CSV serialisation of the raw logs. The raw CSVs are the source of truth; tools/analyze.py recomputes from them. */
object Csv {
    const val FRAMES_HEADER = "seq,arrival_ns,image_ts_ns,burst,counter,pat_luma,sample_hash,dup_sampled,mean_luma_x100," +
        "t_copy_us,t_pre_us,t_roi_us,t_motion_us,t_analysis_us,t_total_us,t_alt_analysis_us,global_motion,local_motion,block_roi_luma_x100"

    fun frames(log: FrameLog, out: Appendable) {
        out.append(FRAMES_HEADER).append('\n')
        for (i in 0 until log.size) {
            out.append(i.toString()).append(',')
                .append(log.arrivalNs[i].toString()).append(',')
                .append(log.imageTsNs[i].toString()).append(',')
                .append(log.burst[i].toString()).append(',')
                .append(log.counter[i].toString()).append(',')
                .append(log.patLuma[i].toString()).append(',')
                .append(log.sampleHash[i].toString()).append(',')
                .append(if (log.dupSampled[i]) "1" else "0").append(',')
                .append(log.meanLuma100[i].toString()).append(',')
                .append(log.tCopyUs[i].toString()).append(',')
                .append(log.tPreUs[i].toString()).append(',')
                .append(log.tRoiUs[i].toString()).append(',')
                .append(log.tMotionUs[i].toString()).append(',')
                .append(log.tAnalysisUs[i].toString()).append(',')
                .append(log.tTotalUs[i].toString()).append(',')
                .append(log.tAltAnalysisUs[i].toString()).append(',')
                .append(f(log.globalMotion[i].toDouble(), 5)).append(',')
                .append(f(log.localMotion[i].toDouble(), 5)).append(',')
                .append(log.blockRoiLuma100[i].toString()).append('\n')
        }
    }

    const val DISPLAY_HEADER = "idx,frame_time_ns,expected_present_ns,flip_state"

    fun display(d: DisplayLog, out: Appendable) {
        out.append(DISPLAY_HEADER).append('\n')
        for (i in 0 until d.size) {
            out.append(i.toString()).append(',').append(d.frameTimeNs[i].toString()).append(',')
                .append(d.expectedPresentNs[i].toString()).append(',').append(d.flipState[i].toString()).append('\n')
        }
    }

    const val EVENTS_HEADER = "id,onset_ns,onset_image_ts_ns,detect_ns,end_ns,confirm_delay_ms,duration_ms,rise_ms," +
        "peak,peak_global,net_up,pattern,merged_bursts"

    fun events(list: List<AttackEvent>, out: Appendable) {
        out.append(EVENTS_HEADER).append('\n')
        for (e in list) {
            out.append(e.id.toString()).append(',').append(e.onsetNs.toString()).append(',')
                .append(e.onsetImageTs.toString()).append(',').append(e.detectNs.toString()).append(',')
                .append(e.endNs.toString()).append(',').append(f(e.confirmDelayMs, 3)).append(',')
                .append(f(e.durationMs, 3)).append(',').append(f(e.riseMs, 3)).append(',')
                .append(f(e.peak.toDouble(), 4)).append(',').append(f(e.peakGlobal.toDouble(), 4)).append(',')
                .append(f(e.netUp.toDouble(), 4)).append(',').append(e.pattern).append(',')
                .append(e.mergedBursts.toString()).append('\n')
        }
    }

    /** Fixed-schema one-line-per-second thermal / load log. */
    const val SECONDS_HEADER = "t_s,fps,analysis_mean_ms,analysis_p95_ms,own_cpu_cores,thermal_status,thermal_headroom,battery_temp_c,cpu_freq_khz_max"

    class SecondRow(
        val tS: Int, val fps: Int, val analysisMeanMs: Double, val analysisP95Ms: Double, val ownCpuCores: Double,
        val thermalStatus: Int, val thermalHeadroom: Double, val batteryTempC: Double, val cpuFreqKhzMax: Long,
    )

    fun seconds(rows: List<SecondRow>, out: Appendable) {
        out.append(SECONDS_HEADER).append('\n')
        for (r in rows) {
            out.append(r.tS.toString()).append(',').append(r.fps.toString()).append(',')
                .append(f(r.analysisMeanMs, 3)).append(',').append(f(r.analysisP95Ms, 3)).append(',')
                .append(f(r.ownCpuCores, 3)).append(',').append(r.thermalStatus.toString()).append(',')
                .append(f(r.thermalHeadroom, 3)).append(',').append(f(r.batteryTempC, 1)).append(',')
                .append(r.cpuFreqKhzMax.toString()).append('\n')
        }
    }
}

/** Assembles the human-readable end-of-session summary. */
object SessionReport {
    fun build(
        header: String,
        log: FrameLog,
        display: DisplayLog?,
        events: List<AttackEvent>,
        detector: AttackEventDetector?,
        seconds: List<Csv.SecondRow>,
        warmupSec: Double = 5.0,
        truth: List<TruthEvent>? = null,
    ): String {
        val cap = CaptureAnalysis.analyze(log, warmupSec, display)
        return buildString {
            appendLine(header.trimEnd())
            appendLine()
            if (log.overflowed) appendLine("!! 프레임 로그 용량 초과: 이후 프레임은 기록되지 않았습니다 !!")
            append(cap.text())
            if (display != null && display.size > 0) { appendLine(); append(DisplayReport.text(display)) }
            appendLine()
            append(TimingReport.text(log, warmupSec))
            appendLine()
            appendLine("== ④ 움직임 이벤트 (자동 기록; 실제 공격 종류/시작은 수동 검증 필요) ==")
            appendLine("이벤트 ${events.size}건, 병합된 중복 ${detector?.mergedDuplicates ?: 0}건, 전체화면 모션으로 무시된 프레임 ${detector?.suppressedGlobalFrames ?: 0}개")
            val confirm = Stats.summarize(DoubleArray(events.size) { events[it].confirmDelayMs })
            appendLine("검출기 내부 확정 지연(첫 임계 초과 → 확정): ${confirm.fmt("ms")}")
            appendLine("  → 이것은 알고리즘 확정 지연일 뿐이며 '실제 동작 시작 대비 지연'이 아닙니다.")
            for (e in events.take(50)) {
                appendLine("  #${e.id} onset=${f((e.onsetNs - log.arrivalNs[0]) / 1e9, 3)}s dur=${f(e.durationMs, 0)}ms peak=${f(e.peak.toDouble(), 3)} ${e.pattern} merged=${e.mergedBursts}")
            }
            if (events.size > 50) appendLine("  ... (나머지는 events.csv)")
            if (truth != null && truth.isNotEmpty()) { appendLine(); append(TruthMatcher.match(truth, events).text()) }
            appendLine()
            appendLine("== 발열/부하 추이 (1초 간격) ==")
            if (seconds.size >= 2) {
                val h = seconds.size / 2
                fun avg(r: List<Csv.SecondRow>, sel: (Csv.SecondRow) -> Double) = r.map(sel).average()
                val a = seconds.subList(0, h); val b = seconds.subList(h, seconds.size)
                appendLine("전반부 평균 FPS ${f(avg(a) { it.fps.toDouble() }, 1)} → 후반부 ${f(avg(b) { it.fps.toDouble() }, 1)}")
                appendLine("전반부 분석 평균 ${f(avg(a) { it.analysisMeanMs }, 3)}ms → 후반부 ${f(avg(b) { it.analysisMeanMs }, 3)}ms")
                appendLine("앱 CPU(코어 환산) 평균 ${f(avg(seconds) { it.ownCpuCores }, 2)}, 최대 열 상태 ${seconds.maxOf { it.thermalStatus }}, 배터리 온도 ${f(seconds.first().batteryTempC, 1)}→${f(seconds.last().batteryTempC, 1)}°C")
                appendLine("  (시스템 전체 CPU 사용률은 Android 8+ 에서 일반 앱이 읽을 수 없어 측정 불가; 앱 자체 CPU 시간만 측정)")
            } else appendLine("데이터 부족")
        }
    }
}
