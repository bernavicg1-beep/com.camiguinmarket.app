package com.camiguinmarket.app

import android.content.Context
import android.graphics.BitmapFactory
import android.content.Intent
import android.widget.Toast
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.background
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
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

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
    val longitude: Double? = null,
    // Download URL sa litrato sa Firebase Storage ("" = walay litrato).
    val imageUrl: String = ""
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Target SDK 36: enforced ang edge-to-edge, busa i-enable nga tarong
        // ang window aron dili ma-tabunan sa system bars ang sulod.
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = marketColorScheme) {
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
    // Ang listing nga gi-edit sa tag-iya (null = wala).
    var editListing by remember { mutableStateOf<Listing?>(null) }
    var selected by remember { mutableStateOf<Listing?>(null) }
    var mapListing by remember { mutableStateOf<Listing?>(null) }
    var mapboxListing by remember { mutableStateOf<Listing?>(null) }
    var showAllMap by remember { mutableStateOf(false) }
    var showCompare by remember { mutableStateOf(false) }
    var showDeleteAccount by remember { mutableStateOf(false) }
    val auth = remember { FirebaseAuth.getInstance() }
    val context = LocalContext.current

    // Navigation ug mga bag-ong feature (mockup): tabs, cart, paborito, notifications, chat.
    var tab by remember { mutableStateOf(MarketTab.Home) }
    var overlay by remember { mutableStateOf<String?>(null) }
    var cartCount by remember { mutableStateOf(0) }
    var unread by remember { mutableStateOf(0) }
    var favorites by remember { mutableStateOf<Set<String>>(emptySet()) }
    var chatWith by remember { mutableStateOf<Pair<String, String>?>(null) }
    var chatListing by remember { mutableStateOf<Listing?>(null) }
    var chatUnread by remember { mutableStateOf(0) }
    var orderDetail by remember { mutableStateOf<Order?>(null) }
    var isAdmin by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val reg = db.collection("carts").document(uid).collection("items")
            .addSnapshotListener { snap, _ ->
                cartCount = snap?.documents?.sumOf { it.getLong("quantity") ?: 1L }?.toInt() ?: 0
            }
        onDispose { reg.remove() }
    }

    DisposableEffect(Unit) {
        val reg = db.collection("notifications").document(uid).collection("items")
            .whereEqualTo("read", false)
            .addSnapshotListener { snap, _ -> unread = snap?.size() ?: 0 }
        onDispose { reg.remove() }
    }

    // Admin kon dunay `admins/{uid}` document (gina-set sa Console).
    DisposableEffect(Unit) {
        val reg = db.collection("admins").document(uid)
            .addSnapshotListener { snap, _ -> isAdmin = snap?.exists() == true }
        onDispose { reg.remove() }
    }

    // Sum sa akong unread sa tanan chat thread (badge sa header).
    DisposableEffect(Unit) {
        val reg = db.collection("chats")
            .whereArrayContains("participants", uid)
            .addSnapshotListener { snap, _ ->
                chatUnread = snap?.documents.orEmpty().sumOf { d ->
                    ((d.get("unread") as? Map<*, *>)?.get(uid) as? Number)?.toInt() ?: 0
                }
            }
        onDispose { reg.remove() }
    }

    DisposableEffect(Unit) {
        val reg = db.collection("favorites").document(uid).collection("items")
            .addSnapshotListener { snap, _ ->
                favorites = snap?.documents?.mapNotNull { it.getString("listingId") }?.toSet()
                    ?: emptySet()
            }
        onDispose { reg.remove() }
    }

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
            if (overlay == null) {
                BrandHeader(
                    cartCount = cartCount,
                    unreadCount = unread,
                    chatUnread = chatUnread,
                    onCart = { tab = MarketTab.Cart },
                    onChats = { overlay = "inbox" },
                    onNotifications = { overlay = "notifications" }
                )
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.White)
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { overlay = null }) {
                        Text("←", style = MaterialTheme.typography.titleLarge)
                    }
                    Text(
                        when (overlay) {
                            "favorites" -> "Paborito"
                            "notifications" -> "Notifications"
                            "orders" -> "Mga Order"
                            "order" -> "Detalye sa Order"
                            "moderation" -> "Moderation"
                            "inbox" -> "Mga Chat"
                            else -> "Chat"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        bottomBar = {
            if (overlay == null) {
                BottomNavBar(tab, cartCount) { picked ->
                    if (picked == MarketTab.Sell) showPost = true else tab = picked
                }
            }
        },
        floatingActionButton = {
            if (overlay == null && tab == MarketTab.Home) {
                ExtendedFloatingActionButton(onClick = { showPost = true }) { Text("+ Baligya") }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (overlay) {
                "favorites" -> FavoritesScreen(uid) { id ->
                    overlay = null
                    listings.firstOrNull { it.id == id }?.let { selected = it }
                }

                "notifications" -> NotificationsScreen(uid)

                "orders" -> OrdersScreen(uid) { order ->
                    orderDetail = order
                    overlay = "order"
                }

                "moderation" -> ModerationScreen(uid, isAdmin)

                "order" -> orderDetail?.let { order ->
                    OrderDetailScreen(
                        order = order,
                        uid = uid,
                        onChat = { otherId, otherName ->
                            chatListing = null
                            chatWith = otherId to otherName
                            overlay = "chat"
                        }
                    )
                }

                "inbox" -> InboxScreen(uid) { thread ->
                    chatListing = null
                    chatWith = thread.otherUid to thread.otherName
                    overlay = "chat"
                }

                "chat" -> chatWith?.let { (otherId, otherName) ->
                    ChatScreen(uid, otherId, otherName, chatListing?.title ?: "")
                }

                else -> when (tab) {
                    MarketTab.Home -> HomeTab(
                        query = query,
                        onQuery = { query = it },
                        category = category,
                        onCategory = { category = it },
                        listings = filtered,
                        favorites = favorites,
                        onOpen = { selected = it },
                        onFavorite = { l -> toggleFavorite(db, uid, l) { } },
                        onAddToCart = { l ->
                            addToCart(db, uid, l, displayName) { err ->
                                Toast.makeText(
                                    context, err ?: "Nadugang sa cart ✅", Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onAllCategories = { tab = MarketTab.Categories },
                        onMap = { showAllMap = true },
                        onCompare = { showCompare = true },
                        onSell = { showPost = true }
                    )

                    MarketTab.Categories -> CategoriesTab(
                        category = category,
                        onCategory = { category = it },
                        town = town,
                        onTown = { town = it },
                        listings = filtered,
                        favorites = favorites,
                        onOpen = { selected = it },
                        onFavorite = { l -> toggleFavorite(db, uid, l) { } },
                        onAddToCart = { l ->
                            addToCart(db, uid, l, displayName) { err ->
                                Toast.makeText(
                                    context, err ?: "Nadugang sa cart ✅", Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    )

                    MarketTab.Cart -> CartScreen(
                        uid = uid,
                        onBrowse = { tab = MarketTab.Home },
                        onCheckout = { items ->
                            checkoutCart(db, uid, displayName, items) { err ->
                                Toast.makeText(
                                    context,
                                    err ?: "Malampuson ang order ✅",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    )

                    MarketTab.Profile -> ProfileScreen(
                        uid = uid,
                        displayName = displayName,
                        email = auth.currentUser?.email ?: "",
                        myListings = listings.filter { it.sellerId == uid },
                        onFavorites = { overlay = "favorites" },
                        isAdmin = isAdmin,
                        onOrders = { overlay = "orders" },
                        onInbox = { overlay = "inbox" },
                        onModeration = { overlay = "moderation" },
                        onLogout = onLogout,
                        onDeleteAccount = { showDeleteAccount = true }
                    )

                    MarketTab.Sell -> HomeTab(
                        query = query,
                        onQuery = { query = it },
                        category = category,
                        onCategory = { category = it },
                        listings = filtered,
                        favorites = favorites,
                        onOpen = { selected = it },
                        onFavorite = { l -> toggleFavorite(db, uid, l) { } },
                        onAddToCart = { l ->
                            addToCart(db, uid, l, displayName) { err ->
                                Toast.makeText(
                                    context, err ?: "Nadugang sa cart ✅", Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onAllCategories = { tab = MarketTab.Categories },
                        onMap = { showAllMap = true },
                        onCompare = { showCompare = true },
                        onSell = { showPost = true }
                    )
                }
            }
        }
    }

    if (showPost) PostDialog(uid, displayName, db) { showPost = false }
    editListing?.let { l ->
        PostDialog(uid, displayName, db, existing = l) { editListing = null }
    }
    selected?.let { l ->
        val sellerLat = l.latitude
        val sellerLng = l.longitude
        AlertDialog(
            onDismissRequest = { selected = null },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Sira") } },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "${l.title} — ₱%.2f sa ${l.town}\nContact: ${l.contact}\n\n".format(l.price) +
                                    "Tan-awa sa Camiguin Market app."
                            )
                        }
                        context.startActivity(Intent.createChooser(share, "Ipakigbahin ang listing"))
                    }) {
                        Text("Ipakigbahin")
                    }
                    if (l.sellerId == uid) {
                        TextButton(onClick = { selected = null; editListing = l }) { Text("I-edit") }
                        TextButton(
                            onClick = {
                                db.collection("listings").document(l.id).delete()
                                selected = null
                            }
                        ) { Text("Papas") }
                    }
                }
            },
            title = { Text(l.title) },
            text = {
                Column {
                    if (l.imageUrl.isNotBlank()) {
                        RemoteImage(
                            url = l.imageUrl,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    Text("₱%.2f".format(l.price), style = MaterialTheme.typography.titleMedium)
                    Text(l.description)
                    Spacer(Modifier.height(8.dp))
                    Text("Seller: ${l.sellerName}")
                    Text("Lugar: ${l.town}")
                    Text("Contact: ${l.contact}")
                    Spacer(Modifier.height(8.dp))
                    Text("Pickup location: ${coordinatesLabel(l) ?: "wala pa ma-set"}")

                    if (l.sellerId != uid) {
                        Button(
                            onClick = {
                                addToCart(db, uid, l, displayName) { err ->
                                    Toast.makeText(
                                        context, err ?: "Nadugang sa cart ✅", Toast.LENGTH_SHORT
                                    ).show()
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Add to Cart") }
                        OutlinedButton(
                            onClick = {
                                chatListing = l
                                chatWith = l.sellerId to l.sellerName
                                selected = null
                                overlay = "chat"
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Chat sa seller") }
                    }

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

/**
 * Pag-post og bag-ong listing, o pag-edit sa existing nga listing sa tag-iya.
 *
 * Kon `existing` ang gihatag, ma-prefill ang mga field ug i-update ang maong
 * document imbes nga maghimo og bag-o. Ang `sellerId`, `sellerName`, ug
 * `createdAt` sa orihinal wala gayud mausab — busa tugotan kini sa rules.
 */
@Composable
fun PostDialog(
    uid: String,
    sellerName: String,
    db: FirebaseFirestore,
    existing: Listing? = null,
    onClose: () -> Unit
) {
    fun priceText(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    var title by remember { mutableStateOf(existing?.title ?: "") }
    var desc by remember { mutableStateOf(existing?.description ?: "") }
    var price by remember { mutableStateOf(existing?.let { priceText(it.price) } ?: "") }
    var contact by remember { mutableStateOf(existing?.contact ?: "") }
    var category by remember { mutableStateOf(existing?.category ?: categories[1]) }
    var town by remember { mutableStateOf(existing?.town ?: towns[0]) }
    var pickup by remember {
        mutableStateOf(
            existing?.let { l ->
                val lat = l.latitude
                val lng = l.longitude
                if (lat != null && lng != null) LatLng(lat, lng) else null
            }
        )
    }
    var showPicker by remember { mutableStateOf(false) }

    // Litrato sa listing: pilion gikan sa gallery, i-upload sa Firebase Storage.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var preview by remember { mutableStateOf<ImageBitmap?>(null) }
    var uploading by remember { mutableStateOf(false) }
    var uploadError by remember { mutableStateOf<String?>(null) }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            imageUri = uri
            uploadError = null
        }
    }

    LaunchedEffect(imageUri) {
        preview = imageUri?.let { decodeImageBitmap(context, it) }
    }

    fun saveListing(imageUrl: String) {
        val ref = if (existing == null) {
            db.collection("listings").document()
        } else {
            db.collection("listings").document(existing.id)
        }
        ref.set(
            Listing(
                title = title.trim(), description = desc.trim(),
                price = price.toDouble(), category = category, town = town,
                // Sa edit, dili mausab ang tag-iya niini nga listing.
                sellerId = existing?.sellerId ?: uid,
                sellerName = existing?.sellerName ?: sellerName,
                contact = contact.trim(),
                createdAt = existing?.createdAt?.takeIf { it > 0 }
                    ?: System.currentTimeMillis(),
                latitude = pickup?.latitude, longitude = pickup?.longitude,
                imageUrl = imageUrl
            )
        )
        onClose()
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (existing == null) "Bag-ong baligya" else "I-edit ang listing") },
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

                Spacer(Modifier.height(8.dp))
                Text("Litrato", style = MaterialTheme.typography.labelMedium)
                when {
                    preview != null -> Image(
                        bitmap = preview!!,
                        contentDescription = "Bag-ong litrato sa listing",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )

                    existing?.imageUrl?.isNotBlank() == true -> RemoteImage(
                        url = existing.imageUrl,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )

                    else -> Text(
                        "Wala pay litrato.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                OutlinedButton(
                    onClick = { pickImage.launch("image/*") },
                    enabled = !uploading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (preview != null || existing?.imageUrl?.isNotBlank() == true)
                            "🖼️ Ilisi ang litrato" else "📷 Pumili og litrato"
                    )
                }
                if (preview != null) {
                    TextButton(
                        onClick = { imageUri = null; preview = null },
                        enabled = !uploading
                    ) { Text("Tangtanga ang bag-ong litrato") }
                }
                if (uploading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Gi-upload ang litrato…", style = MaterialTheme.typography.bodySmall)
                }
                uploadError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !uploading && title.isNotBlank() &&
                    price.toDoubleOrNull() != null && contact.isNotBlank(),
                onClick = {
                    val picked = imageUri
                    val baseUrl = BuildConfig.R2_UPLOAD_URL
                    when {
                        // Walay bag-ong litrato: tipigan ang daan (kon edit).
                        picked == null -> saveListing(existing?.imageUrl ?: "")

                        baseUrl.isBlank() -> {
                            uploadError = "Wala ma-set ang R2_UPLOAD_URL " +
                                "(local.properties o Gradle property)."
                            Unit
                        }

                        else -> {
                            val user = FirebaseAuth.getInstance().currentUser
                            if (user == null) {
                                uploadError = "Kinahanglan naka-log in aron maka-upload."
                            } else {
                                uploading = true
                                uploadError = null
                                user.getIdToken(false)
                                    .addOnSuccessListener { tokenResult ->
                                        val token = tokenResult.token
                                        if (token.isNullOrBlank()) {
                                            uploading = false
                                            uploadError = "Wala makuha ang Firebase ID token."
                                        } else {
                                            scope.launch {
                                                val result = withContext(Dispatchers.IO) {
                                                    runCatching {
                                                        uploadListingImage(
                                                            context, baseUrl, token, picked
                                                        )
                                                    }
                                                }
                                                uploading = false
                                                result
                                                    .onSuccess { url -> saveListing(url) }
                                                    .onFailure {
                                                        uploadError = it.localizedMessage
                                                            ?: "Napakyas ang pag-upload sa litrato."
                                                    }
                                            }
                                        }
                                    }
                                    .addOnFailureListener { e ->
                                        uploading = false
                                        uploadError = e.localizedMessage
                                            ?: "Napakyas ang pagkuha sa ID token."
                                    }
                            }
                        }
                    }
                }
            ) { Text(if (existing == null) "I-post" else "I-save") }
        },
        dismissButton = {
            TextButton(onClick = onClose, enabled = !uploading) { Text("Kanselar") }
        }
    )

    if (showPicker) {
        PickupLocationPickerDialog(
            initial = pickup,
            onPicked = { pickup = it },
            onDismiss = { showPicker = false }
        )
    }
}

/** Decode sa napiling litrato gikan sa gallery (walay dugang nga library). */
fun decodeImageBitmap(context: Context, uri: Uri): ImageBitmap? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream)?.asImageBitmap()
    }
}.getOrNull()

/**
 * Ang litrato sa listing gikan sa Cloudflare R2 (agi sa atong Worker).
 *
 * Gi-download kausa ra matag URL sulod sa composition — igo na alang sa gagmay
 * nga thumbnail ug sa bug-os nga litrato sa detalye.
 */
@Composable
fun RemoteImage(url: String, modifier: Modifier = Modifier) {
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(url) {
        if (url.isBlank()) {
            bitmap = null
            return@LaunchedEffect
        }
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 20_000
                conn.inputStream.use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }

    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = "Litrato sa listing",
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    }
}

/**
 * I-upload ang litrato ngadto sa Cloudflare Worker nga motipig niini sa R2.
 *
 * Ang Firebase ID token maoy nagpamatuod sa user; ang Worker maoy mohimo sa R2
 * key gikan sa `uid` sa token, busa walay R2 credentials dinhi sa app.
 */
fun uploadListingImage(
    context: Context,
    baseUrl: String,
    idToken: String,
    uri: Uri
): String {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: error("Dili mabasa ang napiling litrato.")
    val mime = context.contentResolver.getType(uri) ?: "image/jpeg"

    val conn = URL(baseUrl.trimEnd('/') + "/upload").openConnection() as HttpURLConnection
    try {
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("Authorization", "Bearer $idToken")
        conn.setRequestProperty("Content-Type", mime)
        conn.setFixedLengthStreamingMode(bytes.size)
        conn.outputStream.use { it.write(bytes) }

        val code = conn.responseCode
        val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            error("Upload failed ($code): ${body.take(200)}")
        }
        return JSONObject(body).getString("url")
    } finally {
        conn.disconnect()
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
