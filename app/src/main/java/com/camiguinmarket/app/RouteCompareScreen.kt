package com.camiguinmarket.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.*
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

private enum class CompareTap { NONE, A, B }

/**
 * Compare the A→B route on both maps: Google Map (native) and Mapbox (WebView).
 * The same A and B are used, and the distance/time from both is shown
 * so you can see whether they agree on the route.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteCompareScreen(
    modifier: Modifier = Modifier,
    initialA: LatLng? = null,
    initialB: LatLng? = null,
    apiKey: String = mapsApiKey,
    onBack: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()

    var pointA by remember { mutableStateOf(initialA) }
    var pointB by remember { mutableStateOf(initialB) }
    var tapTarget by remember { mutableStateOf(CompareTap.NONE) }
    var status by remember {
        mutableStateOf("Set Point A and Point B: press 🅰️/🅱️, then tap the Google map.")
    }

    var googleRoute by remember { mutableStateOf<MapRoute?>(null) }
    var googleError by remember { mutableStateOf<String?>(null) }
    var googleLoading by remember { mutableStateOf(false) }
    var mapboxStats by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var mapboxNonce by remember { mutableIntStateOf(0) }

    fun itandi() {
        val a = pointA
        val b = pointB
        if (a == null || b == null) {
            status = "Point A (start) and Point B (destination) are required."
            return
        }
        googleRoute = null
        googleError = null
        mapboxStats = null
        status = "Fetching the route for both maps…"

        if (apiKey.isBlank()) {
            googleError = "No MAPS_API_KEY (Google)."
        } else {
            googleLoading = true
            scope.launch {
                fetchRoute(a, b, apiKey)
                    .onSuccess { googleRoute = it }
                    .onFailure { googleError = friendlyMapError(it) }
                googleLoading = false
            }
        }
        // Force the Mapbox map to recompute the route.
        mapboxNonce = mapboxNonce + 1
    }

    val googleStats = googleRoute?.let {
        formatRouteStats(it.distanceMeters.toDouble(), it.durationSeconds.toDouble())
    }
    val mapboxText = mapboxStats?.let { formatRouteStats(it.first, it.second) }
    val difference = if (googleRoute != null && mapboxStats != null) {
        val gKm = googleRoute!!.distanceMeters / 1000.0
        val mKm = mapboxStats!!.first / 1000.0
        val gMin = googleRoute!!.durationSeconds / 60.0
        val mMin = mapboxStats!!.second / 60.0
        val dKm = abs(gKm - mKm)
        val dMin = abs(gMin - mMin)
        String.format(Locale.US, "📍 Distance: %.2f km • %.0f min", dKm, dMin) +
            if (dKm <= 0.5) "  ✅ Very close" else "  ⚠️ Not the same"
    } else null

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Compare: Google vs Mapbox") },
                navigationIcon = {
                    onBack?.let { TextButton(onClick = it) { Text("⬅ Balik") } }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(status, style = MaterialTheme.typography.bodySmall)
                if (apiKey.isBlank()) {
                    Text(
                        "⚠️ No MAPS_API_KEY: the Google side cannot compute. " +
                            "Add it to local.properties or GitHub secrets.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    "A: ${pointA?.let { formatCoordinates(it) } ?: "wala pa"}  •  " +
                        "B: ${pointB?.let { formatCoordinates(it) } ?: "wala pa"}",
                    style = MaterialTheme.typography.bodySmall
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        tapTarget = CompareTap.A
                        status = "Tap the Google map to place Point A."
                    }) { Text("🅰️ Set A") }
                    OutlinedButton(onClick = {
                        tapTarget = CompareTap.B
                        status = "Tap the Google map to place Point B."
                    }) { Text("🅱️ Set B") }
                    Button(
                        enabled = pointA != null && pointB != null,
                        onClick = { itandi() }
                    ) { Text("🧭 Compare") }
                }
                Text(
                    "Google: " + (googleStats
                        ?: if (googleLoading) "nagkalkulo…" else "wala pa"),
                    style = MaterialTheme.typography.bodyMedium
                )
                googleError?.let { error ->
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Text(
                    "Mapbox: " + (mapboxText ?: "wala pa"),
                    style = MaterialTheme.typography.bodyMedium
                )
                difference?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }

            Text(
                "Google Map",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp)
            )
            CompareGoogleMap(
                pointA = pointA,
                pointB = pointB,
                route = googleRoute,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                onTap = { point ->
                    when (tapTarget) {
                        CompareTap.A -> {
                            pointA = point; googleRoute = null
                            status = "Point A (Google): ${formatCoordinates(point)}"
                        }
                        CompareTap.B -> {
                            pointB = point; googleRoute = null
                            status = "Point B (Google): ${formatCoordinates(point)}"
                        }
                        CompareTap.NONE -> {
                            status = "Press 🅰️ Set A or 🅱️ Set B first, then tap the map."
                        }
                    }
                    tapTarget = CompareTap.NONE
                }
            )

            Text(
                "Mapbox (same A and B)",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 12.dp, top = 4.dp)
            )
            MapboxRouteView(
                latitude = pointB?.latitude ?: pointA?.latitude ?: CamiguinCenter.latitude,
                longitude = pointB?.longitude ?: pointA?.longitude ?: CamiguinCenter.longitude,
                routeA = pointA,
                routeB = pointB,
                nonce = mapboxNonce,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                onRouteResult = { distanceMeters, durationSeconds ->
                    mapboxStats = distanceMeters to durationSeconds
                }
            )
        }
    }
}

/** Small Google Map that draws the A→B route (no other buttons). */
@Composable
private fun CompareGoogleMap(
    pointA: LatLng?,
    pointB: LatLng?,
    route: MapRoute?,
    modifier: Modifier = Modifier,
    onTap: (LatLng) -> Unit
) {
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(pointA ?: pointB ?: CamiguinCenter, ISLAND_ZOOM)
    }

    LaunchedEffect(route) {
        route?.let { r ->
            val builder = LatLngBounds.Builder()
            r.points.forEach { builder.include(it) }
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngBounds(builder.build(), 100)
            )
        }
    }

    GoogleMap(
        modifier = modifier,
        cameraPositionState = cameraPositionState,
        onMapClick = { onTap(it) }
    ) {
        route?.let { r ->
            Polyline(points = r.points, color = Color(0xFF1B7F5C), width = 12f)
        }
        pointA?.let { start ->
            key("A", start) {
                Marker(
                    state = rememberUpdatedMarkerState(position = start),
                    title = "Point A (Google)",
                    snippet = formatCoordinates(start)
                )
            }
        }
        pointB?.let { end ->
            key("B", end) {
                Marker(
                    state = rememberUpdatedMarkerState(position = end),
                    title = "Point B (Google)",
                    snippet = formatCoordinates(end)
                )
            }
        }
    }
}

/** Full-screen compare view. */
@Composable
fun RouteCompareDialog(
    initialA: LatLng? = null,
    initialB: LatLng? = null,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize()) {
            RouteCompareScreen(
                initialA = initialA,
                initialB = initialB,
                onBack = onDismiss,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
