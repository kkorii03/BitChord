package com.music.bitchord.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which party-membership changes have to drop the transient state of the party
 * they came from.
 *
 * The case that used to be missed is a party-to-party switch: the code goes
 * from one non-null value straight to another, so the old was-in-party /
 * now-in-party pair was true on both sides and neither leave nor enter ran.
 * A pending deferred play then survived into the new party (and could start the
 * player on its own), and a queue drag that never ended parked every publish.
 */
class PartyMembershipTransitionTest {

    @Test
    fun `switching from one party to another is not nothing`() {
        assertEquals(
            PartyMembershipTransition.SWITCHED,
            partyMembershipTransition("AAAAAA", "BBBBBB"),
        )
    }

    @Test
    fun `joining from outside is entering even when the code reuses a letter`() {
        assertEquals(
            PartyMembershipTransition.ENTERED,
            partyMembershipTransition(null, "AAAAAA"),
        )
    }

    @Test
    fun `leaving to nothing is leaving`() {
        assertEquals(
            PartyMembershipTransition.LEFT,
            partyMembershipTransition("AAAAAA", null),
        )
    }

    @Test
    fun `the same code on both sides is no membership change`() {
        assertEquals(
            PartyMembershipTransition.NONE,
            partyMembershipTransition("AAAAAA", "AAAAAA"),
        )
        assertEquals(
            PartyMembershipTransition.NONE,
            partyMembershipTransition(null, null),
        )
    }

    @Test
    fun `every non-null to non-null code change resets the old party's state`() {
        // A switch and a leave both have to clear deferred play / drag state.
        // A same-code reconcile must not, or a party would lose a live drag on
        // every state frame.
        assertEquals(
            PartyMembershipTransition.SWITCHED,
            partyMembershipTransition("ABC123", "XYZ789"),
        )
        assertEquals(
            PartyMembershipTransition.NONE,
            partyMembershipTransition("ABC123", "ABC123"),
        )
    }
}
