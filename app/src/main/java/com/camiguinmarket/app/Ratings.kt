package com.camiguinmarket.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

// ---------------------------------------------------------------------------
// Ratings (seller reputation)
// ---------------------------------------------------------------------------

/**
 * Stores a rating for a seller.
 *
 * A buyer may rate a seller once per completed order; the rating is stored in
 * `ratings/{orderId}` so the document id carries the order id (one rating per
 * order) and the seller's reputation is computed by `loadSellerReputation`.
 */
data class SellerRating(
    val orderId: String = "",
    val buyerId: String = "",
    val sellerId: String = "",
    val rating: Long = 0L,
    val comment: String = "",
    val createdAt: Long = 0L
)

@Composable
fun loadSellerRatingSummary(db: FirebaseFirestore, sellerId: String): State<SellerReputation> {
    return loadSellerReputation(db, sellerId)
}

/**
 * Writes the buyer's rating for an order. Returns `null` on success or an
 * error message on failure. Idempotent: re-rating the same order is rejected
 * by the rules (document id = orderId).
 */
fun submitSellerRating(
    db: FirebaseFirestore,
    orderId: String,
    buyerId: String,
    sellerId: String,
    rating: Long,
    comment: String = "",
    onResult: (String?) -> Unit = {}
) {
    if (rating !in 1L..5L) {
        onResult("Pick a star rating (1-5).")
        return
    }
    val doc = SellerRating(
        orderId = orderId,
        buyerId = buyerId,
        sellerId = sellerId,
        rating = rating,
        comment = comment.trim(),
        createdAt = System.currentTimeMillis()
    )
    db.collection("ratings").document(orderId).set(doc)
        .addOnSuccessListener { onResult(null) }
        .addOnFailureListener { onResult(it.localizedMessage ?: "Rating failed.") }
}

@Composable
fun RateSellerDialog(
    orderId: String,
    buyerId: String,
    sellerId: String,
    sellerName: String,
    onDismiss: () -> Unit,
    onSubmitted: () -> Unit
) {
    val S = LocalStrings.current
    val db = remember { FirebaseFirestore.getInstance() }
    var rating by remember { mutableStateOf(0L) }
    var comment by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var alreadyRated by remember { mutableStateOf(false) }

    DisposableEffect(orderId) {
        val reg = db.collection("ratings").document(orderId)
            .addSnapshotListener { snap, _ ->
                alreadyRated = snap?.exists() ?: false
            }
        onDispose { reg.remove() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(S.rateSeller) },
        text = {
            Column {
                Text(S.sellerLine.format(sellerName), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    (1L..5L).forEach { star ->
                        TextButton(onClick = { rating = star }) {
                            Text(
                                if (star <= rating) "★" else "☆",
                                color = Color(0xFFFFC107),
                                style = MaterialTheme.typography.titleLarge
                            )
                        }
                    }
                }
                if (rating > 0) {
                    Text(S.stars.format(rating.toInt()), style = MaterialTheme.typography.labelSmall)
                }
                if (alreadyRated) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        S.alreadyRated,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF4CAF50)
                    )
                }
                error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = Color(0xFFE53935), style = MaterialTheme.typography.labelSmall)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text(S.commentOptional) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (rating == 0L) {
                        error = S.pickStarRating
                        return@TextButton
                    }
                    submitting = true
                    error = null
                    submitSellerRating(db, orderId, buyerId, sellerId, rating, comment) { err ->
                        submitting = false
                        if (err != null) error = err
                        else onSubmitted()
                    }
                },
                enabled = !submitting && !alreadyRated
            ) { Text(if (submitting) "…" else S.done) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(S.cancel) }
        }
    )
}

@Composable
fun LowRatingBanner(sellerId: String) {
    val S = LocalStrings.current
    val db = remember { FirebaseFirestore.getInstance() }
    val rep = loadSellerReputation(db, sellerId)
    if (rep.value.belowThreshold) {
        Surface(color = Color(0xFFFFF3E0), modifier = Modifier.fillMaxWidth()) {
            Text(
                S.lowRatingWarning,
                color = Color(0xFFE65100),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(8.dp)
            )
        }
    }
}
