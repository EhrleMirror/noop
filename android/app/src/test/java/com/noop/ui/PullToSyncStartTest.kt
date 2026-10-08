package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PullToSyncStartTest {

    private fun start(
        connected: Boolean = false,
        bonded: Boolean = false,
        historyReady: Boolean = false,
        backfilling: Boolean = false,
        saved: Boolean = true,
    ) = pullToSyncStart(connected, bonded, historyReady, backfilling, saved)

    @Test
    fun runningOffloadIsFollowedNotRestarted() {
        assertEquals(PullToSyncStart.FOLLOW_RUNNING, start(connected = true, bonded = true, historyReady = true, backfilling = true))
        assertEquals(PullToSyncStart.FOLLOW_RUNNING, start(backfilling = true, saved = false))
    }

    @Test
    fun readyLinkSyncsStraightAway() {
        assertEquals(PullToSyncStart.SYNC, start(connected = true, bonded = true, historyReady = true))
    }

    @Test
    fun linkUpButNotReadyWaits() {
        assertEquals(PullToSyncStart.AWAIT_READY, start(connected = true, bonded = false, historyReady = true))
        assertEquals(PullToSyncStart.AWAIT_READY, start(connected = true, bonded = true, historyReady = false))
    }

    @Test
    fun noLinkConnectsToSavedStrap() {
        // The fork's normal case: "Keep connected in the background" is off, so nothing is connected.
        assertEquals(PullToSyncStart.CONNECT, start())
    }

    @Test
    fun noLinkAndNoSavedStrap() {
        assertEquals(PullToSyncStart.NO_STRAP, start(saved = false))
    }

    @Test
    fun successIsSilentEverythingElseExplains() {
        assertNull(pullToSyncMessage(PullToSyncOutcome.DONE))
        PullToSyncOutcome.values().filter { it != PullToSyncOutcome.DONE }.forEach {
            assertNotNull("no message for $it", pullToSyncMessage(it))
        }
    }
}
