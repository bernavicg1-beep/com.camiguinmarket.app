"""In-app update prompt, admin PIN hash on admins/{uid}, remove the unused
phone-verification section and the facebook-login dependency.

Workflow files are deliberately not touched: GITHUB_TOKEN cannot create or
update them (the deploy guard lives in rules-deploy-guard.yml).
"""
import sys

EXTRAS = "app/src/main/java/com/camiguinmarket/app/Extras.kt"
MAIN = "app/src/main/java/com/camiguinmarket/app/MainActivity.kt"
ADMIN = "app/src/main/java/com/camiguinmarket/app/Admin.kt"
GRADLE = "app/build.gradle.kts"
MANIFEST = "app/src/main/AndroidManifest.xml"
APPINFO = "app/src/main/res/values/app_info.xml"
HASH_TOOL = "scripts/admin-pin-hash.py"


def read(path):
    return open(path, encoding="utf-8").read()


def write(path, text):
    open(path, "w", encoding="utf-8").write(text)


# --- 1. Extras.kt: drop PhoneVerifySection and findActivity -------------------
e = read(EXTRAS)

start = e.index("/**\n * Phone number with SMS verification.")
end = e.index("/** Converts 09XXXXXXXXX")
e = e[:start] + e[end:]

start = e.index("/** The hosting Activity for the Firebase phone verification callbacks. */")
end = e.index("@Composable\nfun StatCard(")
e = e[:start] + e[end:]

# --- 2. Extras.kt: replace the local-PIN tail ------------------------------
cut = e.index("// ---------------------------------------------------------------------------\n// Admin PIN:")
TAIL = '''// ---------------------------------------------------------------------------
// Admin PIN: a second lock in front of the admin dashboard. The hash lives on
// admins/{uid} (written from the Console only), so a fresh install cannot pick
// its own PIN — it has to use the one the owner stored.
// ---------------------------------------------------------------------------

fun sha256Hex(value: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

@Composable
fun AdminPinGate(uid: String, expectedHash: String?, onUnlocked: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val configured = !expectedHash.isNullOrBlank()

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Filled.Security, contentDescription = null, tint = BrandBlue, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            if (configured) "Enter admin PIN" else "Admin PIN not set",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (configured) "The admin dashboard is locked."
            else "Add pinHash to admins/$uid in the Firebase Console (scripts/admin-pin-hash.py prints it).",
            style = MaterialTheme.typography.bodySmall,
            color = BrandMuted,
            textAlign = TextAlign.Center
        )
        if (configured) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = pin,
                onValueChange = { typed -> pin = typed.filter { it.isDigit() }.take(12) },
                label = { Text("PIN") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth()
            )
            error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    if (sha256Hex(pin.trim()) == expectedHash!!.trim().lowercase()) {
                        onUnlocked()
                    } else {
                        error = "Wrong PIN."
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Unlock") }
        }
    }
}

// ---------------------------------------------------------------------------
// In-app update: installing the new APK over this one keeps every listing,
// chat and setting (a reinstall would start from scratch).
// ---------------------------------------------------------------------------

data class AppUpdate(val version: String, val pageUrl: String)

private const val RELEASES_API =
    "https://api.github.com/repos/bernavicg1-beep/com.camiguinmarket.app/releases/latest"

private fun versionParts(value: String): List<Int> =
    value.trim().removePrefix("v").removePrefix("V")
        .split('.', '-', '+')
        .mapNotNull { it.toIntOrNull() }

internal fun isNewerVersion(latest: String, current: String): Boolean {
    val a = versionParts(latest)
    val b = versionParts(current)
    if (a.isEmpty()) return false
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

/** Latest GitHub release when it is newer than this build, otherwise null. */
fun fetchAppUpdate(): AppUpdate? = try {
    val conn = java.net.URL(RELEASES_API).openConnection() as java.net.HttpURLConnection
    conn.connectTimeout = 8000
    conn.readTimeout = 8000
    conn.setRequestProperty("Accept", "application/vnd.github+json")
    conn.setRequestProperty("User-Agent", "CamiguinMarket")
    try {
        if (conn.responseCode == 200) {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val o = JSONObject(body)
            val tag = o.optString("tag_name")
            val page = o.optString("html_url")
            if (page.isNotBlank() && isNewerVersion(tag, BuildConfig.VERSION_NAME)) {
                AppUpdate(tag.trim().removePrefix("v"), page)
            } else null
        } else null
    } finally {
        conn.disconnect()
    }
} catch (e: Exception) {
    null
}

@Composable
fun AppUpdatePrompt() {
    val context = LocalContext.current
    var update by remember { mutableStateOf<AppUpdate?>(null) }
    LaunchedEffect(Unit) {
        val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fetchAppUpdate() }
        if (found != null) update = found
    }
    update?.let { u ->
        AlertDialog(
            onDismissRequest = { update = null },
            title = { Text("Update available") },
            text = {
                Text(
                    "Version ${u.version} is ready. Install it over this app and your listings, chats and settings stay."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    update = null
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u.pageUrl)))
                    }
                }) { Text("Get the update") }
            },
            dismissButton = { TextButton(onClick = { update = null }) { Text("Later") } }
        )
    }
}
'''
e = e[:cut] + TAIL

if "import android.content.Intent\n" not in e:
    anchor = "import android.content.Context\n"
    if anchor not in e:
        sys.exit("Context import anchor missing")
    e = e.replace(anchor, anchor + "import android.content.Intent\n", 1)

write(EXTRAS, e)

# --- 3. MainActivity: show the update prompt --------------------------------
m = read(MAIN)
marker = "    // Navigation and the new features (mockup): tabs, cart, favorites, notifications, chat.\n"
if m.count(marker) != 1:
    sys.exit("MainActivity anchor not unique")
m = m.replace(marker, "    // In-app update: install over this build instead of reinstalling.\n    AppUpdatePrompt()\n\n" + marker, 1)
write(MAIN, m)

# --- 4. Admin.kt: read the PIN hash from admins/{uid} -----------------------
a = read(ADMIN)
old = '''    // Local PIN on top of the admins/{uid} gate.
    var unlocked by remember { mutableStateOf(false) }
    if (!unlocked) {
        AdminPinGate(onUnlocked = { unlocked = true })
        return
    }'''
new = '''    // PIN on top of the admins/{uid} gate: the hash lives in the document, so a
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
    }'''
if a.count(old) != 1:
    sys.exit("AdminScreen gate block not unique")
write(ADMIN, a.replace(old, new))

# --- 5. drop the facebook-login dependency, manifest entries and strings ----
g = read(GRADLE)
fb_dep = '    implementation("com.facebook.android:facebook-login:16.3.0")\n'
if fb_dep not in g:
    sys.exit("facebook dependency line not found")
write(GRADLE, g.replace(fb_dep, ""))

man = read(MANIFEST)
fb_start = man.index("        <!-- Facebook Login")
fb_end = man.index("        <!-- Maps are Leaflet")
write(MANIFEST, man[:fb_start] + man[fb_end:])

info = read(APPINFO)
kept = [ln for ln in info.splitlines(True) if "facebook_app_id" not in ln and "fb_login_protocol_scheme" not in ln]
write(APPINFO, "".join(kept))

# --- 6. helper to compute the hash for the Console --------------------------
write(HASH_TOOL, '''#!/usr/bin/env python3
"""Print the SHA-256 hash to paste into admins/{uid}.pinHash.

Usage:  python3 scripts/admin-pin-hash.py 1234
"""
import getpass
import hashlib
import sys

pin = sys.argv[1] if len(sys.argv) > 1 else getpass.getpass("Admin PIN: ")
pin = pin.strip()
if len(pin) < 4 or not pin.isdigit():
    sys.exit("Use at least 4 digits.")
print(hashlib.sha256(pin.encode()).hexdigest())
''')
print("final cleanup applied")
