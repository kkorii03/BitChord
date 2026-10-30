package com.music.bitchord.playback

import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.listentogether.PartyPlayback
import com.music.bitchord.data.listentogether.PartyQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * What raises a reconcile.
 *
 * The case that used to be missing is the socket becoming live on an empty
 * party: there is no track, so the playback ticker is off, and neither the
 * playback sequence nor the queue sequence changes when the welcome arrives.
 * The key therefore has to carry the connection as well, or a host who created
 * a party while already listening never seeds it.
 */
class PartyReconcileKeyTest {

    private fun state(
        connection: ListenTogether.Connection,
        seq: Long = 0,
        queueSeq: Long = 0,
        clockSynced: Boolean = true,
    ) = ListenTogether.State(
        code = "ABC123",
        playback = PartyPlayback(seq = seq, queueSeq = queueSeq),
        queue = PartyQueue(seq = queueSeq),
        connection = connection,
        clockSynced = clockSynced,
    )

    @Test
    fun `socket becoming live raises a reconcile for an otherwise unchanged party`() {
        val connecting = partyReconcileKey(state(ListenTogether.Connection.CONNECTING))
        val live = partyReconcileKey(state(ListenTogether.Connection.LIVE))

        assertNotEquals(connecting, live)
    }

    @Test
    fun `an identical state still produces an identical key`() {
        assertEquals(
            partyReconcileKey(state(ListenTogether.Connection.LIVE)),
            partyReconcileKey(state(ListenTogether.Connection.LIVE)),
        )
    }

    @Test
    fun `the connection is carried alongside the sequences`() {
        val key = partyReconcileKey(
            state(connection = ListenTogether.Connection.LIVE, seq = 7, queueSeq = 9),
        )
        assertEquals(ListenTogether.Connection.LIVE, key.connection)
        assertEquals(7L, key.seq)
        assertEquals(9L, key.queueSeq)
    }
}
