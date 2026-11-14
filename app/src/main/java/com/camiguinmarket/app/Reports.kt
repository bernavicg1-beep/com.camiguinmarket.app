package com.camiguinmarket.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.FirebaseFirestore

/** Reklamo sa usa ka listing (Firestore: reports/{autoId}). */
data class Report(
    val id: String = "",
    val listingId: String = "",
    val listingTitle: String = "",
    val sellerId: String = "",
    val sellerName: String = "",
    val reporterUid: String = "",
    val reporterName: String = "",
    val reason: String = "",
    val note: String = "",
    val status: String = "open",
    val createdAt: Long = 0L
)

val reportReasons = listOf(
    "Dili tinuod",
    "Scam o pangilad",
    "Dili angay nga sulod",
    "Sayop nga category",
    "Uban pa"
)

fun submitReport(
    db: FirebaseFirestore,
    reporterUid: String,
    reporterName: String,
    listing: Listing,
    reason: String,
    note: String,
    onResult: (String?) -> Unit
) {
    if (reason.isBlank()) {
        onResult("Pilia ang rason sa report.")
        return
    }
    val report = Report(
        listingId = listing.id,
        listingTitle = listing.title,
        sellerId = listing.sellerId,
        sellerName = listing.sellerName,
        reporterUid = reporterUid,
        reporterName = reporterName,
        reason = reason,
        note = note.trim(),
        status = "open",
        createdAt = System.currentTimeMillis()
    )
    db.collection("reports").document().set(report)
        .addOnSuccessListener { onResult(null) }
        .addOnFailureListener { onResult(it.localizedMessage ?: "Napakyas ang report.") }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportDialog(
    listing: Listing,
    uid: String,
    displayName: String,
    onClose: () -> Unit
) {
    val db = remember { FirebaseFirestore.getInstance() }
    var reason by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!sending) onClose() },
        title = { Text("I-report ang listing") },
        text = {
            Column {
                Text("\"${listing.title}\"", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text("Rason", style = MaterialTheme.typography.labelMedium)
                FlowChips(
                    options = reportReasons,
                    selected = reason,
                    onSelect = { reason = it }
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    note,
                    { note = it },
                    label = { Text("Dugang detalye (opsyonal)") },
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = Color(0xFFE53935), style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !sending && reason.isNotBlank(),
                onClick = {
                    sending = true
                    error = null
                    submitReport(db, uid, displayName, listing, reason, note) { err ->
                        sending = false
                        if (err == null) onClose() else error = err
                    }
                }
            ) { Text("Ipadala") }
        },
        dismissButton = {
            TextButton(onClick = onClose, enabled = !sending) { Text("Dili") }
        }
    )
}

/** Yano nga wrap sa mga chip (LazyRow ang gigamit sa ubang dapit). */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FlowChips(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            FilterChip(
                selected = o == selected,
                onClick = { onSelect(o) },
                label = { Text(o, style = MaterialTheme.typography.labelSmall) },
                shape = RoundedCornerShape(12.dp)
            )
        }
    }
}
