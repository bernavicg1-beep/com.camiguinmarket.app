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
 * Itandi ang ruta A→B sa duha ka mapa: Google Map (native) ug Mapbox (WebView).
 * Pareho ang A ug B nga ginagamit, ug ipakita ang gilay-on/oras sa duha
 * aron makita kon managsama ang ilang gihatag nga ruta.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteCompareScreen(
    initialA: LatLng? = null,
    initialB: LatLng? = null,
    apiKey: String = mapsApiKey,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()

    var pointA by remember { mutableStateOf(initialA) }
    var pointB by remember { mutableStateOf(initialB) }
    var tapTarget by remember { mutableStateOf(CompareTap.NONE) }
    var status by remember {
        mutableStateOf("I-set ang Point A ug Point B: pindota ang 🅰️/🅱️ dayon tap sa Google map.")
    }

    var googleRoute by remember { mutableStateOf<MapRoute?>(null) }
    var googleError by remember { mutableStateOf<String?>(null) }
    var googleLoading by remember { mutableStateOf(false) }
    var mapboxStats by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var mapboxNonce by remember { mutableStateOf(0) }

    fun itandi() {
        val a = pointA
        val b = pointB
        if (a == null || b == null) {
            status = "Kinahanglan ang Point A (start) ug Point B (destination)."
            return
        }
        googleRoute = null
        googleError = null
        mapboxStats = null
        status = "Gikuha ang ruta sa duha ka mapa…"

        if (apiKey.isBlank()) {
            googleError = "Walay MAPS_API_KEY (Google)."
        } else {
            googleLoading = true
            scope.launch {
                fetchRoute(a, b, apiKey)
                    .onSuccess { googleRoute = it }
                    .onFailure { googleError = friendlyMapError(it) }
                googleLoading = false
            }
        }
        // Gipugos ang Mapbox map nga kompyuton pag-usab ang ruta.
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
        String.format(Locale.US, "📍 Pagkalahi: %.2f km • %.0f min", dKm, dMin) +
            if (dKm <= 0.5) "  ✅ Duol ra kaayo" else "  ⚠️ Dili pareho"
    } else null

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Itandi: Google vs Mapbox") },
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
                        "⚠️ Walay MAPS_API_KEY: dili maka-compute ang Google side. " +
                            "Idugang kini sa local.properties o GitHub secrets.",
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
                        status = "Pindota ang Google map aron ibutang ang Point A."
                    }) { Text("🅰️ Set A") }
                    OutlinedButton(onClick = {
                        tapTarget = CompareTap.B
                        status = "Pindota ang Google map aron ibutang ang Point B."
                    }) { Text("🅱️ Set B") }
                    Button(
                        enabled = pointA != null && pointB != null,
                        onClick = { itandi() }
                    ) { Text("🧭 Itandi") }
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
                            status = "Pindota ang 🅰️ Set A o 🅱️ Set B una, dayon tap sa mapa."
                        }
                    }
                    tapTarget = CompareTap.NONE
                }
            )

            Text(
                "Mapbox (pareho nga A ug B)",
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

/** Gamay nga Google Map nga nagdrowing sa ruta A→B (walay laing buttons). */
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
                    state = rememberMarkerState(position = start),
                    title = "Point A (Google)",
                    snippet = formatCoordinates(start)
                )
            }
        }
        pointB?.let { end ->
            key("B", end) {
                Marker(
                    state = rememberMarkerState(position = end),
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
