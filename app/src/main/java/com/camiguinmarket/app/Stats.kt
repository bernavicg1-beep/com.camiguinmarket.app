package com.camiguinmarket.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Canvas
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
 * Per-listing stats for the seller: views, cart adds, and orders.
 * Orders are grouped in the app (no composite index needed).
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
                "Listing stats",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            "${myListings.size} listings • ${myListings.sumOf { it.views }} total views",
            style = MaterialTheme.typography.labelSmall
        )
        Spacer(Modifier.height(8.dp))
        if (myListings.isNotEmpty()) {
            ViewsChart(mergedViews(myListings), "All listings • last 7 days")
            Spacer(Modifier.height(8.dp))
        }
        MonthlySalesChart(orders)
        Spacer(Modifier.height(8.dp))

        if (myListings.isEmpty()) {
            Text("No listings yet. Press + Sell to get started.")
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(myListings, key = { it.id }) { l ->
                val cartAdds = l.cartAdds
                val orderCount = ordersByListing[l.id] ?: 0
                var expanded by remember(l.id) { mutableStateOf(false) }
                Card(
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { expanded = !expanded }
                ) {
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
                        Text(
                            if (expanded) "Tap to hide the chart" else "Tap to show the chart",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        if (expanded) {
                            Spacer(Modifier.height(8.dp))
                            ViewsChart(l.viewsByDay, "This listing • last 7 days")
                        }
                    }
                }
            }
        }
    }
}

/** Merge viewsByDay across listings (store total). */
fun mergedViews(listings: List<Listing>): Map<String, Long> {
    val out = mutableMapOf<String, Long>()
    listings.forEach { l -> l.viewsByDay.forEach { (d, v) -> out[d] = (out[d] ?: 0L) + v } }
    return out
}

/** Daily views bar chart (7 days) for one listing or the whole store. */
@Composable
fun ViewsChart(viewsByDay: Map<String, Long>, subtitle: String = "Last 7 days") {
    val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    val days = (6 downTo 0).map { offset ->
        val c = java.util.Calendar.getInstance()
        c.add(java.util.Calendar.DAY_OF_YEAR, -offset)
        fmt.format(c.time)
    }
    val totals = days.map { d -> viewsByDay[d] ?: 0L }
    val max = (totals.maxOrNull() ?: 0L).coerceAtLeast(1L)

    Card(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Views kada adlaw", fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(8.dp))
            Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                val slot = size.width / days.size
                val barW = slot * 0.55f
                totals.forEachIndexed { i, value ->
                    val h = (value.toFloat() / max) * (size.height - 6f)
                    drawRoundRect(
                        color = BrandBlue,
                        topLeft = androidx.compose.ui.geometry.Offset(
                            x = i * slot + (slot - barW) / 2f,
                            y = size.height - h
                        ),
                        size = androidx.compose.ui.geometry.Size(barW, h),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f)
                    )
                }
            }
            Row(Modifier.fillMaxWidth()) {
                days.forEachIndexed { i, d ->
                    Text(
                        d.substring(5).replace("-", "/"),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Today: ${totals.last()} views • last 7 days: ${totals.sum()}",
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

/** Monthly revenue (₱) bar chart for the last 6 months. */
@Composable
fun MonthlySalesChart(orders: List<Order>) {
    val months = (5 downTo 0).map { offset ->
        java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.DAY_OF_MONTH, 1)
            add(java.util.Calendar.MONTH, -offset)
        }
    }
    val labelFmt = java.text.SimpleDateFormat("MMM", java.util.Locale.US)
    val labels = months.map { labelFmt.format(it.time) }
    val keys = months.map { it.get(java.util.Calendar.YEAR) * 100 + it.get(java.util.Calendar.MONTH) }
    val paid = orders.filter { it.status != "cancelled" }
    val sums = keys.map { key ->
        paid.filter { o ->
            val c = java.util.Calendar.getInstance().apply { timeInMillis = o.createdAt }
            c.get(java.util.Calendar.YEAR) * 100 + c.get(java.util.Calendar.MONTH) == key
        }.sumOf { it.total }
    }
    val max = (sums.maxOrNull() ?: 0.0).coerceAtLeast(1.0)

    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Kita kada bulan", fontWeight = FontWeight.Bold)
            Text("Last 6 months (cancelled excluded)", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(8.dp))
            Canvas(Modifier.fillMaxWidth().height(110.dp)) {
                val slot = size.width / sums.size
                val barW = slot * 0.55f
                sums.forEachIndexed { i, value ->
                    val h = (value / max).toFloat() * (size.height - 6f)
                    drawRoundRect(
                        color = BrandGreen,
                        topLeft = androidx.compose.ui.geometry.Offset(
                            x = i * slot + (slot - barW) / 2f,
                            y = size.height - h
                        ),
                        size = androidx.compose.ui.geometry.Size(barW, h),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f)
                    )
                }
            }
            Row(Modifier.fillMaxWidth()) {
                labels.forEach { label ->
                    Text(
                        label,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "This month: ₱%.2f".format(sums.last()),
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}
