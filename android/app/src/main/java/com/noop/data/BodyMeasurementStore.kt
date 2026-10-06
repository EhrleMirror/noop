package com.noop.data

import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalDate
import kotlin.math.roundToLong

/**
 * Fork feature: hand-logged body measurements (body weight and waist circumference) as a dated history.
 *
 * Every entry is one row in the generic `metricSeries` table under its own [SOURCE_ID], one row per
 * (day, metric). That is the same storage path the hydration and cycle trackers use, so there is no
 * schema change and no migration, the rows travel with the regular `.noopbak` database backup, and
 * nothing an importer or the strap analysis writes can overwrite them (different source id).
 *
 * Values are always stored in SI units (kilograms / centimetres) rounded to one decimal; the screen
 * converts for display. A second entry on the same day replaces the first (natural key day + metric).
 *
 * The storage seams are injected as lambdas (the [CycleTrackingStore] pattern) so the logic is testable
 * on the plain JVM without Room.
 */
enum class BodyMetric(val key: String, val minValue: Double, val maxValue: Double) {
    /** Body weight in kilograms. Same plausible range the profile weight stepper allows. */
    WEIGHT("weight_kg", 30.0, 250.0),

    /** Waist circumference in centimetres. */
    WAIST("waist_cm", 40.0, 200.0);

    /** True when [value] is a finite number inside this metric's plausible range. */
    fun accepts(value: Double): Boolean = value.isFinite() && value >= minValue && value <= maxValue
}

/** One stored measurement: the ISO `yyyy-MM-dd` day and the value in SI units. */
data class BodyPoint(val day: String, val value: Double)

/** One logged day as the history list shows it. Either value may be absent (only one was measured). */
data class BodyEntry(val day: String, val weightKg: Double?, val waistCm: Double?)

/** Difference between the newest point and an earlier reference point of the same series. */
data class BodyChange(val delta: Double, val sinceDay: String)

class BodyMeasurementStore(
    private val upsertRows: suspend (List<MetricSeriesRow>) -> Unit,
    private val queryRows: suspend (String, String, String, String) -> List<MetricSeriesRow>,
    private val deletePoint: suspend (String, String, String) -> Unit,
) {
    constructor(repo: WhoopRepository) : this(
        { rows -> repo.upsertMetricSeries(rows) },
        { deviceId, key, from, to -> repo.metricSeries(deviceId, key, from, to) },
        { deviceId, day, key -> repo.deleteMetricSeriesPoint(deviceId, day, key) },
    )

    /**
     * Write the measurements for [day]. A valid value is upserted; a null value REMOVES that metric for
     * the day, so editing an existing day and clearing one field behaves like the user expects. Values
     * outside the plausible range are rejected (nothing is written for that metric) — the dialog never
     * offers to save them in the first place.
     *
     * Returns false when nothing was written because both values were null/invalid.
     */
    suspend fun saveDay(day: String, weightKg: Double?, waistCm: Double?): Boolean {
        val weight = weightKg?.takeIf { BodyMetric.WEIGHT.accepts(it) }
        val waist = waistCm?.takeIf { BodyMetric.WAIST.accepts(it) }
        if (weight == null && waist == null) return false
        val rows = ArrayList<MetricSeriesRow>(2)
        if (weight != null) rows.add(MetricSeriesRow(SOURCE_ID, day, BodyMetric.WEIGHT.key, round1(weight)))
        if (waist != null) rows.add(MetricSeriesRow(SOURCE_ID, day, BodyMetric.WAIST.key, round1(waist)))
        upsertRows(rows)
        if (weightKg == null) deletePoint(SOURCE_ID, day, BodyMetric.WEIGHT.key)
        if (waistCm == null) deletePoint(SOURCE_ID, day, BodyMetric.WAIST.key)
        mutationSeq.value += 1
        return true
    }

    /** Every stored point of [metric] in the inclusive day range, OLDEST first. */
    suspend fun points(metric: BodyMetric, from: String = DAY_MIN, to: String = DAY_MAX): List<BodyPoint> =
        queryRows(SOURCE_ID, metric.key, from, to)
            .filter { it.value.isFinite() }
            .sortedBy { it.day }
            .map { BodyPoint(it.day, it.value) }

    /** The full history merged per day, NEWEST first. */
    suspend fun entries(): List<BodyEntry> =
        mergeEntries(points(BodyMetric.WEIGHT), points(BodyMetric.WAIST))

    /** Remove both measurements of one day. Deleting an absent row is an idempotent no-op. */
    suspend fun deleteDay(day: String) {
        for (metric in BodyMetric.entries) deletePoint(SOURCE_ID, day, metric.key)
        mutationSeq.value += 1
    }

    companion object {
        /** Own local-only source id, never shared with strap, import or computed rows. */
        const val SOURCE_ID: String = "noop-body"

        /**
         * Bumped on every write so screens that show a measurement (Today's weight tile, the body screen)
         * can re-read without polling. Same idiom as `HydrationStore.mutationSeq`.
         */
        val mutationSeq = MutableStateFlow(0)

        private const val DAY_MIN = "0000-01-01"
        private const val DAY_MAX = "9999-12-31"

        /** Round to one decimal, the resolution every scale and tape measure reports. */
        fun round1(v: Double): Double = (v * 10.0).roundToLong() / 10.0

        /**
         * Parse a user-typed decimal. Accepts both a comma and a dot as the separator (German and
         * English keyboards), surrounding blanks, and nothing else. Null for empty or unparseable text.
         */
        fun parseDecimal(text: String): Double? {
            val cleaned = text.trim().replace(',', '.')
            if (cleaned.isEmpty()) return null
            if (cleaned.count { it == '.' } > 1) return null
            if (!cleaned.all { it.isDigit() || it == '.' }) return null
            return cleaned.toDoubleOrNull()?.takeIf { it.isFinite() }
        }

        /** Merge the two per-metric series into one row per day, NEWEST first. */
        fun mergeEntries(weights: List<BodyPoint>, waists: List<BodyPoint>): List<BodyEntry> {
            val byWeight = weights.associate { it.day to it.value }
            val byWaist = waists.associate { it.day to it.value }
            return (byWeight.keys + byWaist.keys)
                .sortedDescending()
                .map { day -> BodyEntry(day, byWeight[day], byWaist[day]) }
        }

        /**
         * Change of the newest point against the series [days] before it.
         *
         * The reference is the newest point at least [days] days older than the latest one. When the
         * history is shorter than that, the OLDEST point is used instead and [BodyChange.sinceDay] names it,
         * so the screen can say "since 12 Sep" rather than pretending to cover a full month. Null when there
         * are fewer than two points (no change to show). [points] must be oldest-first.
         */
        fun change(points: List<BodyPoint>, days: Long): BodyChange? {
            if (points.size < 2) return null
            val latest = points.last()
            val latestDate = parseDay(latest.day) ?: return null
            val cutoff = latestDate.minusDays(days)
            val reference = points.lastOrNull { p ->
                val d = parseDay(p.day)
                d != null && !d.isAfter(cutoff)
            } ?: points.first()
            if (reference.day == latest.day) return null
            return BodyChange(round1(latest.value - reference.value), reference.day)
        }

        /** Points whose day lies within the trailing [days] days ending [today] (inclusive). */
        fun trailing(points: List<BodyPoint>, days: Long, today: LocalDate = LocalDate.now()): List<BodyPoint> {
            val from = today.minusDays((days - 1).coerceAtLeast(0))
            return points.filter { p ->
                val d = parseDay(p.day)
                d != null && !d.isBefore(from)
            }
        }

        /**
         * Should a write for [day] become the profile value? Only when no stored point is newer: back-filling
         * an old measurement must not overwrite the current profile weight that analytics read.
         */
        fun isNewest(day: String, points: List<BodyPoint>): Boolean = points.none { it.day > day }

        /** Waist-to-height ratio, rounded to two decimals; null when either input is missing. */
        fun waistToHeight(waistCm: Double?, heightCm: Double?): Double? {
            if (waistCm == null || heightCm == null || heightCm <= 0.0) return null
            return (waistCm / heightCm * 100.0).roundToLong() / 100.0
        }

        private fun parseDay(day: String): LocalDate? = runCatching { LocalDate.parse(day) }.getOrNull()
    }
}
