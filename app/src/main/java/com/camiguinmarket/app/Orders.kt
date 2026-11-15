package com.camiguinmarket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------------------
// Orders (Firestore: orders/{autoId})
// One order per cart item, so each seller gets their own order.
// ---------------------------------------------------------------------------

data class Order(
    val id: String = "",
    val buyerId: String = "",
    val buyerName: String = "",
    val sellerId: String = "",
    val sellerName: String = "",
    val listingId: String = "",
    val listingTitle: String = "",
    val imageUrl: String = "",
    val price: Double = 0.0,
    val quantity: Long = 1,
    val total: Double = 0.0,
    val town: String = "",
    val contact: String = "",
    val status: String = "pending",
    val createdAt: Long = 0L
)

/** Convert the cart into orders, then empty the cart. */
fun checkoutCart(
    db: FirebaseFirestore,
    uid: String,
    buyerName: String,
    items: List<CartItem>,
    onResult: (String?) -> Unit
) {
    if (items.isEmpty()) {
        onResult("Your cart is empty.")
        return
    }
    val batch = db.batch()
    val orders = items.map { item ->
        val ref = db.collection("orders").document()
        val order = Order(
            buyerId = uid,
            buyerName = buyerName,
            sellerId = item.sellerId,
            sellerName = item.sellerName,
            listingId = item.listingId,
            listingTitle = item.title,
            imageUrl = item.imageUrl,
            price = item.price,
            quantity = item.quantity,
            total = item.price * item.quantity,
            town = item.town,
            contact = item.contact,
            status = "pending",
            createdAt = System.currentTimeMillis()
        )
        batch.set(ref, order)
        batch.delete(db.collection("carts").document(uid).collection("items").document(item.id))
        order.copy(id = ref.id) to item
    }
    batch.commit()
        .addOnSuccessListener {
            orders.forEach { (order, item) ->
                notifyUser(
                    db = db,
                    toUid = order.sellerId,
                    fromUid = uid,
                    fromName = buyerName,
                    message = "$buyerName mi-order: ${item.title} ×${item.quantity}"
                )
                // Push to the seller through the Worker.
                sendPush("order", orderId = order.id)
            }
            onResult(null)
        }
        .addOnFailureListener { onResult(it.localizedMessage ?: "Order failed.") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(uid: String, onOpen: (Order) -> Unit, onRate: (Order) -> Unit) {
    val db = remember { FirebaseFirestore.getInstance() }
    var asBuyer by remember { mutableStateOf(true) }
    var orders by remember { mutableStateOf<List<Order>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    // Filter for the list and the CSV: 0 = all days, "" = every status.
    var rangeDays by remember { mutableStateOf(0) }
    var statusFilter by remember { mutableStateOf("") }

    DisposableEffect(asBuyer) {
        loading = true
        val field = if (asBuyer) "buyerId" else "sellerId"
        // No composite index needed: sorted in the app.
        val reg = db.collection("orders").whereEqualTo(field, uid)
            .addSnapshotListener { snap, _ ->
                orders = snap?.documents.orEmpty()
                    .mapNotNull { d -> d.toObject(Order::class.java)?.copy(id = d.id) }
                    .sortedByDescending { it.createdAt }
                loading = false
            }
        onDispose { reg.remove() }
    }

    val cutoff = if (rangeDays == 0) 0L
    else System.currentTimeMillis() - rangeDays * 24L * 60L * 60L * 1000L
    val visible = orders.filter { o ->
        o.createdAt >= cutoff && (statusFilter.isBlank() || o.status == statusFilter)
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ReceiptLong, contentDescription = null, tint = BrandBlue)
            Spacer(Modifier.width(6.dp))
            Text("Orders", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            val ctx = androidx.compose.ui.platform.LocalContext.current
            TextButton(
                onClick = { shareOrdersCsv(ctx, visible) },
                enabled = visible.isNotEmpty()
            ) {
                Text("CSV (${visible.size})", style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = asBuyer,
                onClick = { asBuyer = true },
                label = { Text("I'm the buyer") }
            )
            FilterChip(
                selected = !asBuyer,
                onClick = { asBuyer = false },
                label = { Text("I'm the seller") }
            )
        }
        Spacer(Modifier.height(4.dp))
        // Date filter (applied to the list and the CSV export).
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(listOf(0 to "All", 7 to "Last 7 days", 30 to "Last 30 days")) { (days, label) ->
                FilterChip(
                    selected = rangeDays == days,
                    onClick = { rangeDays = days },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
        // Status filter.
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val statuses = listOf(
                "" to "All statuses",
                "pending" to "Pending",
                "confirmed" to "Confirmed",
                "done" to "Done",
                "cancelled" to "Gikansela"
            )
            items(statuses) { (value, label) ->
                FilterChip(
                    selected = statusFilter == value,
                    onClick = { statusFilter = value },
                    label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            visible.isEmpty() -> Text(
                if (orders.isEmpty()) {
                    if (asBuyer) "No orders yet." else "No orders received yet."
                } else {
                    "No orders match the filter."
                },
                style = MaterialTheme.typography.bodyMedium
            )

            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible, key = { it.id }) { order ->
                    Card(
                        onClick = { onOpen(order) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                      Column {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                if (order.imageUrl.isNotBlank()) RemoteImage(order.imageUrl, Modifier.fillMaxSize())
                                else Icon(Icons.Filled.Storefront, contentDescription = null, tint = BrandBlue)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    order.listingTitle,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text("₱%.2f × %d = ₱%.2f".format(order.price, order.quantity, order.total), color = BrandBlue)
                                Text(
                                    if (asBuyer) "Seller: ${order.sellerName}" else "Buyer: ${order.buyerName}",
                                    style = MaterialTheme.typography.labelSmall
                                )
                                if (order.contact.isNotBlank()) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Filled.Phone,
                                            contentDescription = null,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(order.contact, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            StatusChip(order.status)
                        }
                        val active = order.status != "done" && order.status != "cancelled"
                        if (!asBuyer && active) {
                            SellerActions(
                                status = order.status,
                                modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                                onConfirm = { updateOrderStatus(db, order, "confirmed") },
                                onDone = { updateOrderStatus(db, order, "done") },
                                onCancel = { cancelOrder(db, order, uid) }
                            )
                        } else if (asBuyer && order.status == "pending") {
                            TextButton(
                                onClick = { cancelOrder(db, order, uid) },
                                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                            ) { Text("Cancel order", color = Color(0xFFE53935)) }
                        } else if (asBuyer && order.status == "done") {
                            TextButton(
                                onClick = { onRate(order) },
                                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                            ) { Text("Rate the seller") }
                        }
                      }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

/** Export the orders to CSV and share through the Android share sheet. */
fun shareOrdersCsv(context: Context, orders: List<Order>) {
    if (orders.isEmpty()) return
    val header = "order_id,listing,price,quantity,total,buyer,seller,contact,town,status,created_at"
    fun esc(v: String): String = "\"" + v.replace("\"", "\"\"") + "\""
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    val rows = orders.joinToString("\n") { o ->
        listOf(
            o.id, o.listingTitle, "%.2f".format(o.price), o.quantity.toString(),
            "%.2f".format(o.total), o.buyerName, o.sellerName, o.contact, o.town,
            o.status, fmt.format(Date(o.createdAt))
        ).joinToString(",") { esc(it) }
    }
    val file = File(context.cacheDir, "orders.csv")
    file.writeText(header + "\n" + rows)
    val uri = FileProvider.getUriForFile(
        context,
        context.packageName + ".fileprovider",
        file
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Export orders"))
}

/** Export daily revenue to CSV (written to the cache, then shared). */
fun shareSalesCsv(context: Context, orders: List<Order>) {
    val paid = orders.filter { it.status != "cancelled" }
    if (paid.isEmpty()) return

    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val byDay = paid.groupBy { fmt.format(Date(it.createdAt)) }.toSortedMap()

    fun esc(v: String): String = "\"" + v.replace("\"", "\"\"") + "\""
    val rows = byDay.entries.map { (day, list) ->
        listOf(day, list.size.toString(), "%.2f".format(list.sumOf { it.total }))
            .joinToString(",") { esc(it) }
    }
    val total = paid.sumOf { it.total }
    val lines = buildList {
        add("date,orders,revenue")
        addAll(rows)
        add(listOf("TOTAL", paid.size.toString(), "%.2f".format(total)).joinToString(",") { esc(it) })
    }

    val file = File(context.cacheDir, "sales.csv")
    file.writeText(lines.joinToString("\n"))
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Export sales"))
}

@Composable
fun StatusChip(status: String) {
    val color = when (status) {
        "pending" -> BrandGold
        "confirmed" -> BrandBlue
        "done" -> BrandGreen
        "cancelled" -> Color(0xFFE53935)
        else -> Color.Gray
    }
    Surface(color = color, shape = RoundedCornerShape(10.dp)) {
        Text(
            status,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/** Only the seller may do this (the Firestore rules allow it). */
fun updateOrderStatus(db: FirebaseFirestore, order: Order, status: String) {
    db.collection("orders").document(order.id).update("status", status)
        .addOnSuccessListener { sendPush("status", orderId = order.id) }
    val action = when (status) {
        "confirmed" -> "confirmed"
        "done" -> "completed"
        else -> "updated"
    }
    // Notify the buyer right away.
    notifyUser(
        db = db,
        toUid = order.buyerId,
        fromUid = order.sellerId,
        fromName = order.sellerName,
        message = "${order.sellerName} $action your order: ${order.listingTitle}".take(190)
    )
}

/** Seller sales summary: revenue, new, confirmed, done. */
@Composable
fun SellerStats(uid: String, myListings: List<Listing>) {
    val db = remember { FirebaseFirestore.getInstance() }
    var orders by remember { mutableStateOf<List<Order>>(emptyList()) }

    DisposableEffect(uid) {
        val reg = db.collection("orders").whereEqualTo("sellerId", uid)
            .addSnapshotListener { snap, _ ->
                orders = snap?.documents.orEmpty().mapNotNull { d ->
                    d.toObject(Order::class.java)?.copy(id = d.id)
                }
            }
        onDispose { reg.remove() }
    }

    var range by remember { mutableStateOf("month") }
    val startDay = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val startMonth = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.DAY_OF_MONTH, 1)
        set(java.util.Calendar.HOUR_OF_DAY, 0)
        set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0)
        set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    val visible = when (range) {
        "today" -> orders.filter { it.createdAt >= startDay }
        "month" -> orders.filter { it.createdAt >= startMonth }
        else -> orders
    }
    val done = visible.filter { it.status == "done" }
    val income = done.sumOf { it.total }

    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Storefront, contentDescription = null, tint = BrandBlue)
                Spacer(Modifier.width(6.dp))
                Text("Sell", fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                val ctx = androidx.compose.ui.platform.LocalContext.current
                TextButton(
                    onClick = { shareSalesCsv(ctx, visible) },
                    enabled = visible.any { it.status != "cancelled" }
                ) {
                    Text("CSV", style = MaterialTheme.typography.labelMedium)
                }
            }
            SellerRatingSummary(uid)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = range == "today",
                    onClick = { range = "today" },
                    label = { Text("Today", style = MaterialTheme.typography.labelSmall) }
                )
                FilterChip(
                    selected = range == "month",
                    onClick = { range = "month" },
                    label = { Text("This month", style = MaterialTheme.typography.labelSmall) }
                )
                FilterChip(
                    selected = range == "all",
                    onClick = { range = "all" },
                    label = { Text("All", style = MaterialTheme.typography.labelSmall) }
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("₱%.0f".format(income), "Revenue (done)", Modifier.weight(1f))
                StatCard(
                    "${visible.count { it.status == "pending" }}",
                    "New",
                    Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard(
                    "${visible.count { it.status == "confirmed" }}",
                    "Confirmed",
                    Modifier.weight(1f)
                )
                StatCard("${done.size}", "Done", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            StatCard(
                "${myListings.sumOf { it.views }}",
                "Views on my listings",
                Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun SellerActions(
    status: String,
    modifier: Modifier = Modifier,
    onConfirm: () -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (status == "pending") {
            Button(onClick = onConfirm, shape = RoundedCornerShape(14.dp)) {
                Text("Kumpirmahon")
            }
        }
        Button(
            onClick = onDone,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BrandGreen)
        ) { Text("Done") }
        OutlinedButton(
            onClick = onCancel,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE53935))
        ) { Text("Cancel") }
    }
}

/** The buyer can cancel while `pending`; the seller any time before `done`. */
fun cancelOrder(db: FirebaseFirestore, order: Order, byUid: String) {
    db.collection("orders").document(order.id).update("status", "cancelled")
    val byBuyer = byUid == order.buyerId
    val toUid = if (byBuyer) order.sellerId else order.buyerId
    val fromName = if (byBuyer) order.buyerName else order.sellerName
    notifyUser(
        db = db,
        toUid = toUid,
        fromUid = byUid,
        fromName = fromName,
        message = "$fromName cancelled the order: ${order.listingTitle}".take(190)
    )
}

@Composable
fun OrderDetailScreen(
    order: Order,
    uid: String,
    onChat: (String, String) -> Unit,
    onRate: (Order) -> Unit
) {
    val db = remember { FirebaseFirestore.getInstance() }
    val isSeller = order.sellerId == uid
    val otherId = if (isSeller) order.buyerId else order.sellerId
    val otherName = if (isSeller) order.buyerName else order.sellerName

    Column(
        Modifier.fillMaxSize().padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        if (order.imageUrl.isNotBlank()) RemoteImage(order.imageUrl, Modifier.fillMaxSize())
                        else Icon(Icons.Filled.Storefront, contentDescription = null, tint = BrandBlue)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(order.listingTitle, fontWeight = FontWeight.Bold)
                        Text("₱%.2f × %d".format(order.price, order.quantity))
                        Text(
                            "Total: ₱%.2f".format(order.total),
                            color = BrandBlue,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    StatusChip(order.status)
                }
                Spacer(Modifier.height(10.dp))
                Divider()
                Spacer(Modifier.height(10.dp))
                Text("Buyer: ${order.buyerName}", style = MaterialTheme.typography.bodySmall)
                Text("Seller: ${order.sellerName}", style = MaterialTheme.typography.bodySmall)
                if (order.town.isNotBlank()) {
                    Text("Town: ${order.town}", style = MaterialTheme.typography.bodySmall)
                }
                if (order.contact.isNotBlank()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Phone, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(order.contact, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Status", fontWeight = FontWeight.Bold)
                OrderStep("Ordered", "pending", order.status)
                OrderStep("Confirmed by the seller", "confirmed", order.status)
                OrderStep("Done", "done", order.status)
                if (order.status == "cancelled") {
                    Text(
                        "Order cancelled",
                        color = Color(0xFFE53935),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        when {
            isSeller && order.status != "done" && order.status != "cancelled" -> SellerActions(
                status = order.status,
                onConfirm = { updateOrderStatus(db, order, "confirmed") },
                onDone = { updateOrderStatus(db, order, "done") },
                onCancel = { cancelOrder(db, order, uid) }
            )

            !isSeller && order.status == "pending" -> OutlinedButton(
                onClick = { cancelOrder(db, order, uid) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE53935))
            ) { Text("Cancel order") }
        }

        if (!isSeller && order.status == "done") {
            Button(
                onClick = { onRate(order) },
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Rate the seller") }
        }

        OutlinedButton(
            onClick = { onChat(otherId, otherName) },
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (isSeller) "Chat with buyer" else "Chat with seller")
        }
    }
}

@Composable
fun OrderStep(label: String, step: String, current: String) {
    val order = listOf("pending", "confirmed", "done")
    val reached = order.indexOf(current) >= order.indexOf(step)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(50))
                .background(if (reached) BrandGreen else Color.LightGray)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = if (reached) Color.Black else Color.Gray,
            fontWeight = if (current == step) FontWeight.Bold else FontWeight.Normal
        )
    }
}

// ---------------------------------------------------------------------------
// Chat inbox: every thread the user is part of.
// ---------------------------------------------------------------------------

data class ChatThread(
    val id: String = "",
    val otherUid: String = "",
    val otherName: String = "",
    val listingTitle: String = "",
    val lastMessage: String = "",
    val updatedAt: Long = 0L,
    // My unread in this thread (from the `unread.<uid>` map).
    val unread: Int = 0
)

@Composable
fun InboxScreen(uid: String, onOpen: (ChatThread) -> Unit) {
    val db = remember { FirebaseFirestore.getInstance() }
    var threads by remember { mutableStateOf<List<ChatThread>>(emptyList()) }

    DisposableEffect(uid) {
        val reg = db.collection("chats")
            .whereArrayContains("participants", uid)
            .addSnapshotListener { snap, _ ->
                threads = snap?.documents.orEmpty().mapNotNull { d ->
                    val participants = (d.get("participants") as? List<*>)?.filterIsInstance<String>().orEmpty()
                    val other = participants.firstOrNull { it != uid } ?: return@mapNotNull null
                    val names = (d.get("names") as? Map<*, *>)
                    ChatThread(
                        id = d.id,
                        otherUid = other,
                        otherName = names?.get(other) as? String ?: "User",
                        listingTitle = d.getString("listingTitle") ?: "",
                        lastMessage = d.getString("lastMessage") ?: "",
                        updatedAt = d.getLong("updatedAt") ?: 0L,
                        unread = ((d.get("unread") as? Map<*, *>)?.get(uid) as? Number)
                            ?.toInt() ?: 0
                    )
                }.sortedByDescending { it.updatedAt }
            }
        onDispose { reg.remove() }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Chat, contentDescription = null, tint = BrandBlue)
            Spacer(Modifier.width(6.dp))
            Text("Chats", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        if (threads.isEmpty()) {
            Text("No chats yet. Open a listing and press \"Chat with seller\".")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(threads, key = { it.id }) { thread ->
                    Card(
                        onClick = { onOpen(thread) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(BrandBlue),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    thread.otherName.take(1).uppercase(),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(thread.otherName, fontWeight = FontWeight.Bold)
                                Text(
                                    thread.lastMessage.ifBlank { thread.listingTitle },
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (thread.unread > 0) FontWeight.Bold
                                    else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            // Badge for unread messages in this thread.
                            if (thread.unread > 0) {
                                Spacer(Modifier.width(8.dp))
                                Badge(containerColor = BrandBlue) {
                                    Text("${thread.unread}")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
