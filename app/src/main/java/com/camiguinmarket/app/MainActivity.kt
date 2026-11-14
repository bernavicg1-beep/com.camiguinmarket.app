package com.camiguinmarket.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.google.android.gms.maps.model.LatLng
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

val categories = listOf("Tanan", "Pagkaon ug Seafood", "Lokal nga Produkto", "Sinina", "Gadgets", "Uban pa")
val towns = listOf("Mambajao", "Mahinog", "Guinsiliban", "Sagay", "Catarman")

data class Listing(
    val id: String = "",
    val title: String = "",
    val description: String = "",
    val price: Double = 0.0,
    val category: String = "",
    val town: String = "",
    val sellerId: String = "",
    val sellerName: String = "",
    val contact: String = "",
    val createdAt: Long = 0L,
    // Pickup location sa seller (pin sa Google Map).
    val latitude: Double? = null,
    val longitude: Double? = null
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Target SDK 36: enforced ang edge-to-edge, busa i-enable nga tarong
        // ang window aron dili ma-tabunan sa system bars ang sulod.
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = androidx.compose.ui.graphics.Color(0xFF1B7F5C),
                    secondary = androidx.compose.ui.graphics.Color(0xFFF2A900)
                )
            ) {
                Surface(Modifier.fillMaxSize()) { AppRoot() }
            }
        }
    }
}

@Composable
fun AppRoot() {
    val auth = remember { FirebaseAuth.getInstance() }
    var user by remember { mutableStateOf(auth.currentUser) }
    if (user == null) {
        LoginScreen(auth) { user = auth.currentUser }
    } else {
        MarketScreen(user!!.uid, user!!.displayName ?: user!!.email ?: "Seller") {
            auth.signOut(); user = null
        }
    }
}

@Composable
fun LoginScreen(auth: FirebaseAuth, onDone: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var isRegister by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize()
            .padding(WindowInsets.systemBars.asPaddingValues())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Camiguin Market 🛒", style = MaterialTheme.typography.headlineMedium)
        Text("Palit ug baligya sa tibuok Camiguin", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        if (isRegister) {
            OutlinedTextField(name, { name = it }, label = { Text("Ngalan") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            email, { email = it }, label = { Text("Email") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            password, { password = it }, label = { Text("Password (6+ ka letra)") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                loading = true; error = null
                val task = if (isRegister)
                    auth.createUserWithEmailAndPassword(email.trim(), password)
                else auth.signInWithEmailAndPassword(email.trim(), password)
                task.addOnCompleteListener { t ->
                    loading = false
                    if (t.isSuccessful) {
                        if (isRegister && name.isNotBlank()) {
                            auth.currentUser?.updateProfile(
                                com.google.firebase.auth.UserProfileChangeRequest.Builder()
                                    .setDisplayName(name.trim()).build()
                            )?.addOnCompleteListener { onDone() } ?: onDone()
                        } else onDone()
                    } else error = t.exception?.localizedMessage ?: "Napakyas"
                }
            }
        ) { Text(if (isRegister) "Mag-register" else "Log in") }
        TextButton(onClick = { isRegister = !isRegister }) {
            Text(if (isRegister) "Naa nay account? Log in" else "Wala pay account? Mag-register")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketScreen(uid: String, displayName: String, onLogout: () -> Unit) {
    val db = remember { FirebaseFirestore.getInstance() }
    var listings by remember { mutableStateOf<List<Listing>>(emptyList()) }
    var category by remember { mutableStateOf(categories[0]) }
    var town by remember { mutableStateOf("Tanan") }
    var query by remember { mutableStateOf("") }
    var showPost by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Listing?>(null) }
    var mapListing by remember { mutableStateOf<Listing?>(null) }
    var mapboxListing by remember { mutableStateOf<Listing?>(null) }
    var showAllMap by remember { mutableStateOf(false) }
    var showCompare by remember { mutableStateOf(false) }
    var showDeleteAccount by remember { mutableStateOf(false) }
    val auth = remember { FirebaseAuth.getInstance() }
    val context = LocalContext.current

    DisposableEffect(Unit) {
        val reg = db.collection("listings")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, _ ->
                listings = snap?.documents?.mapNotNull { d ->
                    d.toObject(Listing::class.java)?.copy(id = d.id)
                } ?: emptyList()
            }
        onDispose { reg.remove() }
    }

    val filtered = listings.filter {
        (category == "Tanan" || it.category == category) &&
            (town == "Tanan" || it.town == town) &&
            (query.isBlank() || it.title.contains(query, true) || it.description.contains(query, true))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Camiguin Market") },
                actions = {
                    TextButton(onClick = { showAllMap = true }) { Text("🗺️ Mapa") }
                    TextButton(onClick = { showCompare = true }) { Text("⚖️ Itandi") }
                    TextButton(onClick = { showDeleteAccount = true }) { Text("🗑️") }
                    TextButton(onClick = onLogout) { Text("Log out") }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { showPost = true }) { Text("+ Baligya") }
        }
    ) { pad ->
        Column(Modifier.padding(pad).padding(horizontal = 12.dp).navigationBarsPadding()) {
            OutlinedTextField(
                query, { query = it }, label = { Text("Pangita…") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            ChipRow(categories, category) { category = it }
            ChipRow(listOf("Tanan") + towns, town) { town = it }
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Wala pay listing. Pindota ang + Baligya!")
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filtered, key = { it.id }) { l ->
                        Card(onClick = { selected = l }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(l.title, style = MaterialTheme.typography.titleMedium)
                                Text("₱%.2f".format(l.price), color = MaterialTheme.colorScheme.primary)
                                Text("${l.category} • ${l.town}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showPost) PostDialog(uid, displayName, db) { showPost = false }
    selected?.let { l ->
        val sellerLat = l.latitude
        val sellerLng = l.longitude
        AlertDialog(
            onDismissRequest = { selected = null },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Sira") } },
            dismissButton = {
                if (l.sellerId == uid) {
                    TextButton(onClick = { db.collection("listings").document(l.id).delete(); selected = null }) { Text("Papas") }
                }
            },
            title = { Text(l.title) },
            text = {
                Column {
                    Text("₱%.2f".format(l.price), style = MaterialTheme.typography.titleMedium)
                    Text(l.description)
                    Spacer(Modifier.height(8.dp))
                    Text("Seller: ${l.sellerName}")
                    Text("Lugar: ${l.town}")
                    Text("Contact: ${l.contact}")
                    Spacer(Modifier.height(8.dp))
                    Text("Pickup location: ${coordinatesLabel(l) ?: "wala pa ma-set"}")

                    if (sellerLat != null && sellerLng != null) {
                        Button(
                            onClick = { selected = null; mapListing = l },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("🗺️ View Map") }
                        OutlinedButton(
                            onClick = {
                                openDirections(
                                    context,
                                    LatLng(sellerLat, sellerLng),
                                    l.title
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("🧭 Get Directions") }
                        TextButton(
                            onClick = { selected = null; mapboxListing = l },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("🌐 Tan-awa sa Mapbox (web)") }
                    } else if (l.sellerId == uid) {
                        Button(
                            onClick = { selected = null; mapListing = l },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("📍 I-set ang Pickup Location") }
                    }
                }
            }
        )
    }

    // Option A: native Google Map sulod sa app.
    mapListing?.let { l ->
        SellerMapDialog(
            listing = l,
            onDismiss = { mapListing = null },
            onSaveLocation = { point ->
                saveListingLocation(db, l.id, point) { }
                mapListing = null
            },
            onOpenMapbox = { mapListing = null; mapboxListing = l }
        )
    }

    // Itandi ang ruta A→B sa Google Map ug Mapbox (pareho nga coordinates).
    if (showCompare) {
        RouteCompareDialog(onDismiss = { showCompare = false })
    }

    // Play requirement: ang user makahimo sa pagpapas sa iyang account ug data.
    if (showDeleteAccount) {
        DeleteAccountDialog(
            auth = auth,
            db = db,
            uid = uid,
            onDismiss = { showDeleteAccount = false },
            onDeleted = {
                showDeleteAccount = false
                onLogout()
            }
        )
    }

    // Tanan nga seller pins sa usa ka mapa (search bar + ruta A→B).
    if (showAllMap) {
        AllSellersMapDialog(
            listings = filtered,
            onSellerClick = { l ->
                showAllMap = false
                selected = l
            },
            onDismiss = { showAllMap = false }
        )
    }

    // Option B: Mapbox GL JS nga naa sa WebView.
    mapboxListing?.let { l ->
        MapboxMapDialog(
            latitude = l.latitude ?: CamiguinCenter.latitude,
            longitude = l.longitude ?: CamiguinCenter.longitude,
            label = l.title.ifBlank { "Seller Pickup Location" },
            sellers = filtered,
            onDismiss = { mapboxListing = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChipRow(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(options) { o ->
            FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(o) })
        }
    }
}

@Composable
fun PostDialog(uid: String, sellerName: String, db: FirebaseFirestore, onClose: () -> Unit) {
    var title by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(categories[1]) }
    var town by remember { mutableStateOf(towns[0]) }
    var pickup by remember { mutableStateOf<LatLng?>(null) }
    var showPicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Bag-ong baligya") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(title, { title = it }, label = { Text("Ngalan sa produkto") })
                OutlinedTextField(desc, { desc = it }, label = { Text("Deskripsyon") })
                OutlinedTextField(
                    price, { price = it }, label = { Text("Presyo (₱)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(
                    contact, { contact = it }, label = { Text("Contact (cellphone)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                )
                Text("Category", style = MaterialTheme.typography.labelMedium)
                ChipRow(categories.drop(1), category) { category = it }
                Text("Lugar", style = MaterialTheme.typography.labelMedium)
                ChipRow(towns, town) { town = it }

                Spacer(Modifier.height(8.dp))
                Text("Pickup location", style = MaterialTheme.typography.labelMedium)
                Text(
                    pickup?.let { "Napili: ${formatCoordinates(it)}" }
                        ?: "Wala pa makapili og pickup point."
                )
                OutlinedButton(
                    onClick = { showPicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("📌 Pili og Pickup Location sa Mapa") }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && price.toDoubleOrNull() != null && contact.isNotBlank(),
                onClick = {
                    db.collection("listings").add(
                        Listing(
                            title = title.trim(), description = desc.trim(),
                            price = price.toDouble(), category = category, town = town,
                            sellerId = uid, sellerName = sellerName, contact = contact.trim(),
                            createdAt = System.currentTimeMillis(),
                            latitude = pickup?.latitude, longitude = pickup?.longitude
                        )
                    )
                    onClose()
                }
            ) { Text("I-post") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Kanselar") } }
    )

    if (showPicker) {
        PickupLocationPickerDialog(
            initial = pickup,
            onPicked = { pickup = it },
            onDismiss = { showPicker = false }
        )
    }
}

/**
 * Play Store requirement: ang user makahimo sa pagpapas sa iyang account ug data
 * gikan mismo sa app. Ang web version naa sa `docs/account-deletion.html`.
 */
@Composable
fun DeleteAccountDialog(
    auth: FirebaseAuth,
    db: FirebaseFirestore,
    uid: String,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit
) {
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!deleting) onDismiss() },
        title = { Text("Papason ang account?") },
        text = {
            Column {
                Text(
                    "Permanenteng mapapas ang imong account, profile, ug tanan nimong " +
                        "listing. Dili na kini mabalik."
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !deleting,
                onClick = {
                    deleting = true
                    error = null
                    deleteAccountAndData(auth, db, uid) { result ->
                        deleting = false
                        if (result == null) onDeleted() else error = result
                    }
                }
            ) { Text(if (deleting) "Ginapas…" else "Papason") }
        },
        dismissButton = {
            TextButton(enabled = !deleting, onClick = onDismiss) { Text("Kanselar") }
        }
    )
}

/**
 * Papason ang tanan listing sa user, dayon ang Firebase Auth account.
 *
 * Ang `delete()` nagkinahanglan og bag-ong login; kon dugay na ang session,
 * makadawat kitag [FirebaseAuthRecentLoginRequiredException].
 * `onResult` makadawat og `null` kon malampuson, o mensahe kon mapakyas.
 */
fun deleteAccountAndData(
    auth: FirebaseAuth,
    db: FirebaseFirestore,
    uid: String,
    onResult: (String?) -> Unit
) {
    db.collection("listings").whereEqualTo("sellerId", uid).get()
        .addOnSuccessListener { snapshot ->
            val deleteUser = {
                val user = auth.currentUser
                if (user == null) {
                    onResult("Wala nay naka-log in nga account. Log in pag-usab.")
                } else {
                    user.delete().addOnCompleteListener { task ->
                        if (task.isSuccessful) onResult(null)
                        else onResult(deleteAccountError(task.exception))
                    }
                }
            }
            if (snapshot.documents.isEmpty()) {
                deleteUser()
            } else {
                val batch = db.batch()
                snapshot.documents.forEach { batch.delete(it.reference) }
                batch.commit().addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        deleteUser()
                    } else {
                        onResult(
                            "Napakyas ang pagpapas sa imong mga listing: " +
                                (task.exception?.localizedMessage ?: "wala mailhi nga sayop")
                        )
                    }
                }
            }
        }
        .addOnFailureListener { e ->
            onResult(
                "Napakyas ang pagbasa sa imong mga listing: " +
                    (e.localizedMessage ?: "wala mailhi nga sayop")
            )
        }
}

private fun deleteAccountError(e: Exception?): String = when (e) {
    is FirebaseAuthRecentLoginRequiredException ->
        "Para seguridad, log out dayon log in pag-usab, unya sulayi ang pagpapas."
    else ->
        "Napakyas ang pagpapas sa account: " +
            (e?.localizedMessage ?: "wala mailhi nga sayop")
}
