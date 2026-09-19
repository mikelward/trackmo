package app.trackmo.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import app.trackmo.data.DataStoreSnapshotStore
import app.trackmo.domain.DeparturesSnapshot
import app.trackmo.domain.SnapshotStore

/**
 * Feeds the widget without changing the in-app view. [save] writes the app's last-good
 * snapshot to the shared [DataStoreSnapshotStore] (the file the widget reads) and pokes the
 * widget to re-render; [load] returns null so the in-app `MainViewModel` does **not** restore
 * it. That asymmetry is deliberate: the in-app nearby set is location-derived, and restoring a
 * previous location's stops under a newly-resolved set would mislead — the reason `MainActivity`
 * used `SnapshotStore.NONE`. This keeps that in-app behavior while still giving the widget data.
 * Interim until Phase 2's user-chosen watched stops make the persisted set meaningful to
 * restore in-app too.
 */
class WidgetSnapshotStore(context: Context) : SnapshotStore {
    private val appContext = context.applicationContext
    // Wire the sanitized warn sink so a discarded corrupt snapshot file is logged rather than
    // silently dropped. from() keeps the first caller's sink, so the widget's provideGlance
    // passes the same one — whichever initializes the singleton first, corruption is traced.
    private val delegate = DataStoreSnapshotStore.from(appContext, warn = ::logWidgetSnapshotWarning)

    override suspend fun load(): DeparturesSnapshot? = null

    override suspend fun save(snapshot: DeparturesSnapshot) {
        delegate.save(snapshot)
        TrackmoWidget().updateAll(appContext)
    }
}
