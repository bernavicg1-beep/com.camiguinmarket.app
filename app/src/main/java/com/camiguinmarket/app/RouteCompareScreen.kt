package com.camiguinmarket.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * A→B route view on the Leaflet map (OpenStreetMap data through OSRM).
 * Set Point A and Point B with the pin, then press "Show the A→B Route".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteCompareScreen(
    modifier: Modifier = Modifier,
    initialA: LatLng? = null,
    initialB: LatLng? = null,
    onBack: (() -> Unit)? = null
) {
    LeafletMapScreen(
        latitude = (initialB ?: initialA ?: CamiguinCenter).latitude,
        longitude = (initialB ?: initialA ?: CamiguinCenter).longitude,
        label = "Route A→B",
        showRoute = true,
        routeA = initialA,
        routeB = initialB,
        onBack = onBack,
        modifier = modifier
    )
}

/** Full-screen route view. */
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
