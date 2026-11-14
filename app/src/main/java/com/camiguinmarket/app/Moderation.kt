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
        Spacer(Modifier.height(8.dp))

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
