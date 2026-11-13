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
    onPicked: ((Double, Double) -> Unit)? = null,
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
            }
        )
    }

    val config = remember(latitude, longitude, label, pickMode) {
        JSONObject()
            .put("token", token)
            .put("lat", latitude)
            .put("lng", longitude)
            .put("label", label)
            .put("pickMode", pickMode)
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
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = WebViewClient()
                            addJavascriptInterface(bridge, "AndroidMap")
                            loadUrl("file:///android_asset/mapbox_map.html")
                        }
                    },
                    update = { web ->
                        // Kung nahuman na og load ang HTML, ipasa ang config.
                        web.evaluateJavascript("window.initMap($config);", null)
                    }
                )
            }
        }
    }
}

/** Tawag gikan sa JavaScript sa mapbox_map.html. */
class MapboxBridge(
    private val readyHandler: () -> Unit,
    private val pickHandler: (Double, Double) -> Unit,
    private val directionsHandler: (Double, Double) -> Unit
) {
    @JavascriptInterface
    fun onReady() = readyHandler()

    @JavascriptInterface
    fun onPick(lat: Double, lng: Double) = pickHandler(lat, lng)

    @JavascriptInterface
    fun onDirections(lat: Double, lng: Double) = directionsHandler(lat, lng)
}

@Composable
fun MapboxMapDialog(
    latitude: Double,
    longitude: Double,
    label: String,
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
                onBack = onDismiss
            )
        }
    }
}
