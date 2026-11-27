package com.camiguinmarket.app

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.content.pm.PackageManager
import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import android.content.Intent
import android.widget.Toast
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
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
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Query
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
        val type = intent?.getStringExtra("type")
        val target = Companion.parsePushTarget(
            type,
            intent?.getStringExtra("orderId"),
            intent?.getStringExtra("chatId"),
            intent?.getStringExtra("savedSearchId")
        )
        if (BuildConfig.DEBUG) {
            android.util.Log.d(
                "CamiguinPush",
                "intent type=$type orderId=${intent?.getStringExtra("orderId")} " +
                    "chatId=${intent?.getStringExtra("chatId")} => $target"
            )
        }
        return target
    }

    /** Read a deep link (camiguinmarket://listing|order|chat/{id}, or the
     *  matching https://<worker>/... app link) from the intent. */
    private fun readDeepLinkTarget(intent: Intent?): String? {
        val uri = intent?.data ?: return null
        // Custom scheme: camiguinmarket://<host>/<id>
        if (uri.scheme == "camiguinmarket") {
            val host = uri.host
            val id = uri.lastPathSegment?.let { android.net.Uri.decode(it) }
            return Companion.parseCustomSchemeHost(host, id)
        }
        // Verified HTTPS app links on the worker origin.
        if (uri.scheme == "https" && uri.host == "camiguin-market-images.bernavicg1.workers.dev") {
            val segs = uri.pathSegments
            if (segs.size >= 2) {
                val kind = segs[0]
                val id = android.net.Uri.decode(segs[1])
                return Companion.parseHttpsAppLinkKind(kind, id)
            }
        }
        return null
    }

    companion object {
        /** Testable parsing of a notification push target. */
        fun parsePushTarget(
            type: String?,
            orderId: String?,
            chatId: String?,
            savedSearchId: String?
        ): String? = when (type) {
            "order" -> orderId?.takeIf { it.isNotBlank() }?.let { "order:$it" }
            "chat" -> chatId?.takeIf { it.isNotBlank() }?.let { "chat:$it" }
            "saved", "saved_search" -> savedSearchId?.takeIf { it.isNotBlank() }?.let { "saved:$it" }
            else -> null
        }

        /** Testable parsing of a camiguinmarket://<host>/<id> deep link. */
        fun parseCustomSchemeHost(host: String?, id: String?): String? {
            if (host == null || id.isNullOrBlank()) return null
            return when (host) {
                "listing" -> "listing:$id"
                "order" -> "order:$id"
                "chat" -> "chat:$id"
                else -> null
            }
        }

        /** Testable parsing of an https app-link path on the worker origin. */
        fun parseHttpsAppLinkKind(kind: String?, id: String?): String? {
            if (kind == null || id.isNullOrBlank()) return null
            return when (kind) {
                "listing" -> "listing:$id"
                "order" -> "order:$id"
                "chat" -> "chat:$id"
                else -> null
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pushTarget = readPushTarget(intent) ?: readDeepLinkTarget(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pushTarget = readPushTarget(intent) ?: readDeepLinkTarget(intent)
        // Target SDK 36 enforces edge-to-edge, so enable the window properly
        // so the system bars do not cover the content.
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            var dark by remember { mutableStateOf(loadDarkMode(context)) }
            var lang by remember { mutableStateOf(loadLanguage(context)) }
            val strings = if (lang == "ceb") CebStrings else EnStrings
            MaterialTheme(
                colorScheme = if (dark) marketDarkColorScheme else marketColorScheme,
                shapes = marketShapes
            ) {
                CompositionLocalProvider(LocalStrings provides strings) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        AppRoot(
                            dark = dark,
                            language = lang,
                            pushTarget = pushTarget,
                            onPushHandled = { pushTarget = null },
                            onToggleDark = { value ->
                                dark = value
                                saveDarkMode(context, value)
                            },
                            onToggleLanguage = { newLang ->
                                lang = newLang
                                saveLanguage(context, newLang)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppRoot(
    dark: Boolean,
    language: String,
    pushTarget: String?,
    onPushHandled: () -> Unit,
    onToggleDark: (Boolean) -> Unit,
    onToggleLanguage: (String) -> Unit
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
            language = language,
            pushTarget = pushTarget,
            onPushHandled = onPushHandled,
            onToggleDark = onToggleDark,
            onToggleLanguage = onToggleLanguage
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
                // Validate before calling Firebase: blank fields make
                // createUserWithEmailAndPassword throw (not return a Task).
                val validationError = when {
                    isRegister && name.isBlank() -> "Name is required."
                    email.isBlank() -> "Email is required."
                    password.isBlank() -> "Password is required."
                    isRegister && password.length < 6 -> "Password must be at least 6 characters."
                    else -> null
                }
                if (validationError != null) { loading = false; error = validationError }
                else try {
                val task = if (isRegister)
                    auth.createUserWithEmailAndPassword(email.trim(), password)
                else auth.signInWithEmailAndPassword(email.trim(), password)
                task.addOnCompleteListener { t ->
                    loading = false
                    if (t.isSuccessful) {
                        try {
                            if (isRegister && name.isNotBlank()) {
                                val u = auth.currentUser
                                if (u != null) {
                                    u.updateProfile(
                                        com.google.firebase.auth.UserProfileChangeRequest.Builder()
                                            .setDisplayName(name.trim()).build()
                                    ).addOnCompleteListener {
                                        try { u.sendEmailVerification() } catch (_: Exception) { }
                                        onDone()
                                    }
                                } else {
                                    onDone()
                                }
                            } else onDone()
                        } catch (e: Exception) {
                            error = e.localizedMessage ?: "Account creation failed"
                        }
                    } else error = t.exception?.localizedMessage ?: "Sign-in failed"
                }
                } catch (e: Exception) {
                    loading = false
                    error = e.localizedMessage ?: "Account creation failed"
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
    language: String = "en",
    pushTarget: String?,
    onPushHandled: () -> Unit,
    onToggleDark: (Boolean) -> Unit,
    onToggleLanguage: (String) -> Unit,
    onLogout: () -> Unit
) {
    val db = remember { FirebaseFirestore.getInstance() }
    val context = LocalContext.current
    val S = LocalStrings.current
    var listings by remember { mutableStateOf(loadListingsCache(context)) }
    var offline by remember { mutableStateOf(false) }
    var nearbyRadius by remember { mutableStateOf<Int?>(null) }
    var userLat by remember { mutableStateOf<Double?>(null) }
    var userLng by remember { mutableStateOf<Double?>(null) }
    var category by remember { mutableStateOf(categories[0]) }
    var town by remember { mutableStateOf("All") }
    var query by remember { mutableStateOf("") }
    var showPost by remember { mutableStateOf(false) }
    // The listing being edited by the owner (null = none).
    var editListing by remember { mutableStateOf<Listing?>(null) }
    var selected by remember { mutableStateOf<Listing?>(null) }
    var sellerInfoListing by remember { mutableStateOf<Listing?>(null) }
    var mapListing by remember { mutableStateOf<Listing?>(null) }
    var showAllMap by remember { mutableStateOf(false) }
    var showCompare by remember { mutableStateOf(false) }
    var showDeleteAccount by remember { mutableStateOf(false) }
    val auth = remember { FirebaseAuth.getInstance() }
    val scope = rememberCoroutineScope()
    var photoUrl by remember { mutableStateOf(auth.currentUser?.photoUrl?.toString()) }

    // Notification permission is requested lazily (save search / checkout).

    val locationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            manager?.let { m ->
                val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { m.isProviderEnabled(it) }
                providers.forEach { p ->
                    try { m.requestSingleUpdate(p, object : android.location.LocationListener {
                        override fun onLocationChanged(loc: Location) {
                            userLat = loc.latitude; userLng = loc.longitude
                            try { m.removeUpdates(this) } catch (_: Exception) { }
                        }
                        override fun onProviderEnabled(p: String) { }
                        override fun onProviderDisabled(p: String) { }
                        @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) { }
                    }, android.os.Looper.getMainLooper()) } catch (_: Exception) { }
                }
            }
        }
    }

    // Keep a public profile document so other users (e.g. buyers) can view
    // the seller's name, photo, phone and email from a listing.
    LaunchedEffect(uid, displayName, photoUrl) {
        db.collection("users").document(uid).set(
            mapOf(
                "uid" to uid,
                "name" to displayName,
                "email" to (auth.currentUser?.email ?: ""),
                "photoUrl" to (photoUrl ?: "")
            ),
            SetOptions.merge()
        )
    }

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
    val blockedSellers = loadBlockedSellers(db, uid)
    var chatWith by remember { mutableStateOf<Pair<String, String>?>(null) }
    var chatListing by remember { mutableStateOf<Listing?>(null) }
    var chatListingId by remember { mutableStateOf("") }
    var chatListingTitle by remember { mutableStateOf("") }
    var chatSellerUid by remember { mutableStateOf("") }
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

            "listing" -> db.collection("listings").document(id).get()
                .addOnSuccessListener { d ->
                    d.toObject(Listing::class.java)?.let { l ->
                        openListing(l.copy(id = d.id, category = normalizeCategory(l.category)))
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
                        chatListingId = d.getString("listingId") ?: ""
                        chatListingTitle = d.getString("listingTitle") ?: ""
                        chatSellerUid = other
                        chatWith = other to (names?.get(other) as? String ?: "User")
                        overlay = "chat"
                        if (BuildConfig.DEBUG) {
                            android.util.Log.d("CamiguinPush", "chat opened: $id (with $other)")
                        }
                    }
                }

            "saved" -> {
                // Open the saved searches screen; the id is informational.
                overlay = "saved"
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("CamiguinPush", "saved searches opened from $id")
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

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // FCM: save the token so the server can send push notifications.
    LaunchedEffect(uid) {
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                db.collection("users").document(uid).collection("tokens").document(token)
                    .set(mapOf("token" to token, "updatedAt" to System.currentTimeMillis()))
            }
    }

    // Notification permission is requested lazily (save search / checkout).

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
            .addSnapshotListener { snap, err ->
                if (snap != null) {
                    listings = snap.documents.mapNotNull { d ->
                        d.toObject(Listing::class.java)?.let { l ->
                            l.copy(id = d.id, category = normalizeCategory(l.category))
                        }
                    }.filterNot { it.isExpired() }
                    saveListingsCache(context, listings)
                    offline = false
                } else {
                    offline = err != null
                }
            }
        onDispose { reg.remove() }
    }

    val filtered = applyNearbyFilter(
        listings.filter {
            !isBlocked(blockedSellers.value, it.sellerId) &&
            (category == "All" || it.category == category) &&
                (town == "All" || it.town == town) &&
                matchesPrice(it.price, priceFilter) &&
                (query.isBlank() || it.title.contains(query, true) || it.description.contains(query, true) || it.sellerName.contains(query, true) || it.town.contains(query, true))
        },
        userLat, userLng, nearbyRadius
    ).let { list -> sortListings(list, sort) }

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
                if (offline) {
                    Text(
                        S.offlineBanner,
                        style = MaterialTheme.typography.labelSmall,
                        color = BrandGold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
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
                ExtendedFloatingActionButton(onClick = { showPost = true }) { Text("+ ${S.sell}") }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (overlay) {
                "favorites" -> FavoritesScreen(uid) { id ->
                    overlay = null
                    listings.firstOrNull { it.id == id }?.let { selected = it }
                }

                "notifications" -> NotificationsScreen(uid) { otherUid, otherName, listingTitle, listingId ->
                    chatListing = null
                    chatListingId = listingId
                    chatListingTitle = listingTitle
                    chatSellerUid = otherUid
                    chatWith = otherUid to otherName
                    overlay = "chat"
                }

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

                "blocked" -> BlockedSellersScreen(uid) { overlay = null }

                "verification" -> AdminVerificationScreen(uid, isAdmin) { overlay = null }

                "verify" -> Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { overlay = null }) { Text("← Back") }
                        Text(
                            "Phone verification",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    PhoneVerifySection(uid)
                }

                "order" -> orderDetail?.let { order ->
                    OrderDetailScreen(
                        order = order,
                        uid = uid,
                        onChat = { otherId, otherName ->
                            chatListing = null
                            chatListingId = order.listingId
                            chatListingTitle = order.listingTitle
                            chatSellerUid = otherId
                            chatWith = otherId to otherName
                            overlay = "chat"
                        },
                        onRate = { ratingOrder = it }
                    )
                }

                "inbox" -> InboxScreen(uid) { thread ->
                    chatListing = null
                    chatListingId = thread.listingId
                    chatListingTitle = thread.listingTitle
                    chatSellerUid = thread.otherUid
                    chatWith = thread.otherUid to thread.otherName
                    overlay = "chat"
                }

                "chat" -> chatWith?.let { (otherId, otherName) ->
                    ChatScreen(uid, otherId, otherName, chatListingTitle, chatSellerUid, chatListingId)
                }

                else -> when (tab) {
                    MarketTab.Home -> Column(Modifier.fillMaxSize()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            listOf(5, 10, 25).forEach { r ->
                                FilterChip(
                                    selected = nearbyRadius == r,
                                    onClick = {
                                        if (nearbyRadius == r) {
                                            nearbyRadius = null
                                        } else {
                                            nearbyRadius = r
                                            val granted = androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                                            if (!granted) {
                                                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                            }
                                        }
                                    },
                                    label = { Text("${r} km") }
                                )
                            }
                            if (shouldPromptForNearbyLocation(nearbyRadius, userLat, userLng)) {
                                Text(S.enableLocationPrompt, style = MaterialTheme.typography.labelSmall, color = BrandGold)
                            }
                        }
                        HomeTab(
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
                        onCheckout = { items, checkoutId, paymentMethod ->
                            requestNotificationPermission()
                            checkoutCart(db, uid, displayName, items, checkoutId, paymentMethod) { err ->
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
                        photoUrl = photoUrl,
                        onPickPhoto = { uri ->
                            val u = auth.currentUser
                            if (u != null) {
                                u.getIdToken(false).addOnSuccessListener { tokenResult ->
                                    val token = tokenResult.token
                                    if (token.isNullOrBlank()) {
                                        Toast.makeText(context, "Couldn't get auth token", Toast.LENGTH_LONG).show()
                                    } else {
                                        scope.launch {
                                            try {
                                                val url = withContext(Dispatchers.IO) {
                                                    uploadListingImage(context, BuildConfig.R2_UPLOAD_URL, token, uri)
                                                }
                                                u.updateProfile(
                                                    com.google.firebase.auth.UserProfileChangeRequest.Builder()
                                                        .setPhotoUri(android.net.Uri.parse(url)).build()
                                                ).addOnCompleteListener {
                                                    photoUrl = url
                                                    db.collection("users").document(uid).set(
                                                        mapOf("photoUrl" to url), SetOptions.merge()
                                                    )
                                                    Toast.makeText(context, "Photo updated", Toast.LENGTH_SHORT).show()
                                                }
                                            } catch (e: Exception) {
                                                Toast.makeText(context, e.localizedMessage ?: "Upload failed", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    }
                                }.addOnFailureListener { e ->
                                    Toast.makeText(context, e.localizedMessage ?: "Auth failed", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        myListings = listings.filter { it.sellerId == uid },
                        onFavorites = { overlay = "favorites" },
                        isAdmin = isAdmin,
                        dark = dark,
                        language = language,
                        onToggleDark = onToggleDark,
                        onToggleLanguage = onToggleLanguage,
                        onOrders = { overlay = "orders" },
                        onInbox = { overlay = "inbox" },
                        onModeration = { overlay = "moderation" },
                        onSavedSearches = { overlay = "saved" },
                        onBlocked = { overlay = "blocked" },
                        onVerification = { overlay = "verification" },
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

    if (showPost) PostDialog(
        uid = uid, sellerName = displayName, db = db,
        onOpenVerification = { showPost = false; overlay = "verify" }
    ) { showPost = false }
    editListing?.let { l ->
        PostDialog(
            uid = uid, sellerName = displayName, db = db, existing = l,
            onOpenVerification = { editListing = null; overlay = "verify" }
        ) { editListing = null }
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
                        // Share the card image (some apps drop the accompanying text).
                        IconButton(onClick = {
                            scope.launch {
                                try {
                                    val bitmap = renderShareCard(l)
                                    val file = java.io.File(context.cacheDir, "share_card_${l.id}.png")
                                    java.io.FileOutputStream(file).use { out -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out) }
                                    val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                    val share = Intent(Intent.ACTION_SEND).apply {
                                        type = "image/png"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        putExtra(Intent.EXTRA_TEXT, shareListingText(l))
                                    }
                                    context.startActivity(Intent.createChooser(share, "Share listing"))
                                } catch (e: Exception) {
                                    val share = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, shareListingText(l))
                                    }
                                    context.startActivity(Intent.createChooser(share, "Share listing"))
                                }
                            }
                        }) {
                            Text("↗", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                        }
                        // Reliable text-only share (title, price, contact, deep link)
                        // that survives apps which drop text attached to an image.
                        IconButton(onClick = {
                            val share = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, shareListingText(l))
                            }
                            context.startActivity(Intent.createChooser(share, "Share listing"))
                        }) {
                            Text("🔗", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                        }
                        if (l.sellerId == uid) {
                            IconButton(onClick = { selected = null; editListing = l }) {
                                Text("✏️", style = MaterialTheme.typography.headlineSmall)
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
                                Text("🗑", style = MaterialTheme.typography.headlineSmall)
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
                                    Text(S.description, fontWeight = FontWeight.Bold)
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
                                val sellerVerification = loadSellerVerification(db, l.sellerId)
                                Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { sellerInfoListing = l }
                            ) {
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
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(l.sellerName, fontWeight = FontWeight.Bold)
                                            Spacer(Modifier.width(6.dp))
                                            VerifiedBadge(
                                                phoneVerified = sellerVerification.value.phoneVerified,
                                                verified = sellerVerification.value.verified,
                                                compact = true
                                            )
                                        }
                                        Text(
                                            "Seller • ${l.contact}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = BrandMuted
                                        )
                                        Text(
                                            "Tap to view seller info",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = BrandBlue
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
                                    }) { Text(S.seeAllReviews) }
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
                            ) { Text(S.addToCart, fontWeight = FontWeight.Bold) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (l.sellerId != uid) {
                                OutlinedButton(
                                    onClick = {
                                        chatListing = l
                                        chatListingId = l.id
                                        chatListingTitle = l.title
                                        chatSellerUid = l.sellerId
                                        chatWith = l.sellerId to l.sellerName
                                        selected = null
                                        overlay = "chat"
                                    },
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.weight(1f)
                                ) { Text(S.chatWithSeller) }
                            }
                            if (sellerLat != null && sellerLng != null) {
                                OutlinedButton(
                                    onClick = { selected = null; mapListing = l },
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.weight(1f)
                                ) { Text(S.viewOnMap) }
                            } else if (l.sellerId == uid) {
                                OutlinedButton(
                                    onClick = { selected = null; mapListing = l },
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.weight(1f)
                                ) { Text(S.setPickupLocation) }
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
                                ) { Text(S.directions) }
                            }
                        }
                        if (l.sellerId != uid) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { reportListing = l }) {
                                    Text(S.report, color = Color(0xFFE53935))
                                }
                                TextButton(onClick = {
                                    blockSeller(db, uid, l.sellerId, l.sellerName) { err ->
                                        Toast.makeText(
                                            context,
                                            err ?: "Seller blocked.",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                }) {
                                    Text("Block seller", color = Color(0xFFE53935))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    sellerInfoListing?.let { l ->
        SellerInfoDialog(
            sellerId = l.sellerId,
            fallbackName = l.sellerName,
            fallbackContact = l.contact,
            onDismiss = { sellerInfoListing = null }
        )
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
    onOpenVerification: () -> Unit = {},
    onClose: () -> Unit
) {
    val S = LocalStrings.current
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

    // Soft trust nudges: surface verification + reputation status to the seller.
    val sellerVerification = loadSellerVerification(db, uid)
    val sellerReputation = loadSellerReputation(db, uid)

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
                // Soft nudges (non-blocking): encourage verification and warn low ratings.
                if (!sellerVerification.value.phoneVerified) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                S.verifyPhoneNudge,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = {
                                if (!uploading) {
                                    onClose()
                                    onOpenVerification()
                                }
                            }) { Text("Verify phone") }
                        }
                    }
                }
                if (sellerReputation.value.belowThreshold) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                                                    Text(
                            S.lowRatingWarning,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFE53935),
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
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
                            ) { Text("🗓️ ${S.setExpiration}") }
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
                Text(S.pickupLocation, style = MaterialTheme.typography.labelMedium)
                Text(
                    pickup?.let { "Picked: ${formatCoordinates(it)}" }
                        ?: "No pickup point selected yet."
                )
                OutlinedButton(
                    onClick = { showPicker = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("📌 ${S.pickPickupLocationOnMap}") }

                Spacer(Modifier.height(8.dp))
                Text(S.photos, style = MaterialTheme.typography.labelMedium)
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
                        onClick = {
                            if (RateLimiter.allow(context, uid, "post", 10)) submit()
                            else uploadError = "Too many posts. Try again later."
                        },
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

/** Decoded photos kept in memory, bounded by ~8 MB so a long scroll can't OOM. */
private val imageCache = object : LruCache<String, ImageBitmap>(8 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int =
        (value.width * value.height * 4) / 1024
}

/** Keep the on-disk image cache under ~50 MB by evicting the oldest files. */
private fun evictDiskCache(dir: File, maxBytes: Long = 50L * 1024 * 1024) {
    val files = dir.listFiles() ?: return
    var total = files.sumOf { it.length() }
    if (total <= maxBytes) return
    files.sortedBy { it.lastModified() }.forEach { f ->
        if (total <= maxBytes) return
        total -= f.length()
        f.delete()
    }
}

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
fun SellerInfoDialog(
    sellerId: String,
    fallbackName: String,
    fallbackContact: String,
    onDismiss: () -> Unit
) {
    val db = remember { FirebaseFirestore.getInstance() }
    val ctx = LocalContext.current
    val S = LocalStrings.current
    var name by remember { mutableStateOf(fallbackName.ifBlank { "Seller" }) }
    var photoUrl by remember { mutableStateOf<String?>(null) }
    var phone by remember { mutableStateOf<String?>(null) }
    var email by remember { mutableStateOf<String?>(null) }
    var phoneVerified by remember { mutableStateOf(false) }
    var communityVerified by remember { mutableStateOf(false) }
    var telegram by remember { mutableStateOf<String?>(null) }
    var messenger by remember { mutableStateOf<String?>(null) }
    var whatsapp by remember { mutableStateOf<String?>(null) }
    val reputation = loadSellerReputation(db, sellerId)
    LaunchedEffect(sellerId) {
        if (sellerId.isBlank()) return@LaunchedEffect
        db.collection("users").document(sellerId).get()
            .addOnSuccessListener { d ->
                d.getString("name")?.takeIf { it.isNotBlank() }?.let { name = it }
                photoUrl = d.getString("photoUrl")?.takeIf { it.isNotBlank() }
                phone = d.getString("phone")?.takeIf { it.isNotBlank() }
                email = d.getString("email")?.takeIf { it.isNotBlank() }
                phoneVerified = d.getBoolean("phoneVerified") ?: false
                communityVerified = d.getBoolean("verified") ?: false
                telegram = d.getString("telegram")?.takeIf { it.isNotBlank() }
                messenger = d.getString("messenger")?.takeIf { it.isNotBlank() }
                whatsapp = d.getString("whatsapp")?.takeIf { it.isNotBlank() }
            }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                VerifiedBadge(phoneVerified = phoneVerified, verified = communityVerified, compact = false)
            }
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(CircleShape)
                        .background(BrandSky),
                    contentAlignment = Alignment.Center
                ) {
                    if (!photoUrl.isNullOrBlank()) {
                        RemoteImage(photoUrl!!, Modifier.fillMaxSize())
                    } else {
                        Text(
                            name.take(1).uppercase(),
                            color = BrandBlue,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (reputation.value.count > 0) {
                    Text(
                        S.reputationLabel.format(
                            reputation.value.average, reputation.value.count,
                            if (reputation.value.belowThreshold) "  ⚠️ low" else ""
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (reputation.value.belowThreshold) Color(0xFFE53935) else BrandMuted
                    )
                }
                if (!phone.isNullOrBlank()) Text("Phone: $phone")
                if (!email.isNullOrBlank()) Text("Email: $email")
                if (fallbackContact.isNotBlank()) Text("Contact: $fallbackContact")
                if (!telegram.isNullOrBlank()) {
                    TextButton(onClick = {
                        val u = "https://t.me/${normalizeTelegramLink(telegram!!)}"
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                    }) { Text("Open Telegram") }
                }
                if (!messenger.isNullOrBlank()) {
                    TextButton(onClick = {
                        val u = "https://m.me/${normalizeMessengerLink(messenger!!)}"
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                    }) { Text("Open Messenger") }
                }
                if (!whatsapp.isNullOrBlank()) {
                    TextButton(onClick = {
                        val num = normalizeWhatsAppLink(whatsapp!!)
                        val u = "https://wa.me/$num"
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                    }) { Text("Open WhatsApp") }
                }
                if (phone.isNullOrBlank() && email.isNullOrBlank() && fallbackContact.isBlank() &&
                    telegram.isNullOrBlank() && messenger.isNullOrBlank() && whatsapp.isNullOrBlank()
                ) {
                    Text("No contact details available.", color = BrandMuted)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
fun RemoteImage(url: String, modifier: Modifier = Modifier, targetPx: Int = 512) {
    val key = "$targetPx|$url"
    var retry by remember(key) { mutableStateOf(0) }
    var bitmap by remember(key, retry) { mutableStateOf(imageCache.get(key)) }

    val ctx = LocalContext.current
    LaunchedEffect(key, retry) {
        if (url.isBlank()) {
            bitmap = null
            return@LaunchedEffect
        }
        imageCache.get(key)?.let { cached ->
            bitmap = cached
            return@LaunchedEffect
        }
        // Disk cache: survive restarts and airplane mode.
        val diskFile = File(ctx.cacheDir, "imgcache/${url.hashCode()}")
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = if (diskFile.exists()) {
                    diskFile.readBytes()
                } else {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 20_000
                    val b = conn.inputStream.use { it.readBytes() }
                    runCatching { diskFile.parentFile?.mkdirs(); evictDiskCache(diskFile.parentFile!!); diskFile.writeBytes(b) }
                    b
                }
                decodeDownsampled(bytes, targetPx)
            }.getOrNull()
        }
        if (loaded != null) {
            imageCache.put(key, loaded)
            bitmap = loaded
        } else if (retry < 3) {
            // Retry a few times so a transient server failure (e.g. the image
            // service was briefly unavailable) recovers without an app restart.
            delay(1500)
            retry++
        } else {
            bitmap = null
        }
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
