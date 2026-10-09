package com.sdrplanes.ui

import android.content.Context
import com.sdrplanes.decode.LatLon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.android.offline.OfflineTilePyramidRegionDefinition
import kotlin.math.cos

sealed interface OfflineMapState {
    data object Unknown : OfflineMapState
    data object None : OfflineMapState
    data class Downloading(val progress: Float, val bytes: Long) : OfflineMapState
    data class Ready(val bytes: Long, val center: LatLon) : OfflineMapState
    data class Failed(val message: String) : OfflineMapState
}

/**
 * Offline map: OpenFreeMap tiles for [RADIUS_KM] around the receiver, up to zoom [MAX_ZOOM],
 * stored by MapLibre's offline database. One region per theme style (dark + light); the
 * vector tiles are shared between them, so the second one mostly adds styles and fonts.
 * MapLibre calls back on the main thread.
 */
object OfflineMaps {
    const val RADIUS_KM = 300.0
    private const val MAX_ZOOM = 10.0
    private const val TAG = "sdrplanes"
    private val STYLES = listOf(STYLE_URL_DARK, STYLE_URL_LIGHT)

    private val _state = MutableStateFlow<OfflineMapState>(OfflineMapState.Unknown)
    val state: StateFlow<OfflineMapState> = _state.asStateFlow()

    private fun ours(regions: Array<OfflineRegion>) =
        regions.filter { String(it.metadata).startsWith("$TAG;") }

    private fun centerOf(r: OfflineRegion): LatLon? =
        String(r.metadata).split(';').getOrNull(2)?.split(',')?.let { p ->
            val lat = p.getOrNull(0)?.toDoubleOrNull()
            val lon = p.getOrNull(1)?.toDoubleOrNull()
            if (lat != null && lon != null) LatLon(lat, lon) else null
        }

    /** Re-reads what is stored (call when the settings screen opens). */
    fun refresh(context: Context) {
        if (_state.value is OfflineMapState.Downloading) return
        val app = context.applicationContext
        OfflineManager.getInstance(app).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val regions = ours(offlineRegions ?: emptyArray())
                _state.value = if (regions.isEmpty()) {
                    OfflineMapState.None
                } else {
                    // Regions share their tiles, so per-region sizes overlap: report the database file.
                    OfflineMapState.Ready(databaseBytes(app), centerOf(regions.first()) ?: LatLon(0.0, 0.0))
                }
            }

            override fun onError(error: String) {
                _state.value = OfflineMapState.Failed(error)
            }
        })
    }

    /** Replaces any stored area with one around [center]. */
    fun download(context: Context, center: LatLon) {
        if (_state.value is OfflineMapState.Downloading) return
        _state.value = OfflineMapState.Downloading(0f, 0)
        delete(context) { downloadStyle(context, center, 0, 0) }
    }

    private fun downloadStyle(context: Context, center: LatLon, index: Int, bytesBefore: Long) {
        if (index >= STYLES.size) {
            _state.value = OfflineMapState.Unknown // done downloading; refresh() reads the result
            refresh(context.applicationContext)
            return
        }
        val definition = OfflineTilePyramidRegionDefinition(
            STYLES[index], bounds(center), 0.0, MAX_ZOOM, context.resources.displayMetrics.density,
        )
        val meta = "$TAG;${STYLES[index]};${center.lat},${center.lon}".toByteArray()
        OfflineManager.getInstance(context).createOfflineRegion(definition, meta, object : OfflineManager.CreateOfflineRegionCallback {
            override fun onCreate(offlineRegion: OfflineRegion) {
                offlineRegion.setObserver(object : OfflineRegion.OfflineRegionObserver {
                    override fun onStatusChanged(status: OfflineRegionStatus) {
                        val part = if (status.requiredResourceCount > 0) {
                            status.completedResourceCount.toFloat() / status.requiredResourceCount
                        } else 0f
                        // The second style reuses the first one's tiles: count only what is new.
                        val bytes = maxOf(bytesBefore, status.completedResourceSize)
                        _state.value = OfflineMapState.Downloading((index + part) / STYLES.size, bytes)
                        if (status.isComplete) {
                            offlineRegion.setObserver(null)
                            offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                            downloadStyle(context, center, index + 1, bytes)
                        }
                    }

                    override fun onError(error: OfflineRegionError) {
                        // MapLibre retries server errors and skips missing tiles by itself; only
                        // a lost connection needs the user.
                        if (error.reason != OfflineRegionError.REASON_CONNECTION) return
                        _state.value = OfflineMapState.Failed("No connection – download paused, try again")
                        offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    }

                    override fun mapboxTileCountLimitExceeded(limit: Long) {
                        _state.value = OfflineMapState.Failed("Tile limit ($limit) exceeded")
                        offlineRegion.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    }
                })
                offlineRegion.setDownloadState(OfflineRegion.STATE_ACTIVE)
            }

            override fun onError(error: String) {
                _state.value = OfflineMapState.Failed(error)
            }
        })
    }

    /** Deletes the stored area, then runs [then]. */
    fun delete(context: Context, then: (() -> Unit)? = null) {
        val app = context.applicationContext
        OfflineManager.getInstance(app).listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
            override fun onList(offlineRegions: Array<OfflineRegion>?) {
                val regions = ours(offlineRegions ?: emptyArray())
                if (regions.isEmpty()) {
                    finish()
                    return
                }
                var pending = regions.size
                for (r in regions) {
                    r.setDownloadState(OfflineRegion.STATE_INACTIVE)
                    r.delete(object : OfflineRegion.OfflineRegionDeleteCallback {
                        override fun onDelete() { if (--pending == 0) finish() }
                        override fun onError(error: String) { if (--pending == 0) finish() }
                    })
                }
            }

            override fun onError(error: String) = finish()

            fun finish() {
                if (then != null) then() else _state.value = OfflineMapState.None
            }
        })
    }

    /** MapLibre's offline database (offline regions plus the small ambient cache). */
    private fun databaseBytes(context: Context) = java.io.File(context.filesDir, "mbgl-offline.db").length()

    private fun bounds(c: LatLon): LatLngBounds {
        val dLat = RADIUS_KM / 111.0
        val dLon = RADIUS_KM / (111.0 * cos(Math.toRadians(c.lat)).coerceAtLeast(0.1))
        return LatLngBounds.from(
            (c.lat + dLat).coerceAtMost(85.0), c.lon + dLon, (c.lat - dLat).coerceAtLeast(-85.0), c.lon - dLon,
        )
    }
}
