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
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.camiguinmarket.app.BuildConfig
import android.app.Activity
import android.content.Context
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

// Palette taken from the poster and the mockups: deep ocean blue header, white
// cards on a pale blue page, gold as the small highlight colour.
val BrandBlue = Color(0xFF1565C0)
val BrandBlueDeep = Color(0xFF0E4FA8)
val BrandBlueDark = Color(0xFF0B3F91)
val BrandSky = Color(0xFFE8F1FD)
val BrandGold = Color(0xFFF2A900)
val BrandGreen = Color(0xFF1B7F5C)
val BrandInk = Color(0xFF10233F)
val BrandMuted = Color(0xFF5B6B80)
val BrandPage = Color(0xFFF5F8FD)
val BrandBadge = Color(0xFFE53935)

/** Header gradient, from the darker top edge to the mid blue of the mockups. */
val BrandHeaderBrush: Brush =
    Brush.verticalGradient(listOf(BrandBlueDeep, Color(0xFF1976D2)))

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
    if (searches.isEmpty() || listings.isEmpty()) return
    searches.forEach { s ->
        val matches = listings.filter {
            it.createdAt > s.lastCheckedAt && it.sellerId != uid &&
                (s.query.isBlank() || it.title.contains(s.query, true) ||
                    it.description.contains(s.query, true)) &&
                (s.category == "All" || normalizeCategory(it.category) == normalizeCategory(s.category)) &&
                (s.town == "All" || it.town == s.town)
        }
        val latest = listings.maxOfOrNull { it.createdAt } ?: s.lastCheckedAt
        if (matches.isNotEmpty()) {
            notifyUser(
                db = db,
                toUid = uid,
                fromUid = uid,
                fromName = "Saved search",
                message = "🔔 ${matches.size} new listings for \"${s.label}\"".take(190)
            )
            db.collection("savedSearches").document(uid).collection("items")
                .document(s.id).update("lastCheckedAt", latest)
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
        val snap = tx.get(ref)
        if (snap.exists()) {
            tx.update(ref, "quantity", (snap.getLong("quantity") ?: 1L) + 1L)
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

fun userDisplayName(): String =
    FirebaseAuth.getInstance().currentUser
        ?.displayName
        ?.takeIf { it.isNotBlank() }
        ?: "Bernavic Gamo"

fun updateCartQuantity(db: FirebaseFirestore, uid: String, itemId: String, quantity: Long) {
    val ref = db.collection("carts").document(uid).collection("items").document(itemId)
    if (quantity <= 0) ref.delete() else ref.update("quantity", quantity)
}

// ---------------------------------------------------------------------------
// Paborito (Firestore: favorites/{uid}/items/{listingId})
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
    listing: Listing? = null
) {
    if (toUid.isBlank() || toUid == fromUid) return
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
}

// ---------------------------------------------------------------------------
// Chat (Firestore: chats/{chatId}/messages/{autoId})
// chatId = the two uids sorted, so both users share the same thread.
// ---------------------------------------------------------------------------

data class ChatMessage(
    val id: String = "",
    val from: String = "",
    val text: String = "",
    val createdAt: Long = 0L
)

fun chatIdFor(a: String, b: String): String = if (a < b) "${a}_$b" else "${b}_$a"

/** Message time, for example "7:42 PM". */
fun chatTime(ts: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ts))

fun ensureChat(db: FirebaseFirestore, uid: String, otherUid: String, otherName: String, listingTitle: String) {
    val id = chatIdFor(uid, otherUid)
    db.collection("chats").document(id).set(
        mapOf(
            "participants" to listOf(uid, otherUid),
            "names" to mapOf(uid to "Me", otherUid to otherName),
            "listingTitle" to listingTitle,
            "updatedAt" to System.currentTimeMillis()
        ),
        com.google.firebase.firestore.SetOptions.merge()
    )
}

fun sendChatMessage(
    db: FirebaseFirestore,
    uid: String,
    otherUid: String,
    otherName: String,
    text: String,
    listingTitle: String
) {
    if (text.isBlank()) return
    ensureChat(db, uid, otherUid, otherName, listingTitle)
    val id = chatIdFor(uid, otherUid)
    val chatRef = db.collection("chats").document(id)
    val senderName = userDisplayName()
    chatRef.collection("messages").add(ChatMessage(from = uid, text = text.trim(), createdAt = System.currentTimeMillis()))
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
            .background(Brush.verticalGradient(listOf(Color(0xFF4FA3F7), Color(0xFF0B4AA2))))
            .then(
                if (ring) Modifier.border(
                    1.5.dp,
                    Color.White.copy(alpha = 0.85f),
                    RoundedCornerShape(size * 0.3f)
                ) else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.ShoppingCart,
            contentDescription = "Camiguin Market",
            tint = Color.White,
            modifier = Modifier
                .size(size * 0.44f)
                .offset(x = -size * 0.1f, y = -size * 0.08f)
        )
        Icon(
            Icons.Filled.Park,
            contentDescription = null,
            tint = Color(0xFF7CE38B),
            modifier = Modifier
                .size(size * 0.42f)
                .align(Alignment.BottomEnd)
                .offset(x = -size * 0.06f, y = -size * 0.06f)
        )
    }
}

@Composable
fun BrandHeader(
    cartCount: Int,
    unreadCount: Int,
    chatUnread: Int,
    onSearch: () -> Unit,
    onCart: () -> Unit,
    onChats: () -> Unit,
    onNotifications: () -> Unit
) {
    // Blue gradient bar from the mockups, with white type and icons.
    Box(
        Modifier
            .fillMaxWidth()
            .background(BrandHeaderBrush)
            .statusBarsPadding()
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BrandLogo(42.dp, ring = true)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Camiguin Market",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    "Buy • Sell • Connect",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.88f)
                )
            }
            IconTextButton(Icons.Filled.Search, 0, onSearch, tint = Color.White)
            IconTextButton(Icons.Filled.ShoppingCart, cartCount, onCart, tint = Color.White)
            IconTextButton(Icons.Filled.Chat, chatUnread, onChats, tint = Color.White)
            IconTextButton(Icons.Filled.Notifications, unreadCount, onNotifications, tint = Color.White)
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

/** Hero banner from the poster: the island photo under the market slogan. */
@Composable
fun HeroBanner() {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = BrandBlueDark),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(Modifier.fillMaxWidth().height(168.dp)) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_island),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0x1A000000), Color(0x4D04224A), Color(0xE604224A))
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
                    fontWeight = FontWeight.Bold
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Support Camiguin",
                        color = Color.White,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
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

@Composable
fun CategoryGrid(selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        categoryIcons.entries.toList().chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (name, icon) ->
                    val tint = categoryTints[name] ?: BrandBlue
                    val active = name == selected
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .then(
                                if (active) Modifier.border(
                                    2.dp,
                                    BrandBlue,
                                    RoundedCornerShape(16.dp)
                                ) else Modifier
                            )
                            .clickable { onSelect(name) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            Modifier
                                .size(44.dp)
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
                        Spacer(Modifier.height(6.dp))
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
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
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
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = modifier.width(164.dp)
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
        modifier = Modifier.fillMaxWidth()
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
                    IconButton(onClick = onFavorite, modifier = Modifier.size(28.dp)) {
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
fun CartScreen(uid: String, onBrowse: () -> Unit, onCheckout: (List<CartItem>) -> Unit) {
    val db = remember { FirebaseFirestore.getInstance() }
    val context = LocalContext.current
    // Show the local cache first so something appears even offline.
    var items by remember { mutableStateOf(loadCartCache(context, uid)) }
    var offline by remember { mutableStateOf(false) }

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

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ShoppingCart, contentDescription = null, tint = BrandBlue)
            Spacer(Modifier.width(6.dp))
            Text("My cart", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
                    Text("Your cart is empty.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onBrowse) { Text("Search products") }
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                items(items, key = { it.id }) { item ->
                    Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                if (item.imageUrl.isNotBlank()) RemoteImage(item.imageUrl, Modifier.fillMaxSize())
                                else Text("🛍️")
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("₱%.2f".format(item.price), color = BrandBlue)
                                Text("Seller: ${item.sellerName} • ${item.town}", style = MaterialTheme.typography.labelSmall)
                                if (item.contact.isNotBlank()) {
                                    Text("📞 ${item.contact}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                IconButton(onClick = { updateCartQuantity(db, uid, item.id, item.quantity + 1) }) {
                                    Icon(Icons.Filled.Add, contentDescription = "Dugangi")
                                }
                                Text("${item.quantity}", fontWeight = FontWeight.Bold)
                                IconButton(onClick = { updateCartQuantity(db, uid, item.id, item.quantity - 1) }) {
                                    Icon(Icons.Filled.Remove, contentDescription = "Kunhoran")
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Total", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("₱%.2f".format(total), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onCheckout(items) },
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) { Text("✅ Checkout (₱%.2f)".format(total)) }
            Spacer(Modifier.height(6.dp))
            Text(
                "Each seller receives their own order and notification.",
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Paborito
// ---------------------------------------------------------------------------

@Composable
fun FavoritesScreen(uid: String, onOpenListing: (String) -> Unit) {
    val db = remember { FirebaseFirestore.getInstance() }
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
            Text("My Favorites", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        if (items.isEmpty()) {
            Text("No favorites yet. Press 🤍 on a listing.")
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
fun NotificationsScreen(uid: String) {
    val db = remember { FirebaseFirestore.getInstance() }
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
            Text("Notifications", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        if (items.isEmpty()) {
            Text("No notifications yet. You'll get them here when someone adds your listing to their cart or messages you.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.id }) { n ->
                    Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(n.message, style = MaterialTheme.typography.bodyMedium)
                            if (n.listingTitle.isNotBlank()) {
                                Text("Listing: ${n.listingTitle}", style = MaterialTheme.typography.labelSmall)
                            }
                            Text("from ${n.fromName}", style = MaterialTheme.typography.labelSmall)
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
fun ChatScreen(uid: String, otherUid: String, otherName: String, listingTitle: String) {
    val db = remember { FirebaseFirestore.getInstance() }
    var messages by remember { mutableStateOf<List<ChatMessage>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    // Last time the other side opened/read the thread.
    var otherRead by remember { mutableStateOf(0L) }
    // Typing indicator: when the other side last typed, plus a local ticker.
    var otherTypingAt by remember { mutableStateOf(0L) }
    var lastTypingWrite by remember { mutableStateOf(0L) }
    var tick by remember { mutableStateOf(0L) }
    val chatId = remember(otherUid) { chatIdFor(uid, otherUid) }

    LaunchedEffect(chatId) {
        ensureChat(db, uid, otherUid, otherName, listingTitle)
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Chat, contentDescription = null, tint = BrandBlue)
            Spacer(Modifier.width(6.dp))
            Text(otherName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Text(
            "by Bernavic Gamo",
            style = MaterialTheme.typography.labelSmall
        )
        if (listingTitle.isNotBlank()) {
            Text("About: $listingTitle", style = MaterialTheme.typography.labelSmall)
        }
        if (otherTypingAt > 0 && tick - otherTypingAt < 7000) {
            Text(
                "nagsulat…",
                style = MaterialTheme.typography.labelSmall,
                color = BrandBlue,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(messages, key = { it.id }) { m ->
                val mine = m.from == uid
                val senderName = if (mine) userDisplayName() else otherName
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
                ) {
                    Surface(
                        color = if (mine) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Text(
                                m.text,
                                color = if (mine) Color.White
                                else MaterialTheme.colorScheme.onSurface
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    chatTime(m.createdAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (mine) Color.White.copy(alpha = 0.8f)
                                    else Color.Gray
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    senderName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (mine) Color.White.copy(alpha = 0.8f)
                                    else Color.Gray
                                )
                                if (mine) {
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                text, { text = it },
                placeholder = { Text("Message…") },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    sendChatMessage(db, uid, otherUid, otherName, text, listingTitle)
                    text = ""
                    db.collection("chats").document(chatId).update("typing.$uid", 0L)
                },
                shape = RoundedCornerShape(20.dp)
            ) { Icon(Icons.Filled.Send, contentDescription = "Send") }
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
    myListings: List<Listing>,
    isAdmin: Boolean,
    dark: Boolean,
    onToggleDark: (Boolean) -> Unit,
    onFavorites: () -> Unit,
    onOrders: () -> Unit,
    onInbox: () -> Unit,
    onModeration: () -> Unit,
    onSavedSearches: () -> Unit,
    onListingStats: () -> Unit,
    onLogout: () -> Unit,
    onDeleteAccount: () -> Unit
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(BrandBlue),
            contentAlignment = Alignment.Center
        ) {
            Text(
                displayName.take(1).uppercase(),
                color = Color.White,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(displayName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(email, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(12.dp))
        PhoneVerifySection(uid)
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("${myListings.size}", "My listings", Modifier.weight(1f))
            StatCard(
                "₱%.0f".format(myListings.sumOf { it.price }),
                "Total value",
                Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(12.dp))

        SellerStats(uid, myListings)

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onFavorites, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Favorite, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Favorites", maxLines = 1)
            }
            OutlinedButton(onClick = onOrders, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.ReceiptLong, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Orders", maxLines = 1)
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onInbox, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Chats")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onSavedSearches, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Notifications, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Saved searches")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onListingStats, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.ReceiptLong, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Listing stats")
        }
        if (isAdmin) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onModeration, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Moderation")
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Dark mode", style = MaterialTheme.typography.bodyMedium)
            Switch(checked = dark, onCheckedChange = onToggleDark)
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

/**
 * Phone number with SMS verification. The number is stored on the user
 * document only after Firebase confirms the 6-digit code sent by text.
 */
@Composable
fun PhoneVerifySection(uid: String) {
    val context = LocalContext.current
    val auth = FirebaseAuth.getInstance()
    val db = remember { FirebaseFirestore.getInstance() }
    var savedPhone by remember { mutableStateOf<String?>(null) }
    var verified by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var verificationId by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }

    fun saveVerified(number: String) {
        db.collection("users").document(uid)
            .set(mapOf("phone" to number, "phoneVerified" to true), SetOptions.merge())
        savedPhone = number
        verified = true
        editing = false
        verificationId = null
        code = ""
        busy = false
    }

    fun linkWith(credential: PhoneAuthCredential) {
        val user = auth.currentUser
        if (user == null) {
            busy = false
            info = "You are not signed in."
            return
        }
        busy = true
        user.linkWithCredential(credential).addOnCompleteListener { t ->
            if (t.isSuccessful) {
                saveVerified(user.phoneNumber ?: normalizePhone(phone))
                info = "Phone number verified ✅"
            } else {
                busy = false
                info = t.exception?.localizedMessage ?: "Could not verify this number."
            }
        }
    }

    fun sendCode() {
        val activity = context.findActivity()
        if (activity == null) {
            info = "SMS verification is not available here."
            return
        }
        val number = normalizePhone(phone)
        busy = true
        info = null
        val callbacks = object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
            override fun onVerificationCompleted(credential: PhoneAuthCredential) {
                linkWith(credential)
            }

            override fun onVerificationFailed(e: FirebaseException) {
                busy = false
                info = e.localizedMessage ?: "Verification failed."
            }

            override fun onCodeSent(id: String, token: PhoneAuthProvider.ForceResendingToken) {
                busy = false
                verificationId = id
                info = "Code sent to $number"
            }
        }
        PhoneAuthProvider.verifyPhoneNumber(
            PhoneAuthOptions.newBuilder(auth)
                .setPhoneNumber(number)
                .setTimeout(60L, TimeUnit.SECONDS)
                .setActivity(activity)
                .setCallbacks(callbacks)
                .build()
        )
    }

    LaunchedEffect(uid) {
        db.collection("users").document(uid).get().addOnSuccessListener { d ->
            savedPhone = d.getString("phone")
            verified = d.getBoolean("phoneVerified") ?: false
            if (!verified) editing = true
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Phone, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "Phone number",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                if (verified) Text("Verified ✅", style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(6.dp))

            if (verified && !editing) {
                Text(savedPhone ?: "", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = {
                    phone = savedPhone ?: ""
                    editing = true
                    info = null
                    verificationId = null
                }) { Text("Change number") }
            } else {
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone number") },
                    placeholder = { Text("09XX XXX XXXX") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Phone,
                        imeAction = ImeAction.Done
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !busy && phone.count { it.isDigit() } >= 10,
                        onClick = { sendCode() }
                    ) {
                        Text(if (verificationId == null) "Send code" else "Resend code")
                    }
                    if (verified) {
                        TextButton(onClick = { editing = false; info = null }) { Text("Cancel") }
                    }
                }
                if (verificationId != null) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { input -> code = input.filter { it.isDigit() }.take(6) },
                        label = { Text("6-digit code") },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        enabled = !busy && code.length == 6,
                        onClick = {
                            val id = verificationId
                            if (id == null) {
                                info = "Tap \"Send code\" first."
                            } else {
                                linkWith(PhoneAuthProvider.getCredential(id, code))
                            }
                        }
                    ) { Text(if (busy) "Verifying…" else "Verify code") }
                }
            }

            info?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
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

/** The hosting Activity for the Firebase phone verification callbacks. */
fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
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
    val trending = listings.filter { it.views > 0 }
        .sortedByDescending { it.views }
        .take(5)

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            val ctx = LocalContext.current
            var recent by remember { mutableStateOf(loadRecentSearches(ctx)) }
            Column {
                SearchBar(
                    query = query,
                    onQuery = onQuery,
                    onSubmit = {
                        addRecentSearch(ctx, query)
                        recent = loadRecentSearches(ctx)
                    }
                )
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
            var searches by remember { mutableStateOf<List<SavedSearch>>(emptyList()) }
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
        item { CategoryGrid(category, onCategory) }
        if (trending.isNotEmpty()) {
            item { SectionTitle("🔥 Trending now") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(trending, key = { it.id }) { l ->
                        ListingTile(
                            listing = l,
                            isFavorite = favorites.contains(l.id),
                            onOpen = { onOpen(l) },
                            onFavorite = { onFavorite(l) }
                        )
                    }
                }
            }
        }
        item {
            SectionTitle("Featured Products") {
                TextButton(onClick = onMap) { Text("🗺️ Map") }
                TextButton(onClick = onCompare) { Text("⚖️ Compare") }
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
        item { CategoryGrid(category, onCategory) }
        item {
            Column {
                Text("Town", style = MaterialTheme.typography.labelMedium)
                TownChips(town, onTown)
            }
        }
        item { FilterBar(priceFilter, onPriceFilter, sort, onSort) }
        if (trending.isNotEmpty()) {
            item { SectionTitle("🔥 Trending now") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(trending, key = { it.id }) { l ->
                        ListingTile(
                            listing = l,
                            isFavorite = favorites.contains(l.id),
                            onOpen = { onOpen(l) },
                            onFavorite = { onFavorite(l) }
                        )
                    }
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
    val db = remember { FirebaseFirestore.getInstance() }
    var searches by remember { mutableStateOf<List<SavedSearch>>(emptyList()) }
    var editing by remember { mutableStateOf<SavedSearch?>(null) }

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
                "Saved searches",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            "Tap to apply the filter. Rename or remove it.",
            style = MaterialTheme.typography.labelSmall
        )
        Spacer(Modifier.height(8.dp))

        if (searches.isEmpty()) {
            Text("No saved searches yet. On Home, press \"Save this search\".")
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
                }) { Text("Tipigi") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("No") }
            }
        )
    }
}
