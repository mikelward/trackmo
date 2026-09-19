package app.trackmo.widget

import app.trackmo.domain.Departure
import app.trackmo.domain.DeparturesSnapshot
import app.trackmo.domain.StopArrivals
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widget's render decision is derived purely from the snapshot and the clock, so it is
 * exercised here without a Glance host. Stops use `490…` example ids and canned line names
 * (SPEC *Privacy* — never a real watched-stop set).
 */
class WidgetModelTest {
    private val now: Instant = Instant.parse("2026-09-18T08:00:00Z")

    private fun departure(lineId: String, offsetSeconds: Long) = Departure(
        lineId = lineId,
        lineName = lineId,
        direction = "inbound",
        destination = "Brixton",
        platform = null,
        expectedArrival = now.plusSeconds(offsetSeconds),
        mode = "tube",
    )

    private fun stop(id: String, departures: List<Departure>, fetchedAt: Instant) = StopArrivals(
        id,
        "Example Stop $id",
        departures,
        fetchedAt,
        disruptions = emptyList(),
    )

    @Test
    fun `a null snapshot is the empty state`() {
        val model = widgetModel(snapshot = null, now = now)
        assertFalse(model.hasData)
        assertFalse(model.stale)
        assertNull(model.stamp)
        assertTrue(model.rows.isEmpty())
    }

    @Test
    fun `a snapshot with no stops is the empty state`() {
        val model = widgetModel(DeparturesSnapshot(stops = emptyList(), fetchedAt = now), now)
        assertFalse(model.hasData)
        assertTrue(model.rows.isEmpty())
    }

    @Test
    fun `a fresh snapshot has data, a stamp, and is not stale`() {
        val snapshot = DeparturesSnapshot(
            stops = listOf(stop("490000001A", listOf(departure("victoria", 120)), now.minusSeconds(30))),
            fetchedAt = now.minusSeconds(30),
        )
        val model = widgetModel(snapshot, now)
        assertTrue(model.hasData)
        assertFalse(model.stale)
        assertEquals("Updated just now", model.stamp)
        assertEquals(listOf("victoria"), model.rows.map { it.lineId })
    }

    @Test
    fun `a snapshot older than the staleness threshold is marked stale`() {
        val snapshot = DeparturesSnapshot(
            stops = listOf(stop("490000001A", listOf(departure("victoria", 120)), now.minusSeconds(600))),
            fetchedAt = now.minusSeconds(600),
        )
        val model = widgetModel(snapshot, now)
        assertTrue(model.hasData)
        assertTrue(model.stale)
    }

    @Test
    fun `fresh rows survive the cap ahead of stale ones`() {
        // A partial refresh: six soon departures from a stale stop (its refresh failed, so its
        // rows will be `?`-withheld) plus one later, trustworthy departure from a fresh stop.
        // Soonest-first, the six stale rows precede the fresh one, so a plain take(6) would
        // drop the only live row and the widget would show six `?`. Fresh must be ranked first.
        val staleStop = stop(
            "490000001A",
            (1..6).map { departure("stale$it", it * 60L) },
            fetchedAt = now.minusSeconds(600),
        )
        val freshStop = stop(
            "490000002B",
            listOf(departure("freshline", 900L)),
            fetchedAt = now.minusSeconds(30),
        )
        val snapshot = DeparturesSnapshot(stops = listOf(staleStop, freshStop), fetchedAt = now.minusSeconds(30))
        val model = widgetModel(snapshot, now, maxRows = 6)
        assertEquals(6, model.rows.size)
        assertTrue("the fresh stop's row must survive the cap", model.rows.any { it.lineId == "freshline" })
    }

    @Test
    fun `rows are capped so a long list can't overflow the cell`() {
        // Ten distinct lines at one stop; the cap keeps the widget within its cell.
        val departures = (1..10).map { departure("line$it", it * 60L) }
        val snapshot = DeparturesSnapshot(
            stops = listOf(stop("490000001A", departures, now)),
            fetchedAt = now,
        )
        val model = widgetModel(snapshot, now, maxRows = 6)
        assertEquals(6, model.rows.size)
    }
}
