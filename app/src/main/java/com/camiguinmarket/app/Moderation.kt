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
 * Moderation screen: makita sa mga user nga dunay `admins/{uid}` document.
 * Gitugotan sa rules ang admin sa pagpapas sa bisan unsang listing
 * (`isAdmin()`), ug pahibaloon ang seller kon unsay nahitabo.
 */
@Composable
fun ModerationScreen(uid: String, isAdmin: Boolean) {
    if (!isAdmin) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Moderation", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Wala kay access niini nga screen. Ang moderator ra ang makakita niini.")
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
            "${listings.size} ka listing sa tanan. Pilia ang angay papason.",
            style = MaterialTheme.typography.labelSmall
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !showReports,
                onClick = { showReports = false },
                label = { Text("Mga Listing") }
            )
            FilterChip(
                selected = showReports,
                onClick = { showReports = true },
                label = { Text("Mga Report") }
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
                            Modifier.size(48.dp).clip(RoundedCornerShape(10.dp)).background(BrandSky),
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
                                "ni ${l.sellerName}",
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
                            Text("Papas", color = Color(0xFFE53935))
                        }
                    }
                }
            }
        }
    }

    toDelete?.let { l ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Papason ang listing?") },
            text = {
                Text(
                    "\"${l.title}\" ni ${l.sellerName} (₱%.2f).\n\n".format(l.price) +
                        "Makadawat ang seller og notification nga gi-remove kini sa moderator."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    db.collection("listings").document(l.id).delete()
                    notifyUser(
                        db = db,
                        toUid = l.sellerId,
                        fromUid = uid,
                        fromName = "Moderator",
                        message = "Gi-remove sa moderator ang listing: ${l.title}".take(190)
                    )
                    toDelete = null
                }) { Text("Papas", color = Color(0xFFE53935)) }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) { Text("Dili") }
            }
        )
    }
}

/** Lista sa mga report; admin ra ang makakita (rules: `allow read: if isAdmin()`). */
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
        Text("Walay report nga nadawat.")
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
                    Text("Rason: ${r.reason}", style = MaterialTheme.typography.labelSmall)
                    if (r.note.isNotBlank()) {
                        Text("\"${r.note}\"", style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "gi-report ni ${r.reporterName} • ${r.status}",
                        style = MaterialTheme.typography.labelSmall
                    )
                    if (r.status == "open") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                db.collection("listings").document(r.listingId).delete()
                                db.collection("reports").document(r.id)
                                    .update("status", "resolved")
                                notifyUser(
                                    db = db,
                                    toUid = r.sellerId,
                                    fromUid = uid,
                                    fromName = "Moderator",
                                    message = "Gi-remove sa moderator ang listing: ${r.listingTitle}"
                                        .take(190)
                                )
                            }) { Text("Papas ang listing", color = Color(0xFFE53935)) }
                            TextButton(onClick = {
                                db.collection("reports").document(r.id)
                                    .update("status", "dismissed")
                            }) { Text("Dismiss") }
                        }
                    }
                }
            }
        }
    }
}
