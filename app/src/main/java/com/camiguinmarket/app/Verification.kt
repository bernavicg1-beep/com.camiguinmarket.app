package com.camiguinmarket.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions

// ---------------------------------------------------------------------------
// Seller-facing: request community verification
// ---------------------------------------------------------------------------

/**
 * Shows the seller's current community-verification status and a button to
 * request it. Writes a `verificationRequests` doc when requested; disabled
 * while a pending request exists.
 */
@Composable
fun RequestVerificationSection(uid: String) {
    val S = LocalStrings.current
    val db = remember { FirebaseFirestore.getInstance() }
    var verified by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }

    DisposableEffect(uid) {
        val regUser = db.collection("users").document(uid)
            .addSnapshotListener { snap, _ ->
                verified = snap?.getBoolean("verified") ?: false
            }
        val regReq = db.collection("verificationRequests")
            .whereEqualTo("sellerUid", uid)
            .addSnapshotListener { snap, _ ->
                pending = snap?.documents.orEmpty().any { it.getString("status") == "pending" }
            }
        onDispose {
            regUser.remove()
            regReq.remove()
        }
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Security, contentDescription = null, tint = BrandBlue)
                Spacer(Modifier.width(8.dp))
                Text(
                    S.communityVerification,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(4.dp))
            when {
                verified -> Text(
                    S.communityVerifiedStatus,
                    style = MaterialTheme.typography.bodySmall,
                    color = BrandBlue
                )
                pending -> Text(
                    S.verificationPending,
                    style = MaterialTheme.typography.bodySmall,
                    color = BrandMuted
                )
                else -> Text(
                    S.requestVerificationBadge,
                    style = MaterialTheme.typography.bodySmall,
                    color = BrandMuted
                )
            }
            info?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = BrandMuted)
            }
            if (!verified && !pending) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        sending = true
                        info = null
                        db.collection("users").document(uid).get()
                            .addOnSuccessListener { d ->
                                val sellerName = d.getString("name").orEmpty().ifBlank { uid }
                                val req = mapOf(
                                    "sellerUid" to uid,
                                    "sellerName" to sellerName,
                                    "status" to "pending",
                                    "createdAt" to System.currentTimeMillis()
                                )
                                db.collection("verificationRequests").add(req)
                                    .addOnSuccessListener {
                                        sending = false
                                        info = S.requestSent
                                    }
                                    .addOnFailureListener { e ->
                                        sending = false
                                        info = e.localizedMessage ?: S.requestFailed
                                    }
                            }
                            .addOnFailureListener { e ->
                                sending = false
                                info = e.localizedMessage ?: S.requestFailed
                            }
                    },
                    enabled = !sending
                ) { Text(if (sending) "Sending…" else S.requestVerification) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Admin screen
// ---------------------------------------------------------------------------

@Composable
fun AdminVerificationScreen(uid: String, isAdmin: Boolean, onBack: () -> Unit) {
    val S = LocalStrings.current
    if (!isAdmin) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text(S.adminVerification, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(S.moderationNoAccess)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onBack) { Text(S.back) }
        }
        return
    }

    val db = remember { FirebaseFirestore.getInstance() }
    var query by remember { mutableStateOf("") }
    var users by remember { mutableStateOf<List<Map<String, Any>>>(emptyList()) }
    var requests by remember { mutableStateOf<List<Pair<String, Map<String, Any>>>>(emptyList()) }
    var showRequests by remember { mutableStateOf(false) }
    var actionInfo by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val regUsers = db.collection("users")
            .addSnapshotListener { snap, _ ->
                users = snap?.documents.orEmpty().map { d ->
                    val m = d.data.orEmpty().toMutableMap()
                    m["_id"] = d.id
                    m
                }
            }
        val regReq = db.collection("verificationRequests")
            .addSnapshotListener { snap, _ ->
                requests = snap?.documents.orEmpty().map { d ->
                    d.id to (d.data.orEmpty())
                }
            }
        onDispose {
            regUsers.remove()
            regReq.remove()
        }
    }

    val filteredUsers = remember(users, query) {
        if (query.isBlank()) users
        else users.filter { u ->
            (u["name"] as? String).orEmpty().contains(query, ignoreCase = true) ||
                (u["_id"] as? String).orEmpty().contains(query, ignoreCase = true)
        }
    }
    val pendingRequests = remember(requests) {
        requests.filter { it.second["status"] == "pending" }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(S.back) }
            Spacer(Modifier.width(8.dp))
            Text(S.adminVerification, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !showRequests,
                onClick = { showRequests = false },
                label = { Text(S.sellersCount.format(users.size)) }
            )
            FilterChip(
                selected = showRequests,
                onClick = { showRequests = true },
                label = { Text(S.requestsCount.format(pendingRequests.size)) }
            )
        }
        Spacer(Modifier.height(8.dp))
        actionInfo?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = BrandMuted)
            Spacer(Modifier.height(4.dp))
        }

        if (showRequests) {
            if (pendingRequests.isEmpty()) {
                Text(S.noPendingVerificationRequests)
                return@Column
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(pendingRequests, key = { it.first }) { (rid, r) ->
                    val sellerUid = r["sellerUid"] as? String ?: ""
                    val sellerName = r["sellerName"] as? String ?: sellerUid
                    Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp)) {
                            Text(sellerName, fontWeight = FontWeight.Bold)
                            Text(S.uidLabel.format(sellerUid), style = MaterialTheme.typography.labelSmall, color = BrandMuted)
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    db.collection("users").document(sellerUid).set(
                                        mapOf(
                                            "verified" to true,
                                            "verifiedBy" to "admin:$uid",
                                            "verifiedAt" to System.currentTimeMillis()
                                        ),
                                        SetOptions.merge()
                                    )
                                    db.collection("verificationRequests").document(rid).update(
                                        mapOf(
                                            "status" to "approved",
                                            "reviewedBy" to uid,
                                            "reviewedAt" to System.currentTimeMillis()
                                        )
                                    )
                                    actionInfo = S.verifiedSeller.format(sellerName)
                                }) { Text("Approve") }
                                OutlinedButton(onClick = {
                                    db.collection("verificationRequests").document(rid).update(
                                        mapOf(
                                            "status" to "rejected",
                                            "reviewedBy" to uid,
                                            "reviewedAt" to System.currentTimeMillis()
                                        )
                                    )
                                    actionInfo = S.rejectedVerificationRequest.format(sellerName)
                                }) { Text("Reject") }
                            }
                        }
                    }
                }
            }
            return@Column
        }

        // Sellers tab
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(S.searchSellers) },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(filteredUsers, key = { it["_id"] as? String ?: "" }) { u ->
                val sellerUid = u["_id"] as? String ?: ""
                if (sellerUid.isBlank()) return@items
                val name = u["name"] as? String ?: sellerUid
                val phoneVerified = u["phoneVerified"] as? Boolean ?: false
                val communityVerified = u["verified"] as? Boolean ?: false
                Card(shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(S.uidLabel.format(sellerUid), style = MaterialTheme.typography.labelSmall, color = BrandMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (phoneVerified) Text("📱", style = MaterialTheme.typography.labelSmall)
                                if (communityVerified) Text("✓", style = MaterialTheme.typography.labelSmall, color = BrandBlue)
                            }
                        }
                        if (communityVerified) {
                            OutlinedButton(onClick = {
                                db.collection("users").document(sellerUid).set(
                                    mapOf(
                                        "verified" to false,
                                        "verifiedBy" to "",
                                        "verifiedAt" to 0L
                                    ),
                                    SetOptions.merge()
                                )
                                actionInfo = S.revokedVerificationFor.format(name)
                            }) { Text(S.revokeVerification) }
                        } else {
                            Button(onClick = {
                                db.collection("users").document(sellerUid).set(
                                    mapOf(
                                        "verified" to true,
                                        "verifiedBy" to "admin:$uid",
                                        "verifiedAt" to System.currentTimeMillis()
                                    ),
                                    SetOptions.merge()
                                )
                                actionInfo = S.verifiedSeller.format(name)
                            }) { Text(S.verifySeller) }
                        }
                    }
                }
            }
        }
    }
}
