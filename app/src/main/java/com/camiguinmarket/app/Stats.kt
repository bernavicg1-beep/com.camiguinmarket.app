package com.camiguinmarket.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Stats kada listing sa seller: views, pagdugang sa cart, ug orders.
 * Ang orders gi-group sa app (walay composite index nga kinahanglan).
 */
@Composable
fun ListingStatsScreen(uid: String, myListings: List<Listing>) {
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

    val ordersByListing = orders.groupingBy { it.listingId }.eachCount()

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ReceiptLong, contentDescription = null, tint = BrandBlue)
            Spacer(Modifier.width(6.dp))
            Text(
                "Stats sa listing",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            "${myListings.size} ka listing • ${myListings.sumOf { it.views }} ka views sa tanan",
            style = MaterialTheme.typography.labelSmall
        )
        Spacer(Modifier.height(8.dp))

        if (myListings.isEmpty()) {
            Text("Wala pa kay listing. Pindota ang + Baligya aron makasugod.")
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(myListings, key = { it.id }) { l ->
                val cartAdds = l.cartAdds
                val orderCount = ordersByListing[l.id] ?: 0
                Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(
                            l.title,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "₱%.2f • ${l.town}".format(l.price),
                            style = MaterialTheme.typography.labelSmall
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatCard("${l.views}", "👁 Views", Modifier.weight(1f))
                            StatCard("$cartAdds", "🛒 Cart", Modifier.weight(1f))
                            StatCard("$orderCount", "📦 Order", Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}
