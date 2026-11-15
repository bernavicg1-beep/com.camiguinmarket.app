package com.camiguinmarket.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.content.Intent
import android.widget.Toast
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

val categories = listOf("All", "Food & Seafood", "Local Products", "Clothing", "Gadgets", "Other")
val towns = listOf("Mambajao", "Mahinog", "Guinsiliban", "Sagay", "Catarman")

/** Old Bisaya category values stored by earlier versions → English labels. */
fun normalizeCategory(raw: String): String = when (raw) {
    "Pagkaon ug Seafood" -> "Food & Seafood"
    "Lokal nga Produkto" -> "Local Products"
    "Sinina" -> "Clothing"
    "Uban pa" -> "Other"
    "All" -> "All"
    else -> raw
}

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
    // Seller pickup location (pin on the Google Map).
    val latitude: Double? = null,
    val longitude: Double? = null,
    // Photo download URL ("" = no photo).
    val imageUrl: String = "",
    // All photos (`imageUrl` is the first one, for older cards).
    val imageUrls: List<String> = emptyList(),
    // Number of detail opens (incremented by any signed-in user).
    val views: Long = 0L,
    // Number of cart adds.
    val cartAdds: Long = 0L,
    // Views kada adlaw: {"2026-11-14": 5}
    val viewsByDay: Map<String, Long> = emptyMap(),
    // Perishable listings leave the market once `expiresAt` passes.
    // 0 means the listing never expires.
    val isPerishable: Boolean = false,
    val expiresAt: Long = 0L
)

/** True once a perishable listing has reached its expiration time. */
fun Listing.isExpired(now: Long = System.currentTimeMillis()): Boolean =
    isPerishable && expiresAt in 1..now

/** Price without a trailing ".00" when the amount is round. */
fun formatPrice(price: Double): String =
    if (price % 1.0 == 0.0) "%.0f".format(price) else "%.2f".format(price)

/** Expiration shown as "Nov 15, 6:00 PM". */
fun formatExpiry(ts: Long): String =
    SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(ts))

/**
 * Ask for the expiration date, then the time, and hand back the timestamp.
 * A perishable listing cannot be posted without this.
 */
fun pickExpiryDateTime(context: Context, initial: Long, onPicked: (Long) -> Unit) {
    val start = Calendar.getInstance().apply {
        if (initial > 0) timeInMillis = initial
    }
    DatePickerDialog(
        context,
        { _, year, month, day ->
            TimePickerDialog(
                context,
                { _, hour, minute ->
                    val picked = Calendar.getInstance().apply {
                        set(Calendar.YEAR, year)
                        set(Calendar.MONTH, month)
                        set(Calendar.DAY_OF_MONTH, day)
                        set(Calendar.HOUR_OF_DAY, hour)
                        set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }
                    onPicked(picked.timeInMillis)
                },
                start.get(Calendar.HOUR_OF_DAY),
                start.get(Calendar.MINUTE),
                false
            ).show()
        },
        start.get(Calendar.YEAR),
        start.get(Calendar.MONTH),
        start.get(Calendar.DAY_OF_MONTH)
    ).show()
}

class MainActivity : ComponentActivity() {
    // Notification tap target: "order:<id>" or "chat:<id>".
    private var pushTarget by mutableStateOf<String?>(null)

    /** Read the extras from the notification intent. */
    private fun readPushTarget(intent: Intent?): String? {
        val type = intent?.getStringExtra("type") ?: return null
        val target = when (type) {
            "order" -> intent.getStringExtra("orderId")?.let { "order:$it" }
            "chat" -> intent.getStringExtra("chatId")?.let { "chat:$it" }
            else -> null
        }
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "CamiguinPush",
                "intent type=$type orderId=${intent.getStringExtra("orderId")} " +
                    "chatId=${intent.getStringExtra("chatId")} => $target"
            )
        }
        return target
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pushTarget = readPushTarget(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pushTarget = readPushTarget(intent)
        // Target SDK 36 enforces edge-to-edge, so enable the window properly
        // so the system bars do not cover the content.
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            var dark by remember { mutableStateOf(loadDarkMode(context)) }
            MaterialTheme(
                colorScheme = if (dark) marketDarkColorScheme else marketColorScheme,
                shapes = marketShapes
            ) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AppRoot(
                        dark = dark,
                        pushTarget = pushTarget,
                        onPushHandled = { pushTarget = null }
                    ) { value ->
                        dark = value
                        saveDarkMode(context, value)
                    }
                }
            }
        }
    }
}

@Composable
fun AppRoot(
    dark: Boolean,
    pushTarget: String?,
    onPushHandled: () -> Unit,
    onToggleDark: (Boolean) -> Unit
) {
    val auth = remember { FirebaseAuth.getInstance() }
    var user by remember { mutableStateOf(auth.currentUser) }
    if (user == null) {
        LoginScreen(auth) { user = auth.currentUser }
    } else {
        MarketScreen(
            uid = user!!.uid,
            displayName = user!!.displayName ?: user!!.email ?: "Seller",
            dark = dark,
            pushTarget = pushTarget,
            onPushHandled = onPushHandled,
            onToggleDark = onToggleDark
        ) {
            auth.signOut(); user = null
        }
    }
}

/**
 * First screen: the poster's lockup over the island photo, with the two ways
 * in. The form itself is a separate step so the app opens on the brand.
 */
@Composable
private fun WelcomeScreen(onStart: () -> Unit, onSignIn: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        Image(
            painter = painterResource(R.drawable.welcome_ocean),
            contentDescription = "Turquoise lagoon in Camiguin",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color(0x400A2B52),
                        0.5f to Color(0x8C0A2B52),
                        0.74f to Color(0xF7FFFFFF),
                        1f to Color.White
                    )
                )
        )
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(28.dp))
            BrandLogo(92.dp)
            Spacer(Modifier.height(16.dp))
            Text(
                "Camiguin Market",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(12.dp))
            Surface(color = BrandBlueDark, shape = RoundedCornerShape(50)) {
                Text(
                    "Buy • Sell • Connect",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                )
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = onStart,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text(
                    "Get Started",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(10.dp))
            OutlinedButton(
                onClick = onSignIn,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.5.dp, BrandBlue),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Text(
                    "I already have an account",
                    color = BrandBlue,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun LoginScreen(auth: FirebaseAuth, onDone: () -> Unit) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var isRegister by remember { mutableStateOf(false) }
    var showForm by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    if (!showForm) {
        WelcomeScreen(
            onStart = { isRegister = true; error = null; showForm = true },
            onSignIn = { isRegister = false; error = null; showForm = true }
        )
    } else Column(
        Modifier.fillMaxSize()
            .padding(WindowInsets.systemBars.asPaddingValues())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { showForm = false; error = null }) {
                Text("←", style = MaterialTheme.typography.titleLarge)
            }
            BrandLogo(38.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    "Camiguin Market",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (isRegister) "Create your seller account" else "Welcome back",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        if (isRegister) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
        }
        OutlinedTextField(
            email, { email = it }, label = { Text("Email") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            password, { password = it }, label = { Text("Password (6+ characters)") },
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
                    } else error = t.exception?.localizedMessage ?: "Sign-in failed"
                }
            }
        ) { Text(if (isRegister) "Create account" else "Log in") }
        TextButton(onClick = { isRegister = !isRegister }) {
            Text(if (isRegister) "Already have an account? Log in" else "No account yet? Create one")
        }
        if (!isRegister) {
            TextButton(onClick = { showReset = true }) {
                Text("Forgot password?")
            }
        }
    }

    if (showReset) {
        var resetEmail by remember { mutableStateOf(email) }
        var resetMsg by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { showReset = false },
            title = { Text("Reset password") },
            text = {
                Column {
                    Text("We'll send the link to your email.")
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
                        resetMsg = "Enter your email."
                    } else {
                        auth.sendPasswordResetEmail(resetEmail.trim())
                            .addOnSuccessListener {
                                resetMsg = "Reset link sent. Check your email."
                            }
                            .addOnFailureListener { e ->
                                resetMsg = e.localizedMessage ?: "Failed."
                            }
                    }
                }) { Text("Send") }
            },
            dismissButton = {
                TextButton(onClick = { showReset = false }) { Text("Close") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketScreen(
    uid: String,
    displayName: String,
    dark: Boolean,
    pushTarget: String?,
    onPushHandled: () -> Unit,
    onToggleDark: (Boolean) -> Unit,
    onLogout: () -> Unit
) {
    val db = remember { FirebaseFirestore.getInstance() }
    var listings by remember { mutableStateOf<List<Listing>>(emptyList()) }
    var category by remember { mutableStateOf(categories[0]) }
    var town by remember { mutableStateOf("All") }
    var query by remember { mutableStateOf("") }
    var showPost by remember { mutableStateOf(false) }
    // The listing being edited by the owner (null = none).
    var editListing by remember { mutableStateOf<Listing?>(null) }
    var selected by remember { mutableStateOf<Listing?>(null) }
    var mapListing by remember { mutableStateOf<Listing?>(null) }
    var webMapListing by remember { mutableStateOf<Listing?>(null) }
    var showAllMap by remember { mutableStateOf(false) }
    var showCompare by remember { mutableStateOf(false) }
    var showDeleteAccount by remember { mutableStateOf(false) }
    val auth = remember { FirebaseAuth.getInstance() }
    val context = LocalContext.current

    // Navigation and the new features (mockup): tabs, cart, favorites, notifications, chat.
    var tab by remember { mutableStateOf(MarketTab.Home) }
    var overlay by remember { mutableStateOf<String?>(null) }

    // Back closes an open screen, then returns to Home, and only exits from Home.
    BackHandler(enabled = overlay != null || tab != MarketTab.Home) {
        if (overlay != null) overlay = null else tab = MarketTab.Home
    }
    var cartCount by remember { mutableStateOf(0) }
    var unread by remember { mutableStateOf(0) }
    var favorites by remember { mutableStateOf<Set<String>>(emptySet()) }
    var chatWith by remember { mutableStateOf<Pair<String, String>?>(null) }
    var chatListing by remember { mutableStateOf<Listing?>(null) }
    var reportListing by remember { mutableStateOf<Listing?>(null) }
    var ratingOrder by remember { mutableStateOf<Order?>(null) }
    var reviewsSeller by remember { mutableStateOf<Pair<String, String>?>(null) }
    val viewed = remember { mutableSetOf<String>() }

    // Open the detail and count the view (not for the owner, once per session).
    fun openListing(l: Listing) {
        if (l.sellerId != uid && viewed.add(l.id)) {
            val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(java.util.Date())
            db.collection("listings").document(l.id).update(
                mapOf(
                    "views" to FieldValue.increment(1),
                    "viewsByDay.$day" to FieldValue.increment(1)
                )
            )
        }
        selected = l
    }

    var photoViewer by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    var priceFilter by remember { mutableStateOf("All") }
    var sort by remember { mutableStateOf("Newest") }
    var chatUnread by remember { mutableStateOf(0) }
    var orderDetail by remember { mutableStateOf<Order?>(null) }

    // Deep link from a push notification: open the order or the chat.
    LaunchedEffect(pushTarget, uid) {
        val target = pushTarget ?: return@LaunchedEffect
        val parts = target.split(":", limit = 2)
        val id = parts.getOrNull(1) ?: return@LaunchedEffect
        if (BuildConfig.DEBUG) android.util.Log.d("CamiguinPush", "opening $parts[0] id=$id")
        when (parts[0]) {
            "order" -> db.collection("orders").document(id).get()
                .addOnSuccessListener { d ->
                    d.toObject(Order::class.java)?.copy(id = d.id)?.let { order ->
                        orderDetail = order
                        overlay = "order"
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d("CamiguinPush", "order detail opened: $id")
                        }
                    }
                }

            "chat" -> db.collection("chats").document(id).get()
                .addOnSuccessListener { d ->
                    val participants = (d.get("participants") as? List<*>)
                        ?.filterIsInstance<String>().orEmpty()
                    val other = participants.firstOrNull { it != uid }
                    if (other != null) {
                        val names = d.get("names") as? Map<*, *>
                        chatListing = null
                        chatWith = other to (names?.get(other) as? String ?: "User")
                        overlay = "chat"
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d("CamiguinPush", "chat opened: $id (with $other)")
                        }
                    }
                }
        }
        onPushHandled()
    }
    // One-off repair of chat names that older builds saved as "Me".
    LaunchedEffect(uid) {
        val prefs = context.getSharedPreferences("chat_repair", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(uid, false)) {
            repairChatNames(db, uid) { prefs.edit().putBoolean(uid, true).apply() }
        }
    }

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

    // FCM: save the token so the server can send push notifications.
    LaunchedEffect(uid) {
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                db.collection("users").document(uid).collection("tokens").document(token)
                    .set(mapOf("token" to token, "updatedAt" to System.currentTimeMillis()))
            }
    }

    // POST_NOTIFICATIONS (Android 13+) for push and local notifications.
    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Admin when an `admins/{uid}` document exists (created in the Console).
    DisposableEffect(Unit) {
        val reg = db.collection("admins").document(uid)
            .addSnapshotListener { snap, _ -> isAdmin = snap?.exists() == true }
        onDispose { reg.remove() }
    }

    // Sum of my unread across all chat threads (header badge).
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
                // Expired perishable listings are dropped here, so they are
                // gone from the home feed, categories, map and profile.
                listings = snap?.documents?.mapNotNull { d ->
                    d.toObject(Listing::class.java)?.let { l ->
                        l.copy(id = d.id, category = normalizeCategory(l.category))
                    }
                }?.filterNot { it.isExpired() } ?: emptyList()
            }
        onDispose { reg.remove() }
    }

    val filtered = listings.filter {
        (category == "All" || it.category == category) &&
            (town == "All" || it.town == town) &&
            matchesPrice(it.price, priceFilter) &&
            (query.isBlank() || it.title.contains(query, true) || it.description.contains(query, true))
    }.let { list -> sortListings(list, sort) }

    Scaffold(
        topBar = {
            if (overlay == null) {
                Column {
                BrandHeader(
                    query = query,
                    onQuery = { query = it },
                    cartCount = cartCount,
                    unreadCount = unread,
                    chatUnread = chatUnread,
                    onSearch = { overlay = "search" },
                    onCart = { tab = MarketTab.Cart },
                    onChats = { overlay = "inbox" },
                    onNotifications = { overlay = "notifications" }
                )
                }
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(BrandHeaderBrush)
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { overlay = null }) {
                        Text("←", style = MaterialTheme.typography.titleLarge, color = Color.White)
                    }
                    Text(
                        when (overlay) {
                            "favorites" -> "Favorites"
                            "notifications" -> "Notifications"
                            "orders" -> "Orders"
                            "order" -> "Order Details"
                            "moderation" -> "Moderation"
                            "reviews" -> "Reviews"
                            "saved" -> "Saved searches"
                            "search" -> "Search"
                            "stats" -> "Listing stats"
                            "inbox" -> "Chats"
                            else -> "Chat"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
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
                ExtendedFloatingActionButton(onClick = { showPost = true }) { Text("+ Sell") }
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

                "stats" -> ListingStatsScreen(uid, listings.filter { it.sellerId == uid })

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
                                    context, err ?: "Added to cart ✅", Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onAllCategories = { tab = MarketTab.Categories },
                        onMap = { showAllMap = true },
                        onCompare = { showCompare = true },
                        onSell = { showPost = true }
                    )

                    MarketTab.Categories -> CategoriesTab(
                        ownerUid = uid,
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
                                    context, err ?: "Added to cart ✅", Toast.LENGTH_SHORT
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
                                    err ?: "Order placed ✅",
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
                        onListingStats = { overlay = "stats" },
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
                                    context, err ?: "Added to cart ✅", Toast.LENGTH_SHORT
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
            Toast.makeText(context, "Report received. Thanks!", Toast.LENGTH_LONG).show()
        }
    }

    if (showPost) PostDialog(uid, displayName, db) { showPost = false }
    editListing?.let { l ->
        PostDialog(uid, displayName, db, existing = l) { editListing = null }
    }
    selected?.let { l ->
        val sellerLat = l.latitude
        val sellerLng = l.longitude
        Dialog(
            onDismissRequest = { selected = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    // Blue bar with the back button and the owner actions.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(BrandHeaderBrush)
                            .statusBarsPadding()
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { selected = null }) {
                            Text("←", color = Color.White, style = MaterialTheme.typography.titleLarge)
                        }
                        Text(
                            "Listing",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = {
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(
                                    Intent.EXTRA_TEXT,
                                    "${l.title} — ₱%.2f in ${l.town}\nContact: ${l.contact}\n\n".format(l.price) +
                                        "View it in the Camiguin Market app."
                                )
                            }
                            context.startActivity(Intent.createChooser(share, "Share listing"))
                        }) {
                            Text("↗", color = Color.White, style = MaterialTheme.typography.titleMedium)
                        }
                        if (l.sellerId == uid) {
                            IconButton(onClick = { selected = null; editListing = l }) {
                                Text("✏️", style = MaterialTheme.typography.titleMedium)
                            }
                            IconButton(onClick = {
                                // Photos are removed only after the document is gone,
                                // so a failed delete keeps a reachable listing.
                                val photos = l.imageUrls.takeIf { it.isNotEmpty() }
                                    ?: listOfNotNull(l.imageUrl.takeIf { !it.isNullOrBlank() })
                                db.collection("listings").document(l.id).delete()
                                    .addOnSuccessListener { deleteListingImages(photos) }
                                selected = null
                            }) {
                                Text("🗑", style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }

                    Column(
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val photos = l.imageUrls.takeIf { it.isNotEmpty() }
                            ?: listOfNotNull(l.imageUrl.takeIf { it.isNotBlank() })
                        if (photos.isNotEmpty()) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(220.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .clickable { photoViewer = photos to 0 }
                            ) {
                                RemoteImage(photos.first(), Modifier.fillMaxSize(), targetPx = 1024)
                            }
                            if (photos.size > 1) {
                                Row(
                                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    photos.drop(1).forEachIndexed { idx, url ->
                                        RemoteImage(
                                            url = url,
                                            targetPx = 400,
                                            modifier = Modifier
                                                .size(72.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable { photoViewer = photos to idx + 1 }
                                        )
                                    }
                                }
                            }
                        }

                        Card(
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(
                                    l.title,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "₱%.2f".format(l.price),
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = BrandBlue
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "${l.town} • ${l.category} • 👁 ${l.views} views",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = BrandMuted
                                )
                                if (l.description.isNotBlank()) {
                                    Spacer(Modifier.height(8.dp))
                                    Text(l.description)
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Pickup: ${coordinatesLabel(l) ?: "not set yet"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BrandMuted
                                )
                                if (l.isPerishable && l.expiresAt > 0) {
                                    Text(
                                        "⏳ Expires ${formatExpiry(l.expiresAt)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF8A6D00)
                                    )
                                }
                            }
                        }

                        Card(
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier
                                            .size(40.dp)
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(BrandSky),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            l.sellerName.take(1).uppercase(),
                                            color = BrandBlue,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(l.sellerName, fontWeight = FontWeight.Bold)
                                        Text(
                                            "Seller • ${l.contact}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = BrandMuted
                                        )
                                    }
                                    SellerRatingSummary(l.sellerId)
                                }
                                if (l.sellerId != uid) {
                                    SellerReviews(l.sellerId)
                                    TextButton(onClick = {
                                        reviewsSeller = l.sellerId to l.sellerName
                                        selected = null
                                        overlay = "reviews"
                                    }) { Text("See all reviews") }
                                }
                            }
                        }
                    }

                    // Bottom actions, as in the mockup: the main action in blue.
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(Color.White)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (l.sellerId != uid) {
                            Button(
                                onClick = {
                                    addToCart(db, uid, l, displayName) { err ->
                                        Toast.makeText(
                                            context, err ?: "Added to cart ✅", Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                },
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth().height(50.dp)
                            ) { Text("Add to Cart", fontWeight = FontWeight.Bold) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (l.sellerId != uid) {
                                OutlinedButton(
                                    onClick = {
                                        chatListing = l
                                        chatWith = l.sellerId to l.sellerName
                                        selected = null
                                        overlay = "chat"
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.weight(1f)
                                ) { Text("Chat with seller") }
                            }
                            if (sellerLat != null && sellerLng != null) {
                                OutlinedButton(
                                    onClick = { selected = null; mapListing = l },
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.weight(1f)
                                ) { Text("View Map") }
                            } else if (l.sellerId == uid) {
                                OutlinedButton(
                                    onClick = { selected = null; mapListing = l },
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.weight(1f)
                                ) { Text("Set pickup location") }
                            }
                        }
                        if (sellerLat != null && sellerLng != null) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        openDirections(
                                            context,
                                            LatLng(sellerLat, sellerLng),
                                            l.title
                                        )
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.weight(1f)
                                ) { Text("Directions") }
                                TextButton(onClick = { selected = null; webMapListing = l }) {
                                    Text("Full map")
                                }
                            }
                        }
                        if (l.sellerId != uid) {
                            TextButton(onClick = { reportListing = l }) {
                                Text("Report this listing", color = Color(0xFFE53935))
                            }
                        }
                    }
                }
            }
        }
    }

    // Option A: native Google Map inside the app.
    mapListing?.let { l ->
        SellerMapDialog(
            listing = l,
            onDismiss = { mapListing = null },
            onSaveLocation = { point ->
                saveListingLocation(db, l.id, point) { }
                mapListing = null
            },
            onOpenWebMap = { mapListing = null; webMapListing = l }
        )
    }

    // Full-screen A→B route planner on the Leaflet map.
    if (showCompare) {
        RouteCompareDialog(onDismiss = { showCompare = false })
    }

    // Play requirement: the user can delete their account and data.
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

    // All seller pins on one map (search bar + A→B route).
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

    // Full-screen Leaflet map (OpenStreetMap) for one listing.
    webMapListing?.let { l ->
        LeafletMapDialog(
            latitude = l.latitude ?: CamiguinCenter.latitude,
            longitude = l.longitude ?: CamiguinCenter.longitude,
            label = l.title.ifBlank { "Seller Pickup Location" },
            sellers = filtered,
            onDismiss = { webMapListing = null }
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
 * Post a new listing, or edit an existing listing owned by the user.
 *
 * When `existing` is given, the fields are prefilled and that
 * document is updated instead of creating a new one. `sellerId`, `sellerName`, and
 * the original `createdAt` never change — that is what the rules allow.
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

    // Perishable items must carry an expiration; the listing is hidden after it.
    var isPerishable by remember { mutableStateOf(existing?.isPerishable ?: false) }
    var expiresAt by remember { mutableStateOf(existing?.expiresAt ?: 0L) }

    // Listing photo: picked from the gallery, uploaded to the Worker.
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var imageUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var previews by remember { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var uploading by remember { mutableStateOf(false) }
    var uploadError by remember { mutableStateOf<String?>(null) }

    // Multiple photos can be picked (max 6).
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
                // When editing, the owner of this listing cannot change.
                sellerId = existing?.sellerId ?: uid,
                sellerName = existing?.sellerName ?: sellerName,
                contact = contact.trim(),
                createdAt = existing?.createdAt?.takeIf { it > 0 }
                    ?: System.currentTimeMillis(),
                latitude = pickup?.latitude, longitude = pickup?.longitude,
                imageUrl = imageUrls.firstOrNull() ?: "",
                imageUrls = imageUrls,
                isPerishable = isPerishable,
                expiresAt = if (isPerishable) expiresAt else 0L
            )
        ).addOnSuccessListener {
            // Replaced photos are no longer referenced: drop them from R2.
            // Only after the save, so a failed update never loses a photo.
            if (existing != null) deleteListingImages(existingPhotos - imageUrls.toSet())
        }
        onClose()
    }

    val canPost = !uploading && title.isNotBlank() &&
        price.toDoubleOrNull() != null && contact.isNotBlank() &&
        (!isPerishable || expiresAt > System.currentTimeMillis())

    fun submit() {
        val picked = imageUris
        val baseUrl = BuildConfig.R2_UPLOAD_URL
        when {
            // No new photo: keep the old ones (when editing).
            picked.isEmpty() -> saveListing(existingPhotos)

            baseUrl.isBlank() -> {
                uploadError = "R2_UPLOAD_URL is not set (local.properties or a Gradle property)."
            }

            else -> {
                val user = FirebaseAuth.getInstance().currentUser
                if (user == null) {
                    uploadError = "You must be signed in to upload."
                } else {
                    uploading = true
                    uploadError = null
                    user.getIdToken(false)
                        .addOnSuccessListener { tokenResult ->
                            val token = tokenResult.token
                            if (token.isNullOrBlank()) {
                                uploading = false
                                uploadError = "Couldn't get the Firebase ID token."
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
                                                ?: "Photo upload failed."
                                        }
                                }
                            }
                        }
                        .addOnFailureListener { e ->
                            uploading = false
                            uploadError = e.localizedMessage
                                ?: "Failed to get the ID token."
                        }
                }
            }
        }
    }

    Dialog(
        onDismissRequest = { if (!uploading) onClose() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(BrandHeaderBrush)
                        .statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { if (!uploading) onClose() }) {
                        Text("←", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    }
                    Text(
                        if (existing == null) "Sell an item" else "Edit listing",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                OutlinedTextField(title, { title = it }, label = { Text("Product name") })
                OutlinedTextField(desc, { desc = it }, label = { Text("Description") })
                OutlinedTextField(
                    price, { price = it }, label = { Text("Price (₱)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(
                    contact, { contact = it }, label = { Text("Contact (cellphone)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                )
                Text("Category", style = MaterialTheme.typography.labelMedium)
                ChipRow(categories.drop(1), category) { category = it }
                Text("Town", style = MaterialTheme.typography.labelMedium)
                ChipRow(towns, town) { town = it }

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = isPerishable,
                        onCheckedChange = { checked ->
                            isPerishable = checked
                            if (!checked) expiresAt = 0L
                        }
                    )
                    Text("Perishable (spoils quickly)")
                }
                if (isPerishable) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Text(
                                "Expiration date and time (required)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (expiresAt > 0) "Expires: ${formatExpiry(expiresAt)}"
                                else "No expiration set yet.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            OutlinedButton(
                                onClick = {
                                    pickExpiryDateTime(context, expiresAt) { expiresAt = it }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("🗓️ Set expiration") }
                            if (expiresAt in 1..System.currentTimeMillis()) {
                                Text(
                                    "Choose a time in the future.",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Text(
                                "The listing leaves the market automatically " +
                                    "once this time is reached.",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("Pickup location", style = MaterialTheme.typography.labelMedium)
                Text(
                    pickup?.let { "Picked: ${formatCoordinates(it)}" }
                        ?: "No pickup point selected yet."
                )
                OutlinedButton(
                    onClick = { showPicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("📌 Pick a pickup location on the Map") }

                Spacer(Modifier.height(8.dp))
                Text("Photos", style = MaterialTheme.typography.labelMedium)
                when {
                    previews.isNotEmpty() -> Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        previews.forEach { bmp ->
                            Image(
                                bitmap = bmp,
                                contentDescription = "New listing photo",
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
                        "No photos yet.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (imageUris.isNotEmpty()) {
                    Text(
                        "${imageUris.size} new photo(s) will replace the old ones.",
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
                            "🖼️ Replace photos" else "📷 Choose photos (max 6)"
                    )
                }
                if (previews.isNotEmpty()) {
                    TextButton(
                        onClick = { imageUris = emptyList(); previews = emptyList() },
                        enabled = !uploading
                    ) { Text("Remove the new photos") }
                }
                if (uploading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        "Uploading photos (${imageUris.size})…",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                uploadError?.let {
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
            }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color.White)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onClose,
                        enabled = !uploading,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("Cancel") }
                    Button(
                        enabled = canPost,
                        onClick = { submit() },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(2f).height(50.dp)
                    ) {
                        Text(
                            if (existing == null) "Post listing" else "Save changes",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    if (showPicker) {
        PickupLocationPickerDialog(
            initial = pickup,
            onPicked = { pickup = it },
            onDismiss = { showPicker = false }
        )
    }
}

/** Decode the selected gallery photo (no extra library). */
fun decodeImageBitmap(context: Context, uri: Uri): ImageBitmap? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream)?.asImageBitmap()
    }
}.getOrNull()

/** Decoded photos kept in memory so scrolling never downloads or decodes twice. */
private val imageCache = object : LruCache<String, ImageBitmap>(32) {}

/** Decode JPEG/PNG bytes at roughly [targetPx] on the longest edge. */
private fun decodeDownsampled(bytes: ByteArray, targetPx: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetPx && bounds.outHeight / (sample * 2) >= targetPx) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
}

/**
 * Listing photos come from Cloudflare R2 (through our Worker).
 *
 * The bitmap is downsampled while decoding (to about [targetPx] on the long
 * edge) and kept in an in-memory cache, so a 5 MB phone photo never becomes a
 * full-size bitmap inside a small row, and scrolling back is instant.
 */
@Composable
fun RemoteImage(url: String, modifier: Modifier = Modifier, targetPx: Int = 512) {
    val key = "$targetPx|$url"
    var bitmap by remember(key) { mutableStateOf(imageCache.get(key)) }

    LaunchedEffect(key) {
        if (url.isBlank()) {
            bitmap = null
            return@LaunchedEffect
        }
        imageCache.get(key)?.let { cached ->
            bitmap = cached
            return@LaunchedEffect
        }
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 20_000
                val bytes = conn.inputStream.use { it.readBytes() }
                decodeDownsampled(bytes, targetPx)
            }.getOrNull()
        }
        if (loaded != null) imageCache.put(key, loaded)
        bitmap = loaded
    }

    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = "Listing photo",
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    }
}

/**
 * Downscale and re-encode a picked photo before uploading it.
 *
 * Gallery photos are 3–8 MB at 4000 px; resized to [maxPx] and JPEG
 * [quality] they are usually 150–400 KB, which is what makes the listing list
 * load quickly on mobile data.
 */
fun compressForUpload(
    context: Context,
    uri: Uri,
    maxPx: Int = 1600,
    quality: Int = 80
): ByteArray {
    val original = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: error("Can't read the selected photo.")
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(original, 0, original.size, bounds)
    if (bounds.outWidth <= maxPx && bounds.outHeight <= maxPx && original.size <= 400_000) {
        return original
    }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= maxPx || bounds.outHeight / (sample * 2) >= maxPx) {
        sample *= 2
    }
    val decoded = BitmapFactory.decodeByteArray(
        original, 0, original.size,
        BitmapFactory.Options().apply { inSampleSize = sample }
    ) ?: return original
    val longest = maxOf(decoded.width, decoded.height)
    val scaled = if (longest > maxPx) {
        val scale = maxPx.toFloat() / longest
        Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true
        )
    } else decoded
    val out = java.io.ByteArrayOutputStream()
    scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
    if (scaled !== decoded) scaled.recycle()
    return out.toByteArray()
}

/**
 * Upload the photo to the Cloudflare Worker that stores it in R2.
 *
 * The Firebase ID token identifies the user; the Worker builds the R2
 * key from the token's `uid`, so no R2 credentials live in the app.
 */
fun uploadListingImage(
    context: Context,
    baseUrl: String,
    idToken: String,
    uri: Uri
): String {
    // Sent already downscaled, so the Worker and every phone that reads the
    // listing deal with a few hundred KB instead of several MB.
    val bytes = compressForUpload(context, uri)
    val mime = "image/jpeg"

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
 * Play Store requirement: the user can delete their account and data
 * from the app itself. The web version is `docs/account-deletion.html`.
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
        title = { Text("Delete account?") },
        text = {
            Column {
                Text(
                    "This permanently deletes your account, profile, and all your " +
                        "listing. This cannot be undone."
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
            ) { Text(if (deleting) "Deleting…" else "Delete") }
        },
        dismissButton = {
            TextButton(enabled = !deleting, onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Delete all of the user's listings, then the Firebase Auth account.
 *
 * `delete()` requires a fresh login; when the session is old,
 * makadawat kitag [FirebaseAuthRecentLoginRequiredException].
 * `onResult` receives `null` on success, or a message on failure.
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
                    onResult("No signed-in account. Please log in again.")
                } else {
                    user.delete().addOnCompleteListener { task ->
                        if (task.isSuccessful) onResult(null)
                        else onResult(deleteAccountError(task.exception))
                    }
                }
            }

            val deleteListings = {
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
                                "Failed to delete your listings: " +
                                    (task.exception?.localizedMessage ?: "unknown error")
                            )
                        }
                    }
                }
            }

            // Photos first: the Worker needs the ID token, and it is gone once
            // the account is deleted. Best effort, so a failure still lets the
            // account go (the cron purge cleans up what is left).
            val photos = snapshot.documents
                .mapNotNull { it.toObject(Listing::class.java) }
                .flatMap { l ->
                    l.imageUrls.takeIf { it.isNotEmpty() }
                        ?: listOfNotNull(l.imageUrl.takeIf { !it.isNullOrBlank() })
                }
                .distinct()
            deleteListingImages(photos) { deleteListings() }
        }
        .addOnFailureListener { e ->
            onResult(
                "Failed to read your listings: " +
                    (e.localizedMessage ?: "unknown error")
            )
        }
}

private fun deleteAccountError(e: Exception?): String = when (e) {
    is FirebaseAuthRecentLoginRequiredException ->
        "For security, log out and log in again, then retry the deletion."
    else ->
        "Failed to delete the account: " +
            (e?.localizedMessage ?: "unknown error")
}
