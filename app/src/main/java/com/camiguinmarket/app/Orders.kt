package com.camiguinmarket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.google.firebase.firestore.Exclude
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
    // Payments: cash is paid on hand-over ("cod"); GCash starts unpaid, the buyer
    // adds the reference ("pending") and only the seller can mark it "paid".
    val paymentMethod: String = "cash",
    val paymentStatus: String = "cod",
    val paymentRef: String = "",
    val paidAt: Long = 0L,
    val createdAt: Long = 0L
) {
    // Helpers only: @Exclude keeps them out of the Firestore document.
    @get:Exclude
    val isGcash: Boolean get() = paymentMethod == "gcash"
    @get:Exclude
    val isPaid: Boolean get() = paymentStatus == "paid" || paymentStatus == "cod"
}

/** Convert the cart into orders, then empty the cart.
 *
 * Idempotent: a client-generated [checkoutId] guards the create path. If a
 * checkout with that id already exists (double-tap or retry), no new orders
 * are created and success is reported.
 */
fun checkoutCart(
    db: FirebaseFirestore,
    uid: String,
    buyerName: String,
    items: List<CartItem>,
    checkoutId: String = java.util.UUID.randomUUID().toString(),
    paymentMethod: String = "cash",
    onResult: (String?) -> Unit
) {
    val gcash = paymentMethod == "gcash"
    if (items.isEmpty()) {
        onResult("Your cart is empty.")
        return
    }
    val checkoutRef = db.collection("checkouts").document(checkoutId)
    // Run the whole checkout in one Firestore transaction so the stock read,
    // sufficiency check and decrement are atomic: concurrent checkouts for the
    // same listing serialize and retry with fresh stock instead of both writing
    // a stale decremented value (oversell). The idempotency guard is read inside
    // the transaction too.
    db.runTransaction { tx ->
        val checkoutSnap = tx.get(checkoutRef)
        if (checkoutSnap.exists()) {
            // Already processed (double-tap / retry): report success, no dupes.
            return@runTransaction null
        }
        val distinctListingIds = items.map { it.listingId }.distinct()
        val stockById = mutableMapOf<String, Long>()
        for (id in distinctListingIds) {
            val snap = tx.get(db.collection("listings").document(id))
            stockById[id] = snap.getLong("stock") ?: -1L
        }
        val qtyByListing = items.groupBy { it.listingId }
            .mapValues { (_, v) -> v.sumOf { it.quantity } }
        // Refuse if any tracked listing is short.
        for (item in items) {
            val s = stockById[item.listingId] ?: -1L
            val need = qtyByListing[item.listingId] ?: item.quantity
            if (s >= 0L && s < need) {
                throw Exception("Not enough stock for ${item.title}.")
            }
        }
        val orders = mutableListOf<Pair<Order, CartItem>>()
        for (item in items) {
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
                paymentMethod = if (gcash) "gcash" else "cash",
                paymentStatus = if (gcash) "unpaid" else "cod",
                createdAt = System.currentTimeMillis()
            )
            tx.set(ref, order)
            tx.delete(db.collection("carts").document(uid).collection("items").document(item.id))
            orders.add(order.copy(id = ref.id) to item)
        }
        // Decrement stock once per distinct listing. Use FieldValue.increment so
        // the decrement is applied server-side against the committed value.
        val decremented = mutableSetOf<String>()
        for (item in items) {
            if (decremented.contains(item.listingId)) continue
            val s = stockById[item.listingId] ?: -1L
            if (s >= 0L) {
                val need = qtyByListing[item.listingId] ?: item.quantity
                tx.update(
                    db.collection("listings").document(item.listingId),
                    "stock", com.google.firebase.firestore.FieldValue.increment(-need)
                )
            }
            decremented.add(item.listingId)
        }
        // Guard marker written in the same transaction.
        tx.set(checkoutRef, mapOf(
            "uid" to uid,
            "createdAt" to System.currentTimeMillis(),
            "orderCount" to items.size
        ))
        orders
    }.addOnSuccessListener { result ->
        val orders = result as? List<Pair<Order, CartItem>> ?: emptyList()
        orders.forEach { (order, item) ->
            notifyUser(
                db = db,
                toUid = order.sellerId,
                fromUid = uid,
                fromName = buyerName,
                message = "$buyerName mi-order: ${item.title} ×${item.quantity}" +
                    if (gcash) " (GCash)" else " (cash)"
            )
            sendPush("order", orderId = order.id)
        }
        onResult(null)
    }.addOnFailureListener { onResult(it.localizedMessage ?: "Order failed.") }
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
        // Pill toggle from the mockup: Buyer / Seller.
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            listOf(true to "I'm the Buyer", false to "I'm the Seller").forEach { (buyer, label) ->
                val active = asBuyer == buyer
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (active) BrandBlue else Color.Transparent)
                        .clickable { asBuyer = buyer }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        color = if (active) Color.White else BrandBlue
                    )
                }
            }
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
                "cancelled" to "Cancelled"
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
                                Text(
                                    "₱%.2f × %d".format(order.price, order.quantity),
                                    color = BrandBlue,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.US)
                                        .format(Date(order.createdAt)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = BrandMuted
                                )
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
                            Column(horizontalAlignment = Alignment.End) {
                                StatusChip(order.status)
                                Spacer(Modifier.height(4.dp))
                                PaymentChip(order)
                            }
                        }
                        val active = order.status != "done" && order.status != "cancelled"
                        if (!asBuyer && active) {
                            SellerActions(
                                status = order.status,
                                modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                                doneEnabled = order.isPaid,
                                paymentPending = order.isGcash && order.paymentStatus != "paid",
                                onConfirm = { updateOrderStatus(db, order, "confirmed") },
                                onDone = { updateOrderStatus(db, order, "done") },
                                onConfirmPayment = { confirmGcashPayment(db, order) },
                                onCancel = { cancelOrder(db, order, uid) }
                            )
                        } else if (asBuyer && order.status != "done") {
                            // Outlined Cancel next to the filled View details (mockup).
                            Row(
                                Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (order.status == "pending") {
                                    OutlinedButton(
                                        onClick = { cancelOrder(db, order, uid) },
                                        shape = RoundedCornerShape(14.dp),
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Cancel") }
                                }
                                if (order.isGcash && order.paymentStatus == "unpaid") {
                                    GcashClaim(db, order, Modifier.weight(1f))
                                }
                                Button(
                                    onClick = { onOpen(order) },
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.weight(1f)
                                ) { Text("View details") }
                            }
                        } else if (asBuyer && order.status == "done") {
                            Row(
                                Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { onRate(order) },
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.weight(1f)
                                ) { Text("Rate the seller") }
                                OutlinedButton(
                                    onClick = { onOpen(order) },
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.weight(1f)
                                ) { Text("View details") }
                            }
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
    val header = "order_id,listing,price,quantity,total,buyer,seller,contact,town,status," +
        "payment_method,payment_status,payment_ref,created_at"
    fun esc(v: String): String = "\"" + v.replace("\"", "\"\"") + "\""
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    val rows = orders.joinToString("\n") { o ->
        listOf(
            o.id, o.listingTitle, "%.2f".format(o.price), o.quantity.toString(),
            "%.2f".format(o.total), o.buyerName, o.sellerName, o.contact, o.town,
            o.status, o.paymentMethod, o.paymentStatus, o.paymentRef,
            fmt.format(Date(o.createdAt))
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
    context.startActivity(Intent.createChooser(intent, "Export sales"))
}

@Composable
fun StatusChip(status: String) {
    // Badge colours from the mockup: orange while pending, green once confirmed.
    val color = when (status) {
        "pending" -> Color(0xFFEF6C00)
        "confirmed" -> Color(0xFF2E9B5B)
        "done" -> BrandGreen
        "cancelled" -> Color(0xFFE53935)
        else -> Color.Gray
    }
    val label = status.replaceFirstChar { it.uppercase() }
    Surface(color = color, shape = RoundedCornerShape(10.dp)) {
        Text(
            label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/** Payment badge: cash on hand-over, or GCash with its verification state. */
@Composable
fun PaymentChip(order: Order) {
    val label: String
    val color: Color
    when {
        !order.isGcash -> {
            label = "Cash"
            color = BrandGreen
        }
        order.paymentStatus == "paid" -> {
            label = "GCash • Paid"
            color = BrandGreen
        }
        order.paymentStatus == "pending" -> {
            label = "GCash • For verification"
            color = Color(0xFFEF6C00)
        }
        else -> {
            label = "GCash • Unpaid"
            color = Color(0xFFE53935)
        }
    }
    Surface(color = color, shape = RoundedCornerShape(10.dp)) {
        Text(
            label,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/** The buyer says the GCash transfer went out: reference only, no "paid" yet. */
fun claimGcashPayment(db: FirebaseFirestore, order: Order, reference: String) {
    val ref = reference.trim().take(40)
    db.collection("orders").document(order.id).update(
        mapOf(
            "paymentStatus" to "pending",
            "paymentRef" to ref,
            "paidAt" to System.currentTimeMillis()
        )
    )
    notifyUser(
        db = db,
        toUid = order.sellerId,
        fromUid = order.buyerId,
        fromName = order.buyerName,
        message = "GCash to verify: ${order.listingTitle}".take(190)
    )
}

/** Only the seller can confirm the money arrived; that is what "paid" means. */
fun confirmGcashPayment(db: FirebaseFirestore, order: Order) {
    db.collection("orders").document(order.id).update(
        mapOf("paymentStatus" to "paid", "paidAt" to System.currentTimeMillis())
    )
    notifyUser(
        db = db,
        toUid = order.buyerId,
        fromUid = order.sellerId,
        fromName = order.sellerName,
        message = "${order.sellerName} confirmed your GCash payment: ${order.listingTitle}".take(190)

    )
}

/**
 * The GCash numbers sellers published in their store info, keyed by seller uid.
 * Buyers read them so the payment prompt and the reference dialog can say where
 * the money goes; a seller without one simply gets no entry.
 */
@Composable
fun rememberSellerGcash(sellerIds: List<String>): Map<String, String> {
    val db = remember { FirebaseFirestore.getInstance() }
    var numbers by remember { mutableStateOf(emptyMap<String, String>()) }
    val ids = remember(sellerIds) { sellerIds.filter { it.isNotBlank() }.distinct().sorted() }
    LaunchedEffect(ids) {
        val loaded = mutableMapOf<String, String>()
        ids.forEach { sid ->
            db.collection("users").document(sid).get().addOnSuccessListener { doc ->
                val number = doc.getString("gcashNumber")?.trim().orEmpty()
                if (number.isNotEmpty()) {
                    loaded[sid] = number
                    numbers = loaded.toMap()
                }
            }
        }
    }
    return numbers
}

/** Buyer button + reference dialog for a GCash order that is still unpaid. */
@Composable
fun GcashClaim(db: FirebaseFirestore, order: Order, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    var ref by remember { mutableStateOf("") }
    // The seller's own number, from their store info: somewhere to send to.
    val gcash = rememberSellerGcash(listOf(order.sellerId))[order.sellerId]
    OutlinedButton(
        onClick = { open = true },
        shape = RoundedCornerShape(14.dp),
        modifier = modifier
    ) { Text("I paid via GCash") }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("GCash reference") },
            text = {
                Column {
                    Text(
                        if (gcash.isNullOrBlank()) {
                            "The seller has not added a GCash number yet. Ask them for it in chat before sending money."
                        } else {
                            "Send ₱%.2f to %s — GCash %s — then paste the reference number here. "
                                .format(order.total, order.sellerName, gcash) +
                                "The seller verifies it before the order is marked paid."
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = ref,
                        onValueChange = { ref = it.take(40) },
                        label = { Text("Reference no.") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        claimGcashPayment(db, order, ref)
                        open = false
                    },
                    enabled = ref.isNotBlank()
                ) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } }
        )
    }
}

/** Only the seller may change the status (the Firestore rules allow it). */
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
                try {
                    orders = snap?.documents.orEmpty().mapNotNull { d ->
                        d.toObject(Order::class.java)?.copy(id = d.id)
                    }
                } catch (e: Exception) {
                    orders = emptyList()
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
    doneEnabled: Boolean = true,
    paymentPending: Boolean = false,
    onConfirm: () -> Unit,
    onDone: () -> Unit,
    onConfirmPayment: () -> Unit = {},
    onCancel: () -> Unit
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (paymentPending) {
            Button(
                onClick = onConfirmPayment,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrandBlueDeep),
                modifier = Modifier.fillMaxWidth()
            ) { Text("GCash payment received") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (status == "pending") {
                Button(onClick = onConfirm, shape = RoundedCornerShape(14.dp)) {
                    Text("Kumpirmahon")
                }
            }
            Button(
                onClick = onDone,
                enabled = doneEnabled,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrandGreen)
            ) { Text("Done") }
            OutlinedButton(
                onClick = onCancel,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFE53935))
            ) { Text("Cancel") }
        }
        if (!doneEnabled) {
            Text(
                "Confirm the GCash payment first — an unpaid GCash order cannot be marked done.",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFEF6C00)
            )
        }
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
    val otherName = chatPartnerName(if (isSeller) order.buyerName else order.sellerName)
    val sellerGcash = rememberSellerGcash(if (order.isGcash) listOf(order.sellerId) else emptyList())[order.sellerId]

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
                    Column(horizontalAlignment = Alignment.End) {
                        StatusChip(order.status)
                        Spacer(Modifier.height(4.dp))
                        PaymentChip(order)
                    }
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
                Text("Payment", fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PaymentChip(order)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            !order.isGcash -> "Cash on hand-over"
                            order.paymentStatus == "paid" -> "Verified by the seller"
                            order.paymentStatus == "pending" -> "Waiting for the seller to verify your GCash reference"
                            else -> "Not paid yet"
                        },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (order.paymentRef.isNotBlank()) {
                    Text(
                        "GCash reference: ${order.paymentRef}",
                        style = MaterialTheme.typography.bodySmall,
                        color = BrandMuted
                    )
                }
                if (order.isGcash && !isSeller) {
                    Text(
                        if (sellerGcash.isNullOrBlank()) {
                            "The seller has not added a GCash number yet — ask them in chat."
                        } else {
                            "Send payments to ${order.sellerName}: GCash $sellerGcash"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = BrandInk
                    )
                }
                if (order.isGcash && order.paymentStatus == "unpaid" && !isSeller &&
                    order.status != "cancelled"
                ) {
                    GcashClaim(db, order, Modifier.fillMaxWidth())
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
                doneEnabled = order.isPaid,
                paymentPending = order.isGcash && order.paymentStatus != "paid",
                onConfirm = { updateOrderStatus(db, order, "confirmed") },
                onDone = { updateOrderStatus(db, order, "done") },
                onConfirmPayment = { confirmGcashPayment(db, order) },
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
    val listingId: String = "",
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
                        otherName = chatPartnerName(names?.get(other) as? String),
                        listingTitle = d.getString("listingTitle") ?: "",
                        listingId = d.getString("listingId") ?: "",
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
                                if (thread.listingTitle.isNotBlank()) {
                                    Text(
                                        "About: ${thread.listingTitle}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = BrandBlue,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    thread.lastMessage.ifBlank { "No messages yet" },
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
