package com.camiguinmarket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Security
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
import com.google.firebase.firestore.Query

/**
 * Moderation screen: visible to users with an `admins/{uid}` document.
 * The rules let an admin delete any listing
 * (`isAdmin()`), and the seller is told what happened.
 */
@Composable
fun ModerationScreen(uid: String, isAdmin: Boolean) {
    val S = LocalStrings.current
    if (!isAdmin) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Moderation", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("You don't have access to this screen. Only moderators can see it.")
        }
        return
    }

    val db = remember { FirebaseFirestore.getInstance() }
    var listings by remember { mutableStateOf<List<Listing>>(emptyList()) }
    var toDelete by remember { mutableStateOf<Listing?>(null) }
    var showReports by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        val reg = db.collection("listings")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snap, _ ->
                listings = snap?.documents?.mapNotNull { d ->
                    d.toObject(Listing::class.java)?.copy(id = d.id)
                } ?: emptyList()
            }
        onDispose { reg.remove() }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Security, contentDescription = null, tint = BrandBlue)
            Spacer(Modifier.width(6.dp))
            Text(
                "Moderation",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            "${listings.size} listings in total. Pick the one to delete.",
            style = MaterialTheme.typography.labelSmall
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !showReports,
                onClick = { showReports = false },
                label = { Text("Listings") }
            )
            FilterChip(
                selected = showReports,
                onClick = { showReports = true },
                label = { Text("Reports") }
            )
        }
        Spacer(Modifier.height(8.dp))
        if (showReports) {
            ReportsList(uid)
            return@Column
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listings, key = { it.id }) { l ->
                Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            if (l.imageUrl.isNotBlank()) RemoteImage(l.imageUrl, Modifier.fillMaxSize())
                            else Icon(Icons.Filled.Storefront, contentDescription = null, tint = BrandBlue)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
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
                            Text(
                                "by ${l.sellerName}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        TextButton(onClick = { toDelete = l }) {
                            Icon(
                                Icons.Filled.Delete,
                                contentDescription = null,
                                tint = Color(0xFFE53935),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Delete", color = Color(0xFFE53935))
                        }
                    }
                }
            }
        }
    }

    toDelete?.let { l ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete listing?") },
            text = {
                Text(
                    "\"${l.title}\" ni ${l.sellerName} (₱%.2f).\n\n".format(l.price) +
                        "The seller gets a notification that a moderator removed it."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    // Admins may delete another seller's photos; the Worker
                    // checks `admins/{uid}`. Only after the document is gone.
                    val photos = l.imageUrls.takeIf { it.isNotEmpty() }
                        ?: listOfNotNull(l.imageUrl.takeIf { !it.isNullOrBlank() })
                    db.collection("listings").document(l.id).delete()
                        .addOnSuccessListener { deleteListingImages(photos) }
                    notifyUser(
                        db = db,
                        toUid = l.sellerId,
                        fromUid = uid,
                        fromName = "Moderator",
                        message = "A moderator removed the listing: ${l.title}".take(190)
                    )
                    toDelete = null
                }) { Text("Delete", color = Color(0xFFE53935)) }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) { Text("No") }
            }
        )
    }
}

/** Report list; only an admin can see it (rules: `allow read: if isAdmin()`). */
@Composable
fun ReportsList(uid: String) {
    val db = remember { FirebaseFirestore.getInstance() }
    var reports by remember { mutableStateOf<List<Report>>(emptyList()) }

    DisposableEffect(Unit) {
        val reg = db.collection("reports")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(50)
            .addSnapshotListener { snap, _ ->
                reports = snap?.documents.orEmpty().mapNotNull { d ->
                    d.toObject(Report::class.java)?.copy(id = d.id)
                }
            }
        onDispose { reg.remove() }
    }

    if (reports.isEmpty()) {
        Text("No reports received.")
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(reports, key = { it.id }) { r ->
            Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        r.listingTitle,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text("Reason: ${r.reason}", style = MaterialTheme.typography.labelSmall)
                    if (r.note.isNotBlank()) {
                        Text("\"${r.note}\"", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "reported by ${r.reporterName} • ${r.status}" +
                            (r.priority?.let { " • $it" } ?: ""),
                        style = MaterialTheme.typography.labelSmall
                    )
                    if (r.status == "open") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                val listingRef =
                                    db.collection("listings").document(r.listingId)
                                // Read the photos first: they are removed after the
                                // listing is deleted (the Worker allows admins).
                                listingRef.get().addOnCompleteListener { task ->
                                    val listing = task.result
                                        ?.toObject(Listing::class.java)
                                    val photos = listing?.let { l ->
                                        l.imageUrls.takeIf { it.isNotEmpty() }
                                            ?: listOfNotNull(
                                                l.imageUrl.takeIf { !it.isNullOrBlank() }
                                            )
                                    } ?: emptyList()
                                    listingRef.delete()
                                        .addOnSuccessListener { deleteListingImages(photos) }
                                }
                                db.collection("reports").document(r.id)
                                    .update("status", "resolved")
                                notifyUser(
                                    db = db,
                                    toUid = r.sellerId,
                                    fromUid = uid,
                                    fromName = "Moderator",
                                    message = "A moderator removed the listing: ${r.listingTitle}"
                                        .take(190)
                                )
                            }) { Text("Delete listing", color = Color(0xFFE53935)) }
                            TextButton(onClick = {
                                triageReport(db, r.id, REPORT_STATUS_IN_REVIEW, priority = REPORT_PRIORITY_NORMAL, moderatorUid = uid)
                            }) { Text("In review") }
                            TextButton(onClick = {
                                triageReport(db, r.id, REPORT_STATUS_RESOLVED, resolution = "Dismissed by moderator", moderatorUid = uid)
                            }) { Text("Dismiss") }
                            TextButton(onClick = {
                                db.collection("users").document(r.sellerId).set(
                                    mapOf(
                                        "verified" to false,
                                        "verifiedBy" to "",
                                        "verifiedAt" to 0L
                                    ),
                                    com.google.firebase.firestore.SetOptions.merge()
                                )
                                db.collection("reports").document(r.id)
                                    .update("status", "resolved")
                                notifyUser(
                                    db = db,
                                    toUid = r.sellerId,
                                    fromUid = uid,
                                    fromName = "Moderator",
                                    message = "Your community verification was revoked by a moderator."
                                        .take(190)
                                )
                            }) { Text(S.revokeVerification, color = Color(0xFFE53935)) }
                        }
                    }
                }
            }
        }
    }
}
