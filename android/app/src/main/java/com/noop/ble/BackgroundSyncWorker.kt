package com.noop.ble

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.noop.NoopApplication
import com.noop.ui.NoopPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Fork: periodic background sync WITHOUT the always-on connection.
 *
 * Upstream offers two modes: "Keep connected in the background" (a foreground service holding the GATT
 * link open around the clock, with its ongoing notification and the battery cost that comes with a
 * permanent link) or nothing, in which case the strap only offloads when NOOP is opened. This worker is
 * the middle ground: roughly every 15 minutes it attaches to the saved strap, lets the normal gated
 * offload run to HISTORY_COMPLETE, and drops the link again.
 *
 * It drives only PUBLIC client entry points ([WhoopBleClient.reconnectToAddress], [WhoopBleClient.syncNow],
 * [WhoopBleClient.endBackgroundSync]), so the offload itself is exactly the one the app runs on open:
 * same Backfiller, same ack/trim contract, same BackfillPolicy floors. Nothing is lost between runs — the
 * strap banks history on-board until it is acknowledged.
 *
 * 15 minutes is WorkManager's minimum period. In Doze (phone idle, e.g. overnight) Android batches jobs
 * into maintenance windows, so runs can stretch to 30–60+ minutes; a battery-optimisation exemption
 * shortens that. A missed or late run costs nothing but freshness.
 */
class BackgroundSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val app = ctx as? NoopApplication ?: return Result.success()

        val saved = NoopPrefs.lastDevice(ctx)
        // On a cold start (process launched just for this job) the first `app.ble` builds the client,
        // which resolves the startup device id with a blocking registry read — keep that off the worker's
        // coroutine thread.
        val ble = withContext(Dispatchers.IO) { app.ble }
        val decision = BackgroundSyncPolicy.decide(
            enabled = isEnabled(ctx),
            backgroundConnection = NoopPrefs.backgroundConnection(ctx),
            hasSavedStrap = saved != null,
            bluetoothReady = bluetoothReady(ctx),
            whoopIsActive = whoopIsActiveDevice(app),
            alreadyConnected = ble.state.value.connected,
        )
        when (decision) {
            BackgroundSyncPolicy.Decision.SKIP -> return Result.success()
            BackgroundSyncPolicy.Decision.NUDGE -> {
                // A link is already up (the app is open, or its process is still holding the link): the
                // client's own 15-min timer covers it. A gated nudge is free; tearing down is not ours to do.
                ble.syncNow()
                return Result.success()
            }
            BackgroundSyncPolicy.Decision.CONNECT_AND_SYNC -> Unit
        }
        val (address, model) = saved ?: return Result.success()

        withContext(Dispatchers.Main) { ble.reconnectToAddress(address, model) }
        try {
            val ready = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
                ble.state.first { it.connected && it.historyReady }
            }
            if (ready != null) {
                // The CONNECT trigger normally starts the offload by itself; this gated nudge covers the
                // case where it was declined or hasn't fired yet. It never double-starts a session.
                ble.syncNow()
                awaitOffloadQuiet(ble)
            } else {
                ble.externalLog("Background sync: strap not reachable within ${CONNECT_TIMEOUT_MS / 1000}s")
            }
        } finally {
            // Hand the link back only if nobody else wants it: the user may have opened NOOP while we
            // were syncing, or switched "Keep connected in the background" on in the meantime.
            if (!app.isAppInForeground && !NoopPrefs.backgroundConnection(ctx)) {
                withContext(NonCancellable + Dispatchers.Main) { ble.endBackgroundSync() }
            }
        }
        return Result.success()
    }

    /**
     * Wait until the offload has run and gone quiet. A HISTORY_COMPLETE can be followed by an
     * auto-continue session when the strap is still behind, so after each session ends we give the client
     * a short window to start another before calling it done. Bounded overall, because a WorkManager job
     * gets ~10 minutes; anything left over is simply picked up by the next run.
     */
    private suspend fun awaitOffloadQuiet(ble: WhoopBleClient) {
        withTimeoutOrNull(SYNC_BUDGET_MS) {
            var startWindow = FIRST_START_GRACE_MS
            while (true) {
                withTimeoutOrNull(startWindow) { ble.state.first { it.backfilling } }
                    ?: return@withTimeoutOrNull   // nothing (more) to offload
                ble.state.first { !it.backfilling }
                startWindow = CONTINUE_GRACE_MS
            }
        }
    }

    private fun bluetoothReady(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) return false
        val adapter = runCatching { ctx.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()
        return adapter?.isEnabled == true
    }

    /** Same rule as `NoopApplication.whoopStartupDeviceId`: fail-open, only a POSITIVELY non-WHOOP active
     *  device (an Oura ring, a generic HR strap) means "don't touch the WHOOP". */
    private suspend fun whoopIsActiveDevice(app: NoopApplication): Boolean {
        val registry = app.deviceRegistry
        val activeId = runCatching { registry.activeDeviceId() }.getOrNull() ?: return true
        val row = runCatching { registry.all() }.getOrNull()?.firstOrNull { it.id == activeId } ?: return true
        return SourceIdentity.isWhoop(row)
    }

    companion object {
        private const val WORK = "fork.backgroundSync"
        private const val KEY_ENABLED = "fork.periodicBackgroundSync"

        /** WorkManager's minimum period. */
        const val INTERVAL_MINUTES = 15L

        private const val CONNECT_TIMEOUT_MS = 60_000L
        private const val FIRST_START_GRACE_MS = 20_000L
        private const val CONTINUE_GRACE_MS = 8_000L
        private const val SYNC_BUDGET_MS = 7 * 60_000L

        /** Default ON: the whole point of the fork setting is that it works without being found first. */
        fun isEnabled(context: Context): Boolean =
            NoopPrefs.of(context).getBoolean(KEY_ENABLED, true)

        fun setEnabled(context: Context, enabled: Boolean) {
            NoopPrefs.of(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
            if (enabled) ensureScheduled(context) else cancel(context)
        }

        /** KEEP, so calling this on every launch never restarts the period. */
        fun ensureScheduled(context: Context) {
            if (!isEnabled(context)) return
            val request = PeriodicWorkRequestBuilder<BackgroundSyncWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES,
            ).build()
            runCatching {
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
            }
        }

        fun cancel(context: Context) {
            runCatching { WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK) }
        }
    }
}

/** Pure go/no-go for one [BackgroundSyncWorker] run, split out so it is unit-testable without Android. */
internal object BackgroundSyncPolicy {
    enum class Decision { SKIP, NUDGE, CONNECT_AND_SYNC }

    fun decide(
        enabled: Boolean,
        backgroundConnection: Boolean,
        hasSavedStrap: Boolean,
        bluetoothReady: Boolean,
        whoopIsActive: Boolean,
        alreadyConnected: Boolean,
    ): Decision = when {
        !enabled -> Decision.SKIP
        // The foreground service already holds the link and offloads every 15 min on its own timer.
        backgroundConnection -> Decision.SKIP
        !hasSavedStrap || !bluetoothReady || !whoopIsActive -> Decision.SKIP
        alreadyConnected -> Decision.NUDGE
        else -> Decision.CONNECT_AND_SYNC
    }
}
