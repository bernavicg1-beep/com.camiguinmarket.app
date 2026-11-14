package com.camiguinmarket.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Rating sa seller human sa `done` nga order (Firestore: ratings/{orderId}).
 * Ang doc id = order id, busa dili makabalik-balik og rate ang buyer.
 */
data class Rating(
    val id: String = "",
    val orderId: String = "",
    val sellerId: String = "",
    val buyerId: String = "",
    val buyerName: String = "",
    val rating: Long = 5,
    val comment: String = "",
    val createdAt: Long = 0L
)

fun submitRating(
    db: FirebaseFirestore,
    order: Order,
    stars: Int,
    comment: String,
    onResult: (String?) -> Unit
) {
    if (stars !in 1..5) {
        onResult("Pilia ang bituon (1-5).")
        return
    }
    val rating = Rating(
        orderId = order.id,
        sellerId = order.sellerId,
        buyerId = order.buyerId,
        buyerName = order.buyerName,
        rating = stars.toLong(),
        comment = comment.trim(),
        createdAt = System.currentTimeMillis()
    )
    db.collection("ratings").document(order.id).set(rating)
        .addOnSuccessListener {
            notifyUser(
                db = db,
                toUid = order.sellerId,
                fromUid = order.buyerId,
                fromName = order.buyerName,
                message = "${order.buyerName} nag-rate og $stars⭐ sa order: ${order.listingTitle}"
                    .take(190)
            )
            onResult(null)
        }
        .addOnFailureListener { onResult(it.localizedMessage ?: "Napakyas ang pag-rate.") }
}

@Composable
fun StarRow(
    stars: Int,
    size: Int = 30,
    onPick: ((Int) -> Unit)? = null
) {
    Row {
        (1..5).forEach { i ->
            Icon(
                if (i <= stars) Icons.Filled.Star else Icons.Outlined.StarOutline,
                contentDescription = "$i ka bituon",
                tint = if (i <= stars) BrandGold else Color.Gray,
                modifier = Modifier
                    .size(size.dp)
                    .then(
                        if (onPick != null) Modifier.clickable { onPick(i) } else Modifier
                    )
            )
        }
    }
}

@Composable
fun RatingDialog(order: Order, onClose: () -> Unit) {
    val db = remember { FirebaseFirestore.getInstance() }
    var stars by remember { mutableStateOf(5) }
    var comment by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var alreadyRated by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(order.id) {
        db.collection("ratings").document(order.id).get()
            .addOnSuccessListener { alreadyRated = it.exists() }
            .addOnFailureListener { alreadyRated = false }
    }

    AlertDialog(
        onDismissRequest = { if (!sending) onClose() },
        title = { Text("Rate sa seller") },
        text = {
            Column {
                Text(order.listingTitle, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Seller: ${order.sellerName}",
                    style = MaterialTheme.typography.labelSmall
                )
                Spacer(Modifier.height(8.dp))
                when (alreadyRated) {
                    null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    true -> Text("Gi-rate na nimo kini nga order. Salamat!")
                    false -> {
                        StarRow(stars, size = 34) { stars = it }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            comment,
                            { comment = it },
                            label = { Text("Komentaryo (opsyonal)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = Color(0xFFE53935), style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            if (alreadyRated == false) {
                TextButton(
                    enabled = !sending,
                    onClick = {
                        sending = true
                        error = null
                        submitRating(db, order, stars, comment) { err ->
                            sending = false
                            if (err == null) onClose() else error = err
                        }
                    }
                ) { Text("Ipadala") }
            } else {
                TextButton(onClick = onClose) { Text("Sira") }
            }
        },
        dismissButton = {
            if (alreadyRated == false) {
                TextButton(onClick = onClose, enabled = !sending) { Text("Dili") }
            }
        }
    )
}

/** Average nga rating sa seller: "⭐ 4.6 (12)". */
@Composable
fun SellerRatingSummary(sellerId: String) {
    val db = remember { FirebaseFirestore.getInstance() }
    var ratings by remember { mutableStateOf<List<Long>>(emptyList()) }

    DisposableEffect(sellerId) {
        val reg = db.collection("ratings").whereEqualTo("sellerId", sellerId)
            .addSnapshotListener { snap, _ ->
                ratings = snap?.documents.orEmpty().mapNotNull {
                    it.getLong("rating")
                }
            }
        onDispose { reg.remove() }
    }

    if (ratings.isEmpty()) return
    val avg = ratings.average()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("⭐ %.1f".format(avg), fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.width(4.dp))
        Text("(${ratings.size})", style = MaterialTheme.typography.labelSmall)
    }
}
