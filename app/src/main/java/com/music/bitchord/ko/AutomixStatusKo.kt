package com.music.bitchord.ko

import com.music.bitchord.data.LocaleTags
import java.util.Locale

/**
 * The Automix status line under the stream stats, in the app's language.
 *
 * [CrossfadeController.describeAutomix] keeps building v1.9's parts; this file
 * only chooses the wording, so the English line stays byte-for-byte what v1.9
 * produced and Korean gets Apple Music Korea's noun phrases and 합니다체.
 */
object AutomixStatusKo {

    enum class Language { EN, KO }

    enum class Check { LYRICS, MASK, LYRICS_AND_MASK }

    enum class Style { CROSSFADE, FILTER, MIX }

    sealed interface LoopStage {
        data class In(val seconds: Int) : LoopStage
        data object Looping : LoopStage
        data object LettingGo : LoopStage
    }

    sealed interface Label {
        data object None : Label
        data object Waiting : Label
        data object Analysing : Label
        data object Failed : Label
        data class Analysed(
            val bpm: Int,
            val key: String,
            val modelGrid: Boolean,
            val confidence: Double,
            val refining: Boolean,
        ) : Label
    }

    data class Loop(
        val outBars: Int,
        val outCheck: Check,
        val inBars: Int,
        val inCheck: Check,
        val fromBpm: Int,
        val toBpm: Int,
        val rampBars: Int,
        val fold: Double,
    )

    sealed interface Status {
        data class LoopMix(
            val stage: LoopStage,
            val loop: Loop,
            val handoverMs: Double? = null,
        ) : Status

        data class PlannedLoop(val seconds: Int, val loop: Loop) : Status

        data class Fade(
            val style: Style,
            val secondsFromNow: Int?,
            val fadeSeconds: Double,
            val bars: Int,
            val incomingRate: Double,
            val reason: String?,
            val noLoop: String?,
        ) : Status

        data class Analysis(val current: Label, val next: Label?) : Status
    }

    /** The same source the rest of playback uses for the app language. */
    fun languageFor(tag: String?): Language =
        if (LocaleTags.normalizeAppTag(tag) == "ko") Language.KO else Language.EN

    fun render(language: Language, status: Status): String = when (language) {
        Language.EN -> english(status)
        Language.KO -> korean(status)
    }

    private fun english(status: Status): String = when (status) {
        is Status.LoopMix -> {
            val stage = when (val s = status.stage) {
                is LoopStage.In -> "loop mix in ${s.seconds} s"
                LoopStage.Looping -> "looping"
                LoopStage.LettingGo -> "letting go"
            }
            val aligned = status.handoverMs
                ?.let { " · handover ${"%+.1f".format(Locale.ROOT, it)} ms" }
                .orEmpty()
            "Automix · $stage · ${loopEnglish(status.loop)}$aligned"
        }

        is Status.PlannedLoop ->
            "Automix · loop mix in ${status.seconds} s · ${loopEnglish(status.loop)}"

        is Status.Fade -> {
            val whenText = status.secondsFromNow?.let { "in $it s" } ?: "now"
            val length = if (status.bars > 0) {
                "${status.bars} bars"
            } else {
                "${"%.1f".format(Locale.ROOT, status.fadeSeconds)} s"
            }
            val why = status.reason?.let { " · $it" }.orEmpty()
            val noLoop = status.noLoop?.let { " · no loop: $it" }.orEmpty()
            when (status.style) {
                Style.CROSSFADE ->
                    "Automix · crossfading $whenText · ${"%.0f".format(Locale.ROOT, status.fadeSeconds)} s$why$noLoop"
                Style.FILTER -> "Automix · filter mix $whenText · $length$why$noLoop"
                Style.MIX -> {
                    val rate = status.incomingRate
                    val stretch = if (kotlin.math.abs(rate - 1) < 0.0005) {
                        "same tempo"
                    } else {
                        "next at ${"%.3f".format(Locale.ROOT, rate)}×"
                    }
                    "Automix · mixing $whenText · $length · $stretch$noLoop"
                }
            }
        }

        is Status.Analysis ->
            "Automix · this song: ${labelEnglish(status.current)} · next: ${labelEnglish(status.next)}"
    }

    private fun korean(status: Status): String = when (status) {
        is Status.LoopMix -> {
            val stage = when (val s = status.stage) {
                is LoopStage.In -> "루프 믹스 ${s.seconds}초 후"
                LoopStage.Looping -> "루프 중"
                LoopStage.LettingGo -> "넘겨주는 중"
            }
            val aligned = status.handoverMs
                ?.let { " · 인계 ${"%+.1f".format(Locale.ROOT, it)} ms" }
                .orEmpty()
            "자동 믹스 · $stage · ${loopKorean(status.loop)}$aligned"
        }

        is Status.PlannedLoop ->
            "자동 믹스 · 루프 믹스 ${status.seconds}초 후 · ${loopKorean(status.loop)}"

        is Status.Fade -> {
            val whenText = status.secondsFromNow?.let { "${it}초 후" } ?: "지금"
            val length = if (status.bars > 0) {
                "${status.bars}마디"
            } else {
                "${"%.1f".format(Locale.ROOT, status.fadeSeconds)}초"
            }
            val why = status.reason?.let { " · ${reasonKorean(it)}" }.orEmpty()
            val noLoop = status.noLoop?.let { " · 루프 없음: $it" }.orEmpty()
            when (status.style) {
                Style.CROSSFADE ->
                    "자동 믹스 · 크로스페이드 $whenText · ${"%.0f".format(Locale.ROOT, status.fadeSeconds)}초$why$noLoop"
                Style.FILTER -> "자동 믹스 · 필터 믹스 $whenText · $length$why$noLoop"
                Style.MIX -> {
                    val rate = status.incomingRate
                    val stretch = if (kotlin.math.abs(rate - 1) < 0.0005) {
                        "같은 템포"
                    } else {
                        "다음 곡 ${"%.3f".format(Locale.ROOT, rate)}배"
                    }
                    "자동 믹스 · 믹스 $whenText · $length · $stretch$noLoop"
                }
            }
        }

        is Status.Analysis ->
            "자동 믹스 · 현재 노래: ${labelKorean(status.current)} · 다음 노래: ${labelKorean(status.next)}"
    }

    private fun loopEnglish(loop: Loop): String {
        val tempo = if (loop.fromBpm == loop.toBpm) {
            "${loop.fromBpm} BPM"
        } else {
            "${loop.fromBpm} → ${loop.toBpm} BPM over ${loop.rampBars} bars"
        }
        val fold = when {
            loop.fold > 1 -> " (next in double time)"
            loop.fold < 1 -> " (next in half time)"
            else -> ""
        }
        return "out ${loop.outBars}-bar, clear by ${checkEnglish(loop.outCheck)} · " +
            "in ${loop.inBars}-bar, clear by ${checkEnglish(loop.inCheck)} · $tempo$fold"
    }

    private fun loopKorean(loop: Loop): String {
        val tempo = if (loop.fromBpm == loop.toBpm) {
            "${loop.fromBpm} BPM"
        } else {
            "${loop.fromBpm} → ${loop.toBpm} BPM, ${loop.rampBars}마디에 걸쳐"
        }
        val fold = when {
            loop.fold > 1 -> " (다음 곡 2배속)"
            loop.fold < 1 -> " (다음 곡 0.5배속)"
            else -> ""
        }
        return "아웃 ${loop.outBars}마디, ${checkKorean(loop.outCheck)} · " +
            "인 ${loop.inBars}마디, ${checkKorean(loop.inCheck)} · $tempo$fold"
    }

    private fun checkEnglish(check: Check): String = when (check) {
        Check.LYRICS -> "lyrics"
        Check.MASK -> "mask"
        Check.LYRICS_AND_MASK -> "lyrics + mask"
    }

    private fun checkKorean(check: Check): String = when (check) {
        Check.LYRICS -> "가사로 확인"
        Check.MASK -> "마스크로 확인"
        Check.LYRICS_AND_MASK -> "가사와 마스크로 확인"
    }

    private fun labelEnglish(label: Label?): String = when (label) {
        null, Label.None -> "none"
        Label.Analysing -> "analysing…"
        Label.Waiting -> "waiting"
        Label.Failed -> "failed"
        is Label.Analysed -> {
            val key = label.key.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
            val grid = if (label.modelGrid) "model grid" else "dsp grid"
            val refining = if (label.refining) ", refining" else ""
            "analysed (${label.bpm} BPM$key, $grid ${"%.2f".format(Locale.ROOT, label.confidence)}$refining)"
        }
    }

    private fun labelKorean(label: Label?): String = when (label) {
        null, Label.None -> "없음"
        Label.Analysing -> "분석 중…"
        Label.Waiting -> "대기 중"
        Label.Failed -> "분석 실패"
        is Label.Analysed -> {
            val key = label.key.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
            val grid = if (label.modelGrid) "모델 그리드" else "DSP 그리드"
            val refining = if (label.refining) ", 다듬는 중" else ""
            "분석됨(${label.bpm} BPM$key, $grid ${"%.2f".format(Locale.ROOT, label.confidence)}$refining)"
        }
    }

    private fun reasonKorean(reason: String): String = when (reason) {
        "outgoing-tempo" -> "나가는 곡 템포"
        "incoming-tempo" -> "다음 곡 템포"
        "tempo-distance" -> "템포 차이"
        "beat-confidence" -> "박자 신뢰도"
        "unmixable-pair" -> "믹스 불가 조합"
        else -> reason
    }
}
