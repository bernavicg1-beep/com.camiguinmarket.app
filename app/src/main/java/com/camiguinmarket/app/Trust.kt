package com.camiguinmarket.app

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query

// ---------------------------------------------------------------------------
// Block / mute sellers
// ---------------------------------------------------------------------------

/** A seller the current user has blocked (Firestore: users/{uid}/blocks/{sellerId}). */
data class BlockedSeller(
    val sellerId: String = "",
    val sellerName: String = "",
    val createdAt: Long = 0L
)

fun blockSeller(
    db: FirebaseFirestore,
    uid: String,
    sellerId: String,
    sellerName: String,
    onResult: (String?) -> Unit = {}
) {
    if (sellerId.isBlank() || sellerId == uid) {
        onResult("Cannot block this seller.")
        return
    }
    db.collection("users").document(uid).collection("blocks").document(sellerId)
        .set(BlockedSeller(sellerId = sellerId, sellerName = sellerName, createdAt = System.currentTimeMillis()))
        .addOnSuccessListener { onResult(null) }
        .addOnFailureListener { onResult(it.localizedMessage ?: "Block failed.") }
}

fun unblockSeller(
    db: FirebaseFirestore,
    uid: String,
    sellerId: String,
    onResult: (String?) -> Unit = {}
) {
    db.collection("users").document(uid).collection("blocks").document(sellerId)
        .delete()
        .addOnSuccessListener { onResult(null) }
        .addOnFailureListener { onResult(it.localizedMessage ?: "Unblock failed.") }
}

@Composable
fun loadBlockedSellers(db: FirebaseFirestore, uid: String): State<List<BlockedSeller>> {
    val state = remember { mutableStateOf<List<BlockedSeller>>(emptyList()) }
    DisposableEffect(uid) {
        val reg = db.collection("users").document(uid).collection("blocks")
            .addSnapshotListener { snap, _ ->
                state.value = snap?.documents.orEmpty().mapNotNull { d ->
                    d.toObject(BlockedSeller::class.java)?.copy(sellerId = d.id)
                }
            }
        onDispose { reg.remove() }
    }
    return state
}

fun isBlocked(blocked: List<BlockedSeller>, sellerId: String): Boolean =
    blocked.any { it.sellerId == sellerId }

// ---------------------------------------------------------------------------
// Rate limiting (client-side, SharedPreferences timestamps)
// ---------------------------------------------------------------------------

object RateLimiter {
    private const val PREFS = "rate_limits"

    private fun key(action: String) = "rl_$action"

    /** Returns true if the action is allowed now. */
    fun allow(context: Context, uid: String, action: String, maxPerHour: Int): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val windowMs = 60L * 60L * 1000L
        val stored = prefs.getString("${uid}_${key(action)}", "") ?: ""
        val times = stored.split(",").mapNotNull { it.toLongOrNull() }
            .filter { now - it < windowMs }
        if (times.size >= maxPerHour) return false
        val updated = (times + now).joinToString(",")
        prefs.edit().putString("${uid}_${key(action)}", updated).apply()
        return true
    }

    fun remaining(context: Context, uid: String, action: String, maxPerHour: Int): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val windowMs = 60L * 60L * 1000L
        val stored = prefs.getString("${uid}_${key(action)}", "") ?: ""
        val times = stored.split(",").mapNotNull { it.toLongOrNull() }
            .filter { now - it < windowMs }
        return (maxPerHour - times.size).coerceAtLeast(0)
    }
}

// ---------------------------------------------------------------------------
// Seller reputation thresholds
// ---------------------------------------------------------------------------

/** Minimum average rating (0..5) below which a seller's listings are flagged. */
const val REPUTATION_THRESHOLD = 2.5f

/** Minimum number of ratings before the threshold applies. */
const val REPUTATION_MIN_RATINGS = 3

data class SellerReputation(
    val sellerId: String = "",
    val average: Float = 0f,
    val count: Int = 0
) {
    val belowThreshold: Boolean
        get() = count >= REPUTATION_MIN_RATINGS && average < REPUTATION_THRESHOLD
}

@Composable
fun loadSellerReputation(db: FirebaseFirestore, sellerId: String): State<SellerReputation> {
    val state = remember(sellerId) { mutableStateOf(SellerReputation(sellerId = sellerId)) }
    DisposableEffect(sellerId) {
        val reg = db.collection("ratings").whereEqualTo("sellerId", sellerId)
            .addSnapshotListener { snap, _ ->
                val ratings = snap?.documents.orEmpty()
                    .mapNotNull { it.getDouble("stars")?.toFloat() }
                state.value = SellerReputation(
                    sellerId = sellerId,
                    average = if (ratings.isEmpty()) 0f else ratings.average().toFloat(),
                    count = ratings.size
                )
            }
        onDispose { reg.remove() }
    }
    return state
}

// ---------------------------------------------------------------------------
// Moderator triage (extends Report with priority / resolution)
// ---------------------------------------------------------------------------

const val REPORT_PRIORITY_LOW = "low"
const val REPORT_PRIORITY_NORMAL = "normal"
const val REPORT_PRIORITY_HIGH = "high"

const val REPORT_STATUS_OPEN = "open"
const val REPORT_STATUS_IN_REVIEW = "in_review"
const val REPORT_STATUS_RESOLVED = "resolved"
const val REPORT_STATUS_DISMISSED = "dismissed"

fun triageReport(
    db: FirebaseFirestore,
    reportId: String,
    status: String,
    priority: String? = null,
    resolution: String? = null,
    moderatorUid: String = ""
) {
    val updates = mutableMapOf<String, Any>(
        "status" to status,
        "triagedAt" to System.currentTimeMillis(),
        "triagedBy" to moderatorUid
    )
    if (priority != null) updates["priority"] = priority
    if (resolution != null) updates["resolution"] = resolution
    db.collection("reports").document(reportId).update(updates)
}

@Composable
fun BlockedSellersScreen(uid: String, onBack: () -> Unit) {
    val db = remember { FirebaseFirestore.getInstance() }
    val blocked = loadBlockedSellers(db, uid)
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Back") }
            Spacer(Modifier.width(8.dp))
            Text("Blocked sellers", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        if (blocked.value.isEmpty()) {
            Text("You haven't blocked any sellers.", style = MaterialTheme.typography.bodyMedium)
            return
        }
        blocked.value.forEach { b ->
            Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(b.sellerName.ifBlank { b.sellerId }, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { unblockSeller(db, uid, b.sellerId) }) {
                        Text("Unblock", color = Color(0xFFE53935))
                    }
                }
            }
        }
    }
}
