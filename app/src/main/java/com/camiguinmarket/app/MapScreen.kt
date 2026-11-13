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
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
import com.google.firebase.firestore.FirebaseFirestore
import com.google.maps.android.compose.*
import kotlinx.coroutines.launch
import java.util.Locale

/** Sentro sa Camiguin Island (Mambajao area). */
val CamiguinCenter = LatLng(9.1732, 124.7290)

const val ISLAND_ZOOM = 11f
const val FOCUS_ZOOM = 16f

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
    modifier: Modifier = Modifier,
    onPickLocation: ((LatLng) -> Unit)? = null,
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

            GoogleMap(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                cameraPositionState = cameraPositionState,
                properties = MapProperties(isMyLocationEnabled = hasPermission),
                uiSettings = MapUiSettings(
                    zoomControlsEnabled = true,
                    myLocationButtonEnabled = false,
                    compassEnabled = true
                ),
                onMapClick = { point ->
                    if (pickMode || isSelecting) {
                        pickupLocation = point
                        status = "Napili ang pickup location. Pindota ang 💾 Save Pickup Location."
                        isSelecting = false
                    }
                }
            ) {
                Marker(
                    state = rememberMarkerState(position = pickupLocation),
                    title = sellerLabel,
                    snippet = sellerSnippet
                )
                customerLocation?.let { point ->
                    Marker(
                        state = rememberMarkerState(position = point),
                        title = "My Current Location",
                        snippet = "Ikaw karon"
                    )
                }
            }

            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Pickup coordinates: ${formatCoordinates(pickupLocation)}",
                    style = MaterialTheme.typography.bodyMedium
                )

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
                } else {
                    OutlinedButton(
                        onClick = {
                            isSelecting = true
                            status = "Pindota ang mapa aron makita ang bag-ong punto."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("📌 Select Pickup Location") }
                }

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
