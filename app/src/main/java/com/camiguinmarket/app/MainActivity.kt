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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import com.google.firebase.firestore.FieldValue
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
    val imageUrl: String = "",
    // Tanan nga litrato (ang `imageUrl` mao ang una, alang sa daan nga cards).
    val imageUrls: List<String> = emptyList(),
    // Ihap sa pag-abli sa detalye (gina-increment sa bisan kinsang naka-log in).
    val views: Long = 0L
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Target SDK 36: enforced ang edge-to-edge, busa i-enable nga tarong
        // ang window aron dili ma-tabunan sa system bars ang sulod.
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            var dark by remember { mutableStateOf(loadDarkMode(context)) }
            MaterialTheme(
                colorScheme = if (dark) marketDarkColorScheme else marketColorScheme
            ) {
                Surface(Modifier.fillMaxSize()) {
                    AppRoot(dark) { value ->
                        dark = value
                        saveDarkMode(context, value)
                    }
                }
            }
        }
    }
}

@Composable
fun AppRoot(dark: Boolean, onToggleDark: (Boolean) -> Unit) {
    val auth = remember { FirebaseAuth.getInstance() }
    var user by remember { mutableStateOf(auth.currentUser) }
    if (user == null) {
        LoginScreen(auth) { user = auth.currentUser }
    } else {
        MarketScreen(
            uid = user!!.uid,
            displayName = user!!.displayName ?: user!!.email ?: "Seller",
            dark = dark,
            onToggleDark = onToggleDark
        ) {
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
    var showReset by remember { mutableStateOf(false) }
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
                            )?.addOnCompleteListener {
                                auth.currentUser?.sendEmailVerification()
                                onDone()
                            } ?: onDone()
                        } else onDone()
                    } else error = t.exception?.localizedMessage ?: "Napakyas"
                }
            }
        ) { Text(if (isRegister) "Mag-register" else "Log in") }
        TextButton(onClick = { isRegister = !isRegister }) {
            Text(if (isRegister) "Naa nay account? Log in" else "Wala pay account? Mag-register")
        }
        if (!isRegister) {
            TextButton(onClick = { showReset = true }) {
                Text("Nakalimot sa password?")
            }
        }
    }

    if (showReset) {
        var resetEmail by remember { mutableStateOf(email) }
        var resetMsg by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text("I-reset ang password") },
            text = {
                Column {
                    Text("Ipadala namo ang link sa imong email.")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        resetEmail,
                        { resetEmail = it },
                        label = { Text("Email") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth()
                    )
                    resetMsg?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, style = MaterialTheme.typography.labelSmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (resetEmail.isBlank()) {
                        resetMsg = "Isulat ang email."
                    } else {
                        auth.sendPasswordResetEmail(resetEmail.trim())
                            .addOnSuccessListener {
                                resetMsg = "Gipadala ang reset link. Tan-awa ang imong email."
                            }
                            .addOnFailureListener { e ->
                                resetMsg = e.localizedMessage ?: "Napakyas."
                            }
                    }
                }) { Text("Ipadala") }
            },
            dismissButton = {
                TextButton(onClick = { showReset = false }) { Text("Sira") }
            }
        )
    }
}

/** Pahinumdom kon wala pa ma-verify ang email sa naka-log in nga user. */
@Composable
fun VerifyEmailBanner(auth: FirebaseAuth) {
    val user = auth.currentUser ?: return
    if (user.isEmailVerified) return
    var info by remember { mutableStateOf<String?>(null) }
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text(
                "Wala pa ma-verify ang imong email.",
                style = MaterialTheme.typography.labelSmall
            )
            Row {
                TextButton(onClick = {
                    user.sendEmailVerification()
                        .addOnSuccessListener { info = "Gipadala pag-usab ang verification email." }
                        .addOnFailureListener { info = it.localizedMessage }
                }) { Text("Ipadala pag-usab", style = MaterialTheme.typography.labelSmall) }
                TextButton(onClick = {
                    user.reload().addOnSuccessListener {
                        info = if (auth.currentUser?.isEmailVerified == true)
                            "Na-verify na ✅ (pag-reopen kon wala ma-refresh ang app)"
                        else "Wala pa gihapon ma-verify."
                    }
                }) { Text("Na-verify na?", style = MaterialTheme.typography.labelSmall) }
            }
            info?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketScreen(
    uid: String,
    displayName: String,
    dark: Boolean,
    onToggleDark: (Boolean) -> Unit,
    onLogout: () -> Unit
) {
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
    var reportListing by remember { mutableStateOf<Listing?>(null) }
    var ratingOrder by remember { mutableStateOf<Order?>(null) }
    var reviewsSeller by remember { mutableStateOf<Pair<String, String>?>(null) }
    val viewed = remember { mutableSetOf<String>() }

    // Ablihan ang detalye ug ihapon ang view (dili sa tag-iya, kausa kada session).
    fun openListing(l: Listing) {
        if (l.sellerId != uid && viewed.add(l.id)) {
            db.collection("listings").document(l.id)
                .update("views", FieldValue.increment(1))
        }
        selected = l
    }
    var photoViewer by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    var priceFilter by remember { mutableStateOf("Tanan") }
    var sort by remember { mutableStateOf("Bag-o") }
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
            matchesPrice(it.price, priceFilter) &&
            (query.isBlank() || it.title.contains(query, true) || it.description.contains(query, true))
    }.let { list ->
        when (sort) {
            "Presyo ↑" -> list.sortedBy { it.price }
            "Presyo ↓" -> list.sortedByDescending { it.price }
            else -> list
        }
    }

    Scaffold(
        topBar = {
            if (overlay == null) {
                Column {
                BrandHeader(
                    cartCount = cartCount,
                    unreadCount = unread,
                    chatUnread = chatUnread,
                    onSearch = { overlay = "search" },
                    onCart = { tab = MarketTab.Cart },
                    onChats = { overlay = "inbox" },
                    onNotifications = { overlay = "notifications" }
                )
                VerifyEmailBanner(auth)
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface)
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
                            "reviews" -> "Mga Review"
                            "saved" -> "Saved searches"
                            "search" -> "Search"
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

                "orders" -> OrdersScreen(
                    uid = uid,
                    onOpen = { order ->
                        orderDetail = order
                        overlay = "order"
                    },
                    onRate = { ratingOrder = it }
                )

                "search" -> GlobalSearchScreen(
                    uid = uid,
                    listings = listings,
                    favorites = favorites,
                    onOpen = { openListing(it) },
                    onFavorite = { l -> toggleFavorite(db, uid, l) { } },
                    onAddToCart = { l -> addToCart(db, uid, l, displayName) { } }
                )

                "moderation" -> ModerationScreen(uid, isAdmin)

                "reviews" -> reviewsSeller?.let { (sid, sname) ->
                    ReviewsScreen(sid, sname)
                }

                "saved" -> SavedSearchesScreen(uid) { q, c, t ->
                    query = q
                    category = c
                    town = t
                    tab = MarketTab.Home
                    overlay = null
                }

                "order" -> orderDetail?.let { order ->
                    OrderDetailScreen(
                        order = order,
                        uid = uid,
                        onChat = { otherId, otherName ->
                            chatListing = null
                            chatWith = otherId to otherName
                            overlay = "chat"
                        },
                        onRate = { ratingOrder = it }
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
                        uid = uid,
                        query = query,
                        onQuery = { query = it },
                        category = category,
                        onCategory = { category = it },
                        priceFilter = priceFilter,
                        onPriceFilter = { priceFilter = it },
                        sort = sort,
                        onSort = { sort = it },
                        listings = filtered,
                        favorites = favorites,
                        onOpen = { openListing(it) },
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
                        priceFilter = priceFilter,
                        onPriceFilter = { priceFilter = it },
                        sort = sort,
                        onSort = { sort = it },
                        town = town,
                        onTown = { town = it },
                        listings = filtered,
                        favorites = favorites,
                        onOpen = { openListing(it) },
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
                        dark = dark,
                        onToggleDark = onToggleDark,
                        onOrders = { overlay = "orders" },
                        onInbox = { overlay = "inbox" },
                        onModeration = { overlay = "moderation" },
                        onSavedSearches = { overlay = "saved" },
                        onLogout = onLogout,
                        onDeleteAccount = { showDeleteAccount = true }
                    )

                    MarketTab.Sell -> HomeTab(
                        uid = uid,
                        query = query,
                        onQuery = { query = it },
                        category = category,
                        onCategory = { category = it },
                        priceFilter = priceFilter,
                        onPriceFilter = { priceFilter = it },
                        sort = sort,
                        onSort = { sort = it },
                        listings = filtered,
                        favorites = favorites,
                        onOpen = { openListing(it) },
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

    photoViewer?.let { (urls, index) ->
        PhotoViewer(urls, index) { photoViewer = null }
    }

    ratingOrder?.let { order ->
        RatingDialog(order) { ratingOrder = null }
    }

    reportListing?.let { l ->
        ReportDialog(l, uid, displayName) {
            reportListing = null
            Toast.makeText(context, "Nadawat ang report. Salamat!", Toast.LENGTH_LONG).show()
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
                    val photos = l.imageUrls.takeIf { it.isNotEmpty() }
                        ?: listOfNotNull(l.imageUrl.takeIf { it.isNotBlank() })
                    if (photos.isNotEmpty()) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            photos.forEachIndexed { idx, url ->
                                RemoteImage(
                                    url = url,
                                    modifier = Modifier
                                        .width(if (photos.size == 1) 300.dp else 150.dp)
                                        .height(180.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { photoViewer = photos to idx }
                                )
                            }
                        }
                        if (photos.size > 1) {
                            Text(
                                "${photos.size} ka litrato — i-scroll pakanan",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Text("₱%.2f".format(l.price), style = MaterialTheme.typography.titleMedium)
                    Text(
                        "👁 ${l.views} ka views",
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(l.description)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Seller: ${l.sellerName}")
                        Spacer(Modifier.width(8.dp))
                        SellerRatingSummary(l.sellerId)
                    }
                    if (l.sellerId != uid) {
                        SellerReviews(l.sellerId)
                        TextButton(onClick = {
                            reviewsSeller = l.sellerId to l.sellerName
                            selected = null
                            overlay = "reviews"
                        }) { Text("Tan-awa ang tanan nga review") }
                    }
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
                        TextButton(
                            onClick = { reportListing = l },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("I-report kini nga listing", color = Color(0xFFE53935)) }
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
    var imageUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var previews by remember { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var uploading by remember { mutableStateOf(false) }
    var uploadError by remember { mutableStateOf<String?>(null) }

    // Makapili og daghan ka litrato (max 6).
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) {
            imageUris = uris.take(6)
            uploadError = null
        }
    }

    LaunchedEffect(imageUris) {
        previews = imageUris.mapNotNull { decodeImageBitmap(context, it) }
    }

    val existingPhotos = remember(existing) {
        existing?.imageUrls?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(existing?.imageUrl?.takeIf { !it.isNullOrBlank() })
    }

    fun saveListing(imageUrls: List<String>) {
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
                imageUrl = imageUrls.firstOrNull() ?: "",
                imageUrls = imageUrls
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
                    previews.isNotEmpty() -> Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        previews.forEach { bmp ->
                            Image(
                                bitmap = bmp,
                                contentDescription = "Bag-ong litrato sa listing",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(92.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                        }
                    }

                    existingPhotos.isNotEmpty() -> Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        existingPhotos.forEach { url ->
                            RemoteImage(
                                url = url,
                                modifier = Modifier
                                    .size(92.dp)
                                    .clip(RoundedCornerShape(8.dp))
                            )
                        }
                    }

                    else -> Text(
                        "Wala pay litrato.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (imageUris.isNotEmpty()) {
                    Text(
                        "${imageUris.size} ka bag-ong litrato ang mapuli sa daan.",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                OutlinedButton(
                    onClick = { pickImage.launch("image/*") },
                    enabled = !uploading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        if (previews.isNotEmpty() || existingPhotos.isNotEmpty())
                            "🖼️ Ilisi ang mga litrato" else "📷 Pumili og mga litrato (max 6)"
                    )
                }
                if (previews.isNotEmpty()) {
                    TextButton(
                        onClick = { imageUris = emptyList(); previews = emptyList() },
                        enabled = !uploading
                    ) { Text("Tangtanga ang bag-ong mga litrato") }
                }
                if (uploading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        "Gi-upload ang mga litrato (${imageUris.size})…",
                        style = MaterialTheme.typography.bodySmall
                    )
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
                    val picked = imageUris
                    val baseUrl = BuildConfig.R2_UPLOAD_URL
                    when {
                        // Walay bag-ong litrato: tipigan ang daan (kon edit).
                        picked.isEmpty() -> saveListing(existingPhotos)

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
                                                        picked.map { uri ->
                                                            uploadListingImage(context, baseUrl, token, uri)
                                                        }
                                                    }
                                                }
                                                uploading = false
                                                result
                                                    .onSuccess { urls -> saveListing(urls) }
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
