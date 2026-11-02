package com.music.bitchord.ko

import com.music.bitchord.ko.AutomixStatusKo.Check
import com.music.bitchord.ko.AutomixStatusKo.Label
import com.music.bitchord.ko.AutomixStatusKo.Language
import com.music.bitchord.ko.AutomixStatusKo.Loop
import com.music.bitchord.ko.AutomixStatusKo.LoopStage
import com.music.bitchord.ko.AutomixStatusKo.Status
import com.music.bitchord.ko.AutomixStatusKo.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Automix status line must read like v1.9 in English and like Apple Music
 * Korea in Korean: noun labels, formal 합니다체, no casual 해요체.
 */
class AutomixStatusKoTest {

    private val loop = Loop(
        outBars = 4,
        outCheck = Check.LYRICS,
        inBars = 2,
        inCheck = Check.MASK,
        fromBpm = 111,
        toBpm = 85,
        rampBars = 11,
        fold = 1.0,
    )

    @Test
    fun `english waiting and none are unchanged`() {
        assertEquals(
            "Automix · this song: waiting · next: none",
            render(Language.EN, Status.Analysis(Label.Waiting, Label.None)),
        )
    }

    @Test
    fun `korean waiting and none are noun phrases`() {
        assertEquals(
            "자동 믹스 · 현재 노래: 대기 중 · 다음 노래: 없음",
            render(Language.KO, Status.Analysis(Label.Waiting, Label.None)),
        )
    }

    @Test
    fun `english analysis states are unchanged`() {
        val analysed = Label.Analysed(120, "C", modelGrid = true, confidence = 0.82, refining = false)
        assertEquals(
            "Automix · this song: analysed (120 BPM C, model grid 0.82) · next: analysing…",
            render(Language.EN, Status.Analysis(analysed, Label.Analysing)),
        )
        assertEquals(
            "Automix · this song: failed · next: analysed (128 BPM, dsp grid 0.50, refining)",
            render(
                Language.EN,
                Status.Analysis(
                    Label.Failed,
                    Label.Analysed(128, "", modelGrid = false, confidence = 0.5, refining = true),
                ),
            ),
        )
    }

    @Test
    fun `korean analysis states use the Apple Music wording`() {
        val analysed = Label.Analysed(120, "C", modelGrid = true, confidence = 0.82, refining = false)
        assertEquals(
            "자동 믹스 · 현재 노래: 분석됨(120 BPM C, 모델 그리드 0.82) · 다음 노래: 분석 중…",
            render(Language.KO, Status.Analysis(analysed, Label.Analysing)),
        )
        assertEquals(
            "자동 믹스 · 현재 노래: 분석 실패 · 다음 노래: 분석됨(128 BPM, DSP 그리드 0.50, 다듬는 중)",
            render(
                Language.KO,
                Status.Analysis(
                    Label.Failed,
                    Label.Analysed(128, "", modelGrid = false, confidence = 0.5, refining = true),
                ),
            ),
        )
    }

    @Test
    fun `english loop mix stages are unchanged`() {
        assertEquals(
            "Automix · loop mix in 12 s · out 4-bar, clear by lyrics · in 2-bar, clear by mask · 111 → 85 BPM over 11 bars",
            render(Language.EN, Status.LoopMix(LoopStage.In(12), loop)),
        )
        assertEquals(
            "Automix · looping · out 4-bar, clear by lyrics · in 2-bar, clear by mask · 111 → 85 BPM over 11 bars · handover +12.3 ms",
            render(Language.EN, Status.LoopMix(LoopStage.Looping, loop, handoverMs = 12.34)),
        )
        assertEquals(
            "Automix · letting go · out 4-bar, clear by lyrics · in 2-bar, clear by mask · 111 → 85 BPM over 11 bars",
            render(Language.EN, Status.LoopMix(LoopStage.LettingGo, loop)),
        )
        assertEquals(
            "Automix · loop mix in 8 s · out 4-bar, clear by lyrics · in 2-bar, clear by mask · 111 → 85 BPM over 11 bars",
            render(Language.EN, Status.PlannedLoop(8, loop)),
        )
    }

    @Test
    fun `korean loop mix stages use loop nouns`() {
        assertEquals(
            "자동 믹스 · 루프 믹스 12초 후 · 아웃 4마디, 가사로 확인 · 인 2마디, 마스크로 확인 · 111 → 85 BPM, 11마디에 걸쳐",
            render(Language.KO, Status.LoopMix(LoopStage.In(12), loop)),
        )
        assertEquals(
            "자동 믹스 · 루프 중 · 아웃 4마디, 가사로 확인 · 인 2마디, 마스크로 확인 · 111 → 85 BPM, 11마디에 걸쳐 · 인계 +12.3 ms",
            render(Language.KO, Status.LoopMix(LoopStage.Looping, loop, handoverMs = 12.34)),
        )
        assertEquals(
            "자동 믹스 · 넘겨주는 중 · 아웃 4마디, 가사로 확인 · 인 2마디, 마스크로 확인 · 111 → 85 BPM, 11마디에 걸쳐",
            render(Language.KO, Status.LoopMix(LoopStage.LettingGo, loop)),
        )
        assertEquals(
            "자동 믹스 · 루프 믹스 8초 후 · 아웃 4마디, 가사로 확인 · 인 2마디, 마스크로 확인 · 111 → 85 BPM, 11마디에 걸쳐",
            render(Language.KO, Status.PlannedLoop(8, loop)),
        )
    }

    @Test
    fun `english crossfade is unchanged`() {
        assertEquals(
            "Automix · crossfading in 5 s · 8 s · tempo-distance · no loop: no grid on the incoming song",
            render(Language.EN, fade(Style.CROSSFADE, secondsFromNow = 5, noLoop = "no grid on the incoming song")),
        )
        assertEquals(
            "Automix · crossfading now · 8 s",
            render(Language.EN, fade(Style.CROSSFADE, secondsFromNow = null, reason = null, noLoop = null)),
        )
    }

    @Test
    fun `korean crossfade uses seconds and loop phrases`() {
        assertEquals(
            "자동 믹스 · 크로스페이드 5초 후 · 8초 · 템포 차이 · 루프 없음: no grid on the incoming song",
            render(Language.KO, fade(Style.CROSSFADE, secondsFromNow = 5, noLoop = "no grid on the incoming song")),
        )
        assertEquals(
            "자동 믹스 · 크로스페이드 지금 · 8초",
            render(Language.KO, fade(Style.CROSSFADE, secondsFromNow = null, reason = null, noLoop = null)),
        )
    }

    @Test
    fun `english filter and mix are unchanged`() {
        assertEquals(
            "Automix · filter mix now · 6.5 s · tempo-distance",
            render(Language.EN, fade(Style.FILTER, secondsFromNow = null)),
        )
        assertEquals(
            "Automix · mixing in 3 s · 4 bars · next at 1.020×",
            render(Language.EN, fade(Style.MIX, secondsFromNow = 3, rate = 1.02)),
        )
        assertEquals(
            "Automix · mixing now · 4 bars · same tempo",
            render(Language.EN, fade(Style.MIX, secondsFromNow = null, rate = 1.0, reason = null)),
        )
    }

    @Test
    fun `korean filter and mix use Korean nouns and a rate`() {
        assertEquals(
            "자동 믹스 · 필터 믹스 지금 · 6.5초 · 템포 차이",
            render(Language.KO, fade(Style.FILTER, secondsFromNow = null)),
        )
        assertEquals(
            "자동 믹스 · 믹스 3초 후 · 4마디 · 다음 곡 1.020배",
            render(Language.KO, fade(Style.MIX, secondsFromNow = 3, rate = 1.02)),
        )
        assertEquals(
            "자동 믹스 · 믹스 지금 · 4마디 · 같은 템포",
            render(Language.KO, fade(Style.MIX, secondsFromNow = null, rate = 1.0, reason = null)),
        )
    }

    @Test
    fun `korean output has no casual haeyo endings`() {
        val casual = listOf(
            "하세요", "해 주세요", "해주세요", "보세요", "주세요",
            "있어요", "없어요", "돼요", "할게요", "거예요", "예요", "이에요",
            "나요", "가요",
        )
        val lines = listOf(
            render(Language.KO, Status.Analysis(Label.Waiting, Label.None)),
            render(Language.KO, Status.Analysis(Label.Analysing, Label.Failed)),
            render(Language.KO, Status.LoopMix(LoopStage.In(12), loop, handoverMs = 12.34)),
            render(Language.KO, Status.LoopMix(LoopStage.Looping, loop)),
            render(Language.KO, Status.LoopMix(LoopStage.LettingGo, loop)),
            render(Language.KO, Status.PlannedLoop(8, loop)),
            render(Language.KO, fade(Style.CROSSFADE, secondsFromNow = 5, noLoop = "no grid on the incoming song")),
            render(Language.KO, fade(Style.FILTER, secondsFromNow = null)),
            render(Language.KO, fade(Style.MIX, secondsFromNow = 3, rate = 1.02)),
        )
        for (line in lines) {
            val found = casual.firstOrNull { line.contains(it) }
            assertFalse("casual Korean ('$found'): $line", found != null)
        }
    }

    @Test
    fun `the app language picks Korean only for ko`() {
        assertEquals(Language.KO, AutomixStatusKo.languageFor("ko-KR"))
        assertEquals(Language.KO, AutomixStatusKo.languageFor("ko"))
        assertEquals(Language.EN, AutomixStatusKo.languageFor("en-US"))
        assertEquals(Language.EN, AutomixStatusKo.languageFor(null))
        assertTrue(AutomixStatusKo.languageFor("ko") == Language.KO)
    }

    private fun fade(
        style: Style,
        secondsFromNow: Int?,
        rate: Double = 1.0,
        reason: String? = "tempo-distance",
        noLoop: String? = null,
    ) = Status.Fade(
        style = style,
        secondsFromNow = secondsFromNow,
        fadeSeconds = if (style == Style.FILTER) 6.5 else 8.0,
        bars = if (style == Style.CROSSFADE || style == Style.FILTER) 0 else 4,
        incomingRate = rate,
        reason = reason,
        noLoop = noLoop,
    )

    private fun render(language: Language, status: Status): String =
        AutomixStatusKo.render(language, status)
}
