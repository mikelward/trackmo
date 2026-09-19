package app.trackmo.widget

import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.testing.unit.hasText
import app.trackmo.domain.Departure
import app.trackmo.domain.DepartureRow
import java.time.Instant
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Layout coverage for the Glance widget. Glance emits RemoteViews rather than a Compose tree,
 * so Roborazzi can't pixel-capture it; this harness renders [WidgetContent] and asserts the
 * emitted layout nodes for each state instead — the reasonable form of screenshot coverage
 * here (SPEC *Testing*). The `widgetModel` decision is covered separately by [WidgetModelTest].
 *
 * Runs under Robolectric because the Glance unit-test harness builds a real `android.os.Bundle`
 * (a plain JVM unit test throws "not mocked" on it); no pixels are rendered, so no graphics mode
 * is needed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WidgetContentTest {
    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    private fun row(lineId: String, offsetSeconds: Long, fetchedAt: Instant) = DepartureRow(
        stopId = "490000001A",
        stopName = "Example Stop",
        lineId = lineId,
        lineName = lineId,
        direction = "inbound",
        directionKey = "inbound",
        destination = "Brixton",
        mode = "tube",
        upcoming = listOf(
            Departure(
                lineId = lineId,
                lineName = lineId,
                direction = "inbound",
                destination = "Brixton",
                platform = null,
                expectedArrival = now.plusSeconds(offsetSeconds),
                mode = "tube",
            ),
        ),
        fetchedAt = fetchedAt,
    )

    @Test
    fun `no data renders the open-app prompt`() = runGlanceAppWidgetUnitTest {
        provideComposable {
            WidgetContent(WidgetModel(hasData = false, stale = false, stamp = null, rows = emptyList()), now)
        }
        onNode(hasText("Open Trackmo")).assertExists()
    }

    @Test
    fun `a snapshot with no rows renders the no-departures message`() = runGlanceAppWidgetUnitTest {
        provideComposable {
            WidgetContent(
                WidgetModel(hasData = true, stale = false, stamp = "Updated just now", rows = emptyList()),
                now,
            )
        }
        onNode(hasText("No upcoming departures")).assertExists()
    }

    @Test
    fun `a stale empty snapshot says the data may be out of date`() = runGlanceAppWidgetUnitTest {
        provideComposable {
            WidgetContent(
                WidgetModel(hasData = true, stale = true, stamp = "Updated 12 min ago", rows = emptyList()),
                now,
            )
        }
        onNode(hasText("out of date")).assertExists()
    }

    @Test
    fun `a fresh row renders its destination and stamp`() = runGlanceAppWidgetUnitTest {
        provideComposable {
            WidgetContent(
                WidgetModel(
                    hasData = true,
                    stale = false,
                    stamp = "Updated just now",
                    rows = listOf(row("victoria", 120, now.minusSeconds(30))),
                ),
                now,
            )
        }
        onNode(hasText("Brixton")).assertExists()
        onNode(hasText("Updated just now")).assertExists()
    }

    @Test
    fun `a stale row withholds its countdown as a question mark`() = runGlanceAppWidgetUnitTest {
        provideComposable {
            WidgetContent(
                WidgetModel(
                    hasData = true,
                    stale = true,
                    stamp = "Updated 12 min ago",
                    rows = listOf(row("victoria", 120, now.minusSeconds(900))),
                ),
                now,
            )
        }
        // The stamp invites a refresh, and the withheld countdown is "?" (never a live number).
        onNode(hasText("tap to refresh")).assertExists()
        onNode(hasText("?")).assertExists()
    }
}
