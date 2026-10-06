package com.noop.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Storage + pure-logic tests for the fork's hand-logged body measurements (`noop-body` source). */
class BodyMeasurementStoreTest {
    private class FakeSeries {
        val rows = LinkedHashMap<Triple<String, String, String>, MetricSeriesRow>()
        fun upsert(newRows: List<MetricSeriesRow>) {
            newRows.forEach { rows[Triple(it.deviceId, it.day, it.key)] = it }
        }
        fun query(deviceId: String, key: String, from: String, to: String): List<MetricSeriesRow> =
            rows.values.filter { it.deviceId == deviceId && it.key == key && it.day in from..to }
        fun delete(deviceId: String, day: String, key: String) {
            rows.remove(Triple(deviceId, day, key))
        }
    }

    private fun store(fake: FakeSeries) = BodyMeasurementStore(
        { fake.upsert(it) },
        { deviceId, key, from, to -> fake.query(deviceId, key, from, to) },
        { deviceId, day, key -> fake.delete(deviceId, day, key) },
    )

    @Test
    fun savesBothMetricsUnderTheOwnSourceRoundedToOneDecimal() = runBlocking {
        val fake = FakeSeries()
        val store = store(fake)
        assertTrue(store.saveDay("2026-10-06", 82.44, 88.06))

        assertEquals(2, fake.rows.size)
        assertTrue(fake.rows.values.all { it.deviceId == "noop-body" })
        assertEquals(82.4, fake.rows[Triple("noop-body", "2026-10-06", "weight_kg")]!!.value, 0.0)
        assertEquals(88.1, fake.rows[Triple("noop-body", "2026-10-06", "waist_cm")]!!.value, 0.0)
    }

    @Test
    fun sameDayReplacesAndANullFieldRemovesThatMetric() = runBlocking {
        val fake = FakeSeries()
        val store = store(fake)
        store.saveDay("2026-10-06", 82.4, 88.0)
        store.saveDay("2026-10-06", 81.9, null)

        assertEquals(listOf(BodyEntry("2026-10-06", 81.9, null)), store.entries())
    }

    @Test
    fun rejectsImplausibleValuesAndWritesNothingWhenBothAreMissing() = runBlocking {
        val fake = FakeSeries()
        val store = store(fake)
        assertFalse(store.saveDay("2026-10-06", null, null))
        assertFalse(store.saveDay("2026-10-06", 900.0, 5.0))
        assertTrue(fake.rows.isEmpty())
    }

    @Test
    fun entriesMergePerDayNewestFirstAndIgnoreOtherSources() = runBlocking {
        val fake = FakeSeries()
        val store = store(fake)
        store.saveDay("2026-09-01", 84.0, null)
        store.saveDay("2026-09-15", null, 90.0)
        store.saveDay("2026-10-01", 83.0, 89.0)
        fake.upsert(listOf(MetricSeriesRow("health-connect", "2026-10-02", "weight_kg", 70.0)))

        assertEquals(
            listOf(
                BodyEntry("2026-10-01", 83.0, 89.0),
                BodyEntry("2026-09-15", null, 90.0),
                BodyEntry("2026-09-01", 84.0, null),
            ),
            store.entries(),
        )
        store.deleteDay("2026-10-01")
        assertEquals("2026-09-15", store.entries().first().day)
    }

    @Test
    fun parsesCommaAndDotDecimals() {
        assertEquals(82.5, BodyMeasurementStore.parseDecimal("82,5")!!, 0.0)
        assertEquals(82.5, BodyMeasurementStore.parseDecimal(" 82.5 ")!!, 0.0)
        assertEquals(90.0, BodyMeasurementStore.parseDecimal("90")!!, 0.0)
        assertNull(BodyMeasurementStore.parseDecimal(""))
        assertNull(BodyMeasurementStore.parseDecimal("8,2,5"))
        assertNull(BodyMeasurementStore.parseDecimal("-3"))
        assertNull(BodyMeasurementStore.parseDecimal("abc"))
    }

    @Test
    fun changeUsesThePointAtLeastNDaysOlderElseTheOldest() {
        val pts = listOf(
            BodyPoint("2026-08-01", 86.0),
            BodyPoint("2026-09-05", 84.0),
            BodyPoint("2026-09-20", 83.5),
            BodyPoint("2026-10-06", 82.0),
        )
        // 30 days before 10-06 is 09-06 → newest point not after that is 09-05.
        assertEquals(BodyChange(-2.0, "2026-09-05"), BodyMeasurementStore.change(pts, 30))
        // A window longer than the history falls back to the oldest point.
        assertEquals(BodyChange(-4.0, "2026-08-01"), BodyMeasurementStore.change(pts, 365))
        assertNull(BodyMeasurementStore.change(pts.take(1), 30))
    }

    @Test
    fun trailingWindowIsInclusiveOfToday() {
        val pts = listOf(BodyPoint("2026-09-06", 1.0), BodyPoint("2026-09-07", 2.0), BodyPoint("2026-10-06", 3.0))
        val out = BodyMeasurementStore.trailing(pts, 30, LocalDate.parse("2026-10-06"))
        assertEquals(listOf("2026-09-07", "2026-10-06"), out.map { it.day })
    }

    @Test
    fun onlyTheNewestDayFeedsTheProfile() {
        val pts = listOf(BodyPoint("2026-09-01", 84.0), BodyPoint("2026-10-01", 83.0))
        assertTrue(BodyMeasurementStore.isNewest("2026-10-06", pts))
        assertTrue(BodyMeasurementStore.isNewest("2026-10-01", pts))
        assertFalse(BodyMeasurementStore.isNewest("2026-09-15", pts))
    }

    @Test
    fun waistToHeightRatio() {
        assertEquals(0.5, BodyMeasurementStore.waistToHeight(90.0, 180.0)!!, 0.0)
        assertNull(BodyMeasurementStore.waistToHeight(null, 180.0))
    }
}
