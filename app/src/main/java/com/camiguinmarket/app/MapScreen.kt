package com.camiguinmarket.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.firebase.firestore.FirebaseFirestore
import com.google.maps.android.compose.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** Sentro sa Camiguin Island (Mambajao area). */
val CamiguinCenter = LatLng(9.1732, 124.7290)

const val ISLAND_ZOOM = 11f
const val FOCUS_ZOOM = 16f
const val ROUTE_ZOOM = 12f

/** Google Maps API key gikan sa local.properties / CI secrets (BuildConfig). */
val mapsApiKey: String
    get() = BuildConfig.MAPS_API_KEY

/** Ruta gikan sa Google Directions API. */
data class MapRoute(
    val points: List<LatLng>,
    val distanceText: String,
    val durationText: String,
    val distanceMeters: Int,
    val durationSeconds: Int
)

/** "12.40 km • 21 min" gikan sa meters/seconds (pareho ang format sa Mapbox). */
fun formatRouteStats(distanceMeters: Double, durationSeconds: Double): String =
    String.format(
        Locale.US,
        "%.2f km • %d min",
        distanceMeters / 1000.0,
        maxOf(1, Math.round(durationSeconds / 60.0))
    )

/** Kinsa ang mosunod nga tap sa mapa. */
private enum class TapTarget { NONE, ROUTE_A, ROUTE_B }

/** Usa ka resulta sa search bar (seller pin o lugar). */
data class MapSearchResult(
    val label: String,
    val detail: String,
    val point: LatLng,
    val seller: Listing? = null
)

/** Decode sa "encoded polyline" sa Directions API. */
fun decodePolyline(encoded: String): List<LatLng> {
    val points = mutableListOf<LatLng>()
    var index = 0
    var lat = 0
    var lng = 0
    while (index < encoded.length) {
        var shift = 0
        var result = 0
        var b: Int
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        lat += if (result and 1 != 0) (result shr 1).inv() else result shr 1

        shift = 0
        result = 0
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        lng += if (result and 1 != 0) (result shr 1).inv() else result shr 1
        points.add(LatLng(lat / 1e5, lng / 1e5))
    }
    return points
}

private fun httpGetJson(url: String): JSONObject {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 10_000
        readTimeout = 10_000
    }
    try {
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        return JSONObject(text)
    } finally {
        connection.disconnect()
    }
}

/** Ruta A→B (driving, walking, o bicycling) pinaagi sa Google Directions API. */
suspend fun fetchRoute(
    origin: LatLng,
    destination: LatLng,
    key: String,
    travelMode: String = "driving"
): Result<MapRoute> = withContext(Dispatchers.IO) {
    if (key.isBlank()) {
        return@withContext Result.failure(IllegalStateException("Walay Google Maps API key."))
    }
    try {
        val url = "https://maps.googleapis.com/maps/api/directions/json" +
            "?origin=${origin.latitude},${origin.longitude}" +
            "&destination=${destination.latitude},${destination.longitude}" +
            "&mode=$travelMode&language=en&key=$key"
        val json = httpGetJson(url)
        val status = json.optString("status")
        if (status != "OK") {
            val message = json.optString("error_message").ifBlank { "Directions status: $status" }
            return@withContext Result.failure(IllegalStateException(message))
        }
        val route = json.getJSONArray("routes").getJSONObject(0)
        val leg = route.getJSONArray("legs").getJSONObject(0)
        val points = decodePolyline(
            route.getJSONObject("overview_polyline").optString("points")
        )
        if (points.isEmpty()) {
            return@withContext Result.failure(IllegalStateException("Walay ruta nga nakit-an."))
        }
        val distance = leg.getJSONObject("distance")
        val duration = leg.getJSONObject("duration")
        Result.success(
            MapRoute(
                points = points,
                distanceText = distance.optString("text"),
                durationText = duration.optString("text"),
                distanceMeters = distance.optInt("value"),
                durationSeconds = duration.optInt("value")
            )
        )
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/** Pangita og lugar sa Camiguin pinaagi sa Google Geocoding API. */
suspend fun geocodePlace(query: String, key: String): Result<MapSearchResult> =
    withContext(Dispatchers.IO) {
        if (key.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Walay Google Maps API key."))
        }
        try {
            val q = URLEncoder.encode("$query, Camiguin, Philippines", "UTF-8")
            val url = "https://maps.googleapis.com/maps/api/geocode/json" +
                "?address=$q&region=ph&key=$key"
            val json = httpGetJson(url)
            val status = json.optString("status")
            if (status != "OK") {
                val message = json.optString("error_message").ifBlank { "Geocoding status: $status" }
                return@withContext Result.failure(IllegalStateException(message))
            }
            val first = json.getJSONArray("results").getJSONObject(0)
            val location = first.getJSONObject("geometry").getJSONObject("location")
            Result.success(
                MapSearchResult(
                    label = first.optString("formatted_address"),
                    detail = "Lugar sa Camiguin",
                    point = LatLng(location.getDouble("lat"), location.getDouble("lng"))
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

/** Mga seller nga may coordinates (pang-search ug pins). */
fun sellersWithLocation(listings: List<Listing>): List<Listing> =
    listings.filter { it.latitude != null && it.longitude != null }

fun formatCoordinates(point: LatLng): String =
    String.format(Locale.US, "%.5f, %.5f", point.latitude, point.longitude)

/** Tunog sa pin: "9.17320, 124.72900" nga andam i-save sa Firestore. */
fun coordinatesLabel(listing: Listing): String? =
    if (listing.latitude != null && listing.longitude != null)
        String.format(Locale.US, "%.5f, %.5f", listing.latitude, listing.longitude)
    else null

fun openDirections(context: Context, point: LatLng, label: String? = null) {
    val destination = if (label.isNullOrBlank()) {
        "${point.latitude},${point.longitude}"
    } else {
        "${point.latitude},${point.longitude}(${Uri.encode(label)})"
    }
    openUri(
        context,
        Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$destination&travelmode=driving"),
        "Walay app nga makaabli sa Google Maps."
    )
}

fun openMapPreview(context: Context, point: LatLng, label: String?) {
    val name = Uri.encode(label ?: "Pickup location")
    openUri(
        context,
        Uri.parse("geo:${point.latitude},${point.longitude}?q=${point.latitude},${point.longitude}($name)"),
        "Walay app nga makaabli sa mapa."
    )
}

private fun openUri(context: Context, uri: Uri, errorMessage: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
    }
}

fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

/**
 * Option A: Google Map sulod mismo sa app.
 *
 * @param sellerLocation ang pin sa seller / pickup point.
 * @param pickMode kon true, ang pag-tap sa mapa nagbalhin sa pickup pin
 *   ug makita ang "Save Pickup Location" button.
 * @param onPickLocation gitawag kung gi-save ang bag-ong pickup coordinates.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CamiguinMarketMapScreen(
    sellerLocation: LatLng,
    sellerLabel: String = "Seller Pickup Location",
    sellerSnippet: String = "Camiguin Market seller",
    initialCustomerLocation: LatLng? = null,
    pickMode: Boolean = false,
    showSearch: Boolean = false,
    showRoute: Boolean = false,
    showPickupMarker: Boolean = true,
    initialRouteB: LatLng? = null,
    sellers: List<Listing> = emptyList(),
    apiKey: String = mapsApiKey,
    modifier: Modifier = Modifier,
    onPickLocation: ((LatLng) -> Unit)? = null,
    onSellerClick: ((Listing) -> Unit)? = null,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var pickupLocation by remember(sellerLocation) { mutableStateOf(sellerLocation) }
    var customerLocation by remember { mutableStateOf(initialCustomerLocation) }
    var isSelecting by remember { mutableStateOf(false) }
    var hasPermission by remember { mutableStateOf(hasLocationPermission(context)) }
    var focusTarget by remember { mutableStateOf<LatLng?>(null) }
    var status by remember {
        mutableStateOf(
            if (pickMode) "Pindota ang mapa aron ibutang ang pickup pin."
            else "Pindota ang 📍 Use My Current Location aron makita asa ka karon."
        )
    }

    // Ruta A→B.
    var tapTarget by remember { mutableStateOf(TapTarget.NONE) }
    var pointA by remember { mutableStateOf<LatLng?>(null) }
    var pointB by remember(sellerLocation, showRoute) { mutableStateOf(initialRouteB) }
    var route by remember { mutableStateOf<MapRoute?>(null) }
    var routeLoading by remember { mutableStateOf(false) }

    // Search bar sa ibabaw sa mapa.
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<MapSearchResult>>(emptyList()) }
    var searchNote by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var searchedPlace by remember { mutableStateOf<LatLng?>(null) }

    val fusedLocationClient = remember {
        LocationServices.getFusedLocationProviderClient(context)
    }

    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            sellerLocation,
            if (pickMode) FOCUS_ZOOM else ISLAND_ZOOM
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasPermission = result.values.any { it } || hasLocationPermission(context)
        status = if (hasPermission) {
            "Location permission granted. Pindota pag-usab ang 📍 Use My Current Location."
        } else {
            "Location permission denied. Pwede gihapon ka mopili og pin sa mapa."
        }
    }

    // Iliso ang camera sa pin nga bag-o natong napilian / na-locate.
    LaunchedEffect(focusTarget) {
        focusTarget?.let { point ->
            cameraPositionState.animate(
                CameraUpdateFactory.newLatLngZoom(point, FOCUS_ZOOM)
            )
        }
    }

    fun onPointFound(point: LatLng) {
        customerLocation = point
        focusTarget = point
        if (showRoute && pointA == null) pointA = point
        status = "Nakita na ang imong current location."
    }

    fun getCurrentLocation() {
        if (!hasPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }
        try {
            fusedLocationClient.lastLocation
                .addOnSuccessListener { location ->
                    if (location != null) {
                        onPointFound(LatLng(location.latitude, location.longitude))
                    } else {
                        // Walay cached fix: pangayoa ang bag-ong GPS reading.
                        fusedLocationClient.getCurrentLocation(
                            Priority.PRIORITY_HIGH_ACCURACY,
                            null
                        ).addOnSuccessListener { fresh ->
                            if (fresh != null) {
                                onPointFound(LatLng(fresh.latitude, fresh.longitude))
                            } else {
                                status = "Walay GPS signal. Sulayi pag-usab sa gawas."
                            }
                        }.addOnFailureListener {
                            status = "Walay GPS signal. Sulayi pag-usab sa gawas."
                        }
                    }
                }
                .addOnFailureListener { status = "Napakyas ang pagkuha sa GPS location." }
        } catch (e: SecurityException) {
            status = "Kinahanglan ang location permission."
        }
    }

    fun runSearch() {
        val q = searchQuery.trim()
        searchNote = null
        if (q.isEmpty()) {
            searchResults = emptyList()
            return
        }
        val local = sellersWithLocation(sellers).filter {
            it.title.contains(q, true) || it.description.contains(q, true) ||
                it.town.contains(q, true) || it.category.contains(q, true) ||
                it.sellerName.contains(q, true)
        }
        if (local.isNotEmpty()) {
            searchResults = local.take(6).map { listing ->
                MapSearchResult(
                    label = listing.title.ifBlank { "Seller" },
                    detail = listOf(listing.sellerName, listing.town)
                        .filter { it.isNotBlank() }.joinToString(" • "),
                    point = LatLng(listing.latitude!!, listing.longitude!!),
                    seller = listing
                )
            }
            status = "Nakita ang ${local.size} ka seller pin."
            return
        }
        if (apiKey.isBlank()) {
            searchResults = emptyList()
            searchNote = "Walay seller nga nakit-an. Idugang ang MAPS_API_KEY aron makapangita og lugar."
            return
        }
        searching = true
        scope.launch {
            val result = geocodePlace(q, apiKey)
            searching = false
            result.onSuccess { place ->
                searchResults = listOf(place)
                searchedPlace = place.point
                focusTarget = place.point
                status = "Nakita ang lugar: ${place.label}"
            }.onFailure { e ->
                searchResults = emptyList()
                searchNote = e.message ?: "Wala makita ang lugar."
            }
        }
    }

    fun selectResult(result: MapSearchResult) {
        searchResults = emptyList()
        searchedPlace = result.point
        focusTarget = result.point
        if (showRoute) pointB = result.point
        status = "${result.label} — ${formatCoordinates(result.point)}"
        result.seller?.let { seller -> onSellerClick?.invoke(seller) }
    }

    fun buildRoute() {
        val start = pointA
        val end = pointB
        if (start == null || end == null) {
            status = "Kinahanglan ang Point A (start) ug Point B (destination)."
            return
        }
        if (apiKey.isBlank()) {
            status = "Walay MAPS_API_KEY. Idugang sa local.properties o GitHub secrets."
            return
        }
        routeLoading = true
        status = "Ginakuha ang ruta A→B…"
        scope.launch {
            val result = fetchRoute(start, end, apiKey)
            routeLoading = false
            result.onSuccess { r ->
                route = r
                status = "Ruta A→B (Google): " +
                    formatRouteStats(r.distanceMeters.toDouble(), r.durationSeconds.toDouble())
                val builder = LatLngBounds.Builder()
                r.points.forEach { builder.include(it) }
                cameraPositionState.animate(
                    CameraUpdateFactory.newLatLngBounds(builder.build(), 120)
                )
            }.onFailure { e ->
                status = "Napakyas ang ruta: ${e.message}"
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(if (pickMode) "Pili og Pickup Location" else "Camiguin Market Map") },
                navigationIcon = {
                    onBack?.let {
                        TextButton(onClick = it) { Text("⬅ Balik") }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Text(
                text = status,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                GoogleMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraPositionState = cameraPositionState,
                    properties = MapProperties(isMyLocationEnabled = hasPermission),
                    uiSettings = MapUiSettings(
                        zoomControlsEnabled = true,
                        myLocationButtonEnabled = false,
                        compassEnabled = true
                    ),
                    onMapClick = { point ->
                        when {
                            tapTarget == TapTarget.ROUTE_A -> {
                                pointA = point
                                tapTarget = TapTarget.NONE
                                status = "Point A na-set: ${formatCoordinates(point)}"
                            }
                            tapTarget == TapTarget.ROUTE_B -> {
                                pointB = point
                                tapTarget = TapTarget.NONE
                                status = "Point B na-set: ${formatCoordinates(point)}"
                            }
                            pickMode || isSelecting -> {
                                pickupLocation = point
                                status = "Napili ang pickup location. Pindota ang 💾 Save Pickup Location."
                                isSelecting = false
                            }
                        }
                    }
                ) {
                    if (showPickupMarker) {
                        Marker(
                            state = rememberMarkerState(position = pickupLocation),
                            title = sellerLabel,
                            snippet = sellerSnippet
                        )
                    }
                    customerLocation?.let { point ->
                        Marker(
                            state = rememberMarkerState(position = point),
                            title = "My Current Location",
                            snippet = "Ikaw karon"
                        )
                    }
                    // Tanan nga seller pins (listahan sa mga listing nga may coordinates).
                    sellersWithLocation(sellers).forEach { listing ->
                        val lat = listing.latitude
                        val lng = listing.longitude
                        if (lat != null && lng != null) {
                            key(listing.id) {
                                Marker(
                                    state = rememberMarkerState(position = LatLng(lat, lng)),
                                    title = listing.title.ifBlank { "Seller" },
                                    snippet = "${listing.sellerName} • ₱%.2f".format(listing.price),
                                    onClick = { _ ->
                                        onSellerClick?.invoke(listing)
                                        true
                                    }
                                )
                            }
                        }
                    }
                    searchedPlace?.let { point ->
                        key(point) {
                            Marker(
                                state = rememberMarkerState(position = point),
                                title = "Gipangita nga lugar",
                                snippet = formatCoordinates(point)
                            )
                        }
                    }
                    // Ruta A→B: ang linya sa dalan.
                    route?.let { r ->
                        Polyline(
                            points = r.points,
                            color = Color(0xFF1B7F5C),
                            width = 12f
                        )
                    }
                    pointA?.let { start ->
                        Marker(
                            state = rememberMarkerState(position = start),
                            title = "Point A (start)",
                            snippet = formatCoordinates(start)
                        )
                    }
                    pointB?.let { end ->
                        Marker(
                            state = rememberMarkerState(position = end),
                            title = "Point B (destination)",
                            snippet = formatCoordinates(end)
                        )
                    }
                }

                if (showSearch) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(8.dp),
                        shape = RoundedCornerShape(12.dp),
                        tonalElevation = 4.dp,
                        shadowElevation = 4.dp
                    ) {
                        Column(Modifier.padding(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = searchQuery,
                                    onValueChange = { searchQuery = it },
                                    singleLine = true,
                                    placeholder = { Text("Pangita og lugar o seller…") },
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                Button(
                                    enabled = !searching,
                                    onClick = { runSearch() }
                                ) { Text(if (searching) "…" else "🔍") }
                            }
                            searchNote?.let { note ->
                                Text(
                                    note,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            if (searchResults.isNotEmpty()) {
                                Column(Modifier.padding(top = 4.dp)) {
                                    searchResults.forEach { result ->
                                        Column(
                                            Modifier
                                                .fillMaxWidth()
                                                .clickable { selectResult(result) }
                                                .padding(horizontal = 8.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                if (result.seller == null) "🔎 ${result.label}"
                                                else "📍 ${result.label}",
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Text(result.detail, style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .heightIn(max = 300.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (showPickupMarker) {
                    Text(
                        text = "Pickup coordinates: ${formatCoordinates(pickupLocation)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Button(
                    onClick = { getCurrentLocation() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("📍 Use My Current Location") }

                if (pickMode) {
                    OutlinedButton(
                        onClick = {
                            isSelecting = true
                            status = "Pindota ang mapa aron ibutang ang pickup pin."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("📌 Select Pickup Location") }

                    Button(
                        onClick = { onPickLocation?.invoke(pickupLocation) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("💾 Save Pickup Location") }
                }

                if (showPickupMarker && !pickMode) {
                    OutlinedButton(
                        onClick = {
                            isSelecting = true
                            status = "Pindota ang mapa aron makita ang bag-ong punto."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("📌 Select Pickup Location") }
                }

                if (showRoute) {
                    Text("Ruta A→B", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "A: ${pointA?.let { formatCoordinates(it) } ?: "wala pa ma-set"}\n" +
                            "B: ${pointB?.let { formatCoordinates(it) } ?: "wala pa ma-set"}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                tapTarget = TapTarget.ROUTE_A
                                status = "Pindota ang mapa aron ibutang ang Point A (start)."
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("🅰️ Set A") }
                        OutlinedButton(
                            onClick = {
                                tapTarget = TapTarget.ROUTE_B
                                status = "Pindota ang mapa aron ibutang ang Point B (destination)."
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("🅱️ Set B") }
                    }
                    Button(
                        enabled = pointA != null && pointB != null && !routeLoading,
                        onClick = { buildRoute() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (routeLoading) "Ginakuha ang ruta…" else "🧭 Ipakita ang Ruta A→B")
                    }
                    route?.let { r ->
                        Text(
                            "📏 " + formatRouteStats(
                                r.distanceMeters.toDouble(),
                                r.durationSeconds.toDouble()
                            ),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        TextButton(
                            onClick = { route = null; status = "Giklar ang ruta." },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("🗑️ Klarohon ang ruta") }
                    }
                }

                if (showPickupMarker) {
                    Button(
                        onClick = {
                            openDirections(context, pickupLocation, sellerLabel)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("🧭 Get Directions to Seller") }

                    OutlinedButton(
                        onClick = { openMapPreview(context, pickupLocation, sellerLabel) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("🗺️ View Map sa Google Maps App") }
                }
            }
        }
    }
}

/** Full-screen picker gikan sa Post dialog, mobalik ang napili nga coordinates. */
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
            CamiguinMarketMapScreen(
                sellerLocation = initial ?: CamiguinCenter,
                sellerLabel = "Pickup Location",
                sellerSnippet = "Dinhi mokuha ang customer",
                pickMode = true,
                onPickLocation = { point ->
                    onPicked(point)
                    onDismiss()
                },
                onBack = onDismiss
            )
        }
    }
}

/** View Map + Get Directions sa usa ka listing (Option A). */
@Composable
fun SellerMapDialog(
    listing: Listing,
    onDismiss: () -> Unit,
    onSaveLocation: ((LatLng) -> Unit)? = null,
    onOpenMapbox: (() -> Unit)? = null
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
                CamiguinMarketMapScreen(
                    sellerLocation = LatLng(lat, lng),
                    sellerLabel = listing.title.ifBlank { "Seller Pickup Location" },
                    sellerSnippet = "${listing.sellerName} • ${listing.town}",
                    showRoute = true,
                    initialRouteB = LatLng(lat, lng),
                    onBack = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                onOpenMapbox?.let { open ->
                    TextButton(
                        onClick = open,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("🌐 Tan-awa sa Mapbox (web map)") }
                }
            }
        }
    }
}

/**
 * Tanan nga seller pins sa usa ka mapa, uban ang search bar sa ibabaw
 * ug ruta A→B. Tap sa pin aron makita ang detalye sa listing.
 */
@Composable
fun AllSellersMapDialog(
    listings: List<Listing>,
    apiKey: String = mapsApiKey,
    onSellerClick: ((Listing) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize()) {
            CamiguinMarketMapScreen(
                sellerLocation = CamiguinCenter,
                sellerLabel = "Camiguin Market",
                sellerSnippet = "Pili og seller pin",
                showSearch = true,
                showRoute = true,
                showPickupMarker = false,
                sellers = listings,
                apiKey = apiKey,
                onSellerClick = onSellerClick,
                onBack = onDismiss,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/** I-save ang coordinates sa listing document sa Firestore. */
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
