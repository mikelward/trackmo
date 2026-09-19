package app.trackmo.widget

import android.content.Context
import android.util.Log
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.trackmo.MainActivity
import app.trackmo.data.DataStoreSnapshotStore
import app.trackmo.domain.Countdown
import app.trackmo.domain.DepartureLabels
import app.trackmo.domain.DepartureRow
import app.trackmo.domain.DepartureRows
import app.trackmo.domain.DeparturesSnapshot
import app.trackmo.domain.RelativeTime
import app.trackmo.domain.Staleness
import app.trackmo.domain.lineCode
import app.trackmo.ui.lineFillColor
import app.trackmo.ui.textColorOn
import java.time.Duration
import java.time.Instant
import kotlin.time.toKotlinDuration
import kotlinx.coroutines.CancellationException

/**
 * The home-screen (and, where Android 16 QPR allows, lock-screen) widget. It renders the
 * **persisted** departures snapshot ([DataStoreSnapshotStore]) — the same last-good the app
 * writes and restores (SPEC *One widget, many surfaces*) — never the network: `provideGlance`
 * reads the snapshot once off the render path and renders from it, so the widget can't stall
 * on a fetch. The stamp keeps it honest when the data is old, and tapping opens the app.
 *
 * **Interim data source**: until Phase 2's user-chosen watched stops, the app feeds this the
 * last *nearby* set it fetched (see the save-only [WidgetSnapshotStore] wiring in
 * `MainActivity`), so the widget shows "the stops near where you last opened the app". Phase 2
 * replaces that with the watched stops; a live-refresh cadence for the widget is D5.
 */
class TrackmoWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // Off the render path: read the persisted snapshot before composing. A read failure
        // degrades to the empty state (open-the-app prompt) rather than crashing the host —
        // but cancellation is rethrown (never swallowed, which would break structured
        // concurrency) and a real read failure is logged, sanitized, so it's not silent.
        val snapshot = try {
            // Pass the sanitized warn sink so a *corrupt* file is logged, not silently
            // dropped: the corruption handler consumes the CorruptionException and calls this
            // callback (the catch below only sees a read that throws all the way out, which a
            // handled corruption doesn't). from() uses the first caller's warn, so both this
            // and WidgetSnapshotStore wire it — whichever initializes the singleton first.
            DataStoreSnapshotStore.from(context, warn = ::logWidgetSnapshotWarning).load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logWidgetSnapshotWarning("widget snapshot read failed: ${e::class.simpleName}")
            null
        }
        val now = Instant.now()
        provideContent { WidgetContent(widgetModel(snapshot, now), now) }
    }
}

/**
 * The widget's render inputs, derived purely from the snapshot and the clock so the decision
 * of what to show is unit-testable without a Glance host. [rows] is capped so a long list
 * can't push the widget past its cell; [stale] drives the "tap to refresh" note (SPEC D4 —
 * old data is marked, not passed off as live).
 */
internal data class WidgetModel(
    val hasData: Boolean,
    val stale: Boolean,
    val stamp: String?,
    val rows: List<DepartureRow>,
)

internal fun widgetModel(snapshot: DeparturesSnapshot?, now: Instant, maxRows: Int = 6): WidgetModel {
    if (snapshot == null || snapshot.stops.isEmpty()) {
        return WidgetModel(hasData = false, stale = false, stamp = null, rows = emptyList())
    }
    val age = Duration.between(snapshot.fetchedAt, now)
    val stale = Staleness.isStale(age.toKotlinDuration())
    // Rank fresh rows ahead of stale ones before the cap, so a partial refresh (six stale
    // rows from stops that failed to refresh, plus a trustworthy later departure from a fresh
    // stop) doesn't spend every slot on `?`-withheld stale rows and drop the live one. Stable,
    // so `across`'s soonest-first order is preserved within each group. Staleness is per row,
    // from its own stop's fetch age (matching WidgetRow's per-row withhold).
    val rows = DepartureRows.across(snapshot.stops, now)
        .sortedBy { if (Staleness.isStale(Duration.between(it.fetchedAt, now).toKotlinDuration())) 1 else 0 }
        .take(maxRows)
    return WidgetModel(
        hasData = true,
        stale = stale,
        stamp = "Updated ${RelativeTime.formatAge(age.toKotlinDuration())}",
        rows = rows,
    )
}

@androidx.compose.runtime.Composable
internal fun WidgetContent(model: WidgetModel, now: Instant) {
    GlanceTheme {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.background)
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            Text(
                text = "Trackmo",
                style = TextStyle(
                    color = GlanceTheme.colors.onBackground,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                ),
            )
            model.stamp?.let { stamp ->
                Text(
                    text = if (model.stale) "$stamp · tap to refresh" else stamp,
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                )
            }
            Spacer(GlanceModifier.height(8.dp))
            when {
                !model.hasData ->
                    WidgetMessage("Open Trackmo to load departures")
                // Has a snapshot but no rows to show — every service has departed or the
                // stops returned none. Distinguish a trustworthy "none" from data too old to
                // assert that (SPEC D4), rather than leaving the widget blank below the header.
                model.rows.isEmpty() ->
                    WidgetMessage(
                        if (model.stale) "Departures may be out of date" else "No upcoming departures",
                    )
                else ->
                    model.rows.forEach { row ->
                        WidgetRow(row, now)
                        Spacer(GlanceModifier.height(8.dp))
                    }
            }
        }
    }
}

/** A single-line message row (empty / prompt states), styled like the stamp. */
@androidx.compose.runtime.Composable
private fun WidgetMessage(text: String) {
    Text(
        text = text,
        style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
    )
}

@androidx.compose.runtime.Composable
private fun WidgetRow(row: DepartureRow, now: Instant) {
    // Withhold this row's countdown once ITS stop is stale (per-row, from the row's own fetch
    // age — a fresh stop beside a stale one stays live), so old predictions aren't shown as
    // live-looking numbers (SPEC D4). "?" means "unknown", matching the in-app card.
    val stale = Staleness.isStale(Duration.between(row.fetchedAt, now).toKotlinDuration())
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val fill = lineFillColor(row.lineId, row.mode)
        Box(
            modifier = GlanceModifier
                .background(if (fill != null) ColorProvider(fill) else GlanceTheme.colors.surfaceVariant)
                .cornerRadius(6.dp)
                .padding(horizontal = 8.dp, vertical = 2.dp)
                // The visible label is the short code; the accessible label is the full line
                // name, so TalkBack announces "Victoria", not "VIC" (SPEC parity with the app).
                .semantics { contentDescription = row.lineName },
        ) {
            Text(
                text = lineCode(row.lineName, row.mode),
                style = TextStyle(
                    color = if (fill != null) ColorProvider(textColorOn(fill)) else GlanceTheme.colors.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                ),
            )
        }
        Spacer(GlanceModifier.width(8.dp))
        Text(
            text = DepartureLabels.destinationLabel(row.destination, row.directionKey) ?: "—",
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(color = GlanceTheme.colors.onBackground, fontSize = 13.sp),
        )
        Spacer(GlanceModifier.width(8.dp))
        Text(
            text = if (stale) "?" else Countdown.mergedLabel(row.upcoming, now),
            maxLines = 1,
            style = TextStyle(
                color = if (stale) GlanceTheme.colors.onSurfaceVariant else GlanceTheme.colors.onBackground,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
            ),
        )
    }
}

/**
 * Sanitized log sink for the widget's snapshot read — a discarded corrupt file, or a read
 * that threw. Class name / a fixed reason only, never a stop id or coordinate (SPEC Privacy).
 * A top-level function so both [TrackmoWidget.provideGlance] and [WidgetSnapshotStore] can
 * wire it into `DataStoreSnapshotStore.from`, which keeps the first caller's sink.
 */
internal fun logWidgetSnapshotWarning(message: String) = Log.w("Trackmo.Widget", message)
