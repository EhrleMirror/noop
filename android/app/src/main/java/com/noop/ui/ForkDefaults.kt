package com.noop.ui

import android.content.Context

/**
 * Fork: one-time defaults of the personal build.
 *
 * On the first start of the fork (fresh install, or an existing install updated to it) the app switches
 * to the Strap look once: strap colours on true black, no day-cycle sky, solid cards and bottom bar, the
 * ring gauges, and strain on the familiar 0–21 axis. Each of these is an ordinary preference, so every
 * choice can still be changed in Settings afterwards; the flag guarantees a later start never overrides
 * what the user picked.
 *
 * Runs before the preference stores load (MainActivity), so the first frame already uses the new look.
 */
object ForkDefaults {
    private const val KEY_APPLIED = "fork.strapDefaults.v1"

    fun applyOnce(context: Context) {
        val app = context.applicationContext
        val prefs = NoopPrefs.of(app)
        if (prefs.getBoolean(KEY_APPLIED, false)) return
        ChartStylePrefs.set(app, ChartStyle.STRAP)
        AccentPrefs.setColor(app, AccentColor.WHOOP_BLUE)
        NoopPrefs.setShowDayCycleBackground(app, false)
        NoopPrefs.setCardOpacityPercent(app, 100)
        NoopPrefs.setTodayRingGauges(app, true)
        UnitPrefs.setEffortScale(app, EffortScale.WHOOP)
        BottomBarStyleStore.setOpacityStep(app, MAX_OPACITY_STEP)
        prefs.edit().putBoolean(KEY_APPLIED, true).apply()
    }
}
