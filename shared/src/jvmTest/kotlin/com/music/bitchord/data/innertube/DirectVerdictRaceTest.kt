package com.music.bitchord.data.innertube

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The direct probe beside a learned InnerTubeX walk: only a recording-specific
 * verdict may pre-empt the walk; every other answer leaves the existing order
 * and learning path in place.
 */
class DirectVerdictRaceTest {

    @Test
    fun `a definitive direct verdict cancels the InnerTubeX walk`() = runBlocking {
        val itxCancelled = AtomicBoolean(false)
        val itxStarted = CompletableDeferred<Unit>()
        val direct = async {
            DirectProbe.Gone("SME님이 소유권 주장을 제기한 콘텐츠로 인해 차단되었습니다")
        }
        val itx = async {
            itxStarted.complete(Unit)
            try {
                delay(10_000)
                "itx-stream"
            } finally {
                itxCancelled.set(true)
            }
        }
        itxStarted.await()

        val outcome = raceDirectDefinitive(direct, itx)

        assertIs<DirectVerdictRace.Gone>(outcome)
        assertTrue(outcome.reason.contains("소유권 주장"))
        assertTrue(itx.isCancelled, "the walk must be cancelled when direct names the recording")
        itx.join()
        assertTrue(itxCancelled.get())
    }

    @Test
    fun `a non-definitive direct refusal keeps InnerTubeX leading`() = runBlocking {
        val direct = async {
            DirectProbe.Other(refused = true, reason = "Music Premium이 필요한 콘텐츠입니다")
        }
        val itx = async {
            delay(20)
            "itx-stream"
        }

        val outcome = raceDirectDefinitive(direct, itx)

        assertIs<DirectVerdictRace.ViaInnerTubeX<String>>(outcome)
        assertEquals("itx-stream", outcome.value)
        assertFalse(itx.isCancelled)
    }

    @Test
    fun `an anonymous private answer cannot settle a signed-in upload`() {
        assertTrue(
            anonymousPrivateMayBeOwned(
                "Private video",
                mayBeOwnPrivateUpload = true,
            ),
        )
        assertFalse(
            anonymousPrivateMayBeOwned(
                "This video has been removed by the uploader",
                mayBeOwnPrivateUpload = true,
            ),
            "a removal is gone even for the account that owns the video",
        )
        assertFalse(
            anonymousPrivateMayBeOwned(
                "Private video",
                mayBeOwnPrivateUpload = false,
            ),
            "without a session or a known upload the private verdict is the recording's",
        )
    }

    @Test
    fun `a playable direct stream does not jump the learned InnerTubeX path`() = runBlocking {
        val direct = async { DirectProbe.Stream("direct-stream") }
        val itx = async {
            delay(20)
            "itx-stream"
        }

        val outcome = raceDirectDefinitive(direct, itx)

        assertIs<DirectVerdictRace.ViaInnerTubeX<String>>(outcome)
        assertEquals("itx-stream", outcome.value, "InnerTubeX keeps the lead it had before the race")
    }

    @Test
    fun `a successful InnerTubeX answer cancels a slow direct probe`() = runBlocking {
        val directFinished = AtomicBoolean(false)
        val direct = async {
            delay(300)
            directFinished.set(true)
            DirectProbe.Other(refused = false, reason = null)
        }
        val itx = async { "itx-stream" }

        val outcome = raceDirectDefinitive(direct, itx)

        assertIs<DirectVerdictRace.ViaInnerTubeX<String>>(outcome)
        delay(500)
        assertFalse(directFinished.get(), "the losing direct probe must not keep working")
    }

    @Test
    fun `both paths failing returns no stream for the ordinary fallback`() = runBlocking {
        val direct = async { DirectProbe.Other(refused = true, reason = "503 Service Unavailable") }
        val itx = async {
            delay(10)
            null
        }

        val outcome = raceDirectDefinitive(direct, itx)

        assertIs<DirectVerdictRace.None>(outcome)
        Unit
    }
}
