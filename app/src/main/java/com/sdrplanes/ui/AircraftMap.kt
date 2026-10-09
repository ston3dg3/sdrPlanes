package com.sdrplanes.ui

import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.sdrplanes.R
import com.sdrplanes.decode.LatLon
import com.sdrplanes.model.Aircraft
import com.sdrplanes.ui.theme.NightSky
import com.sdrplanes.ui.theme.Phosphor
import com.sdrplanes.ui.theme.PhosphorDim
import com.sdrplanes.ui.theme.Plum
import com.sdrplanes.ui.theme.RadarTeal
import com.sdrplanes.ui.theme.Rust
import com.sdrplanes.ui.theme.Sunset
import com.sdrplanes.ui.theme.isEmergencySquawk
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.color
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.switchCase
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.backgroundColor
import org.maplibre.android.style.layers.PropertyFactory.circleBlur
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.fillColor
import org.maplibre.android.style.layers.PropertyFactory.fillOpacity
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconColor
import org.maplibre.android.style.layers.PropertyFactory.iconHaloBlur
import org.maplibre.android.style.layers.PropertyFactory.iconHaloColor
import org.maplibre.android.style.layers.PropertyFactory.iconHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.iconRotate
import org.maplibre.android.style.layers.PropertyFactory.iconRotationAlignment
import org.maplibre.android.style.layers.PropertyFactory.iconSize
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textLetterSpacing
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textOpacity
import org.maplibre.android.style.layers.PropertyFactory.textOptional
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.PropertyFactory.textTransform
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Dark base style; every layer is recoloured into the app palette in [retroRecolor]. */
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/dark"
private const val SRC_PLANES = "planes"
private const val SRC_TRAILS = "trails"
private const val SRC_RX = "receiver"
private const val SRC_RINGS = "rings"
private const val LAYER_PLANES = "planes-layer"
private const val IMG_PLANE = "plane"
private const val MIN_FOCUS_ZOOM = 8.0
private val RING_KM = listOf(50, 100, 150, 200)
private val MAP_FONT = arrayOf("Noto Sans Bold")

/**
 * Request to move the camera; null [zoom] keeps the current zoom but at least [MIN_FOCUS_ZOOM].
 * [seq] makes repeated requests to the same spot distinct.
 */
data class CameraRequest(val target: LatLon, val zoom: Double? = null, val seq: Long = System.nanoTime())

@Composable
fun AircraftMap(
    aircraft: List<Aircraft>,
    receiver: LatLon?,
    selectedIcao: Int?,
    cameraRequest: CameraRequest?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var style by remember { mutableStateOf<Style?>(null) }
    val currentOnSelect by rememberUpdatedState(onSelect)

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { m ->
            m.uiSettings.isAttributionEnabled = true
            m.uiSettings.setAttributionTintColor(RadarTeal.toArgb())
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isCompassEnabled = false
            m.addOnMapClickListener { point ->
                val p = m.projection.toScreenLocation(point)
                val hits = m.queryRenderedFeatures(RectF(p.x - 40, p.y - 40, p.x + 40, p.y + 40), LAYER_PLANES)
                currentOnSelect(hits.firstOrNull()?.getStringProperty("icao")?.toIntOrNull(16))
                true
            }
            m.setStyle(Style.Builder().fromUri(STYLE_URL)) { st ->
                retroRecolor(st)
                installLayers(context, st)
                style = st
            }
            map = m
        }
    }

    LaunchedEffect(style, aircraft, selectedIcao) {
        val st = style ?: return@LaunchedEffect
        st.getSourceAs<GeoJsonSource>(SRC_PLANES)?.setGeoJson(planeFeatures(aircraft, selectedIcao))
        st.getSourceAs<GeoJsonSource>(SRC_TRAILS)?.setGeoJson(trailFeatures(aircraft))
    }

    LaunchedEffect(style, receiver) {
        val st = style ?: return@LaunchedEffect
        st.getSourceAs<GeoJsonSource>(SRC_RX)?.setGeoJson(
            FeatureCollection.fromFeatures(listOfNotNull(receiver?.let { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) }))
        )
        st.getSourceAs<GeoJsonSource>(SRC_RINGS)?.setGeoJson(rangeRings(receiver))
    }

    // Wait for the style: loading it applies the style's own default camera, which would
    // overwrite any earlier move.
    LaunchedEffect(style, cameraRequest) {
        val m = map ?: return@LaunchedEffect
        if (style == null) return@LaunchedEffect
        val r = cameraRequest ?: return@LaunchedEffect
        val zoom = r.zoom ?: m.cameraPosition.zoom.coerceAtLeast(MIN_FOCUS_ZOOM)
        m.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(r.target.lat, r.target.lon), zoom), 600)
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/**
 * Repaints the OpenFreeMap "dark" style in the palette: night-sky land, teal-tinted water,
 * plum land use, teal roads, sunset airports and borders, phosphor labels.
 */
private fun retroRecolor(st: Style) {
    val water = lerp(NightSky, RadarTeal, 0.28f).toArgb()
    for (layer in st.layers) {
        val id = layer.id
        when (layer) {
            is BackgroundLayer -> layer.setProperties(backgroundColor(NightSky.toArgb()))
            is FillLayer -> layer.setProperties(
                *when {
                    id == "water" -> arrayOf(fillColor(water), fillOpacity(1f))
                    id.startsWith("aeroway") -> arrayOf(fillColor(Sunset.toArgb()), fillOpacity(0.22f))
                    id == "building" -> arrayOf(fillColor(Plum.toArgb()), fillOpacity(0.45f))
                    else -> arrayOf(fillColor(Plum.toArgb()), fillOpacity(0.22f))
                }
            )
            is LineLayer -> layer.setProperties(
                *when {
                    id.contains("casing") -> arrayOf(visibility(Property.NONE))
                    id.startsWith("aeroway") -> arrayOf(lineColor(Sunset.toArgb()), lineOpacity(0.75f))
                    id == "waterway" -> arrayOf(lineColor(water), lineOpacity(1f))
                    id.startsWith("highway_motorway") -> arrayOf(lineColor(RadarTeal.toArgb()), lineOpacity(0.6f))
                    id.startsWith("highway") || id.startsWith("road") -> arrayOf(lineColor(RadarTeal.toArgb()), lineOpacity(0.3f))
                    id.startsWith("railway") -> arrayOf(lineColor(Plum.toArgb()), lineOpacity(0.9f))
                    // Muted, so sunset/rust stay reserved for aircraft, trails and HUD.
                    id.startsWith("boundary_country") -> arrayOf(lineColor(PhosphorDim.toArgb()), lineOpacity(0.35f))
                    id.startsWith("boundary") -> arrayOf(lineColor(PhosphorDim.toArgb()), lineOpacity(0.15f))
                    else -> arrayOf(lineColor(RadarTeal.toArgb()), lineOpacity(0.3f))
                }
            )
            is SymbolLayer -> layer.setProperties(
                *when {
                    id.startsWith("road") || id.startsWith("highway_name") -> arrayOf(visibility(Property.NONE))
                    id == "water_name" -> arrayOf(textColor(RadarTeal.toArgb()))
                    id.startsWith("place_country") -> arrayOf(textColor(PhosphorDim.toArgb()), textOpacity(0.9f))
                    id.startsWith("place_city") || id == "place_state" -> arrayOf(textColor(Phosphor.toArgb()), textOpacity(0.8f))
                    else -> arrayOf(textColor(PhosphorDim.toArgb()), textOpacity(0.7f))
                },
                textHaloColor(NightSky.toArgb()),
                textHaloWidth(1.2f),
            )
        }
    }
}

private fun installLayers(context: android.content.Context, st: Style) {
    val density = context.resources.displayMetrics.density
    val icon = ContextCompat.getDrawable(context, R.drawable.ic_plane)!!
        .toBitmap((28 * density).toInt(), (28 * density).toInt())
    st.addImage(IMG_PLANE, icon, true) // SDF: lets us tint by altitude

    st.addSource(GeoJsonSource(SRC_RINGS))
    st.addSource(GeoJsonSource(SRC_TRAILS))
    st.addSource(GeoJsonSource(SRC_RX))
    st.addSource(GeoJsonSource(SRC_PLANES))

    // Radar range rings around the receiver.
    st.addLayer(
        LineLayer("rings-layer", SRC_RINGS).withProperties(
            lineColor(RadarTeal.toArgb()), lineWidth(1.2f), lineOpacity(0.55f), lineDasharray(arrayOf(4f, 3f)),
        )
    )
    st.addLayer(
        SymbolLayer("rings-label-layer", SRC_RINGS).withProperties(
            textField(get("label")), textFont(MAP_FONT), textSize(10f), textLetterSpacing(0.1f),
            textColor(RadarTeal.toArgb()), textHaloColor(NightSky.toArgb()), textHaloWidth(1.5f),
            textAllowOverlap(true),
        )
    )
    st.addLayer(
        LineLayer("trails-layer", SRC_TRAILS).withProperties(
            lineColor(Sunset.toArgb()), lineWidth(2f), lineOpacity(0.5f),
        )
    )
    // Receiver: soft teal glow with a phosphor core.
    st.addLayer(
        CircleLayer("receiver-glow-layer", SRC_RX).withProperties(
            circleRadius(18f), circleColor(RadarTeal.toArgb()), circleOpacity(0.35f), circleBlur(1f),
        )
    )
    st.addLayer(
        CircleLayer("receiver-layer", SRC_RX).withProperties(
            circleRadius(5f), circleColor(Phosphor.toArgb()),
            circleStrokeColor(RadarTeal.toArgb()), circleStrokeWidth(2f),
        )
    )

    val selected = get("selected")
    val emergency = get("emergency")
    st.addLayer(
        SymbolLayer(LAYER_PLANES, SRC_PLANES).withProperties(
            iconImage(IMG_PLANE),
            iconRotate(get("track")),
            iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
            iconAllowOverlap(true),
            iconIgnorePlacement(true),
            iconSize(switchCase(selected, literal(1.4f), literal(1.0f))),
            // Altitude ramp teal → phosphor → sunset → rust (see theme.altitudeColor); rust for emergencies.
            iconColor(
                switchCase(
                    emergency, color(Rust.toArgb()),
                    interpolate(
                        linear(), get("alt"),
                        stop(-1, color(PhosphorDim.toArgb())),
                        stop(0, color(RadarTeal.toArgb())),
                        stop(10000, color(Phosphor.toArgb())),
                        stop(25000, color(Sunset.toArgb())),
                        stop(40000, color(Rust.toArgb())),
                    ),
                )
            ),
            iconHaloColor(switchCase(selected, color(Sunset.toArgb()), color(NightSky.toArgb()))),
            iconHaloWidth(switchCase(selected, literal(3f), literal(1.5f))),
            iconHaloBlur(switchCase(selected, literal(2f), literal(0f))),
            textField(get("label")),
            textFont(MAP_FONT),
            textTransform(Property.TEXT_TRANSFORM_UPPERCASE),
            textSize(11f),
            textLetterSpacing(0.08f),
            textOffset(arrayOf(0f, 1.6f)),
            textAnchor(Property.TEXT_ANCHOR_TOP),
            textOptional(true),
            textColor(
                switchCase(
                    emergency, color(Rust.toArgb()),
                    selected, color(Sunset.toArgb()),
                    color(Phosphor.toArgb()),
                )
            ),
            textHaloColor(NightSky.toArgb()),
            textHaloWidth(1.6f),
        )
    )
}

private fun planeFeatures(aircraft: List<Aircraft>, selected: Int?): FeatureCollection =
    FeatureCollection.fromFeatures(aircraft.mapNotNull { a ->
        val p = a.position ?: return@mapNotNull null
        Feature.fromGeometry(Point.fromLngLat(p.lon, p.lat)).apply {
            addStringProperty("icao", a.icaoHex)
            addStringProperty("label", buildString {
                append(a.label)
                a.altitudeFt?.let { append('\n').append(formatAltitude(it)) }
            })
            addNumberProperty("track", a.trackDeg ?: 0.0)
            addNumberProperty("alt", a.altitudeFt ?: -1)
            addBooleanProperty("selected", a.icao == selected)
            addBooleanProperty("emergency", isEmergencySquawk(a.squawk))
        }
    })

private fun trailFeatures(aircraft: List<Aircraft>): FeatureCollection =
    FeatureCollection.fromFeatures(aircraft.filter { it.trail.size >= 2 }.map { a ->
        Feature.fromGeometry(LineString.fromLngLats(a.trail.map { Point.fromLngLat(it.lon, it.lat) }))
    })

/** Dashed circles at [RING_KM] around the receiver, each labelled at its northern point. */
private fun rangeRings(center: LatLon?): FeatureCollection {
    if (center == null) return FeatureCollection.fromFeatures(emptyList())
    val features = mutableListOf<Feature>()
    for (km in RING_KM) {
        val pts = (0..72).map { i -> destination(center, i * 5.0, km.toDouble()).let { Point.fromLngLat(it.lon, it.lat) } }
        features += Feature.fromGeometry(LineString.fromLngLats(pts))
        val north = destination(center, 0.0, km.toDouble())
        features += Feature.fromGeometry(Point.fromLngLat(north.lon, north.lat)).apply {
            addStringProperty("label", "$km KM")
        }
    }
    return FeatureCollection.fromFeatures(features)
}

/** Great-circle destination from [from] along [bearingDeg] for [km]. */
private fun destination(from: LatLon, bearingDeg: Double, km: Double): LatLon {
    val d = km / 6371.0
    val b = Math.toRadians(bearingDeg)
    val lat1 = Math.toRadians(from.lat)
    val lon1 = Math.toRadians(from.lon)
    val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(b))
    val lon2 = lon1 + atan2(sin(b) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
    return LatLon(Math.toDegrees(lat2), Math.toDegrees(lon2))
}

fun formatAltitude(ft: Int): String = if (ft >= 18000) "FL%03d".format(ft / 100) else "%,d ft".format(ft)
