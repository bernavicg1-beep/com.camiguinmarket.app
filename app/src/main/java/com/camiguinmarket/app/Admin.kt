package com.camiguinmarket.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/**
 * One admin announcement (`announcements/{id}`). Admins write them in the
 * dashboard; every signed-in user sees the active ones on Home.
 */
data class Announcement(
    val id: String = "",
    val title: String = "",
    val message: String = "",
    val authorUid: String = "",
    val authorName: String = "",
    val active: Boolean = true,
    val createdAt: Long = 0L
)

/**
 * The latest active announcements, shown at the top of Home. Reading is open to
 * every signed-in user, writing is admin-only (`isAdmin()` in firestore.rules).
 *
 * The query filters on `active` only: Firestore would need a composite index for
 * an equality filter plus `orderBy(createdAt)`, so the ordering is done here.
 */
@Composable
fun AnnouncementsBanner() {
    val db = remember { FirebaseFirestore.getInstance() }
    var items by remember { mutableStateOf<List<Announcement>>(emptyList()) }

    DisposableEffect(Unit) {
        val reg = db.collection("announcements")
            .whereEqualTo("active", true)
            .limit(20)
            .addSnapshotListener { snap, _ ->
                items = snap?.documents.orEmpty()
                    .mapNotNull { d -> d.toObject(Announcement::class.java)?.copy(id = d.id) }
                    .sortedByDescending { it.createdAt }
                    .take(3)
            }
        onDispose { reg.remove() }
    }

    if (items.isEmpty()) return

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { a ->
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = BrandSky),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "\uD83D\uDCE3 " + a.title,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        a.message,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * Admin dashboard: the counts that matter (listings, sellers, open reports,
 * pending verification requests) and the way into the moderation,
 * verification and announcement tools.
 *
 * Visible only with an `admins/{uid}` document; the app cannot create one.
 */
@Composable
fun AdminScreen(
    uid: String,
    isAdmin: Boolean,
    onModeration: () -> Unit,
    onVerification: () -> Unit
) {
    val S = LocalStrings.current
    if (!isAdmin) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text(
                S.adminDashboard,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text("You don't have access to this screen. Only an admin can see it.")
        }
        return
    }

    // PIN on top of the admins/{uid} gate: the hash lives in the document, so a
    // fresh install cannot choose its own PIN.
    val adminDb = remember { FirebaseFirestore.getInstance() }
    var pinHash by remember { mutableStateOf<String?>(null) }
    var pinLoaded by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val reg = adminDb.collection("admins").document(uid)
            .addSnapshotListener { snap, _ ->
                pinHash = snap?.getString("pinHash")
                pinLoaded = true
            }
        onDispose { reg.remove() }
    }
    var unlocked by remember { mutableStateOf(false) }
    if (!unlocked) {
        if (!pinLoaded) {
            Text("Loading…", Modifier.fillMaxSize().padding(16.dp))
            return
        }
        AdminPinGate(uid = uid, expectedHash = pinHash, onUnlocked = { unlocked = true })
        return
    }

    val db = remember { FirebaseFirestore.getInstance() }
    var section by remember { mutableStateOf("overview") }
    var listings by remember { mutableStateOf(0) }
    var sellers by remember { mutableStateOf(0) }
    var openReports by remember { mutableStateOf(0) }
    var pendingVerification by remember { mutableStateOf(0) }
    var announcements by remember { mutableStateOf(0) }

    DisposableEffect(Unit) {
        val listingsReg = db.collection("listings").addSnapshotListener { snap, _ ->
            listings = snap?.size() ?: 0
            sellers = snap?.documents.orEmpty()
                .mapNotNull { it.getString("sellerId") }
                .distinct()
                .size
        }
        val reportsReg = db.collection("reports")
            .whereEqualTo("status", "open")
            .addSnapshotListener { snap, _ -> openReports = snap?.size() ?: 0 }
        val verificationReg = db.collection("verificationRequests")
            .whereEqualTo("status", "pending")
            .addSnapshotListener { snap, _ -> pendingVerification = snap?.size() ?: 0 }
        val announcementsReg = db.collection("announcements")
            .addSnapshotListener { snap, _ -> announcements = snap?.size() ?: 0 }
        onDispose {
            listingsReg.remove()
            reportsReg.remove()
            verificationReg.remove()
            announcementsReg.remove()
        }
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Security, contentDescription = null, tint = BrandBlue)
            Spacer(Modifier.width(6.dp))
            Text(
                S.adminDashboard,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            "$listings listings from $sellers sellers",
            style = MaterialTheme.typography.labelSmall
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = section == "overview",
                onClick = { section = "overview" },
                label = { Text("Overview") }
            )
            FilterChip(
                selected = section == "announcements",
                onClick = { section = "announcements" },
                label = { Text("Announcements ($announcements)") }
            )
        }
        Spacer(Modifier.height(8.dp))

        if (section == "announcements") {
            AnnouncementsAdmin(uid)
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AdminStat("Open reports", openReports, Modifier.weight(1f))
                    AdminStat("Verification", pendingVerification, Modifier.weight(1f))
                }
                AdminAction(
                    icon = Icons.Filled.Delete,
                    title = "Moderation",
                    subtitle = "Remove listings and review reports",
                    onClick = onModeration
                )
                AdminAction(
                    icon = Icons.Filled.Security,
                    title = "Seller verification",
                    subtitle = "Approve or revoke community badges",
                    onClick = onVerification
                )
                AdminAction(
                    icon = Icons.Filled.Campaign,
                    title = "Announcements",
                    subtitle = "Post a message that everyone sees on Home",
                    onClick = { section = "announcements" }
                )
            }
        }
    }
}

@Composable
private fun AdminStat(label: String, value: Int, modifier: Modifier = Modifier) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = modifier
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "$value",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = BrandBlue
            )
            Text(label, style = MaterialTheme.typography.labelSmall, color = BrandMuted)
        }
    }
}

@Composable
private fun AdminAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(BrandSky),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = BrandBlue, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Medium)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = BrandMuted)
            }
            TextButton(onClick = onClick) { Text("Open") }
        }
    }
}

/** Compose a new announcement and manage the ones already posted. */
@Composable
private fun AnnouncementsAdmin(uid: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val db = remember { FirebaseFirestore.getInstance() }
    val author = FirebaseAuth.getInstance().currentUser?.displayName ?: "Admin"

    var title by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    var posted by remember { mutableStateOf<List<Announcement>>(emptyList()) }

    DisposableEffect(Unit) {
        val reg = db.collection("announcements")
            .limit(50)
            .addSnapshotListener { snap, _ ->
                posted = snap?.documents.orEmpty()
                    .mapNotNull { d -> d.toObject(Announcement::class.java)?.copy(id = d.id) }
                    .sortedByDescending { it.createdAt }
            }
        onDispose { reg.remove() }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Title") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = message,
            onValueChange = { message = it },
            label = { Text("Message") },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = {
                info = null
                val t = title.trim()
                val m = message.trim()
                if (t.isEmpty() || m.isEmpty()) {
                    info = "Add a title and a message."
                } else if (t.length > 80 || m.length > 500) {
                    info = "Keep the title under 80 and the message under 500 characters."
                } else {
                    busy = true
                    db.collection("announcements").add(
                        mapOf(
                            "title" to t,
                            "message" to m,
                            "authorUid" to uid,
                            "authorName" to author,
                            "active" to true,
                            "createdAt" to System.currentTimeMillis()
                        )
                    ).addOnSuccessListener {
                        busy = false
                        title = ""
                        message = ""
                        info = "Announcement posted."
                    }.addOnFailureListener { e ->
                        busy = false
                        info = e.localizedMessage ?: "Failed to post the announcement."
                    }
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (busy) "Posting…" else "Post announcement") }
        info?.let { Text(it, style = MaterialTheme.typography.labelSmall) }

        if (posted.isEmpty()) {
            Text("No announcements yet.", style = MaterialTheme.typography.labelSmall)
        } else {
            posted.forEach { a ->
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (a.active) BrandSky else MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(a.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(a.message, style = MaterialTheme.typography.bodySmall)
                        Text(
                            (if (a.active) "visible" else "hidden") + " • by " + a.authorName,
                            style = MaterialTheme.typography.labelSmall,
                            color = BrandMuted
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                db.collection("announcements").document(a.id)
                                    .update("active", !a.active)
                                    .addOnFailureListener { e ->
                                        android.widget.Toast.makeText(
                                            context,
                                            e.localizedMessage ?: "Update failed",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                            }) { Text(if (a.active) "Hide" else "Show") }
                            TextButton(onClick = {
                                db.collection("announcements").document(a.id).delete()
                                    .addOnFailureListener { e ->
                                        android.widget.Toast.makeText(
                                            context,
                                            e.localizedMessage ?: "Delete failed",
                                            android.widget.Toast.LENGTH_LONG
                                        ).show()
                                    }
                            }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
}
