package com.camiguinmarket.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.location.Location
import android.location.LocationManager
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.Locale
import kotlin.math.max

// ---------------------------------------------------------------------------
// Maps: Leaflet inside a WebView, with OpenStreetMap tiles.
// No Google Maps SDK and no Mapbox token are used anywhere in the app.
// ---------------------------------------------------------------------------

/** Coordinates, without depending on the Google Maps SDK. */
data class LatLng(val latitude: Double, val longitude: Double)

/** Center of Camiguin Island (Mambajao area). */
val CamiguinCenter = LatLng(9.1732, 124.7290)

const val ISLAND_ZOOM = 11f
const val FOCUS_ZOOM = 16f
const val ROUTE_ZOOM = 12f

/** One A→B route (OSRM, over OpenStreetMap data). */
data class MapRoute(
    val points: List<LatLng>,
    val distanceText: String,
    val durationText: String,
    val distanceMeters: Int,
    val durationSeconds: Int
)

/** One search result (a seller pin or a place). */
data class MapSearchResult(
    val label: String,
    val detail: String,
    val point: LatLng,
    val seller: Listing? = null
)

/** "12.40 km • 21 min" from meters/seconds. */
fun formatRouteStats(distanceMeters: Double, durationSeconds: Double): String =
    String.format(
        Locale.US,
        "%.2f km • %d min",
        distanceMeters / 1000.0,
        max(1, Math.round(durationSeconds / 60.0))
    )

/**
 * User-friendly message: offline, service rejected the call, or nothing found.
 * The raw exception message should not be shown to the user.
 */
fun friendlyMapError(e: Throwable?): String {
    val raw = e?.message.orEmpty()
    return when {
        e is UnknownHostException ->
            "No internet connection. Check your data or WiFi, then try again."
        e is SocketTimeoutException -> "Slow connection. Try again."
        e is ConnectException -> "Can't connect to the map service. Try again."
        raw.contains("ZERO_RESULTS", true) -> "No route or place found for this search."
        raw.isBlank() -> "Failed to load data. Try again."
        else -> raw
    }
}

private fun httpGetJson(url: String): JSONObject {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        // Nominatim asks for an identifying User-Agent.
        setRequestProperty("User-Agent", "CamiguinMarket/1.0 (Android)")
        connectTimeout = 10_000
        readTimeout = 10_000
    }
    try {
        val code = connection.responseCode
        if (code in 200..299) {
            val text = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(text)
        }
        throw IllegalStateException("HTTP $code from the map service.")
    } finally {
        connection.disconnect()
    }
}

/**
 * A→B route from OSRM's public demo server (OpenStreetMap data, driving).
 * No API key is needed.
 */
suspend fun fetchRoute(start: LatLng, end: LatLng): Result<MapRoute> =
    withContext(Dispatchers.IO) {
        try {
            val url = "https://router.project-osrm.org/route/v1/driving/" +
                "${start.longitude},${start.latitude};${end.longitude},${end.latitude}" +
                "?overview=full&geometries=geojson&steps=false"
            val json = httpGetJson(url)
            val route = json.getJSONArray("routes").getJSONObject(0)
            val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
            val points = ArrayList<LatLng>(coords.length())
            for (i in 0 until coords.length()) {
                val pair = coords.getJSONArray(i)
                points.add(LatLng(pair.getDouble(1), pair.getDouble(0)))
            }
            if (points.isEmpty()) {
                return@withContext Result.failure(IllegalStateException("ZERO_RESULTS"))
            }
            val meters = route.getDouble("distance")
            val seconds = route.getDouble("duration")
            Result.success(
                MapRoute(
                    points = points,
                    distanceText = formatRouteStats(meters, seconds),
                    durationText = "${Math.round(seconds / 60.0)} min",
                    distanceMeters = meters.toInt(),
                    durationSeconds = seconds.toInt()
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

/** Place search through Nominatim (OpenStreetMap). No API key is needed. */
suspend fun geocodePlace(query: String): Result<MapSearchResult> =
    withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode("$query, Camiguin, Philippines", "UTF-8")
            val url = "https://nominatim.openstreetmap.org/search" +
                "?q=$q&format=json&limit=1&addressdetails=0"
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", "CamiguinMarket/1.0 (Android)")
                connectTimeout = 10_000
                readTimeout = 10_000
            }
            val body = try {
                if (connection.responseCode in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    throw IllegalStateException("HTTP ${connection.responseCode}")
                }
            } finally {
                connection.disconnect()
            }
            val results = JSONArray(body)
            if (results.length() == 0) {
                return@withContext Result.failure(IllegalStateException("ZERO_RESULTS"))
            }
            val first = results.getJSONObject(0)
            Result.success(
                MapSearchResult(
                    label = first.optString("display_name").substringBefore(','),
                    detail = first.optString("display_name"),
                    point = LatLng(
                        first.optString("lat").toDoubleOrNull() ?: CamiguinCenter.latitude,
                        first.optString("lon").toDoubleOrNull() ?: CamiguinCenter.longitude
                    )
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

/** Sellers that have coordinates (pins on the map). */
fun sellersWithLocation(listings: List<Listing>): List<Listing> =
    listings.filter { it.latitude != null && it.longitude != null }

fun formatCoordinates(point: LatLng): String =
    String.format(Locale.US, "%.5f, %.5f", point.latitude, point.longitude)

/** Pin coordinates: "9.17320, 124.72900", ready to save in Firestore. */
fun coordinatesLabel(listing: Listing): String? =
    if (listing.latitude != null && listing.longitude != null) {
        String.format(Locale.US, "%.5f, %.5f", listing.latitude, listing.longitude)
    } else {
        null
    }

/**
 * Hand off to whichever map app the phone has. The geo: URI is handled by any
 * map application, so this no longer hardcodes Google Maps.
 */
fun openDirections(context: Context, point: LatLng, label: String? = null) {
    val name = Uri.encode(label ?: "Destination")
    openUri(
        context,
        ("geo:${point.latitude},${point.longitude}" +
            "?q=${point.latitude},${point.longitude}($name)").toUri(),
        "No map app found on this device."
    )
}

/** Opens the point on the OpenStreetMap website. */
fun openMapPreview(context: Context, point: LatLng, label: String?) {
    openUri(
        context,
        ("https://www.openstreetmap.org/?mlat=${point.latitude}" +
            "&mlon=${point.longitude}#map=16/${point.latitude}/${point.longitude}").toUri(),
        "No browser app found on this device."
    )
}

private fun openUri(context: Context, uri: Uri, errorMessage: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
    }
}

/** Save the coordinates into the listing document in Firestore. */
fun saveListingLocation(
    db: FirebaseFirestore,
    listingId: String,
    point: LatLng,
    onDone: (Boolean) -> Unit
) {
    db.collection("listings").document(listingId)
        .update(
            mapOf(
                "latitude" to point.latitude,
                "longitude" to point.longitude
            )
        )
        .addOnCompleteListener { onDone(it.isSuccessful) }
}

// ---------------------------------------------------------------------------
// WebView bridge
// ---------------------------------------------------------------------------

/** Called from the JavaScript in leaflet_map.html. */
class LeafletBridge(
    private val readyHandler: () -> Unit,
    private val pickHandler: (Double, Double) -> Unit,
    private val markerHandler: (String) -> Unit,
    private val myLocationHandler: () -> Unit = {}
) {
    @JavascriptInterface
    fun onReady() {
        readyHandler()
    }

    @JavascriptInterface
    fun onPick(lat: Double, lng: Double) {
        pickHandler(lat, lng)
    }

    @JavascriptInterface
    fun onMarker(id: String) {
        markerHandler(id)
    }

    @JavascriptInterface
    fun onMyLocation() {
        myLocationHandler()
    }
}

private fun leafletConfig(
    latitude: Double,
    longitude: Double,
    label: String,
    pickMode: Boolean,
    sellers: List<Listing>,
    route: List<LatLng>? = null,
    showPickup: Boolean = true,
    zoom: Float = 13f
): String {
    val pins = JSONArray()
    sellersWithLocation(sellers).forEach { listing ->
        pins.put(
            JSONObject()
                .put("id", listing.id)
                .put("title", listing.title.ifBlank { "Seller" })
                .put("town", listing.town)
                .put("price", listing.price)
                .put("lat", listing.latitude)
                .put("lng", listing.longitude)
        )
    }
    val config = JSONObject()
        .put("lat", latitude)
        .put("lng", longitude)
        .put("label", label)
        .put("pickMode", pickMode)
        .put("showPickup", showPickup)
        .put("zoom", zoom.toDouble())
        .put("sellers", pins)
    route?.let { points ->
        val line = JSONArray()
        points.forEach { p -> line.put(JSONArray().put(p.latitude).put(p.longitude)) }
        config.put("route", line)
    }
    return config.toString()
}

/** The Leaflet map itself (a WebView with a local HTML asset). */
@SuppressLint("JavascriptInterface", "SetJavaScriptEnabled")
@Composable
fun LeafletMapView(
    latitude: Double,
    longitude: Double,
    modifier: Modifier = Modifier,
    zoom: Double = 14.0
) {
    // osmdroid: native OpenStreetMap view with pan/zoom and a marker.
    val context = LocalContext.current
    var mapView by remember { mutableStateOf<org.osmdroid.views.MapView?>(null) }
    DisposableEffect(mapView) {
        mapView?.onResume()
        onDispose { mapView?.onPause() }
    }
    AndroidView(
        modifier = modifier.defaultMinSize(minHeight = 280.dp),
        factory = { ctx ->
            org.osmdroid.config.Configuration.getInstance().load(
                ctx, ctx.getSharedPreferences("osmdroid", Context.MODE_PRIVATE)
            )
            org.osmdroid.views.MapView(ctx).apply {
                mapView = this
                setTileSource(org.osmdroid.tileprovider.tilesource.TileSourceFactory.MAPNIK)
                setMultiTouchControls(true)
                controller.setZoom(zoom)
                controller.setCenter(org.osmdroid.util.GeoPoint(latitude, longitude))
                // Marker for the seller/pin.
                val marker = org.osmdroid.views.overlay.Marker(this).apply {
                    position = org.osmdroid.util.GeoPoint(latitude, longitude)
                    setAnchor(org.osmdroid.views.overlay.Marker.ANCHOR_CENTER, org.osmdroid.views.overlay.Marker.ANCHOR_BOTTOM)
                    title = "Location"
                }
                overlays.add(marker)
            }
        },
        update = { mv ->
            mv.controller.setCenter(org.osmdroid.util.GeoPoint(latitude, longitude))
        }
    )
}


// ---------------------------------------------------------------------------
// Full-screen map
// ---------------------------------------------------------------------------

/**
 * Full-screen Leaflet map.
 *
 * @param pickMode tapping the map moves the pin and reports it through [onPicked].
 * @param showSearch shows the place search bar (Nominatim).
 * @param showRoute shows the A→B route controls (OSRM).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LeafletMapScreen(
    latitude: Double,
    longitude: Double,
    label: String = "Seller Pickup Location",
    modifier: Modifier = Modifier,
    pickMode: Boolean = false,
    showSearch: Boolean = false,
    showRoute: Boolean = false,
    sellers: List<Listing> = emptyList(),
    routeA: LatLng? = null,
    routeB: LatLng? = null,
    onPicked: ((LatLng) -> Unit)? = null,
    onSellerClick: ((Listing) -> Unit)? = null,
    onBack: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var marker by remember { mutableStateOf(LatLng(latitude, longitude)) }
    var pointA by remember { mutableStateOf(routeA) }
    var pointB by remember { mutableStateOf(routeB) }
    var route by remember { mutableStateOf<MapRoute?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember {
        mutableStateOf(
            if (pickMode) "Tap or drag the pin to set the location."
            else formatCoordinates(LatLng(latitude, longitude))
        )
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            var best: Location? = null
            for (p in providers) {
                try {
                    val l = manager?.getLastKnownLocation(p)
                    if (l != null && (best == null || l.accuracy < best.accuracy)) best = l
                } catch (_: SecurityException) { }
            }
            best?.let { marker = LatLng(it.latitude, it.longitude); status = formatCoordinates(marker) }
        }
    }

    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MapSearchResult>>(emptyList()) }

    fun buildRoute() {
        val a = pointA
        val b = pointB
        if (a == null || b == null) {
            status = "Set Point A and Point B first."
            return
        }
        busy = true
        status = "Fetching the A→B route…"
        scope.launch {
            fetchRoute(a, b)
                .onSuccess {
                    route = it
                    status = "Route A→B: " + it.distanceText
                }
                .onFailure { status = friendlyMapError(it) }
            busy = false
        }
    }

    fun search() {
        val q = query.trim()
        if (q.isEmpty()) {
            results = emptyList()
            return
        }
        val local = sellersWithLocation(sellers).filter {
            it.title.contains(q, true) || it.town.contains(q, true) ||
                it.sellerName.contains(q, true) || it.category.contains(q, true)
        }
        if (local.isNotEmpty()) {
            results = local.take(6).map { listing ->
                MapSearchResult(
                    label = listing.title.ifBlank { "Seller" },
                    detail = "${listing.sellerName} • ${listing.town}",
                    point = LatLng(listing.latitude!!, listing.longitude!!),
                    seller = listing
                )
            }
            status = "Found ${local.size} seller pins."
            return
        }
        busy = true
        scope.launch {
            geocodePlace(q)
                .onSuccess {
                    results = listOf(it)
                    marker = it.point
                    status = it.label
                }
                .onFailure { status = friendlyMapError(it) }
            busy = false
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (pickMode) "Pick a location" else "Camiguin Map") },
                navigationIcon = {
                    onBack?.let { back ->
                        TextButton(onClick = back) { Text("← Back", color = BrandBlue) }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = BrandMuted,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )

            if (showSearch) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search a place or seller…") },
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = !busy,
                        onClick = { search() },
                        shape = RoundedCornerShape(14.dp)
                    ) { Text(if (busy) "…" else "Search") }
                }
                if (results.isNotEmpty()) {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Column {
                            results.forEach { result ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp)
                                ) {
                                    Text(result.label, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                                    Text(
                                        result.detail,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = BrandMuted
                                    )
                                }
                            }
                        }
                    }
                }
            }

            LeafletMapView(
                latitude = marker.latitude,
                longitude = marker.longitude,
                modifier = Modifier.weight(1f).fillMaxWidth()
            )

            Column(
                Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Pin: ${formatCoordinates(marker)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = BrandMuted
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (ContextCompat.checkSelfPermission(
                                    context, android.Manifest.permission.ACCESS_FINE_LOCATION
                                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            ) {
                                val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                                if (manager == null) {
                                    status = "Location service unavailable."
                                } else {
                                    // Prefer a fresh fix over a stale last-known location.
                                    var gotFix = false
                                    val listener = object : android.location.LocationListener {
                                        override fun onLocationChanged(loc: Location) {
                                            if (!gotFix) {
                                                gotFix = true
                                                marker = LatLng(loc.latitude, loc.longitude)
                                                status = formatCoordinates(marker)
                                                try { manager.removeUpdates(this) } catch (_: Exception) { }
                                            }
                                        }
                                        override fun onProviderEnabled(p: String) { }
                                        override fun onProviderDisabled(p: String) { }
                                        @Deprecated("Deprecated in Java")
                                        override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) { }
                                    }
                                    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                                        .filter { manager.isProviderEnabled(it) }
                                    if (providers.isEmpty()) {
                                        status = "No location provider enabled."
                                    } else {
                                        providers.forEach { p ->
                                            try {
                                                manager.requestSingleUpdate(p, listener, android.os.Looper.getMainLooper())
                                            } catch (_: SecurityException) { }
                                            catch (_: IllegalArgumentException) { }
                                        }
                                        // Fall back to last-known if no fix arrives shortly.
                                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                            if (!gotFix) {
                                                var best: Location? = null
                                                providers.forEach { p ->
                                                    try {
                                                        val l = manager.getLastKnownLocation(p)
                                                        if (l != null && (best == null || l.accuracy < best.accuracy)) best = l
                                                    } catch (_: SecurityException) { }
                                                }
                                                best?.let { marker = LatLng(it.latitude, it.longitude); status = formatCoordinates(marker) }
                                            }
                                        }, 4000)
                                    }
                                }
                            } else {
                                locationPermissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("My location") }
                    OutlinedButton(
                        onClick = { openMapPreview(context, marker, label) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("Open map") }
                }
                if (pickMode && onPicked != null) {
                    Button(
                        onClick = { onPicked(marker) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().height(50.dp)
                    ) { Text("Save this location") }
                }
            }
        }
    }
}

/** Full-screen map dialog used by the listing detail. */
@Composable
fun LeafletMapDialog(
    latitude: Double,
    longitude: Double,
    label: String,
    sellers: List<Listing> = emptyList(),
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize()) {
            LeafletMapScreen(
                latitude = latitude,
                longitude = longitude,
                label = label,
                showSearch = true,
                showRoute = true,
                sellers = sellers,
                onBack = onDismiss
            )
        }
    }
}

/** Full-screen picker opened from the post dialog; returns the picked coordinates. */
@Composable
fun PickupLocationPickerDialog(
    initial: LatLng?,
    onPicked: (LatLng) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize()) {
            LeafletMapScreen(
                latitude = initial?.latitude ?: CamiguinCenter.latitude,
                longitude = initial?.longitude ?: CamiguinCenter.longitude,
                label = "Pickup Location",
                pickMode = true,
                onPicked = { point ->
                    onPicked(point)
                    onDismiss()
                },
                onBack = onDismiss
            )
        }
    }
}

/** View Map + Get Directions for one listing. */
@Composable
fun SellerMapDialog(
    listing: Listing,
    onDismiss: () -> Unit,
    onSaveLocation: ((LatLng) -> Unit)? = null,
    onOpenWebMap: (() -> Unit)? = null
) {
    val lat = listing.latitude
    val lng = listing.longitude

    if (lat == null || lng == null) {
        PickupLocationPickerDialog(
            initial = null,
            onPicked = { point -> onSaveLocation?.invoke(point) },
            onDismiss = onDismiss
        )
        return
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                LeafletMapScreen(
                    latitude = lat,
                    longitude = lng,
                    label = listing.title.ifBlank { "Seller Pickup Location" },
                    showRoute = true,
                    routeB = LatLng(lat, lng),
                    onBack = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                onOpenWebMap?.let { open ->
                    TextButton(onClick = open, modifier = Modifier.fillMaxWidth()) {
                        Text("🌐 Open the full web map")
                    }
                }
            }
        }
    }
}

/** All seller pins on one map, with the search bar on top and the A→B route. */
@Composable
fun AllSellersMapDialog(
    listings: List<Listing>,
    onSellerClick: ((Listing) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize()) {
            LeafletMapScreen(
                latitude = CamiguinCenter.latitude,
                longitude = CamiguinCenter.longitude,
                label = "Camiguin Market",
                showSearch = true,
                showRoute = true,
                sellers = listings,
                onSellerClick = onSellerClick,
                onBack = onDismiss,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
