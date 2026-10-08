package com.noop.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.noop.R
import com.noop.ble.LiveState
import com.noop.ble.WhoopBleClient
import com.noop.ble.WhoopModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fork: pull-to-sync on Today that works without the always-on connection.
 *
 * Upstream's pull gesture only fired while a strap link was already up, and it stopped the spinner the
 * moment the request was posted. With "Keep connected in the background" off the app does not connect
 * on launch, so the gesture was permanently disabled. This version connects to the saved strap when
 * needed, asks for a MANUAL offload (never floored by [com.noop.ble.BackfillPolicy]) and keeps the
 * refresh indicator spinning until the offload has finished or a time cap is reached.
 */
internal enum class PullToSyncStart {
    /** An offload is already running: just follow it. */
    FOLLOW_RUNNING,

    /** Link up and ready: request the sync straight away. */
    SYNC,

    /** Link up but service discovery / bonding / the history check has not finished yet. */
    AWAIT_READY,

    /** No link: connect to the saved strap first. */
    CONNECT,

    /** No link and no saved strap to connect to. */
    NO_STRAP,
}

/** Pure first step of a pull, split out so it is unit-testable without Android. */
internal fun pullToSyncStart(
    connected: Boolean,
    bonded: Boolean,
    historyReady: Boolean,
    backfilling: Boolean,
    hasSavedStrap: Boolean,
): PullToSyncStart = when {
    backfilling -> PullToSyncStart.FOLLOW_RUNNING
    connected && bonded && historyReady -> PullToSyncStart.SYNC
    connected -> PullToSyncStart.AWAIT_READY
    hasSavedStrap -> PullToSyncStart.CONNECT
    else -> PullToSyncStart.NO_STRAP
}

internal enum class PullToSyncOutcome {
    /** The offload ran to the end, or there was nothing new to fetch. */
    DONE,

    /** The time cap was reached while the strap was still sending; the sync carries on by itself. */
    STILL_RUNNING,

    /** No connection (or no ready connection) within the connect window. */
    UNREACHABLE,
    NO_STRAP,
    BLUETOOTH_OFF,
}

/** The short note shown after a pull, or null when the indicator disappearing says enough. */
@StringRes
internal fun pullToSyncMessage(outcome: PullToSyncOutcome): Int? = when (outcome) {
    PullToSyncOutcome.DONE -> null
    PullToSyncOutcome.STILL_RUNNING -> R.string.fork_pull_sync_continues
    PullToSyncOutcome.UNREACHABLE -> R.string.fork_pull_sync_unreachable
    PullToSyncOutcome.NO_STRAP -> R.string.fork_pull_sync_no_strap
    PullToSyncOutcome.BLUETOOTH_OFF -> R.string.fork_pull_sync_bluetooth_off
}

internal object PullToSync {
    /** autoConnect links can take a while to come up; long enough for that, short enough to watch. */
    const val CONNECT_TIMEOUT_MS = 30_000L

    /** How long to wait for the requested offload to actually start before calling it "nothing new". */
    const val START_GRACE_MS = 10_000L

    /** After one session ends, the client may chain an auto-continue session when the strap is behind. */
    const val CONTINUE_GRACE_MS = 5_000L

    /** Upper bound for the spinner; a long backlog keeps syncing behind the existing sync chip. */
    const val SYNC_CAP_MS = 60_000L

    /**
     * One pull, start to finish. Suspends while the indicator spins. Cancelling (leaving Today) is safe:
     * every step goes through the client's public, gated entry points.
     */
    suspend fun run(
        context: Context,
        ble: WhoopBleClient,
        savedStrap: Pair<String, WhoopModel>?,
    ): PullToSyncOutcome {
        val state = ble.state
        val s = state.value
        val start = pullToSyncStart(
            connected = s.connected,
            bonded = s.bonded,
            historyReady = s.historyReady,
            backfilling = s.backfilling,
            hasSavedStrap = savedStrap != null,
        )
        when (start) {
            PullToSyncStart.NO_STRAP -> return PullToSyncOutcome.NO_STRAP
            PullToSyncStart.FOLLOW_RUNNING -> return awaitOffload(state, alreadyRunning = true)
            PullToSyncStart.SYNC -> Unit
            PullToSyncStart.AWAIT_READY, PullToSyncStart.CONNECT -> {
                if (!bluetoothReady(context)) return PullToSyncOutcome.BLUETOOTH_OFF
                if (start == PullToSyncStart.CONNECT) {
                    val (address, model) = savedStrap ?: return PullToSyncOutcome.NO_STRAP
                    ble.reconnectToAddress(address, model)
                }
                val ready = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                    state.first { it.backfilling || (it.connected && it.bonded && it.historyReady) }
                }
                if (ready == null) {
                    // Close a passive connect we opened that never came up, so it does not linger and
                    // attach later in the background against the user's "no permanent link" choice.
                    if (start == PullToSyncStart.CONNECT && !state.value.connected) {
                        ble.endBackgroundSync()
                    }
                    ble.externalLog("Pull to sync: strap not reachable within ${CONNECT_TIMEOUT_MS / 1000}s")
                    return PullToSyncOutcome.UNREACHABLE
                }
                // The CONNECT trigger may already have started the offload on its own.
                if (ready.backfilling) return awaitOffload(state, alreadyRunning = true)
            }
        }
        ble.syncNow()
        return awaitOffload(state, alreadyRunning = false)
    }

    private suspend fun awaitOffload(state: StateFlow<LiveState>, alreadyRunning: Boolean): PullToSyncOutcome {
        val finished = withTimeoutOrNull(SYNC_CAP_MS) {
            var running = alreadyRunning
            var window = START_GRACE_MS
            while (true) {
                if (!running) {
                    // Nothing (more) started within the window: the strap is caught up.
                    withTimeoutOrNull(window) { state.first { it.backfilling } } ?: break
                }
                state.first { !it.backfilling }
                running = false
                window = CONTINUE_GRACE_MS
            }
        }
        return if (finished != null) PullToSyncOutcome.DONE else PullToSyncOutcome.STILL_RUNNING
    }

    private fun bluetoothReady(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        val adapter = runCatching { ctx.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()
        return adapter?.isEnabled == true
    }
}
