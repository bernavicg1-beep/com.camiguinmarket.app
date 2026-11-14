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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import android.content.Context
import com.google.firebase.firestore.FieldValue
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

// ---------------------------------------------------------------------------
// Design system gikan sa mockup: asul nga tema, linginon nga cards, gold accent.
// ---------------------------------------------------------------------------

val BrandBlue = Color(0xFF1565C0)
val BrandBlueDark = Color(0xFF0D47A1)
val BrandSky = Color(0xFFE3F2FD)
val BrandGold = Color(0xFFF2A900)
val BrandGreen = Color(0xFF1B7F5C)

val marketColorScheme
    @Composable get() = lightColorScheme(
        primary = BrandBlue,
        onPrimary = Color.White,
        primaryContainer = BrandSky,
        onPrimaryContainer = BrandBlueDark,
        secondary = BrandGold,
        onSecondary = Color.White,
        background = Color(0xFFF7F9FC),
        surface = Color.White,
        surfaceVariant = BrandSky,
        onSurfaceVariant = Color(0xFF334155)
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
    val category: String = "Tanan",
    val town: String = "Tanan",
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

/** Pag-abli sa app: pahibaloon ang user kon dunay bag-ong listing nga mohaom. */
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
                (s.category == "Tanan" || it.category == s.category) &&
                (s.town == "Tanan" || it.town == s.town)
        }
        val latest = listings.maxOfOrNull { it.createdAt } ?: s.lastCheckedAt
        if (matches.isNotEmpty()) {
            notifyUser(
                db = db,
                toUid = uid,
                fromUid = uid,
                fromName = "Saved search",
                message = "🔔 ${matches.size} ka bag-ong listing para sa \"${s.label}\"".take(190)
            )
            db.collection("savedSearches").document(uid).collection("items")
                .document(s.id).update("lastCheckedAt", latest)
        }
    }
}

/** Ang mga tab sa ubos nga navigation. */
enum class MarketTab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Filled.Home),
    Categories("Categories", Icons.Filled.Apps),
    Sell("Sell", Icons.Filled.Add),
    Cart("Cart", Icons.Filled.ShoppingCart),
    Profile("Profile", Icons.Filled.Person)
}

/** Ang mga kategorya sa grid (emoji ang icon, walay dugang nga library). */
val priceFilters = listOf("Tanan", "Ubos sa ₱50", "₱50–₱200", "₱200–₱500", "Sobras ₱500")
val sortOptions = listOf("Bag-o", "Presyo ↑", "Presyo ↓", "Trending")

fun sortListings(list: List<Listing>, sort: String): List<Listing> = when (sort) {
    "Presyo ↑" -> list.sortedBy { it.price }
    "Presyo ↓" -> list.sortedByDescending { it.price }
    "Trending" -> list.sortedByDescending { it.views }
    else -> list
}

fun matchesPrice(price: Double, filter: String): Boolean = when (filter) {
    "Ubos sa ₱50" -> price < 50
    "₱50–₱200" -> price in 50.0..200.0
    "₱200–₱500" -> price in 200.0..500.0
    "Sobras ₱500" -> price > 500
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
    "Tanan" to Icons.Filled.Apps,
    "Pagkaon ug Seafood" to Icons.Filled.Restaurant,
    "Lokal nga Produkto" to Icons.Filled.LocalFlorist,
    "Sinina" to Icons.Filled.Checkroom,
    "Gadgets" to Icons.Filled.PhoneAndroid,
    "Uban pa" to Icons.Filled.MoreHoriz
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
        onResult("Imo man kining listing.")
        return
    }
    val ref = db.collection("carts").document(uid).collection("items").document(listing.id)
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
        null
    }.addOnSuccessListener {
        notifyUser(
            db = db,
            toUid = listing.sellerId,
            fromUid = uid,
            fromName = fromName,
            message = "$fromName nagdugang sa cart: ${listing.title}",
            listing = listing
        )
        onResult(null)
    }.addOnFailureListener { onResult(it.localizedMessage ?: "Napakyas ang pagdugang sa cart.") }
}

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
    }.addOnFailureListener { onResult(it.localizedMessage ?: "Napakyas ang paborito.") }
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
// Ang chatId = duha ka uid nga gi-sort, busa pareho ang thread sa duha ka user.
// ---------------------------------------------------------------------------

data class ChatMessage(
    val id: String = "",
    val from: String = "",
    val text: String = "",
    val createdAt: Long = 0L
)

fun chatIdFor(a: String, b: String): String = if (a < b) "${a}_$b" else "${b}_$a"

/** Oras sa mensahe, pananglitan "7:42 PM". */
fun chatTime(ts: Long): String =
    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(ts))

fun ensureChat(db: FirebaseFirestore, uid: String, otherUid: String, otherName: String, listingTitle: String) {
    val id = chatIdFor(uid, otherUid)
    db.collection("chats").document(id).set(
        mapOf(
            "participants" to listOf(uid, otherUid),
            "names" to mapOf(uid to "Ako", otherUid to otherName),
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
    chatRef.collection("messages").add(ChatMessage(from = uid, text = text.trim(), createdAt = System.currentTimeMillis()))
    chatRef.update(
        mapOf(
            "updatedAt" to System.currentTimeMillis(),
            "lastMessage" to text.trim(),
            // Kon ang pikas wala pa kaabli sa chat, dugangi ang iyang unread badge.
            "unread.$otherUid" to FieldValue.increment(1)
        )
    )
    db.collection("chats").document(id).get().addOnSuccessListener { snap ->
        val participants = (snap.get("participants") as? List<*>)?.filterIsInstance<String>().orEmpty()
        participants.filter { it != uid }.forEach { other ->
            notifyUser(
                db = db,
                toUid = other,
                fromUid = uid,
                fromName = FirebaseAuth.getInstance().currentUser?.displayName ?: "User",
                message = text.trim()
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Mga komponent sa UI
// ---------------------------------------------------------------------------

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
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 2.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Brush.verticalGradient(listOf(BrandBlue, BrandBlueDark))),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Filled.Landscape, contentDescription = null, tint = Color.White) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Camiguin Market",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text("Buy Local. Support Camiguin.", style = MaterialTheme.typography.labelSmall)
            }
            IconTextButton(icon = Icons.Filled.Search, badge = 0, onClick = onSearch)
            IconTextButton(icon = Icons.Filled.ShoppingCart, badge = cartCount, onClick = onCart)
            IconTextButton(icon = Icons.Filled.Chat, badge = chatUnread, onClick = onChats)
            IconTextButton(icon = Icons.Filled.Notifications, badge = unreadCount, onClick = onNotifications)
        }
    }
}

@Composable
fun IconTextButton(icon: ImageVector, badge: Int, onClick: () -> Unit) {
    Box {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = null) }
        if (badge > 0) {
            Badge(
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 2.dp),
                containerColor = BrandGold
            ) { Text(if (badge > 9) "9+" else "$badge") }
        }
    }
}

/** Recent searches: tipigan sa device aron dali makabalik ang user. */
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
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text("Search products…") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun HeroBanner() {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = BrandBlue),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            Modifier
                .background(Brush.linearGradient(listOf(BrandBlue, BrandBlueDark)))
                .padding(18.dp)
        ) {
            Column {
                Icon(
                    Icons.Filled.Landscape,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(34.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Welcome to Camiguin Market",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "Buy Local. Support Local!",
                    color = BrandSky,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    towns.take(3).forEach {
                        Surface(
                            color = Color.White.copy(alpha = 0.18f),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text("📍 $it", color = Color.White, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) }
                    }
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

@Composable
fun CategoryGrid(selected: String, onSelect: (String) -> Unit) {
    val entries = categoryIcons.entries.toList()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (name, emoji) ->
                    val active = name == selected
                    Card(
                        onClick = { onSelect(name) },
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (active) BrandBlue
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            Modifier.padding(vertical = 12.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                emoji,
                                contentDescription = null,
                                tint = if (active) Color.White else MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(26.dp)
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                name,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (active) Color.White else MaterialTheme.colorScheme.onSurface,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
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
        shape = RoundedCornerShape(16.dp),
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
                            contentDescription = "Paborito",
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
                if (showViews) {
                    Text(
                        "👁 ${listing.views} ka views",
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
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        MarketTab.entries.forEach { item ->
            NavigationBarItem(
                selected = tab == item,
                onClick = { onSelect(item) },
                icon = {
                    if (item == MarketTab.Cart && cartCount > 0) {
                        BadgedBox(badge = { Badge(containerColor = BrandGold) { Text("$cartCount") } }) {
                            Icon(item.icon, contentDescription = item.label)
                        }
                    } else {
                        Icon(item.icon, contentDescription = item.label)
                    }
                },
                label = { Text(item.label, style = MaterialTheme.typography.labelSmall) }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Cart screen
// ---------------------------------------------------------------------------

/** Offline mirror sa cart: makita gihapon bisan walay internet. */
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
    // Unaha ang local cache aron dunay makita bisan offline.
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
            Text("Akong Cart", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        if (offline) {
            Text(
                "Offline — gipakita ang na-save nga cart sa device.",
                style = MaterialTheme.typography.labelSmall,
                color = BrandGold
            )
        }
        Spacer(Modifier.height(8.dp))
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Wala pay sulod ang cart.", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onBrowse) { Text("Pangita og produkto") }
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
            ) { Text("✅ I-checkout (₱%.2f)".format(total)) }
            Spacer(Modifier.height(6.dp))
            Text(
                "Ang matag seller makadawat og kaugalingong order ug notification.",
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
            Text("Akong mga Paborito", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        if (items.isEmpty()) {
            Text("Wala pa kay paborito. Pindota ang 🤍 sa listing.")
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
        // Markahan nga nabasa ang tanan pag-abli sa screen.
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
            Text("Wala pay notification. Makadawat ka dinhi kon dunay magdugang sa imong listing sa cart o mag-chat.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.id }) { n ->
                    Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(n.message, style = MaterialTheme.typography.bodyMedium)
                            if (n.listingTitle.isNotBlank()) {
                                Text("Listing: ${n.listingTitle}", style = MaterialTheme.typography.labelSmall)
                            }
                            Text("gikan ni ${n.fromName}", style = MaterialTheme.typography.labelSmall)
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
    // Kataposang oras nga giablihan/nabasahan sa pikas ang thread.
    var otherRead by remember { mutableStateOf(0L) }
    // Typing indicator: kanus-a katapusan nagsulat ang pikas, ug lokal nga ticker.
    var otherTypingAt by remember { mutableStateOf(0L) }
    var lastTypingWrite by remember { mutableStateOf(0L) }
    var tick by remember { mutableStateOf(0L) }
    val chatId = remember(otherUid) { chatIdFor(uid, otherUid) }

    LaunchedEffect(chatId) {
        ensureChat(db, uid, otherUid, otherName, listingTitle)
    }

    // Markahan nga nabasa samtang bukas ang thread (unread = 0, read receipt).
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

    // Ticker aron ma-expire ang "nagsulat…" human sa pipila ka segundo.
    LaunchedEffect(Unit) {
        while (true) {
            tick = System.currentTimeMillis()
            kotlinx.coroutines.delay(2000)
        }
    }

    // Ipadala ang typing signal, labing daghan kada 2.5s aron dili maghuot ang writes.
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
        if (listingTitle.isNotBlank()) {
            Text("Mahitungod sa: $listingTitle", style = MaterialTheme.typography.labelSmall)
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
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = if (m.from == uid) Arrangement.End else Arrangement.Start
                ) {
                    Surface(
                        color = if (m.from == uid) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Text(
                                m.text,
                                color = if (m.from == uid) Color.White
                                else MaterialTheme.colorScheme.onSurface
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    chatTime(m.createdAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (m.from == uid) Color.White.copy(alpha = 0.8f)
                                    else Color.Gray
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                text, { text = it },
                placeholder = { Text("Mensahe…") },
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
        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("${myListings.size}", "Akong listing", Modifier.weight(1f))
            StatCard(
                "₱%.0f".format(myListings.sumOf { it.price }),
                "Total bili",
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
                Text("Paborito", maxLines = 1)
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
            Text("Mga Chat")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onSavedSearches, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Notifications, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Saved searches")
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
            Text("Papason ang account ug data", color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Camiguin Market • Buy Local. Support Local!",
            style = MaterialTheme.typography.labelSmall
        )
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

/** Ang mga lungsod sa Camiguin (gigamit sa hero banner ug filters). */
@Composable
fun TownChips(selected: String, onSelect: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(listOf("Tanan") + towns) { t ->
            FilterChip(selected = t == selected, onClick = { onSelect(t) }, label = { Text(t) })
        }
    }
}

// ---------------------------------------------------------------------------
// Home ug Categories tab (gikuha gikan sa mockup)
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
                            Text("Bag-ong gipangita", style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = {
                                clearRecentSearches(ctx)
                                recent = emptyList()
                            }) { Text("Klarohon") }
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
                            db, uid, query, category, "Tanan",
                            name = query.ifBlank { category }
                        )
                    },
                    enabled = query.isNotBlank() || category != "Tanan"
                ) {
                    Icon(
                        Icons.Filled.Notifications,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("I-save kini nga pagpangita", style = MaterialTheme.typography.labelMedium)
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
                                        contentDescription = "Tangtanga",
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
            item { SectionTitle("🔥 Trending karon") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(trending, key = { it.id }) { l ->
                        Box(Modifier.width(300.dp)) {
                            ListingCard(
                                listing = l,
                                isFavorite = favorites.contains(l.id),
                                onOpen = { onOpen(l) },
                                onFavorite = { onFavorite(l) },
                                onAddToCart = { onAddToCart(l) }
                            )
                        }
                    }
                }
            }
        }
        item {
            SectionTitle("Featured Products") {
                TextButton(onClick = onMap) { Text("🗺️ Mapa") }
                TextButton(onClick = onCompare) { Text("⚖️ Itandi") }
            }
        }
        if (listings.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Wala pay listing nga makita.", fontWeight = FontWeight.Bold)
                        Text("Pindota ang + Baligya aron makabaligya ka.")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onSell) { Text("+ Baligya") }
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
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                "Mga Category",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        item { CategoryGrid(category, onCategory) }
        item {
            Column {
                Text("Lugar", style = MaterialTheme.typography.labelMedium)
                TownChips(town, onTown)
            }
        }
        item { FilterBar(priceFilter, onPriceFilter, sort, onSort) }
        item {
            Text("${listings.size} ka listing", style = MaterialTheme.typography.labelMedium)
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
            "Pindota aron magamit ang filter. Usba ang pangan o tangtanga.",
            style = MaterialTheme.typography.labelSmall
        )
        Spacer(Modifier.height(8.dp))

        if (searches.isEmpty()) {
            Text("Wala pa kay saved search. Sa Home, pindota ang \"I-save kini nga pagpangita\".")
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
                                        s.category.takeIf { it != "Tanan" },
                                        s.town.takeIf { it != "Tanan" }
                                    ).joinToString(" • ").ifBlank { "Tanan nga listing" },
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            IconButton(onClick = { editing = s }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Usba ang pangan")
                            }
                            IconButton(onClick = { deleteSavedSearch(db, uid, s.id) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Tangtanga",
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
            title = { Text("Usba ang pangan") },
            text = {
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text("Pangan sa saved search") },
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
                TextButton(onClick = { editing = null }) { Text("Dili") }
            }
        )
    }
}
