package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MonitorWeight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.R
import com.noop.data.BodyEntry
import com.noop.data.BodyMeasurementStore
import com.noop.data.BodyMetric
import com.noop.data.BodyPoint
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

// MARK: - Body measurements (fork feature)
//
// A dated log of body weight and waist circumference: two headline tiles with the change over the last
// month, a trend chart per measurement over a selectable range, and the full history with edit + delete.
// Everything is stored on the phone in the generic metric-series table (BodyMeasurementStore), in SI
// units; this screen only converts for display when the body-measurement unit is Imperial.
//
// The newest logged weight and waist also become the profile values, so calories, HR-zone maths and the
// waist-based VO2max estimate follow what was measured instead of a number typed into Settings once.

/** Route of the Body measurements screen. Also handed through Today's `onOpenMetric` by the Weight tile. */
internal const val BODY_MEASUREMENTS_ROUTE = "body_measurements"

/** How many history rows the card draws before it summarises the rest (keeps the card cheap). */
private const val HISTORY_ROW_CAP = 120

/** The trend window offered above the charts. [days] null = the whole history. */
private enum class BodyRange(val days: Long?, val labelRes: Int) {
    D30(30L, R.string.fork_body_range_30),
    D90(90L, R.string.fork_body_range_90),
    Y1(365L, R.string.fork_body_range_365),
    ALL(null, R.string.fork_body_range_all),
}

/** Display conversion for the body-measurement unit system. Storage is always kg / cm. */
internal class BodyUnits(val imperial: Boolean) {
    val weightUnit: String get() = if (imperial) "lb" else "kg"
    val waistUnit: String get() = if (imperial) "in" else "cm"
    fun weightOut(kg: Double): Double = if (imperial) kg * UnitFormatter.POUNDS_PER_KILOGRAM else kg
    fun waistOut(cm: Double): Double = if (imperial) cm / UnitFormatter.CENTIMETERS_PER_INCH else cm
    fun weightIn(value: Double): Double = if (imperial) value / UnitFormatter.POUNDS_PER_KILOGRAM else value
    fun waistIn(value: Double): Double = if (imperial) value * UnitFormatter.CENTIMETERS_PER_INCH else value
}

/** One decimal in the app language (a comma in German), the format the entry fields also accept. */
private fun oneDecimal(v: Double): String = String.format(Locale.getDefault(), "%.1f", v)

/** A change with an explicit sign and a real minus sign; a change under 0.05 reads as 0. */
private fun signedOneDecimal(v: Double): String = when {
    v >= 0.05 -> "+" + oneDecimal(v)
    v <= -0.05 -> "−" + oneDecimal(-v)
    else -> oneDecimal(0.0)
}

/** "6 Oct" / "6. Okt." for chart axes and change captions; the raw key when unparseable. */
private fun shortDate(day: String): String {
    val locale = Locale.getDefault()
    val pattern = if (locale.language == "de") "d. MMM" else "d MMM"
    return runCatching { LocalDate.parse(day).format(DateTimeFormatter.ofPattern(pattern, locale)) }
        .getOrDefault(day)
}

/** The locale's medium date ("06.10.2026" / "Oct 6, 2026") for the history list and the dialog. */
private fun mediumDateOf(date: LocalDate): String =
    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()))

private fun mediumDate(day: String): String =
    runCatching { mediumDateOf(LocalDate.parse(day)) }.getOrDefault(day)

@Composable
fun BodyMeasurementsScreen(viewModel: AppViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(viewModel) { BodyMeasurementStore(viewModel.repo) }
    val seq by BodyMeasurementStore.mutationSeq.collectAsStateWithLifecycle()

    val unitSystem = remember { UnitPrefs.system(context) }
    val units = remember(unitSystem) { BodyUnits(unitSystem == UnitSystem.IMPERIAL) }
    val profile = remember { ProfileStore.from(context) }
    val heightCm = remember { profile.heightCm }
    val weightFollowsHealthConnect = remember { profile.useHealthConnectWeight }
    val showDayCycleBackground = remember { NoopPrefs.showDayCycleBackground(context) }
    val skyBehindCards = remember { NoopPrefs.skyBehindCards(context) }

    var weights by remember { mutableStateOf<List<BodyPoint>>(emptyList()) }
    var waists by remember { mutableStateOf<List<BodyPoint>>(emptyList()) }
    LaunchedEffect(seq) {
        weights = runCatching { store.points(BodyMetric.WEIGHT) }.getOrDefault(emptyList())
        waists = runCatching { store.points(BodyMetric.WAIST) }.getOrDefault(emptyList())
    }
    val entries = remember(weights, waists) { BodyMeasurementStore.mergeEntries(weights, waists) }
    val entriesByDay = remember(entries) { entries.associateBy { it.day } }

    var range by remember { mutableStateOf(BodyRange.D90) }
    var editorDay by remember { mutableStateOf<LocalDate?>(null) }
    var pendingDelete by remember { mutableStateOf<BodyEntry?>(null) }

    val weightColor = Palette.metricCyan
    val waistColor = Palette.metricPurple

    fun inRange(points: List<BodyPoint>): List<BodyPoint> =
        range.days?.let { BodyMeasurementStore.trailing(points, it) } ?: points

    // Save one day. The profile takes the value only when this day is the newest of its series, so
    // back-filling an old measurement never replaces the current weight the analytics use.
    val save: (String, Double?, Double?) -> Unit = { day, weightKg, waistCm ->
        scope.launch {
            val before = runCatching {
                store.points(BodyMetric.WEIGHT) to store.points(BodyMetric.WAIST)
            }.getOrNull() ?: return@launch
            val saved = runCatching { store.saveDay(day, weightKg, waistCm) }.getOrDefault(false)
            if (saved) {
                if (weightKg != null && !weightFollowsHealthConnect &&
                    BodyMeasurementStore.isNewest(day, before.first)
                ) {
                    profile.weightKg = BodyMeasurementStore.round1(weightKg)
                }
                if (waistCm != null && BodyMeasurementStore.isNewest(day, before.second)) {
                    profile.waistCm = BodyMeasurementStore.round1(waistCm)
                }
            }
        }
    }

    // Delete one day. When it was the newest of a series, the profile falls back to the newest remaining
    // measurement of that series (if any) instead of keeping a value that no longer exists in the log.
    val delete: (BodyEntry) -> Unit = { entry ->
        scope.launch {
            val newestWeightDay = weights.lastOrNull()?.day
            val newestWaistDay = waists.lastOrNull()?.day
            runCatching { store.deleteDay(entry.day) }
            if (entry.day == newestWeightDay && !weightFollowsHealthConnect) {
                runCatching { store.points(BodyMetric.WEIGHT).lastOrNull() }.getOrNull()
                    ?.let { profile.weightKg = it.value }
            }
            if (entry.day == newestWaistDay) {
                runCatching { store.points(BodyMetric.WAIST).lastOrNull() }.getOrNull()
                    ?.let { profile.waistCm = it.value }
            }
        }
    }

    editorDay?.let { day ->
        BodyEntryDialog(
            initialDay = day,
            entriesByDay = entriesByDay,
            units = units,
            accent = Palette.accent,
            onDismiss = { editorDay = null },
            onSave = { d, w, wa ->
                editorDay = null
                save(d, w, wa)
            },
        )
    }
    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = Palette.surfaceOverlay,
            title = {
                Text(uiString(R.string.fork_body_delete_title), style = NoopType.title2, color = Palette.textPrimary)
            },
            text = {
                Text(
                    uiString(R.string.fork_body_delete_message, mediumDate(entry.day)),
                    style = NoopType.body,
                    color = Palette.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    delete(entry)
                }) {
                    Text(uiString(R.string.fork_body_delete), color = Palette.statusCritical)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(uiString(R.string.fork_body_cancel), color = Palette.textSecondary)
                }
            },
        )
    }

    val latestWeight = weights.lastOrNull()
    val latestWaist = waists.lastOrNull()
    val weightChange = remember(weights) { BodyMeasurementStore.change(weights, 30L) }
    val waistChange = remember(waists) { BodyMeasurementStore.change(waists, 30L) }
    val whtr = BodyMeasurementStore.waistToHeight(latestWaist?.value, heightCm)

    LazyScreenScaffold(
        title = uiString(R.string.nav_body_measurements),
        subtitle = uiString(R.string.fork_body_subtitle),
        topBackground = screenBackdropSlot(showDayCycleBackground, skyBehindCards),
        fullBleedBackground = screenBackdropFullBleed(showDayCycleBackground, skyBehindCards),
    ) {
        // HEADLINE — the newest weight and waist, each with its change over roughly the last month.
        item {
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(Metrics.gap),
            ) {
                BodyStatTile(
                    label = uiString(R.string.fork_body_weight),
                    value = latestWeight?.let { oneDecimal(units.weightOut(it.value)) },
                    unit = units.weightUnit,
                    date = latestWeight?.let { shortDate(it.day) },
                    change = weightChange?.let { c ->
                        uiString(
                            R.string.fork_body_change_since,
                            signedOneDecimal(units.weightOut(c.delta)) + " " + units.weightUnit,
                            shortDate(c.sinceDay),
                        )
                    },
                    color = weightColor,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                BodyStatTile(
                    label = uiString(R.string.fork_body_waist),
                    value = latestWaist?.let { oneDecimal(units.waistOut(it.value)) },
                    unit = units.waistUnit,
                    date = latestWaist?.let { shortDate(it.day) },
                    change = waistChange?.let { c ->
                        uiString(
                            R.string.fork_body_change_since,
                            signedOneDecimal(units.waistOut(c.delta)) + " " + units.waistUnit,
                            shortDate(c.sinceDay),
                        )
                    },
                    color = waistColor,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }

        item {
            NoopButton(
                text = uiString(R.string.fork_body_add_entry),
                leadingIcon = Icons.Filled.Add,
                kind = NoopButtonKind.Primary,
                modifier = Modifier.fillMaxWidth(),
            ) { editorDay = LocalDate.now() }
        }

        if (weightFollowsHealthConnect) {
            item {
                Text(
                    uiString(R.string.fork_body_hc_note),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
        }

        if (whtr != null) {
            item {
                NoopCard(padding = 16.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Overline(uiString(R.string.fork_body_whtr), color = waistColor)
                            Text(
                                uiString(
                                    R.string.fork_body_whtr_note,
                                    UnitFormatter.heightFromCentimeters(heightCm, unitSystem),
                                ),
                                style = NoopType.footnote,
                                color = Palette.textSecondary,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            String.format(Locale.getDefault(), "%.2f", whtr),
                            style = NoopType.number(28f, weight = FontWeight.Bold),
                            color = if (whtr < 0.5) Palette.statusPositive else Palette.textPrimary,
                        )
                    }
                }
            }
        }

        // TREND — one chart per measurement over the selected window.
        item {
            SegmentedPillControl(
                items = BodyRange.entries.toList(),
                selection = range,
                label = { uiString(it.labelRes) },
                onSelect = { range = it },
                modifier = Modifier.fillMaxWidth(),
                adaptsToAvailableWidth = true,
            )
        }
        item {
            NoopCard(padding = 18.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Overline(
                        uiString(R.string.fork_body_weight) + " · " + units.weightUnit,
                        color = weightColor,
                    )
                    BodyTrendChart(
                        points = inRange(weights),
                        color = weightColor,
                        toDisplay = { units.weightOut(it) },
                    )
                }
            }
        }
        item {
            NoopCard(padding = 18.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Overline(
                        uiString(R.string.fork_body_waist) + " · " + units.waistUnit,
                        color = waistColor,
                    )
                    BodyTrendChart(
                        points = inRange(waists),
                        color = waistColor,
                        toDisplay = { units.waistOut(it) },
                    )
                }
            }
        }

        // HISTORY — every logged day, newest first. Tap a row to edit it, the bin to delete it.
        item {
            NoopCard(padding = 18.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Overline(uiString(R.string.fork_body_history))
                    if (entries.isEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.MonitorWeight,
                                contentDescription = null,
                                tint = Palette.accent,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                uiString(R.string.fork_body_no_entries),
                                style = NoopType.headline,
                                color = Palette.textPrimary,
                            )
                        }
                        Text(
                            uiString(R.string.fork_body_empty_hint),
                            style = NoopType.subhead,
                            color = Palette.textSecondary,
                        )
                    } else {
                        BodyHistoryHeader(units)
                        entries.take(HISTORY_ROW_CAP).forEach { entry ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(Palette.hairline),
                            )
                            BodyHistoryRow(
                                entry = entry,
                                units = units,
                                onEdit = {
                                    editorDay = runCatching { LocalDate.parse(entry.day) }.getOrNull()
                                },
                                onDelete = { pendingDelete = entry },
                            )
                        }
                        if (entries.size > HISTORY_ROW_CAP) {
                            Text(
                                "+ " + (entries.size - HISTORY_ROW_CAP),
                                style = NoopType.footnote,
                                color = Palette.textTertiary,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One headline tile: label, newest value + unit, its date, and the change over the last month. */
@Composable
private fun BodyStatTile(
    label: String,
    value: String?,
    unit: String,
    date: String?,
    change: String?,
    color: Color,
    modifier: Modifier = Modifier,
) {
    NoopCard(modifier = modifier, padding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Overline(label, color = color)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value ?: "–",
                    style = NoopType.number(32f, weight = FontWeight.Bold),
                    color = Palette.textPrimary,
                    maxLines = 1,
                )
                if (value != null) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        unit,
                        style = NoopType.subhead,
                        color = Palette.textSecondary,
                        modifier = Modifier.padding(bottom = 5.dp),
                    )
                }
            }
            Text(
                date ?: uiString(R.string.fork_body_no_entries),
                style = NoopType.footnote,
                color = Palette.textTertiary,
                maxLines = 1,
            )
            if (change != null) {
                Text(change, style = NoopType.footnote, color = Palette.textSecondary, maxLines = 2)
            }
        }
    }
}

/** Width of the two value columns of the history list, so header and rows line up. */
private val HistoryWeightColumn = 84.dp
private val HistoryWaistColumn = 76.dp
private val HistoryActionColumn = 40.dp

@Composable
private fun BodyHistoryHeader(units: BodyUnits) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            uiString(R.string.fork_body_date),
            style = NoopType.caption,
            color = Palette.textTertiary,
            modifier = Modifier.weight(1f),
        )
        Text(
            uiString(R.string.fork_body_weight) + " (" + units.weightUnit + ")",
            style = NoopType.caption,
            color = Palette.textTertiary,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(HistoryWeightColumn),
        )
        Text(
            uiString(R.string.fork_body_waist) + " (" + units.waistUnit + ")",
            style = NoopType.caption,
            color = Palette.textTertiary,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(HistoryWaistColumn),
        )
        Spacer(Modifier.width(HistoryActionColumn))
    }
}

@Composable
private fun BodyHistoryRow(
    entry: BodyEntry,
    units: BodyUnits,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClickLabel = uiString(R.string.fork_body_edit_entry), onClick = onEdit)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            mediumDate(entry.day),
            style = NoopType.body,
            color = Palette.textPrimary,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            entry.weightKg?.let { oneDecimal(units.weightOut(it)) } ?: "–",
            style = NoopType.bodyNumber,
            color = Palette.textPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(HistoryWeightColumn),
        )
        Text(
            entry.waistCm?.let { oneDecimal(units.waistOut(it)) } ?: "–",
            style = NoopType.bodyNumber,
            color = Palette.textPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(HistoryWaistColumn),
        )
        IconButton(onClick = onDelete, modifier = Modifier.size(HistoryActionColumn)) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = uiString(R.string.fork_body_delete),
                tint = Palette.textTertiary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * A small line chart of one measurement: dates on the x-axis at their real spacing (a gap without entries
 * stays a gap), the value range padded so a flat series does not sit on the frame, the newest point marked.
 */
@Composable
private fun BodyTrendChart(
    points: List<BodyPoint>,
    color: Color,
    toDisplay: (Double) -> Double,
) {
    val dated = remember(points) {
        points.mapNotNull { p ->
            runCatching { LocalDate.parse(p.day).toEpochDay() }.getOrNull()?.let { epochDay -> epochDay to p.value }
        }
    }
    if (dated.size < 2) {
        Text(
            uiString(R.string.fork_body_chart_empty),
            style = NoopType.footnote,
            color = Palette.textTertiary,
        )
        return
    }
    val values = dated.map { toDisplay(it.second) }
    val minV = values.min()
    val maxV = values.max()
    val pad = maxOf((maxV - minV) * 0.15, 0.5)
    val yMin = minV - pad
    val yMax = maxV + pad
    val x0 = dated.first().first
    val x1 = dated.last().first
    val grid = Palette.textPrimary.copy(alpha = 0.08f)
    val markerCore = Palette.tipCore
    val chartHeight = 150.dp
    val axisWidth = 44.dp

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.width(axisWidth).height(chartHeight),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(oneDecimal(yMax), style = NoopType.captionNumber, color = Palette.textTertiary, maxLines = 1)
                Text(oneDecimal((yMax + yMin) / 2.0), style = NoopType.captionNumber, color = Palette.textTertiary, maxLines = 1)
                Text(oneDecimal(yMin), style = NoopType.captionNumber, color = Palette.textTertiary, maxLines = 1)
            }
            Canvas(modifier = Modifier.weight(1f).height(chartHeight)) {
                val w = size.width
                val h = size.height
                for (i in 0..2) {
                    val y = h * i / 2f
                    drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1.dp.toPx())
                }
                val span = (x1 - x0).toFloat().coerceAtLeast(1f)
                val yRange = (yMax - yMin).toFloat()
                val pts = dated.mapIndexed { i, (x, _) ->
                    Offset(
                        ((x - x0).toFloat() / span) * w,
                        h - ((values[i] - yMin).toFloat() / yRange) * h,
                    )
                }
                val line = Path().apply {
                    moveTo(pts.first().x, pts.first().y)
                    for (k in 1 until pts.size) lineTo(pts[k].x, pts[k].y)
                }
                val area = Path().apply {
                    addPath(line)
                    lineTo(pts.last().x, h)
                    lineTo(pts.first().x, h)
                    close()
                }
                drawPath(area, brush = Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f))))
                drawPath(
                    line,
                    color = color,
                    style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                if (pts.size <= 60) {
                    pts.forEach { drawCircle(color, radius = 2.5.dp.toPx(), center = it) }
                }
                drawCircle(markerCore, radius = 4.5.dp.toPx(), center = pts.last())
                drawCircle(color, radius = 3.dp.toPx(), center = pts.last())
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = axisWidth),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(shortDate(points.first().day), style = NoopType.caption, color = Palette.textTertiary)
            Text(shortDate(points.last().day), style = NoopType.caption, color = Palette.textTertiary)
        }
    }
}

/**
 * Add or edit the measurements of one day. Arrows step the date (never into the future); stepping onto a
 * day that already has entries loads them, so the same dialog edits. An empty field skips that value (and
 * removes it when the day had one). Save stays disabled until at least one valid value is entered.
 */
@Composable
private fun BodyEntryDialog(
    initialDay: LocalDate,
    entriesByDay: Map<String, BodyEntry>,
    units: BodyUnits,
    accent: Color,
    onDismiss: () -> Unit,
    onSave: (String, Double?, Double?) -> Unit,
) {
    val today = remember { LocalDate.now() }
    fun weightTextFor(d: LocalDate): String =
        entriesByDay[d.toString()]?.weightKg?.let { oneDecimal(units.weightOut(it)) } ?: ""
    fun waistTextFor(d: LocalDate): String =
        entriesByDay[d.toString()]?.waistCm?.let { oneDecimal(units.waistOut(it)) } ?: ""

    var day by remember { mutableStateOf(initialDay) }
    var weightText by remember { mutableStateOf(weightTextFor(initialDay)) }
    var waistText by remember { mutableStateOf(waistTextFor(initialDay)) }

    fun moveTo(d: LocalDate) {
        day = d
        weightText = weightTextFor(d)
        waistText = waistTextFor(d)
    }

    val weightKg = BodyMeasurementStore.parseDecimal(weightText)?.let { units.weightIn(it) }
    val waistCm = BodyMeasurementStore.parseDecimal(waistText)?.let { units.waistIn(it) }
    val weightOk = weightText.isBlank() || (weightKg != null && BodyMetric.WEIGHT.accepts(weightKg))
    val waistOk = waistText.isBlank() || (waistCm != null && BodyMetric.WAIST.accepts(waistCm))
    val canSave = weightOk && waistOk && (weightKg != null || waistCm != null)
    val editing = entriesByDay.containsKey(day.toString())

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Palette.textPrimary,
        unfocusedTextColor = Palette.textPrimary,
        cursorColor = accent,
        focusedBorderColor = accent,
        unfocusedBorderColor = Palette.hairline,
        focusedLabelColor = accent,
        unfocusedLabelColor = Palette.textSecondary,
        focusedContainerColor = Palette.surfaceInset,
        unfocusedContainerColor = Palette.surfaceInset,
    )
    fun cleanDecimal(input: String): String = input.filter { it.isDigit() || it == ',' || it == '.' }.take(6)
    fun rangeText(v: Double): String = String.format(Locale.getDefault(), "%.0f", v)

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.surfaceOverlay,
        title = {
            Text(
                uiString(if (editing) R.string.fork_body_edit_entry else R.string.fork_body_add_entry),
                style = NoopType.title2,
                color = Palette.textPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = { moveTo(day.minusDays(1)) }) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = uiString(R.string.fork_body_previous_day),
                            tint = Palette.textSecondary,
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            mediumDateOf(day),
                            style = NoopType.headline,
                            color = Palette.textPrimary,
                            textAlign = TextAlign.Center,
                        )
                        if (day == today) {
                            Text(
                                uiString(R.string.fork_body_today),
                                style = NoopType.footnote,
                                color = Palette.textTertiary,
                            )
                        }
                    }
                    IconButton(
                        onClick = { moveTo(day.plusDays(1)) },
                        enabled = day.isBefore(today),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = uiString(R.string.fork_body_next_day),
                            tint = if (day.isBefore(today)) Palette.textSecondary else Palette.textTertiary.copy(alpha = 0.4f),
                        )
                    }
                }
                OutlinedTextField(
                    value = weightText,
                    onValueChange = { weightText = cleanDecimal(it) },
                    label = {
                        Text(uiString(R.string.fork_body_weight_field, units.weightUnit), style = NoopType.footnote)
                    },
                    singleLine = true,
                    isError = !weightOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = waistText,
                    onValueChange = { waistText = cleanDecimal(it) },
                    label = {
                        Text(uiString(R.string.fork_body_waist_field, units.waistUnit), style = NoopType.footnote)
                    },
                    singleLine = true,
                    isError = !waistOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (!weightOk || !waistOk) {
                        uiString(R.string.fork_body_invalid)
                    } else {
                        uiString(
                            R.string.fork_body_range_hint,
                            rangeText(units.weightOut(BodyMetric.WEIGHT.minValue)) + " " + units.weightUnit,
                            rangeText(units.weightOut(BodyMetric.WEIGHT.maxValue)) + " " + units.weightUnit,
                            rangeText(units.waistOut(BodyMetric.WAIST.minValue)) + " " + units.waistUnit,
                            rangeText(units.waistOut(BodyMetric.WAIST.maxValue)) + " " + units.waistUnit,
                        )
                    },
                    style = NoopType.footnote,
                    color = if (!weightOk || !waistOk) Palette.statusWarning else Palette.textTertiary,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        day.toString(),
                        if (weightText.isBlank()) null else weightKg,
                        if (waistText.isBlank()) null else waistCm,
                    )
                },
                enabled = canSave,
            ) {
                Text(
                    uiString(R.string.fork_body_save),
                    color = if (canSave) accent else Palette.textTertiary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(uiString(R.string.fork_body_cancel), color = Palette.textSecondary)
            }
        },
    )
}
