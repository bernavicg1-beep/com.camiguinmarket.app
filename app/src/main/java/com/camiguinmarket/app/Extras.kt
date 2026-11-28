package com.camiguinmarket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Landscape
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.material.icons.filled.Park
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.net.Uri
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.camiguinmarket.app.BuildConfig
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.ContextWrapper
import com.google.firebase.FirebaseException
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.concurrent.TimeUnit
import java.util.Date
import java.util.Locale
import com.google.firebase.firestore.Query

// ---------------------------------------------------------------------------
// Design system from the mockup: blue theme, rounded cards, gold accent.
// ---------------------------------------------------------------------------

// Camiguin Market palette: navy for text and the header edge, ocean blue for the
// actions, orange for the highlights, green for the confirmed/paid states, and
// the Philippine peso for every price.
val BrandNavy = Color(0xFF0B1F3A)
val BrandBlue = Color(0xFF0F76C4)
val BrandBlueDeep = Color(0xFF0B4F8A)
val BrandBlueDark = Color(0xFF083A66)
val BrandSky = Color(0xFFE8F3FC)
val BrandOrange = Color(0xFFF47B20)
val BrandGold = BrandOrange
val BrandGreen = Color(0xFF1E8E4E)
val BrandInk = BrandNavy
val BrandMuted = Color(0xFF5B6B80)
val BrandPage = Color(0xFFF5F9FD)
val BrandBadge = Color(0xFFE53935)

/** Header gradient: navy at the top edge into ocean blue, like the mockups. */
val BrandHeaderBrush: Brush =
    Brush.verticalGradient(listOf(BrandNavy, BrandBlue))

val marketShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp)
)

val marketColorScheme
    @Composable get() = lightColorScheme(
        primary = BrandBlue,
        onPrimary = Color.White,
        primaryContainer = BrandSky,
        onPrimaryContainer = BrandBlueDark,
        secondary = BrandGold,
        onSecondary = Color.White,
        background = BrandPage,
        surface = Color.White,
        surfaceVariant = BrandSky,
        onSurface = BrandInk,
        onSurfaceVariant = BrandMuted
    )

val marketDarkColorScheme
    @Composable get() = darkColorScheme(
        primary = Color(0xFF64B5F6),
        onPrimary = Color(0xFF06213F),
        primaryContainer = Color(0xFF1E3A5F),
        onPrimaryContainer = Color(0xFFD6EBFF),
        secondary = BrandGold,
        onSecondary = Color(0xFF241A00),
        background = Color(0xFF0F172A),
        surface = Color(0xFF111C2E),
        surfaceVariant = Color(0xFF1E293B),
        onSurface = Color(0xFFE6EDF7),
        onSurfaceVariant = Color(0xFFC3CFE0)
    )

private fun themePrefs(context: Context) =
    context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)

fun loadDarkMode(context: Context): Boolean = themePrefs(context).getBoolean("dark", false)

fun saveDarkMode(context: Context, dark: Boolean) {
    themePrefs(context).edit().putBoolean("dark", dark).apply()
}

fun loadLanguage(context: Context): String = themePrefs(context).getString("lang", "en") ?: "en"

fun saveLanguage(context: Context, lang: String) {
    themePrefs(context).edit().putString("lang", lang).apply()
}

// ---------------------------------------------------------------------------
// Saved searches (Firestore: savedSearches/{uid}/items)
// ---------------------------------------------------------------------------

data class SavedSearch(
    val id: String = "",
    val name: String = "",
    val query: String = "",
    val category: String = "All",
    val town: String = "All",
    val createdAt: Long = 0L,
    val lastCheckedAt: Long = 0L
) {
    val label: String get() = query.ifBlank { "$category • $town" }
}

fun saveSearch(
    db: FirebaseFirestore,
    uid: String,
    query: String,
    category: String,
    town: String,
    name: String = ""
) {
    db.collection("savedSearches").document(uid).collection("items").document().set(
        SavedSearch(
            name = name.trim(),
            query = query.trim(),
            category = category,
            town = town,
            createdAt = System.currentTimeMillis(),
            lastCheckedAt = System.currentTimeMillis()
        )
    )
}

fun deleteSavedSearch(db: FirebaseFirestore, uid: String, id: String) {
    db.collection("savedSearches").document(uid).collection("items").document(id).delete()
}

/** App open: notify the user when new listings match a saved search. */
fun checkSavedSearches(
    db: FirebaseFirestore,
    uid: String,
    searches: List<SavedSearch>,
    listings: List<Listing>
) {
    if (searches.isEmpty()) return
    // Only listings newer than the oldest checkpoint can possibly match.
    val oldestCheckpoint = searches.minOf { it.lastCheckedAt }
    val relevant = listings.filter { it.createdAt > oldestCheckpoint }
    if (relevant.isEmpty()) return
    val now = System.currentTimeMillis()
    searches.forEach { s ->
        val update = planSavedSearchUpdate(
            uid = uid,
            search = s,
            storedCheckpoint = s.lastCheckedAt,
            listings = relevant,
            now = now
        ) ?: return@forEach
        // Write the alert (if any) and the checkpoint in a single transaction so
        // a failed notification write leaves the old checkpoint behind; the next
        // run re-decides from the stored checkpoint instead of silently losing
        // the alert. Push delivery stays worker-only (this only writes the doc).
        val savedSearchRef = db.collection("savedSearches").document(uid)
            .collection("items").document(s.id)
        db.runTransaction { tx ->
            if (update.message != null &&
                canDeliverNotification(uid, uid, allowSelfNotification = true)
            ) {
                val notifRef = db.collection("notifications").document(uid)
                    .collection("items").document()
                tx.set(
                    notifRef,
                    AppNotification(
                        fromUid = uid,
                        fromName = "Saved search",
                        message = update.message,
                        createdAt = System.currentTimeMillis()
                    )
                )
            }
            tx.update(savedSearchRef, "lastCheckedAt", update.checkpoint)
            null
        }.addOnSuccessListener {
            // Push is worker-only and best-effort; only after the checkpoint
            // commits so a failed write never notifies about stale matches.
            if (update.message != null) {
                sendPush("saved", savedSearchId = s.id, text = update.message)
            }
        }
    }
}

/** The bottom navigation tabs. */
enum class MarketTab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Home),
    Categories("Categories", Icons.Filled.Apps),
    Sell("Sell", Icons.Filled.Add),
    Cart("Cart", Icons.Filled.ShoppingCart),
    Profile("Profile", Icons.Filled.Person)
}

/** The categories in the grid (with icons). */
val priceFilters = listOf("All", "Under ₱50", "₱50–₱200", "₱200–₱500", "Over ₱500")
val sortOptions = listOf("Newest", "Price ↑", "Price ↓", "Trending")

fun sortListings(list: List<Listing>, sort: String): List<Listing> = when (sort) {
    "Price ↑" -> list.sortedBy { it.price }
    "Price ↓" -> list.sortedByDescending { it.price }
    "Trending" -> list.sortedByDescending { it.views }
    else -> list
}

fun matchesPrice(price: Double, filter: String): Boolean = when (filter) {
    "Under ₱50" -> price < 50
    "₱50–₱200" -> price in 50.0..200.0
    "₱200–₱500" -> price in 200.0..500.0
    "Over ₱500" -> price > 500
    else -> true
}

@Composable
fun FilterBar(
    priceFilter: String,
    onPriceFilter: (String) -> Unit,
    sort: String,
    onSort: (String) -> Unit
) {
    Column {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(priceFilters) { f ->
                FilterChip(
                    selected = f == priceFilter,
                    onClick = { onPriceFilter(f) },
                    label = { Text(f, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(sortOptions) { s ->
                FilterChip(
                    selected = s == sort,
                    onClick = { onSort(s) },
                    label = { Text(s, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
    }
}

val categoryIcons: Map<String, ImageVector> = linkedMapOf(
    "All" to Icons.Filled.Apps,
    "Food & Seafood" to Icons.Filled.Restaurant,
    "Local Products" to Icons.Filled.LocalFlorist,
    "Clothing" to Icons.Filled.Checkroom,
    "Gadgets" to Icons.Filled.PhoneAndroid,
    "Other" to Icons.Filled.MoreHoriz
)

// ---------------------------------------------------------------------------
// Cart (Firestore: carts/{uid}/items/{listingId})
// ---------------------------------------------------------------------------

data class CartItem(
    val id: String = "",
    val listingId: String = "",
    val title: String = "",
    val price: Double = 0.0,
    val imageUrl: String = "",
    val sellerId: String = "",
    val sellerName: String = "",
    val contact: String = "",
    val town: String = "",
    val quantity: Long = 1
)

fun addToCart(
    db: FirebaseFirestore,
    uid: String,
    listing: Listing,
    fromName: String,
    onResult: (String?) -> Unit
) {
    if (listing.sellerId == uid) {
        onResult("This listing is yours.")
        return
    }
    val ref = db.collection("carts").document(uid).collection("items").document(listing.id)
    val listingRef = db.collection("listings").document(listing.id)
    db.runTransaction { tx ->
        val listingSnap = tx.get(listingRef)
        val stock = listingSnap.getLong("stock") ?: -1L
        val cartSnap = tx.get(ref)
        val currentQty = if (cartSnap.exists()) (cartSnap.getLong("quantity") ?: 1L) else 0L
        if (stock == 0L) {
            throw Exception("Out of stock.")
        }
        if (stock > 0L && currentQty + 1L > stock) {
            throw Exception("Only $stock left in stock.")
        }
        if (cartSnap.exists()) {
            tx.update(ref, "quantity", (cartSnap.getLong("quantity") ?: 1L) + 1L)
        } else {
            tx.set(
                ref,
                CartItem(
                    listingId = listing.id,
                    title = listing.title,
                    price = listing.price,
                    imageUrl = listing.imageUrl,
                    sellerId = listing.sellerId,
                    sellerName = listing.sellerName,
                    contact = listing.contact,
                    town = listing.town,
                    quantity = 1
                )
            )
        }
        // Seller stats: count cart adds.
        tx.update(listingRef, "cartAdds", FieldValue.increment(1))
        null
    }.addOnSuccessListener {
        notifyUser(
            db = db,
            toUid = listing.sellerId,
            fromUid = uid,
            fromName = fromName,
            message = "$fromName added to cart: ${listing.title}",
            listing = listing
        )
        onResult(null)
    }.addOnFailureListener { onResult(it.localizedMessage ?: "Failed to add to cart.") }
}

fun userDisplayName(): String {
    val user = FirebaseAuth.getInstance().currentUser
    return user?.displayName?.takeIf { it.isNotBlank() }
        ?: user?.email?.substringBefore('@')?.takeIf { it.isNotBlank() }
        ?: "User"
}

/**
 * Threads written by older builds stored the sender's own name as "Me", so the
 * other side saw "Me" instead of a person. Older documents are cleaned up here.
 */
fun chatPartnerName(raw: String?): String =
    raw?.trim()?.takeIf { it.isNotEmpty() && !it.equals("Me", ignoreCase = true) } ?: "User"

/**
 * Repairs every chat thread I am part of in one pass: my own name is rewritten
 * from the account, and a partner that an older build saved as "Me" is looked
 * up in the users collection (falling back to "User").
 *
 * Called once per account; [onDone] runs when every thread has been written.
 */
fun repairChatNames(db: FirebaseFirestore, uid: String, onDone: () -> Unit = {}) {
    val myName = userDisplayName()
    db.collection("chats").whereArrayContains("participants", uid).get()
        .addOnSuccessListener { snap ->
            val threads = snap.documents
            if (threads.isEmpty()) {
                onDone()
                return@addOnSuccessListener
            }
            var pending = threads.size
            fun oneDone() {
                pending -= 1
                if (pending <= 0) onDone()
            }
            threads.forEach { doc ->
                val participants = (doc.get("participants") as? List<*>)
                    ?.filterIsInstance<String>().orEmpty()
                val other = participants.firstOrNull { it != uid }
                val names = doc.get("names") as? Map<*, *>
                val stored = (names?.get(other) as? String)?.trim()
                val fields = mutableMapOf<String, Any>("names.$uid" to myName)

                fun write() {
                    doc.reference.update(fields)
                        .addOnCompleteListener { oneDone() }
                }

                if (other == null || !stored.isNullOrEmpty() && !stored.equals("Me", true)) {
                    write()
                } else {
                    db.collection("users").document(other).get()
                        .addOnSuccessListener { user ->
                            val real = (user.getString("name") ?: user.getString("displayName"))
                                ?.trim()
                                ?.takeIf { it.isNotEmpty() && !it.equals("Me", true) }
                            fields["names.$other"] = real ?: "User"
                            write()
                        }
                        .addOnFailureListener {
                            fields["names.$other"] = "User"
                            write()
                        }
                }
            }
        }
        .addOnFailureListener { onDone() }
}

fun updateCartQuantity(db: FirebaseFirestore, uid: String, itemId: String, quantity: Long) {
    val ref = db.collection("carts").document(uid).collection("items").document(itemId)
    if (quantity <= 0) ref.delete() else ref.update("quantity", quantity)
}

// ---------------------------------------------------------------------------
// Favorites (Firestore: favorites/{uid}/items/{listingId})
// ---------------------------------------------------------------------------

data class FavoriteItem(
    val id: String = "",
    val listingId: String = "",
    val title: String = "",
    val price: Double = 0.0,
    val imageUrl: String = "",
    val town: String = "",
    val createdAt: Long = 0L
)

fun toggleFavorite(db: FirebaseFirestore, uid: String, listing: Listing, onResult: (String?) -> Unit) {
    val ref = db.collection("favorites").document(uid).collection("items").document(listing.id)
    db.runTransaction { tx ->
        val snap = tx.get(ref)
        if (snap.exists()) {
            tx.delete(ref)
        } else {
            tx.set(
                ref,
                FavoriteItem(
                    listingId = listing.id,
                    title = listing.title,
                    price = listing.price,
                    imageUrl = listing.imageUrl,
                    town = listing.town,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
        null
    }.addOnFailureListener { onResult(it.localizedMessage ?: "Favorite failed.") }
        .addOnSuccessListener { onResult(null) }
}

// ---------------------------------------------------------------------------
// Notifications (Firestore: notifications/{uid}/items/{autoId})
// ---------------------------------------------------------------------------

data class AppNotification(
    val id: String = "",
    val fromUid: String = "",
    val fromName: String = "",
    val message: String = "",
    val listingId: String = "",
    val listingTitle: String = "",
    val createdAt: Long = 0L,
    val read: Boolean = false
)

fun notifyUser(
    db: FirebaseFirestore,
    toUid: String,
    fromUid: String,
    fromName: String,
    message: String,
    listing: Listing? = null,
    allowSelfNotification: Boolean = false
) {
    if (!canDeliverNotification(toUid, fromUid, allowSelfNotification)) return
    if (toUid == fromUid) {
        // Self notification: allowed by the rules, write directly.
        db.collection("notifications").document(toUid).collection("items").add(
            AppNotification(
                fromUid = fromUid,
                fromName = fromName,
                message = message,
                listingId = listing?.id ?: "",
                listingTitle = listing?.title ?: "",
                createdAt = System.currentTimeMillis()
            )
        )
    } else {
        // Cross-user: client writes are blocked by the rules, so route the
        // inbox write through the Worker (which uses the service account).
        sendNotification(toUid, fromUid, fromName, message, listing)
    }
}

// ---------------------------------------------------------------------------
// Chat (Firestore: chats/{chatId}/messages/{autoId})
// chatId = the two uids sorted, so both users share the same thread.
// ---------------------------------------------------------------------------

data class ChatMessage(
    val id: String = "",
    val from: String = "",
    val fromName: String = "",
    val text: String = "",
    val createdAt: Long = 0L
)

fun chatIdFor(a: String, b: String, listingId: String = ""): String {
    val base = if (a < b) "${a}_$b" else "${b}_$a"
    return if (listingId.isBlank()) base else "${base}__${listingId}"
}

/** Message time, for example "7:42 PM". */
fun chatTime(ts: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ts))

fun ensureChat(db: FirebaseFirestore, uid: String, otherUid: String, otherName: String, listingTitle: String, listingId: String = "") {
    val id = chatIdFor(uid, otherUid, listingId)
    // Field paths only: writing the whole `names` map let one device overwrite
    // the other participant's name (that is where "Me" came from).
    val fields = mutableMapOf<String, Any>(
        "participants" to listOf(uid, otherUid),
        "names.$uid" to userDisplayName(),
        "listingTitle" to listingTitle,
        "listingId" to listingId,
        "updatedAt" to System.currentTimeMillis()
    )
    val knownName = chatPartnerName(otherName)
    if (knownName != "User") fields["names.$otherUid"] = knownName
    db.collection("chats").document(id)
        .set(fields, com.google.firebase.firestore.SetOptions.merge())
}

fun sendChatMessage(
    db: FirebaseFirestore,
    uid: String,
    otherUid: String,
    otherName: String,
    text: String,
    listingTitle: String,
    listingId: String = ""
) {
    if (text.isBlank()) return
    ensureChat(db, uid, otherUid, otherName, listingTitle, listingId)
    val id = chatIdFor(uid, otherUid, listingId)
    val chatRef = db.collection("chats").document(id)
    val senderName = userDisplayName()
    chatRef.collection("messages").add(ChatMessage(from = uid, fromName = senderName, text = text.trim(), createdAt = System.currentTimeMillis()))
    chatRef.update(
        mapOf(
            "updatedAt" to System.currentTimeMillis(),
            "lastMessage" to text.trim(),
            // If the other side has not opened the chat, bump their unread badge.
            "unread.$otherUid" to FieldValue.increment(1)
        )
    )
    // Push to the other participant through the Worker (best-effort).
    sendPush("chat", chatId = id, text = text.trim())
    db.collection("chats").document(id).get().addOnSuccessListener { snap ->
        val participants = (snap.get("participants") as? List<*>)?.filterIsInstance<String>().orEmpty()
        participants.filter { it != uid }.forEach { other ->
            notifyUser(
                db = db,
                toUid = other,
                fromUid = uid,
                fromName = senderName,
                message = text.trim()
            )
        }
    }
}


// ---------------------------------------------------------------------------
// UI components
// ---------------------------------------------------------------------------

/**
 * Brand mark from the marketing poster: a shopping cart and a palm tree over
 * the island blue, inside a rounded square.
 */
@Composable
fun BrandLogo(size: Dp = 40.dp, modifier: Modifier = Modifier, ring: Boolean = false) {
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .then(
                if (ring) Modifier.border(
                    1.5.dp,
                    Color.White.copy(alpha = 0.85f),
                    RoundedCornerShape(size * 0.3f)
                ) else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_island),
            contentDescription = "Camiguin Market",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun BrandHeader(
    query: String,
    onQuery: (String) -> Unit,
    cartCount: Int,
    unreadCount: Int,
    chatUnread: Int,
    onSearch: () -> Unit,
    onCart: () -> Unit,
    onChats: () -> Unit,
    onNotifications: () -> Unit
) {
    // Deep blue bar with the search field inside it, as in the mockups.
    Column(
        Modifier
            .fillMaxWidth()
            .background(BrandHeaderBrush)
            .statusBarsPadding()
            .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BrandLogo(34.dp, ring = true)
            Spacer(Modifier.width(10.dp))
            Text(
                "Camiguin Market",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.weight(1f)
            )
            HeaderIcon(Icons.Filled.Search, 0, onSearch)
            HeaderIcon(Icons.Filled.ShoppingCart, cartCount, onCart)
            HeaderIcon(Icons.Filled.Chat, chatUnread, onChats)
            HeaderIcon(Icons.Filled.Notifications, unreadCount, onNotifications)
        }
        Spacer(Modifier.height(10.dp))
        SearchBar(query, onQuery)
    }
}

/** White glyph on a soft white circle: the round header buttons of the mockups. */
@Composable
private fun HeaderIcon(icon: ImageVector, badge: Int, onClick: () -> Unit) {
    Box {
        Box(
            Modifier
                .padding(horizontal = 2.dp)
                .size(36.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.16f))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
        }
        if (badge > 0) {
            Badge(
                modifier = Modifier.align(Alignment.TopEnd),
                containerColor = BrandBadge,
                contentColor = Color.White
            ) { Text(if (badge > 9) "9+" else "$badge") }
        }
    }
}

@Composable
fun IconTextButton(
    icon: ImageVector,
    badge: Int,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface
) {
    Box {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = null, tint = tint) }
        if (badge > 0) {
            Badge(
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 2.dp),
                containerColor = BrandBadge,
                contentColor = Color.White
            ) { Text(if (badge > 9) "9+" else "$badge") }
        }
    }
}

/** Recent searches: stored on the device for quick access. */
private fun searchPrefs(context: Context) =
    context.getSharedPreferences("search_cache", Context.MODE_PRIVATE)

fun loadRecentSearches(context: Context): List<String> =
    searchPrefs(context).getString("recent", null)
        ?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()

fun addRecentSearch(context: Context, q: String) {
    val clean = q.trim()
    if (clean.isBlank()) return
    val list = (listOf(clean) + loadRecentSearches(context).filter { it != clean }).take(8)
    searchPrefs(context).edit().putString("recent", list.joinToString("\n")).apply()
}

fun clearRecentSearches(context: Context) {
    searchPrefs(context).edit().remove("recent").apply()
}

@Composable
fun SearchBar(query: String, onQuery: (String) -> Unit, onSubmit: () -> Unit = {}) {
    // White pill floating over the blue header, as in the mockup.
    TextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text("Search a product or seller…", color = BrandMuted) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = BrandMuted) },
        singleLine = true,
        shape = RoundedCornerShape(26.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.White,
            unfocusedContainerColor = Color.White,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        modifier = Modifier.fillMaxWidth()
    )
}

/**
 * Hero banner from the poster: an aerial ocean photo (Unsplash, free licence —
 * photo by Bernd Dittrich, El Nido, Palawan) under the market slogan.
 */
@Composable
fun HeroBanner() {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = BrandBlueDark),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(Modifier.fillMaxWidth().height(178.dp)) {
            Image(
                painter = painterResource(R.drawable.hero_ocean),
                contentDescription = "Turquoise lagoon in Camiguin",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color(0x10001A33),
                            0.45f to Color(0x4D06264A),
                            1f to Color(0xE60A2B52)
                        )
                    )
            )
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Text(
                    "Shop Local.",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    fontStyle = FontStyle.Italic
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Support Camiguin",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        fontStyle = FontStyle.Italic
                    )
                    Text(
                        " ♡",
                        color = BrandGold,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Colour per category, matching the coloured tiles in the mockup. */
private val categoryTints = linkedMapOf(
    "All" to Color(0xFF0B57D0),
    "Food & Seafood" to Color(0xFFEF6C00),
    "Local Products" to Color(0xFF2E7D32),
    "Clothing" to Color(0xFF7B1FA2),
    "Gadgets" to Color(0xFF1565C0),
    "Other" to Color(0xFF00695C)
)

/** One-line description under each category name, as in the mockup list. */
val categoryBlurbs: Map<String, String> = linkedMapOf(
    "All" to "Everything on the market",
    "Food & Seafood" to "Fresh catch from the island",
    "Local Products" to "Camiguin-made goods",
    "Clothing" to "Wear and accessories",
    "Gadgets" to "Phones and electronics",
    "Other" to "And more…"
)

/**
 * Coloured icon tiles with the label underneath, exactly like the mockup's
 * "Shop by Category" row. "All" lives in the filter chips, not in the tiles.
 */
@Composable
fun CategoryGrid(selected: String, onSelect: (String) -> Unit, columns: Int = 3) {
    val entries = categoryIcons.entries.filterNot { it.key == "All" }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        entries.chunked(columns).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (name, icon) ->
                    val tint = categoryTints[name] ?: BrandBlue
                    val active = name == selected
                    Column(
                        Modifier.weight(1f).clickable { onSelect(name) },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            Modifier
                                .size(if (columns >= 4) 56.dp else 60.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(tint)
                                .then(
                                    if (active) Modifier.border(
                                        3.dp,
                                        BrandGold,
                                        RoundedCornerShape(16.dp)
                                    ) else Modifier
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                icon,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Spacer(Modifier.height(7.dp))
                        Text(
                            name,
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                            color = if (active) BrandBlue else BrandInk,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * The Categories screen lists the categories as white rows with a thumbnail,
 * a name and a short description, like the mockup.
 */
@Composable
fun CategoryRows(selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        categoryIcons.entries.forEach { (name, icon) ->
            val tint = categoryTints[name] ?: BrandBlue
            val active = name == selected
            Card(
                onClick = { onSelect(name) },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (active) Modifier.border(2.dp, BrandBlue, RoundedCornerShape(16.dp))
                        else Modifier
                    )
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(46.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(tint),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (active) BrandBlue else BrandInk
                        )
                        Text(
                            categoryBlurbs[name] ?: "",
                            style = MaterialTheme.typography.labelSmall,
                            color = BrandMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        "\u203A",
                        style = MaterialTheme.typography.titleLarge,
                        color = BrandMuted
                    )
                }
            }
        }
    }
}

/** Product tile for the "Trending now" row: photo on top, price and town below. */
@Composable
fun ListingTile(
    listing: Listing,
    isFavorite: Boolean,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
    modifier: Modifier = Modifier.width(164.dp)
) {
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(112.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            ) {
                if (listing.imageUrl.isNotBlank()) {
                    RemoteImage(listing.imageUrl, Modifier.fillMaxSize())
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("🛍️", style = MaterialTheme.typography.headlineMedium)
                    }
                }
                IconButton(
                    onClick = onFavorite,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(28.dp)
                        .background(Color.White.copy(alpha = 0.9f), CircleShape)
                ) {
                    Icon(
                        if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = "Favorites",
                        tint = if (isFavorite) Color(0xFFE53935) else Color(0xFF5A6472),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    listing.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "₱${formatPrice(listing.price)}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = BrandBlue
                )
                Text(
                    "📍 ${listing.town}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (listing.isPerishable && listing.expiresAt > 0) {
                    Text(
                        "⏳ ${formatExpiry(listing.expiresAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF8A6D00),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun ListingCard(
    listing: Listing,
    isFavorite: Boolean,
    onOpen: () -> Unit,
    onFavorite: () -> Unit,
    onAddToCart: () -> Unit,
    showViews: Boolean = false
) {
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("listing_card_" + listing.id)
    ) {
        Row(Modifier.padding(12.dp)) {
            Box(
                Modifier
                    .size(84.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (listing.imageUrl.isNotBlank()) {
                    RemoteImage(listing.imageUrl, Modifier.fillMaxSize())
                } else {
                    Text("🛍️", style = MaterialTheme.typography.headlineMedium)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listing.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onFavorite, modifier = Modifier.size(36.dp)) {
                        Icon(
                            if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = "Favorites",
                            tint = if (isFavorite) Color(0xFFE53935) else Color.Gray
                        )
                    }
                }
                Text(
                    "₱%.2f".format(listing.price),
                    style = MaterialTheme.typography.titleMedium,
                    color = BrandBlue,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "${listing.town} • ${listing.category}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (listing.description.isNotBlank()) {
                    Text(
                        listing.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = BrandMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (listing.isPerishable && listing.expiresAt > 0) {
                    Text(
                        "⏳ Expires ${formatExpiry(listing.expiresAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF8A6D00)
                    )
                }
                if (showViews) {
                    Text(
                        "👁 ${listing.views} views",
                        style = MaterialTheme.typography.labelSmall,
                        color = BrandBlue
                    )
                }
                if (listing.sellerName.isNotBlank()) {
                    val db = remember { FirebaseFirestore.getInstance() }
                    val verification = loadSellerVerification(db, listing.sellerId)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            listing.sellerName,
                            style = MaterialTheme.typography.labelSmall,
                            color = BrandMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(Modifier.width(4.dp))
                        VerifiedBadge(
                            phoneVerified = verification.value.phoneVerified,
                            verified = verification.value.verified,
                            compact = true
                        )
                        Spacer(Modifier.width(4.dp))
                        SellerRatingSummary(listing.sellerId)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = onAddToCart,
                    shape = RoundedCornerShape(20.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Filled.AddShoppingCart, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add to Cart", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
fun BottomNavBar(tab: MarketTab, cartCount: Int, onSelect: (MarketTab) -> Unit) {
    NavigationBar(containerColor = Color.White, tonalElevation = 6.dp) {
        MarketTab.entries.forEach { item ->
            NavigationBarItem(
                selected = tab == item,
                onClick = { onSelect(item) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = BrandBlue,
                    selectedTextColor = BrandBlue,
                    indicatorColor = BrandSky,
                    unselectedIconColor = BrandMuted,
                    unselectedTextColor = BrandMuted
                ),
                icon = {
                    if (item == MarketTab.Cart && cartCount > 0) {
                        BadgedBox(badge = { Badge(containerColor = BrandBadge) { Text("$cartCount") } }) {
                            Icon(item.icon, contentDescription = item.label)
                        }
                    } else {
                        Icon(item.icon, contentDescription = item.label)
                    }
                },
                label = {
                    Text(
                        item.label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (tab == item) FontWeight.Bold else FontWeight.Normal
                    )
                }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Cart screen
// ---------------------------------------------------------------------------

/** Offline cart mirror: still visible without internet. */
private fun cartPrefs(context: Context) =
    context.getSharedPreferences("cart_cache", Context.MODE_PRIVATE)

fun loadCartCache(context: Context, uid: String): List<CartItem> {
    val raw = cartPrefs(context).getString(uid, null) ?: return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            CartItem(
                id = o.optString("id"),
                listingId = o.optString("listingId"),
                title = o.optString("title"),
                price = o.optDouble("price"),
                imageUrl = o.optString("imageUrl"),
                sellerId = o.optString("sellerId"),
                sellerName = o.optString("sellerName"),
                contact = o.optString("contact"),
                town = o.optString("town"),
                quantity = o.optLong("quantity", 1L)
            )
        }
    }.getOrDefault(emptyList())
}

fun saveCartCache(context: Context, uid: String, items: List<CartItem>) {
    val arr = JSONArray()
    items.forEach { i ->
        arr.put(
            JSONObject().apply {
                put("id", i.id)
                put("listingId", i.listingId)
                put("title", i.title)
                put("price", i.price)
                put("imageUrl", i.imageUrl)
                put("sellerId", i.sellerId)
                put("sellerName", i.sellerName)
                put("contact", i.contact)
                put("town", i.town)
                put("quantity", i.quantity)
            }
        )
    }
    cartPrefs(context).edit().putString(uid, arr.toString()).apply()
}

@Composable
fun CartScreen(
    uid: String,
    onBrowse: () -> Unit,
    onCheckout: (List<CartItem>, String, String) -> Unit
) {
    // Payment is chosen at checkout: "cash" (pay on pickup/delivery) or "gcash".
    var paymentMethod by remember { mutableStateOf("cash") }
    val db = remember { FirebaseFirestore.getInstance() }
    val context = LocalContext.current
    val S = LocalStrings.current
    // Show the local cache first so something appears even offline.
    var items by remember { mutableStateOf(loadCartCache(context, uid)) }
    var offline by remember { mutableStateOf(false) }
    // Each seller's GCash number so the prompt says where the money goes.
    val sellerGcash = rememberSellerGcash(if (paymentMethod == "gcash") items.map { it.sellerId } else emptyList())

    DisposableEffect(Unit) {
        val reg = db.collection("carts").document(uid).collection("items")
            .addSnapshotListener { snap, err ->
                if (snap != null) {
                    items = snap.documents.mapNotNull { d ->
                        d.toObject(CartItem::class.java)?.copy(id = d.id)
                    }
                    saveCartCache(context, uid, items)
                    offline = false
                } else {
                    offline = err != null
                }
            }
        onDispose { reg.remove() }
    }

    val total = items.sumOf { it.price * it.quantity }
    val checkoutId = remember { java.util.UUID.randomUUID().toString() }
    var checkingOut by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ShoppingCart, contentDescription = null, tint = BrandBlue)
            Spacer(Modifier.width(6.dp))
            Text(S.myCart, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(
                if (items.size == 1) "1 item" else "${items.size} items",
                style = MaterialTheme.typography.labelMedium,
                color = BrandMuted
            )
        }
        if (offline) {
            Text(
                "Offline — showing the cart saved on this device.",
                style = MaterialTheme.typography.labelSmall,
                color = BrandGold
            )
        }
        Spacer(Modifier.height(8.dp))
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(S.cartEmpty, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onBrowse) { Text(S.searchProducts) }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(items, key = { it.id }) { item ->
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                if (item.imageUrl.isNotBlank()) RemoteImage(item.imageUrl, Modifier.fillMaxSize())
                                else Text("🛍️")
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "₱%.2f × %d".format(item.price, item.quantity),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = BrandMuted
                                )
                                Spacer(Modifier.height(6.dp))
                                // Quantity stepper from the mockup: − count +.
                                Row(
                                    Modifier
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(BrandSky),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        onClick = { updateCartQuantity(db, uid, item.id, item.quantity - 1) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.Remove,
                                            contentDescription = "Decrease quantity",
                                            tint = BrandBlue,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    Text(
                                        "${item.quantity}",
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 6.dp)
                                    )
                                    IconButton(
                                        onClick = { updateCartQuantity(db, uid, item.id, item.quantity + 1) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.Add,
                                            contentDescription = "Increase quantity",
                                            tint = BrandBlue,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }
                            Text(
                                "₱%.0f".format(item.price * item.quantity),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = BrandInk
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("Payment method", style = MaterialTheme.typography.labelLarge, color = BrandMuted)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("cash" to "Cash", "gcash" to "GCash").forEach { (value, label) ->
                            FilterChip(
                                selected = paymentMethod == value,
                                onClick = { paymentMethod = value },
                                label = { Text(label) }
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (paymentMethod == "gcash") {
                            "Send the total to the seller's GCash number, then add the reference in Orders. " +
                                "The seller verifies it before the order is marked paid."
                        } else {
                            "Pay in cash when you get the order. The seller confirms it in Orders."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = BrandMuted
                    )
                    if (paymentMethod == "gcash") {
                        items.map { it.sellerId to it.sellerName }.distinct().forEach { (sid, sname) ->
                            val number = sellerGcash[sid]
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (number.isNullOrBlank()) {
                                    "$sname has not added a GCash number yet — ask in chat."
                                } else {
                                    "Send to $sname: GCash $number"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = BrandInk
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(S.total, style = MaterialTheme.typography.titleMedium, color = BrandMuted, modifier = Modifier.weight(1f))
                    Text(
                        "₱%.0f".format(total),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = BrandBlue
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { checkingOut = true; onCheckout(items, checkoutId, paymentMethod) },
                enabled = !checkingOut,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                Icon(Icons.Filled.ShoppingCart, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(S.checkout, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Each seller receives their own order and notification.",
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "Prices are in Philippine pesos (₱).",
                style = MaterialTheme.typography.labelSmall,
                color = BrandMuted
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Favorites
// ---------------------------------------------------------------------------

@Composable
fun FavoritesScreen(uid: String, onOpenListing: (String) -> Unit) {
    val db = remember { FirebaseFirestore.getInstance() }
    val S = LocalStrings.current
    var items by remember { mutableStateOf<List<FavoriteItem>>(emptyList()) }

    DisposableEffect(Unit) {
        val reg = db.collection("favorites").document(uid).collection("items")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, _ ->
                items = snap?.documents?.mapNotNull { d ->
                    d.toObject(FavoriteItem::class.java)?.copy(id = d.id)
                } ?: emptyList()
            }
        onDispose { reg.remove() }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Favorite, contentDescription = null, tint = Color(0xFFE53935))
            Spacer(Modifier.width(6.dp))
            Text(S.myFavorites, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        if (items.isEmpty()) {
            Text(S.noFavorites)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.id }) { f ->
                    Card(onClick = { onOpenListing(f.listingId) }, shape = RoundedCornerShape(14.dp)) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                if (f.imageUrl.isNotBlank()) RemoteImage(f.imageUrl, Modifier.fillMaxSize())
                                else Text("🛍️")
                            }
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(f.title, fontWeight = FontWeight.Bold)
                                Text("₱%.2f".format(f.price), color = BrandBlue)
                                Text(f.town, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Notifications
// ---------------------------------------------------------------------------

@Composable
fun NotificationsScreen(uid: String, onOpen: (String, String, String, String) -> Unit = { _, _, _, _ -> }) {
    val db = remember { FirebaseFirestore.getInstance() }
    val S = LocalStrings.current
    var items by remember { mutableStateOf<List<AppNotification>>(emptyList()) }

    DisposableEffect(Unit) {
        val reg = db.collection("notifications").document(uid).collection("items")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(50)
            .addSnapshotListener { snap, _ ->
                items = snap?.documents?.mapNotNull { d ->
                    d.toObject(AppNotification::class.java)?.copy(id = d.id)
                } ?: emptyList()
            }
        onDispose { reg.remove() }
    }

    LaunchedEffect(items.size) {
        // Mark everything as read when the screen opens.
        items.filter { !it.read }.forEach { n ->
            db.collection("notifications").document(uid).collection("items")
                .document(n.id).update("read", true)
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Notifications, contentDescription = null, tint = BrandBlue)
            Spacer(Modifier.width(6.dp))
            Text(S.notifications, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        if (items.isEmpty()) {
            Text(S.noNotifications)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.id }) { n ->
                    Card(
                        onClick = { onOpen(n.fromUid, n.fromName, n.listingTitle, n.listingId) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(n.message, style = MaterialTheme.typography.bodyMedium)
                            if (n.listingTitle.isNotBlank()) {
                                Text("Listing: ${n.listingTitle}", style = MaterialTheme.typography.labelSmall)
                            }
                            Text("from ${n.fromName}", style = MaterialTheme.typography.labelSmall)
                            Text(S.tapToOpenChat, style = MaterialTheme.typography.labelSmall, color = BrandBlue)
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Chat
// ---------------------------------------------------------------------------

@Composable
fun ChatScreen(uid: String, otherUid: String, otherName: String, listingTitle: String, sellerUid: String = "", listingId: String = "") {
    val db = remember { FirebaseFirestore.getInstance() }
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    // Last time the other side opened/read the thread.
    var otherRead by remember { mutableStateOf(0L) }
    // Typing indicator: when the other side last typed, plus a local ticker.
    var otherTypingAt by remember { mutableStateOf(0L) }
    var lastTypingWrite by remember { mutableStateOf(0L) }
    var tick by remember { mutableStateOf(0L) }
    val chatId = remember(otherUid, listingId) { chatIdFor(uid, otherUid, listingId) }
    val partner = remember(otherName) { chatPartnerName(otherName) }
    // The seller is always shown on the right and the buyer on the left.
    // When no seller id is known (inbox/orders) the chat partner is treated as
    // the seller, matching the "Chat with seller" flow of this app.
    val sellerResolved = remember(sellerUid, otherUid) {
        if (sellerUid.isNotBlank()) sellerUid else otherUid
    }
    val partnerIsSeller = sellerResolved == otherUid
    val partnerLabel = if (partnerIsSeller) "$partner \u2022 Seller" else partner

    LaunchedEffect(chatId) {
        ensureChat(db, uid, otherUid, partner, listingTitle, listingId)
        // Repair threads where an older build saved my own name as "Me".
        db.collection("chats").document(chatId).update("names.$uid", userDisplayName())
    }

    // Mark as read while the thread is open (unread = 0, read receipt).
    LaunchedEffect(chatId, messages.size) {
        db.collection("chats").document(chatId).update(
            mapOf(
                "unread.$uid" to 0,
                "lastRead.$uid" to System.currentTimeMillis()
            )
        )
    }

    DisposableEffect(chatId) {
        val reg = db.collection("chats").document(chatId)
            .addSnapshotListener { snap, _ ->
                otherRead = ((snap?.get("lastRead") as? Map<*, *>)?.get(otherUid) as? Number)
                    ?.toLong() ?: 0L
                otherTypingAt = ((snap?.get("typing") as? Map<*, *>)?.get(otherUid) as? Number)
                    ?.toLong() ?: 0L
            }
        onDispose { reg.remove() }
    }

    // Ticker so "typing…" expires after a few seconds.
    LaunchedEffect(Unit) {
        while (true) {
            tick = System.currentTimeMillis()
            kotlinx.coroutines.delay(2000)
        }
    }

    // Send the typing signal at most every 2.5s so writes do not pile up.
    LaunchedEffect(text) {
        val now = System.currentTimeMillis()
        if (text.isNotBlank() && now - lastTypingWrite > 2500) {
            lastTypingWrite = now
            db.collection("chats").document(chatId).update("typing.$uid", now)
        }
    }

    DisposableEffect(chatId) {
        val reg = db.collection("chats").document(chatId).collection("messages")
            .orderBy("createdAt", Query.Direction.ASCENDING)
            .addSnapshotListener { snap, _ ->
                messages = snap?.documents?.mapNotNull { d ->
                    d.toObject(ChatMessage::class.java)?.copy(id = d.id)
                } ?: emptyList()
            }
        onDispose { reg.remove() }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        val typing = otherTypingAt > 0 && tick - otherTypingAt < 7000
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).clip(CircleShape).background(BrandSky),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    partner.take(1).uppercase(),
                    color = BrandBlue,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(partnerLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    when {
                        typing -> "typing…"
                        listingTitle.isNotBlank() -> "About: $listingTitle"
                        else -> "Online"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (typing) FontWeight.Bold else FontWeight.Normal,
                    color = if (typing) BrandBlue else BrandMuted
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (listingTitle.isNotBlank()) {
            Surface(
                color = BrandSky,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
            ) {
                Text(
                    "About: $listingTitle",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = BrandBlue,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(messages, key = { it.id }) { m ->
                val fromSeller = m.from == sellerResolved
                val senderName = m.fromName.ifBlank {
                    if (m.from == uid) userDisplayName() else partner
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (fromSeller) Arrangement.End else Arrangement.Start
                ) {
                    Surface(
                        color = if (fromSeller) BrandBlue else Color.White,
                        shape = RoundedCornerShape(
                            topStart = 18.dp,
                            topEnd = 18.dp,
                            bottomStart = if (fromSeller) 18.dp else 4.dp,
                            bottomEnd = if (fromSeller) 4.dp else 18.dp
                        ),
                        shadowElevation = 1.dp
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(
                                senderName,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (fromSeller) Color.White.copy(alpha = 0.9f)
                                else BrandBlue
                            )
                            Text(
                                m.text,
                                color = if (fromSeller) Color.White
                                else BrandInk
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    chatTime(m.createdAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (fromSeller) Color.White.copy(alpha = 0.8f)
                                    else BrandMuted
                                )
                                if (m.from == uid) {
                                    Spacer(Modifier.width(4.dp))
                                    val seen = m.createdAt <= otherRead
                                    Text(
                                        if (seen) "✓✓" else "✓",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (seen) Color(0xFF90CAF9)
                                        else Color.White.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(Color.White)
                .padding(start = 8.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Type a message…", color = BrandMuted) },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                maxLines = 4,
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = {
                    sendChatMessage(db, uid, otherUid, partner, text, listingTitle, listingId)
                    text = ""
                    db.collection("chats").document(chatId).update("typing.$uid", 0L)
                },
                shape = CircleShape,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(46.dp)
            ) { Icon(Icons.Filled.Send, contentDescription = "Send", modifier = Modifier.size(20.dp)) }
        }
    }
}

// ---------------------------------------------------------------------------
// Profile
// ---------------------------------------------------------------------------

@Composable
fun ProfileScreen(
    uid: String,
    displayName: String,
    email: String,
    photoUrl: String? = null,
    onPickPhoto: ((Uri) -> Unit)? = null,
    myListings: List<Listing>,
    isAdmin: Boolean,
    dark: Boolean,
    language: String = "en",
    onToggleDark: (Boolean) -> Unit,
    onToggleLanguage: (String) -> Unit,
    onFavorites: () -> Unit,
    onOrders: () -> Unit,
    onInbox: () -> Unit,
    onSavedSearches: () -> Unit,
    onListingStats: () -> Unit,
    onBlocked: () -> Unit = {},
    onAdmin: () -> Unit = {},
    onLogout: () -> Unit,
    onDeleteAccount: () -> Unit
) {
    val S = LocalStrings.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { onPickPhoto?.invoke(it) }
        }
        Box(
            Modifier.size(80.dp).clip(CircleShape).background(BrandBlue),
            contentAlignment = Alignment.Center
        ) {
            if (!photoUrl.isNullOrBlank()) {
                RemoteImage(photoUrl, Modifier.fillMaxSize())
            } else {
                Text(
                    displayName.take(1).uppercase(),
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = { photoPicker.launch("image/*") }, enabled = onPickPhoto != null) {
            Text(if (photoUrl.isNullOrBlank()) S.addPhoto else S.changePhoto)
        }
        Spacer(Modifier.height(8.dp))
        Text(displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(email, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        RequestVerificationSection(uid)
        Spacer(Modifier.height(12.dp))
        ContactLinksSection(uid)
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("${myListings.size}", S.myListings, Modifier.weight(1f))
            StatCard(
                "₱%.0f".format(myListings.sumOf { it.price }),
                S.totalValue,
                Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(12.dp))

        SellerStats(uid, myListings)

        Spacer(Modifier.height(16.dp))

        // White rows with a coloured icon, a label and a chevron (mockup).
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                ProfileRow(Icons.Filled.ReceiptLong, S.myListings, onListingStats, "${myListings.size}")
                ProfileDivider()
                ProfileRow(Icons.Filled.Favorite, S.favorites, onFavorites)
                ProfileDivider()
                ProfileRow(Icons.Filled.ReceiptLong, S.orders, onOrders)
                ProfileDivider()
                ProfileRow(Icons.Filled.Chat, S.chats, onInbox)
                ProfileDivider()
                ProfileRow(Icons.Filled.Notifications, S.savedSearches, onSavedSearches)
                ProfileDivider()
                ProfileRow(Icons.Filled.Close, "Blocked sellers", onBlocked)
                ProfileRow(Icons.Filled.Storefront, S.listingStats, onListingStats)
                if (isAdmin) {
                    ProfileDivider()
                    ProfileRow(Icons.Filled.Security, "Admin dashboard", onAdmin)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(S.darkMode, style = MaterialTheme.typography.bodyMedium)
            Switch(checked = dark, onCheckedChange = onToggleDark)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(S.language, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { onToggleLanguage(if (language == "en") "ceb" else "en") }) {
                Text(if (language == "en") "English" else "Cebuano")
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Log out")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onDeleteAccount, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Delete account and data", color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Camiguin Market • Buy Local. Support Local!",
            style = MaterialTheme.typography.labelSmall
        )
        Text(
            "by Bernavic Gamo",
            style = MaterialTheme.typography.labelSmall
        )
        Text(
            "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/** One tappable row of the profile menu card. */
@Composable
private fun ProfileRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    trailing: String? = null
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(BrandSky),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = BrandBlue, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.bodyMedium, color = BrandMuted)
            Spacer(Modifier.width(6.dp))
        }
        Text("\u203A", style = MaterialTheme.typography.titleMedium, color = BrandMuted)
    }
}

/** Hairline between two profile rows. */
@Composable
private fun ProfileDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 62.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
    )
}

/** Converts 09XXXXXXXXX / 9XXXXXXXXX into +63 E.164 form. */
fun normalizePhone(raw: String): String {
    val trimmed = raw.trim()
    val digits = trimmed.filter { it.isDigit() }
    return when {
        trimmed.startsWith("+") -> "+$digits"
        digits.length == 11 && digits.startsWith("09") -> "+63" + digits.drop(1)
        digits.length == 10 && digits.startsWith("9") -> "+63$digits"
        digits.length == 12 && digits.startsWith("63") -> "+$digits"
        else -> "+$digits"
    }
}

fun normalizeTelegramLink(input: String): String {
    val t = input.trim()
    if (t.isBlank()) return ""
    val lower = t.lowercase()
    val marker = when {
        lower.contains("t.me/") -> "t.me/"
        lower.contains("telegram.me/") -> "telegram.me/"
        else -> null
    }
    val raw = if (marker != null) t.substringAfter(marker, t).substringBefore("?").substringBefore("/")
    else t
    return raw.trim().removePrefix("@").trim()
}

fun normalizeMessengerLink(input: String): String {
    val t = input.trim()
    if (t.isBlank()) return ""
    val lower = t.lowercase()
    val marker = when {
        lower.contains("m.me/") -> "m.me/"
        lower.contains("facebook.com/") -> "facebook.com/"
        lower.contains("messenger.com/") -> "messenger.com/"
        else -> null
    }
    val raw = if (marker != null) t.substringAfter(marker, t).substringBefore("?").substringBefore("/")
    else t
    return raw.trim().trimStart('@')
}

fun normalizeWhatsAppLink(input: String): String {
    val t = input.trim()
    if (t.isBlank()) return ""
    val lower = t.lowercase()
    val raw = when {
        lower.contains("wa.me/") -> t.substringAfter("wa.me/", t)
        lower.contains("phone=") -> t.substringAfter("phone=", t)
        else -> t
    }
    return raw.substringBefore("?").substringBefore("/").filter { it.isDigit() || it == '+' }.trim()
}

@Composable
fun ContactLinksSection(uid: String) {
    val db = remember { FirebaseFirestore.getInstance() }
    var telegram by remember { mutableStateOf("") }
    var messenger by remember { mutableStateOf("") }
    var whatsapp by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Store info: the GCash number buyers pay into (empty when not set).
    var gcashNumber by remember { mutableStateOf("") }
    var tgToken by remember { mutableStateOf<String?>(null) }
    var tgDialogOpen by remember { mutableStateOf(false) }
    var tgInfo by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(uid) {
        try {
            db.collection("users").document(uid).get().addOnSuccessListener { d ->
                telegram = d.getString("telegram") ?: ""
                messenger = d.getString("messenger") ?: ""
                whatsapp = d.getString("whatsapp") ?: ""
                gcashNumber = d.getString("gcashNumber") ?: ""
            }
        } catch (e: Exception) {
            info = e.localizedMessage ?: "Failed to load contact links."
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text("Contact links (optional)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(telegram, { telegram = it }, label = { Text("Telegram username (or t.me link)") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(messenger, { messenger = it }, label = { Text("Messenger username (or m.me link)") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(whatsapp, { whatsapp = it }, label = { Text("WhatsApp number with country code (or wa.me link)") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            Text(
                "GCash number (buyers send payments here)",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = gcashNumber,
                onValueChange = { typed -> gcashNumber = typed.filter { it.isDigit() }.take(11) },
                label = { Text("09XX XXX XXXX") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    tgInfo = null
                    val u = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser
                    if (u == null) {
                        tgInfo = "Sign in to your account first."
                    } else {
                        u.getIdToken(true)
                            .addOnSuccessListener { res ->
                                tgToken = res.token
                                tgDialogOpen = true
                            }
                            .addOnFailureListener { e -> tgInfo = e.localizedMessage }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Connect Telegram") }
            tgInfo?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.labelSmall)
            }
            if (tgDialogOpen && !tgToken.isNullOrBlank()) {
                AlertDialog(
                    onDismissRequest = { tgDialogOpen = false },
                    title = { Text("Connect Telegram") },
                    text = {
                        AndroidView(
                            factory = { c ->
                                android.webkit.WebView(c).apply {
                                    settings.javaScriptEnabled = true
                                    addJavascriptInterface(object {
                                        @android.webkit.JavascriptInterface
                                        fun onVerified(username: String) {
                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                if (username.isNotBlank()) {
                                                    telegram = username
                                                    db.collection("users").document(uid).set(
                                                        mapOf("telegram" to username, "telegramVerified" to true),
                                                        com.google.firebase.firestore.SetOptions.merge()
                                                    )
                                                    tgInfo = "Connected: $username"
                                                }
                                                tgDialogOpen = false
                                            }
                                        }
                                        @android.webkit.JavascriptInterface
                                        fun onError(msg: String) {
                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                tgInfo = msg
                                                tgDialogOpen = false
                                            }
                                        }
                                    }, "Android")
                                    webViewClient = object : android.webkit.WebViewClient() {}
                                    loadUrl("${BuildConfig.R2_UPLOAD_URL}/telegram/login?token=${tgToken}")
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(360.dp)
                        )
                    },
                    confirmButton = { TextButton(onClick = { tgDialogOpen = false }) { Text("Close") } }
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val gcash = gcashNumber.trim()
                    if (gcash.isNotEmpty() && !Regex("^09\\d{9}$").matches(gcash)) {
                        info = "GCash number must be an 11-digit mobile starting with 09, e.g. 09171234567."
                        return@Button
                    }
                    busy = true
                    db.collection("users").document(uid).set(
                        mapOf(
                            "telegram" to normalizeTelegramLink(telegram),
                            "messenger" to normalizeMessengerLink(messenger),
                            "whatsapp" to normalizeWhatsAppLink(whatsapp),
                            "gcashNumber" to gcash
                        ),
                        SetOptions.merge()
                    ).addOnSuccessListener {
                        busy = false
                        info = "Saved."
                    }.addOnFailureListener { e ->
                        busy = false
                        info = e.localizedMessage ?: "Failed."
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Saving…" else "Save links") }
            info?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun StatCard(value: String, label: String, modifier: Modifier = Modifier) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = modifier) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** The towns of Camiguin (used by the hero banner and filters). */
@Composable
fun TownChips(selected: String, onSelect: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(listOf("All") + towns) { t ->
            FilterChip(selected = t == selected, onClick = { onSelect(t) }, label = { Text(t) })
        }
    }
}

// ---------------------------------------------------------------------------
// Home and Categories tabs (from the mockup)
// ---------------------------------------------------------------------------

@Composable
fun HomeTab(
    uid: String,
    query: String,
    onQuery: (String) -> Unit,
    category: String,
    onCategory: (String) -> Unit,
    priceFilter: String,
    onPriceFilter: (String) -> Unit,
    sort: String,
    onSort: (String) -> Unit,
    listings: List<Listing>,
    favorites: Set<String>,
    onOpen: (Listing) -> Unit,
    onFavorite: (Listing) -> Unit,
    onAddToCart: (Listing) -> Unit,
    onAllCategories: () -> Unit,
    onMap: () -> Unit,
    onCompare: () -> Unit,
    onSell: () -> Unit
) {
    val S = LocalStrings.current
    val trending = listings.filter { it.views > 0 }
        .sortedByDescending { it.views }
        .take(5)

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { AnnouncementsBanner() }
        item {
            val ctx = LocalContext.current
            var recent by remember { mutableStateOf(loadRecentSearches(ctx)) }
            LaunchedEffect(query) {
                if (query.isNotBlank()) addRecentSearch(ctx, query)
                recent = loadRecentSearches(ctx)
            }
            Column {
                val suggestions = if (query.isBlank()) emptyList() else listings
                    .map { it.title }
                    .distinct()
                    .filter { it.contains(query, true) && it != query }
                    .take(5)
                when {
                    query.isBlank() && recent.isNotEmpty() -> Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Recent searches", style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = {
                                clearRecentSearches(ctx)
                                recent = emptyList()
                            }) { Text("Clear") }
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(recent) { r ->
                                FilterChip(
                                    selected = false,
                                    onClick = { onQuery(r) },
                                    label = { Text(r) }
                                )
                            }
                        }
                    }

                    suggestions.isNotEmpty() -> LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(suggestions) { s ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    onQuery(s)
                                    addRecentSearch(ctx, s)
                                    recent = loadRecentSearches(ctx)
                                },
                                label = { Text(s) }
                            )
                        }
                    }
                }
            }
        }
        item { FilterBar(priceFilter, onPriceFilter, sort, onSort) }
        item {
            val db = remember { FirebaseFirestore.getInstance() }
            val ctx = androidx.compose.ui.platform.LocalContext.current
            var searches by remember { mutableStateOf<List<SavedSearch>>(emptyList()) }
            val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
            fun requestNotificationPermission() {
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    val granted = ContextCompat.checkSelfPermission(
                        ctx, android.Manifest.permission.POST_NOTIFICATIONS
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (!granted) notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            DisposableEffect(uid) {
                val reg = db.collection("savedSearches").document(uid).collection("items")
                    .addSnapshotListener { snap, _ ->
                        searches = snap?.documents?.mapNotNull { d ->
                            d.toObject(SavedSearch::class.java)?.copy(id = d.id)
                        } ?: emptyList()
                    }
                onDispose { reg.remove() }
            }
            LaunchedEffect(listings, searches) {
                checkSavedSearches(db, uid, searches, listings)
            }
            Column {
                TextButton(
                    onClick = {
                        requestNotificationPermission()
                        saveSearch(
                            db, uid, query, category, "All",
                            name = query.ifBlank { category }
                        )
                    },
                    enabled = query.isNotBlank() || category != "All"
                ) {
                    Icon(
                        Icons.Filled.Notifications,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Save this search", style = MaterialTheme.typography.labelMedium)
                }
                if (searches.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(searches, key = { it.id }) { s ->
                            FilterChip(
                                selected = false,
                                onClick = {
                                    onQuery(s.query)
                                    onCategory(s.category)
                                },
                                label = { Text(s.label, style = MaterialTheme.typography.labelSmall) },
                                trailingIcon = {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = "Remove",
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clickable { deleteSavedSearch(db, uid, s.id) }
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
        item { HeroBanner() }
        item {
            SectionTitle("Shop by Category") {
                TextButton(onClick = onAllCategories) { Text("See All") }
            }
        }
        // Four tiles per row on Home, matching the mockup's "Shop by Category".
        item { CategoryGrid(category, onCategory, columns = 4) }
        if (trending.isNotEmpty()) {
            item { SectionTitle("🔥 Trending now") }
            // Two cards per row, as in the mockup's "Trending Now" grid.
            items(trending.chunked(2)) { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { l ->
                        ListingTile(
                            listing = l,
                            isFavorite = favorites.contains(l.id),
                            onOpen = { onOpen(l) },
                            onFavorite = { onFavorite(l) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (pair.size == 1) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        item {
            SectionTitle("Featured Products") {
                TextButton(onClick = onMap) { Text("🗺️ Map") }
                TextButton(onClick = onCompare) { Text("🛣️ Route") }
            }
        }
        if (listings.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("No listings to show.", fontWeight = FontWeight.Bold)
                        Text("Press + Sell to post a listing.")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onSell) { Text("+ Sell") }
                    }
                }
            }
        }
        items(listings, key = { it.id }) { l ->
            ListingCard(
                listing = l,
                isFavorite = favorites.contains(l.id),
                onOpen = { onOpen(l) },
                onFavorite = { onFavorite(l) },
                onAddToCart = { onAddToCart(l) },
                showViews = l.sellerId == uid
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
fun CategoriesTab(
    ownerUid: String,
    category: String,
    onCategory: (String) -> Unit,
    priceFilter: String,
    onPriceFilter: (String) -> Unit,
    sort: String,
    onSort: (String) -> Unit,
    town: String,
    onTown: (String) -> Unit,
    listings: List<Listing>,
    favorites: Set<String>,
    onOpen: (Listing) -> Unit,
    onFavorite: (Listing) -> Unit,
    onAddToCart: (Listing) -> Unit
) {
    val S = LocalStrings.current
    val trending = listings.filter { it.views > 0 }
        .sortedByDescending { it.views }
        .take(5)

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                "Categories",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        item { CategoryRows(category, onCategory) }
        item {
            Column {
                Text(S.town, style = MaterialTheme.typography.labelMedium)
                TownChips(town, onTown)
            }
        }
        item { FilterBar(priceFilter, onPriceFilter, sort, onSort) }
        if (trending.isNotEmpty()) {
            item { SectionTitle("🔥 Trending now") }
            // Two cards per row, as in the mockup's "Trending Now" grid.
            items(trending.chunked(2)) { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { l ->
                        ListingTile(
                            listing = l,
                            isFavorite = favorites.contains(l.id),
                            onOpen = { onOpen(l) },
                            onFavorite = { onFavorite(l) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (pair.size == 1) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        item {
            Text("${listings.size} listings", style = MaterialTheme.typography.labelMedium)
        }
        items(listings, key = { it.id }) { l ->
            ListingCard(
                listing = l,
                isFavorite = favorites.contains(l.id),
                onOpen = { onOpen(l) },
                onFavorite = { onFavorite(l) },
                onAddToCart = { onAddToCart(l) },
                showViews = l.sellerId == ownerUid
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

// ---------------------------------------------------------------------------
// Manage saved searches
// ---------------------------------------------------------------------------

@Composable
fun SavedSearchesScreen(uid: String, onApply: (String, String, String) -> Unit) {
    val S = LocalStrings.current
    val db = remember { FirebaseFirestore.getInstance() }
    val context = androidx.compose.ui.platform.LocalContext.current
    var searches by remember { mutableStateOf<List<SavedSearch>>(emptyList()) }
    var editing by remember { mutableStateOf<SavedSearch?>(null) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    DisposableEffect(uid) {
        val reg = db.collection("savedSearches").document(uid).collection("items")
            .addSnapshotListener { snap, _ ->
                searches = snap?.documents?.mapNotNull { d ->
                    d.toObject(SavedSearch::class.java)?.copy(id = d.id)
                }?.sortedByDescending { it.createdAt } ?: emptyList()
            }
        onDispose { reg.remove() }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Notifications, contentDescription = null, tint = BrandBlue)
            Spacer(Modifier.width(6.dp))
            Text(
                S.savedSearches,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            S.savedSearchesHint,
            style = MaterialTheme.typography.labelSmall
        )
        Spacer(Modifier.height(8.dp))

        if (searches.isEmpty()) {
            Text(S.noSavedSearches)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(searches, key = { it.id }) { s ->
                    Card(
                        onClick = { onApply(s.query, s.category, s.town) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    s.name.ifBlank { s.label },
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    listOfNotNull(
                                        s.query.takeIf { it.isNotBlank() },
                                        s.category.takeIf { it != "All" },
                                        s.town.takeIf { it != "All" }
                                    ).joinToString(" • ").ifBlank { "All listings" },
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            IconButton(onClick = { editing = s }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Rename")
                            }
                            IconButton(onClick = { deleteSavedSearch(db, uid, s.id) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Remove",
                                    tint = Color(0xFFE53935)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { s ->
        var name by remember(s.id) { mutableStateOf(s.name.ifBlank { s.label }) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Rename") },
            text = {
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text("Saved search name") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    db.collection("savedSearches").document(uid).collection("items")
                        .document(s.id).update("name", name.trim())
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("No") }
            }
        )
    }
}

// ---------------------------------------------------------------------------
// Listings cache (offline mirror)
// ---------------------------------------------------------------------------

private fun listingsPrefs(context: Context) =
    context.getSharedPreferences("listings_cache", Context.MODE_PRIVATE)

fun loadListingsCache(context: Context): List<Listing> {
    val raw = listingsPrefs(context).getString("all", null) ?: return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Listing(
                id = o.optString("id"),
                title = o.optString("title"),
                description = o.optString("description"),
                price = o.optDouble("price"),
                category = o.optString("category"),
                town = o.optString("town"),
                sellerId = o.optString("sellerId"),
                sellerName = o.optString("sellerName"),
                contact = o.optString("contact"),
                createdAt = o.optLong("createdAt"),
                latitude = if (o.has("latitude") && !o.isNull("latitude")) o.optDouble("latitude") else null,
                longitude = if (o.has("longitude") && !o.isNull("longitude")) o.optDouble("longitude") else null,
                imageUrl = o.optString("imageUrl"),
                imageUrls = (0 until o.optJSONArray("imageUrls")?.length().orZero()).map { j ->
                    o.optJSONArray("imageUrls")!!.optString(j)
                },
                views = o.optLong("views"),
                cartAdds = o.optLong("cartAdds"),
                isPerishable = o.optBoolean("isPerishable"),
                expiresAt = o.optLong("expiresAt"),
                stock = if (o.has("stock")) o.optLong("stock") else -1L
            )
        }
    }.getOrDefault(emptyList())
}

fun saveListingsCache(context: Context, items: List<Listing>) {
    val arr = JSONArray()
    items.forEach { l ->
        arr.put(
            JSONObject().apply {
                put("id", l.id)
                put("title", l.title)
                put("description", l.description)
                put("price", l.price)
                put("category", l.category)
                put("town", l.town)
                put("sellerId", l.sellerId)
                put("sellerName", l.sellerName)
                put("contact", l.contact)
                put("createdAt", l.createdAt)
                if (l.latitude != null) put("latitude", l.latitude) else put("latitude", JSONObject.NULL)
                if (l.longitude != null) put("longitude", l.longitude) else put("longitude", JSONObject.NULL)
                put("imageUrl", l.imageUrl)
                put("imageUrls", JSONArray(l.imageUrls))
                put("views", l.views)
                put("cartAdds", l.cartAdds)
                put("isPerishable", l.isPerishable)
                put("expiresAt", l.expiresAt)
                put("stock", l.stock)
            }
        )
    }
    listingsPrefs(context).edit().putString("all", arr.toString()).apply()
}

private fun Int?.orZero(): Int = this ?: 0

// ---------------------------------------------------------------------------
// Share card rendering
// ---------------------------------------------------------------------------

fun renderShareCard(listing: Listing): android.graphics.Bitmap {
    val width = 1080
    val height = 1350
    val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawColor(android.graphics.Color.parseColor("#1E3A8A"))
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.WHITE
        isAntiAlias = true
    }
    paint.textSize = 72f
    canvas.drawText(listing.title, 60f, 200f, paint)
    paint.textSize = 48f
    canvas.drawText("₱" + formatPrice(listing.price), 60f, 320f, paint)
    canvas.drawText(listing.town, 60f, 420f, paint)
    if (listing.contact.isNotBlank()) {
        canvas.drawText("Contact: ${listing.contact}", 60f, 520f, paint)
    }
    paint.textSize = 36f
    paint.color = android.graphics.Color.parseColor("#C3CFE0")
    canvas.drawText("Camiguin Market", 60f, height - 80f, paint)
    return bitmap
}

// ---------------------------------------------------------------------------
// Admin PIN: a second lock in front of the admin dashboard. The hash lives on
// admins/{uid} (written from the Console only), so a fresh install cannot pick
// its own PIN — it has to use the one the owner stored.
// ---------------------------------------------------------------------------

fun sha256Hex(value: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

@Composable
fun AdminPinGate(
    uid: String,
    expectedHash: String?,
    serverError: String? = null,
    onUnlocked: () -> Unit,
    onSetPin: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val configured = !expectedHash.isNullOrBlank()

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Filled.Security, contentDescription = null, tint = BrandBlue, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            if (configured) "Enter admin PIN" else "Set admin PIN",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (configured) "The admin dashboard is locked."
            else "Choose a PIN for this admin account. It is stored in Firebase, so every install (a fresh one too) has to use it, and it can only be changed from the Console.",
            style = MaterialTheme.typography.bodySmall,
            color = BrandMuted,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { typed -> pin = typed.filter { it.isDigit() }.take(12) },
            label = { Text(if (configured) "PIN" else "New PIN") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth()
        )
        if (!configured) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = confirm,
                onValueChange = { typed -> confirm = typed.filter { it.isDigit() }.take(12) },
                label = { Text("Confirm PIN") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth()
            )
        }
        (error ?: serverError)?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val typed = pin.trim()
                if (configured) {
                    if (sha256Hex(typed) == expectedHash!!.trim().lowercase()) {
                        onUnlocked()
                    } else {
                        error = "Wrong PIN."
                    }
                } else if (typed.length < 4) {
                    error = "Use at least 4 digits."
                } else if (typed != confirm.trim()) {
                    error = "The PINs do not match."
                } else {
                    error = null
                    onSetPin(sha256Hex(typed))
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (configured) "Unlock" else "Save PIN") }
    }
}

// ---------------------------------------------------------------------------
// In-app update: installing the new APK over this one keeps every listing,
// chat and setting (a reinstall would start from scratch).
// ---------------------------------------------------------------------------

data class AppUpdate(val version: String, val pageUrl: String)

private const val RELEASES_API =
    "https://api.github.com/repos/bernavicg1-beep/com.camiguinmarket.app/releases/latest"

private fun versionParts(value: String): List<Int> =
    value.trim().removePrefix("v").removePrefix("V")
        .split('.', '-', '+')
        .mapNotNull { it.toIntOrNull() }

internal fun isNewerVersion(latest: String, current: String): Boolean {
    val a = versionParts(latest)
    val b = versionParts(current)
    if (a.isEmpty()) return false
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

/** Latest GitHub release when it is newer than this build, otherwise null. */
fun fetchAppUpdate(): AppUpdate? = try {
    val conn = java.net.URL(RELEASES_API).openConnection() as java.net.HttpURLConnection
    conn.connectTimeout = 8000
    conn.readTimeout = 8000
    conn.setRequestProperty("Accept", "application/vnd.github+json")
    conn.setRequestProperty("User-Agent", "CamiguinMarket")
    try {
        if (conn.responseCode == 200) {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(body)
            val tag = o.optString("tag_name")
            val page = o.optString("html_url")
            if (page.isNotBlank() && isNewerVersion(tag, BuildConfig.VERSION_NAME)) {
                AppUpdate(tag.trim().removePrefix("v"), page)
            } else null
        } else null
    } finally {
        conn.disconnect()
    }
} catch (e: Exception) {
    null
}

@Composable
fun AppUpdatePrompt() {
    val context = LocalContext.current
    var update by remember { mutableStateOf<AppUpdate?>(null) }
    LaunchedEffect(Unit) {
        val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fetchAppUpdate() }
        if (found != null) update = found
    }
    update?.let { u ->
        AlertDialog(
            onDismissRequest = { update = null },
            title = { Text("Update available") },
            text = {
                Text(
                    "Version ${u.version} is ready. Install it over this app and your listings, chats and settings stay."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    update = null
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.pageUrl)))
                    }
                }) { Text("Get the update") }
            },
            dismissButton = { TextButton(onClick = { update = null }) { Text("Later") } }
        )
    }
}
