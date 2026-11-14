package com.camiguinmarket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import com.google.firebase.firestore.FirebaseFirestore

// ---------------------------------------------------------------------------
// Orders (Firestore: orders/{autoId})
// Usa ka order kada cart item, busa ang matag seller dunay kaugalingong order.
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

/** I-convert ang cart ngadto sa mga order, dayon i-empty ang cart. */
fun checkoutCart(
    db: FirebaseFirestore,
    uid: String,
    buyerName: String,
    items: List<CartItem>,
    onResult: (String?) -> Unit
) {
    if (items.isEmpty()) {
        onResult("Walay sulod ang cart.")
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
        order to item
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
            }
            onResult(null)
        }
        .addOnFailureListener { onResult(it.localizedMessage ?: "Napakyas ang order.") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(uid: String, onOpen: (Order) -> Unit) {
    val db = remember { FirebaseFirestore.getInstance() }
    var asBuyer by remember { mutableStateOf(true) }
    var orders by remember { mutableStateOf<List<Order>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    DisposableEffect(asBuyer) {
        loading = true
        val field = if (asBuyer) "buyerId" else "sellerId"
        // Dili kinahanglan og composite index: i-sort sa app.
        val reg = db.collection("orders").whereEqualTo(field, uid)
            .addSnapshotListener { snap, _ ->
                orders = snap?.documents.orEmpty()
                    .mapNotNull { d -> d.toObject(Order::class.java)?.copy(id = d.id) }
                    .sortedByDescending { it.createdAt }
                loading = false
            }
        onDispose { reg.remove() }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ReceiptLong, contentDescription = null, tint = BrandBlue)
            Spacer(Modifier.width(6.dp))
            Text("Mga Order", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = asBuyer,
                onClick = { asBuyer = true },
                label = { Text("Ako ang mipalit") }
            )
            FilterChip(
                selected = !asBuyer,
                onClick = { asBuyer = false },
                label = { Text("Ako ang gibaligya") }
            )
        }
        Spacer(Modifier.height(8.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            orders.isEmpty() -> Text(
                if (asBuyer) "Wala pa kay order." else "Wala pay order nga nadawat.",
                style = MaterialTheme.typography.bodyMedium
            )

            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(orders, key = { it.id }) { order ->
                    Card(
                        onClick = { onOpen(order) },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                      Column {
                        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(BrandSky),
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
                        if (!asBuyer && order.status != "done") {
                            SellerActions(
                                status = order.status,
                                modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                                onConfirm = { updateOrderStatus(db, order.id, "confirmed") },
                                onDone = { updateOrderStatus(db, order.id, "done") }
                            )
                        }
                      }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
fun StatusChip(status: String) {
    val color = when (status) {
        "pending" -> BrandGold
        "confirmed" -> BrandBlue
        "done" -> BrandGreen
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

/** Ang seller ra ang makagamit niini (gitugotan sa Firestore rules). */
fun updateOrderStatus(db: FirebaseFirestore, orderId: String, status: String) {
    db.collection("orders").document(orderId).update("status", status)
}

@Composable
fun SellerActions(
    status: String,
    modifier: Modifier = Modifier,
    onConfirm: () -> Unit,
    onDone: () -> Unit
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
        ) { Text("Tapos na") }
    }
}

@Composable
fun OrderDetailScreen(order: Order, uid: String, onChat: (String, String) -> Unit) {
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
                        Modifier.size(64.dp).clip(RoundedCornerShape(12.dp)).background(BrandSky),
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
                    Text("Lugar: ${order.town}", style = MaterialTheme.typography.bodySmall)
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
                OrderStep("Na-order", "pending", order.status)
                OrderStep("Gikumpirma sa seller", "confirmed", order.status)
                OrderStep("Tapos na", "done", order.status)
            }
        }

        if (isSeller && order.status != "done") {
            SellerActions(
                status = order.status,
                onConfirm = { updateOrderStatus(db, order.id, "confirmed") },
                onDone = { updateOrderStatus(db, order.id, "done") }
            )
        }

        OutlinedButton(
            onClick = { onChat(otherId, otherName) },
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (isSeller) "Chat sa buyer" else "Chat sa seller")
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
// Chat inbox: tanan nga thread nga apil ang user.
// ---------------------------------------------------------------------------

data class ChatThread(
    val id: String = "",
    val otherUid: String = "",
    val otherName: String = "",
    val listingTitle: String = "",
    val lastMessage: String = "",
    val updatedAt: Long = 0L
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
                        updatedAt = d.getLong("updatedAt") ?: 0L
                    )
                }.sortedByDescending { it.updatedAt }
            }
        onDispose { reg.remove() }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Chat, contentDescription = null, tint = BrandBlue)
            Spacer(Modifier.width(6.dp))
            Text("Mga Chat", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        if (threads.isEmpty()) {
            Text("Wala pa kay chat. Ablihi ang usa ka listing ug pindota ang \"Chat sa seller\".")
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
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
