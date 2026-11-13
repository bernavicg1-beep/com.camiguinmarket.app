package com.camiguinmarket.app

import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.google.android.gms.maps.model.LatLng
import androidx.compose.ui.window.DialogProperties
import org.json.JSONArray
import org.json.JSONObject

/**
 * Option B: Mapbox GL JS nga naa sa WebView (hybrid).
 *
 * Ang token gikuha gikan sa BuildConfig.MAPBOX_ACCESS_TOKEN (local.properties),
 * dili ibutang sa HTML aron dili ma-commit sa public repo.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapboxMapScreen(
    latitude: Double,
    longitude: Double,
    label: String = "Seller Pickup Location",
    pickMode: Boolean = false,
    showSearch: Boolean = false,
    showRoute: Boolean = false,
    sellers: List<Listing> = emptyList(),
    routeA: LatLng? = null,
    routeB: LatLng? = null,
    autoRoute: Boolean = false,
    onPicked: ((Double, Double) -> Unit)? = null,
    onRouteResult: ((Double, Double) -> Unit)? = null,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val token = BuildConfig.MAPBOX_ACCESS_TOKEN
    var status by remember { mutableStateOf("Nagkarga sa Mapbox…") }

    val bridge = remember {
        MapboxBridge(
            readyHandler = { status = "Andam na ang Mapbox map." },
            pickHandler = { lat, lng ->
                status = "Napili: $lat, $lng"
                onPicked?.invoke(lat, lng)
            },
            directionsHandler = { lat, lng ->
                openDirections(context, LatLng(lat, lng), label)
            },
            routeHandler = { distanceMeters, durationSeconds ->
                status = "Mapbox ruta: " +
                    formatRouteStats(distanceMeters, durationSeconds)
                onRouteResult?.invoke(distanceMeters, durationSeconds)
            }
        )
    }

    val config = remember(
        latitude, longitude, label, pickMode, showSearch, showRoute, sellers,
        routeA, routeB, autoRoute
    ) {
        val sellerArray = JSONArray()
        sellersWithLocation(sellers).forEach { listing ->
            sellerArray.put(
                JSONObject()
                    .put("id", listing.id)
                    .put("title", listing.title.ifBlank { "Seller" })
                    .put("seller", listing.sellerName)
                    .put("town", listing.town)
                    .put("price", listing.price)
                    .put("lat", listing.latitude)
                    .put("lng", listing.longitude)
            )
        }
        JSONObject()
            .put("token", token)
            .put("lat", latitude)
            .put("lng", longitude)
            .put("label", label)
            .put("pickMode", pickMode)
            .put("showSearch", showSearch)
            .put("showRoute", showRoute)
            .put("sellers", sellerArray)
            .put("routeB", JSONObject().put("lat", latitude).put("lng", longitude))
            .put(
                "routeA",
                routeA?.let { JSONObject().put("lat", it.latitude).put("lng", it.longitude) }
                    ?: JSONObject.NULL
            )
            .put("autoRoute", autoRoute)
            .toString()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (pickMode) "Pili og Pin (Mapbox)" else "Mapbox Map") },
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
            Text(
                text = status,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )

            if (token.isBlank()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Walay Mapbox access token.\n" +
                            "Idugang ang MAPBOX_ACCESS_TOKEN sa local.properties " +
                            "ug i-rebuild ang app.",
                        modifier = Modifier.padding(24.dp)
                    )
                }
            } else {
                var webView by remember { mutableStateOf<WebView?>(null) }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = WebViewClient()
                            addJavascriptInterface(bridge, "AndroidMap")
                            loadUrl("file:///android_asset/mapbox_map.html")
                        }.also { webView = it }
                    },
                    update = { web ->
                        // Kung nahuman na og load ang HTML, ipasa ang config.
                        web.evaluateJavascript("window.initMap($config);", null)
                    }
                )

                // Ipasa ang A ug B ngadto sa Mapbox map (compare mode).
                val routeRequest = if (routeA != null && routeB != null) routeA to routeB else null
                LaunchedEffect(webView, routeRequest, config) {
                    if (routeRequest != null) {
                        webView?.evaluateJavascript(
                            "window.initMap($config); if (window.setRoute) { window.setRoute(" +
                                "${routeRequest.first.latitude},${routeRequest.first.longitude}," +
                                "${routeRequest.second.latitude},${routeRequest.second.longitude}); }",
                            null
                        )
                    }
                }
            }
        }
    }
}

/**
 * Lean nga Mapbox view (walay top bar) alang sa compare screen:
 * nagpakita sa ruta A→B ug nagbalik sa distance/duration ngadto sa Android.
 */
@Composable
fun MapboxRouteView(
    latitude: Double,
    longitude: Double,
    routeA: LatLng?,
    routeB: LatLng?,
    sellers: List<Listing> = emptyList(),
    nonce: Int = 0,
    modifier: Modifier = Modifier,
    onRouteResult: ((Double, Double) -> Unit)? = null
) {
    val context = LocalContext.current
    val token = BuildConfig.MAPBOX_ACCESS_TOKEN
    var status by remember { mutableStateOf("Nagkarga sa Mapbox…") }

    val bridge = remember {
        MapboxBridge(
            readyHandler = { status = "Andam na ang Mapbox map." },
            pickHandler = { _, _ -> },
            directionsHandler = { lat, lng ->
                openDirections(context, LatLng(lat, lng), "Seller Pickup Location")
            },
            routeHandler = { distanceMeters, durationSeconds ->
                status = "Mapbox ruta: " + formatRouteStats(distanceMeters, durationSeconds)
                onRouteResult?.invoke(distanceMeters, durationSeconds)
            }
        )
    }

    val config = remember(latitude, longitude, routeA, routeB, sellers) {
        JSONObject()
            .put("token", token)
            .put("lat", latitude)
            .put("lng", longitude)
            .put("label", "Seller Pickup Location")
            .put("pickMode", false)
            .put("showSearch", false)
            .put("showRoute", true)
            .put("sellers", JSONArray())
            .put(
                "routeA",
                routeA?.let { JSONObject().put("lat", it.latitude).put("lng", it.longitude) }
                    ?: JSONObject.NULL
            )
            .put(
                "routeB",
                routeB?.let { JSONObject().put("lat", it.latitude).put("lng", it.longitude) }
                    ?: JSONObject().put("lat", latitude).put("lng", longitude)
            )
            .put("autoRoute", routeA != null && routeB != null)
            .toString()
    }

    if (token.isBlank()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text(
                "Walay Mapbox access token.\nIdugang ang MAPBOX_ACCESS_TOKEN sa local.properties.",
                modifier = Modifier.padding(24.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }
        return
    }

    var webView by remember { mutableStateOf<WebView?>(null) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                webViewClient = WebViewClient()
                addJavascriptInterface(bridge, "AndroidMap")
                loadUrl("file:///android_asset/mapbox_map.html")
            }.also { webView = it }
        },
        update = { web ->
            web.evaluateJavascript("window.initMap($config);", null)
        }
    )

    val routeRequest = if (routeA != null && routeB != null) routeA to routeB else null
    LaunchedEffect(webView, routeRequest, nonce, config) {
        if (routeRequest != null) {
            webView?.evaluateJavascript(
                "window.initMap($config); if (window.setRoute) { window.setRoute(" +
                    "${routeRequest.first.latitude},${routeRequest.first.longitude}," +
                    "${routeRequest.second.latitude},${routeRequest.second.longitude}); }",
                null
            )
        }
    }
}

/** Tawag gikan sa JavaScript sa mapbox_map.html. */
class MapboxBridge(
    private val readyHandler: () -> Unit,
    private val pickHandler: (Double, Double) -> Unit,
    private val directionsHandler: (Double, Double) -> Unit,
    private val routeHandler: (Double, Double) -> Unit
) {
    @JavascriptInterface
    fun onReady() = readyHandler()

    @JavascriptInterface
    fun onPick(lat: Double, lng: Double) = pickHandler(lat, lng)

    @JavascriptInterface
    fun onDirections(lat: Double, lng: Double) = directionsHandler(lat, lng)

    /** Gisugo niini ang JavaScript: onRoute(distanceMeters, durationSeconds). */
    @JavascriptInterface
    fun onRoute(distanceMeters: Double, durationSeconds: Double) =
        routeHandler(distanceMeters, durationSeconds)
}

@Composable
fun MapboxMapDialog(
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
            MapboxMapScreen(
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
