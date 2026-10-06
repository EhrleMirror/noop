package com.noop.ble

import com.noop.ble.BackgroundSyncPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/** Fork: the go/no-go of one periodic background-sync run ([BackgroundSyncWorker]). */
class BackgroundSyncPolicyTest {

    private fun decide(
        enabled: Boolean = true,
        backgroundConnection: Boolean = false,
        hasSavedStrap: Boolean = true,
        bluetoothReady: Boolean = true,
        whoopIsActive: Boolean = true,
        alreadyConnected: Boolean = false,
    ) = BackgroundSyncPolicy.decide(
        enabled, backgroundConnection, hasSavedStrap, bluetoothReady, whoopIsActive, alreadyConnected,
    )

    @Test fun connectsWhenEverythingIsInPlace() {
        assertEquals(Decision.CONNECT_AND_SYNC, decide())
    }

    @Test fun skipsWhenTurnedOff() {
        assertEquals(Decision.SKIP, decide(enabled = false))
    }

    @Test fun leavesTheAlwaysOnConnectionAlone() {
        // The foreground service already offloads on its own timer; the worker must not touch its link.
        assertEquals(Decision.SKIP, decide(backgroundConnection = true))
        assertEquals(Decision.SKIP, decide(backgroundConnection = true, alreadyConnected = true))
    }

    @Test fun skipsWithoutASavedStrapBluetoothOrAnActiveWhoop() {
        assertEquals(Decision.SKIP, decide(hasSavedStrap = false))
        assertEquals(Decision.SKIP, decide(bluetoothReady = false))
        assertEquals(Decision.SKIP, decide(whoopIsActive = false))
    }

    @Test fun onlyNudgesALinkThatIsAlreadyUp() {
        assertEquals(Decision.NUDGE, decide(alreadyConnected = true))
    }
}
