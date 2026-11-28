"""Restore the contact-link inputs (Telegram/Messenger/WhatsApp), add a device
PIN in front of the admin dashboard, remove the orphaned phone-verify route,
and pin the GCash permission fix with a rules test.
"""
import subprocess
import sys

EXTRAS = "app/src/main/java/com/camiguinmarket/app/Extras.kt"
MAIN = "app/src/main/java/com/camiguinmarket/app/MainActivity.kt"
ADMIN = "app/src/main/java/com/camiguinmarket/app/Admin.kt"
TEST = "scripts/test-firestore-rules.py"
BASE = "7f79c82"  # last commit that still had ContactLinksSection


def read(path):
    return open(path, encoding="utf-8").read()


def write(path, text):
    open(path, "w", encoding="utf-8").write(text)


# --- 1. recover the old contact-links section, minus the Facebook parts ------
old = subprocess.run(
    ["git", "show", BASE + ":" + EXTRAS], capture_output=True, text=True, check=True
).stdout
start = old.index("fun ContactLinksSection(uid: String) {")
end = old.index("/** The hosting Activity for the Firebase phone verification callbacks. */")
fn = old[start:end]

fb_busy = "    var fbBusy by remember { mutableStateOf(false) }\n"
fb_info = "    var fbInfo by remember { mutableStateOf<String?>(null) }\n"
assert fb_busy in fn and fb_info in fn
fn = fn.replace(fb_busy, "").replace(fb_info, "")

a = fn.index("    val fbAvailable = remember")
b = fn.index("    LaunchedEffect(uid) {")
fn = fn[:a] + fn[b:]

a = fn.index("            if (fbAvailable) {")
b = fn.index("            OutlinedButton(\n                onClick = {\n                    tgInfo = null")
fn = fn[:a] + fn[b:]

if "facebook" in fn.lower():
    sys.exit("Facebook code still present in the recovered section")
without_ctx = fn.replace("    val ctx = LocalContext.current\n", "")
if "ctx" not in without_ctx:
    fn = without_ctx

cur = read(EXTRAS)
g_start = cur.index("@Composable\nfun GcashSection(uid: String) {")
g_end = cur.index("/** The hosting Activity for the Firebase phone verification callbacks. */")
cur = cur[:g_start] + "@Composable\n" + fn + cur[g_end:]
cur = cur.replace("        GcashSection(uid)\n", "        ContactLinksSection(uid)\n")
if "GcashSection" in cur:
    sys.exit("GcashSection still referenced")

# --- 2. admin PIN gate (local second lock, hashed in SharedPreferences) ------
PIN = '''
// ---------------------------------------------------------------------------
// Admin PIN: a local second lock in front of the admin dashboard. The real
// gate is the `admins/{uid}` document (written from the Console only); this PIN
// keeps the dashboard closed on this device even for someone holding the phone.
// Only the hash is stored.
// ---------------------------------------------------------------------------

private fun adminLockPrefs(context: Context) =
    context.getSharedPreferences("admin_lock", Context.MODE_PRIVATE)

private fun pinHash(pin: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(pin.toByteArray())
        .joinToString("") { "%02x".format(it) }

@Composable
fun AdminPinGate(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { adminLockPrefs(context) }
    val stored = remember { prefs.getString("hash", null) }
    var first by remember { mutableStateOf("") }
    var second by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Filled.Security, contentDescription = null, tint = BrandBlue, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            if (stored == null) "Set an admin PIN" else "Enter admin PIN",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (stored == null) "Only this phone will be able to open the admin dashboard."
            else "The admin dashboard is locked on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = BrandMuted,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = first,
            onValueChange = { typed -> first = typed.filter { it.isDigit() }.take(12) },
            label = { Text("PIN") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth()
        )
        if (stored == null) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = second,
                onValueChange = { typed -> second = typed.filter { it.isDigit() }.take(12) },
                label = { Text("Confirm PIN") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth()
            )
        }
        error?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                if (stored == null) {
                    if (first.length < 4) {
                        error = "Use at least 4 digits."
                    } else if (first != second) {
                        error = "The PINs do not match."
                    } else {
                        prefs.edit().putString("hash", pinHash(first)).apply()
                        onUnlocked()
                    }
                } else if (pinHash(first) == stored) {
                    onUnlocked()
                } else {
                    error = "Wrong PIN."
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (stored == null) "Save PIN" else "Unlock") }
    }
}
'''
write(EXTRAS, cur + PIN)

# --- 3. drop the orphaned phone-verify route --------------------------------
m = read(MAIN)
v_start = m.index('                "verify" -> Column(Modifier.fillMaxSize()) {')
v_end = m.index('                "order" -> orderDetail?.let { order ->')
m = m[:v_start] + m[v_end:]
write(MAIN, m)

# --- 4. lock the admin dashboard behind the PIN ------------------------------
a = read(ADMIN)
anchor = '''    val db = remember { FirebaseFirestore.getInstance() }
    var section by remember { mutableStateOf("overview") }'''
gate = '''    // Local PIN on top of the admins/{uid} gate.
    var unlocked by remember { mutableStateOf(false) }
    if (!unlocked) {
        AdminPinGate(onUnlocked = { unlocked = true })
        return
    }

    val db = remember { FirebaseFirestore.getInstance() }
    var section by remember { mutableStateOf("overview") }'''
if a.count(anchor) != 1:
    sys.exit("AdminScreen anchor found %d times" % a.count(anchor))
write(ADMIN, a.replace(anchor, gate))

# --- 5. rules test so the verified-user GCash bug cannot come back -----------
t = read(TEST)
block = '''# --- regression: a verified seller can still save their GCash number ---------
# The owner branch used to compare every key of the merged document, so the
# `verified` fields an admin writes made every profile save answer
# PERMISSION_DENIED. Saving only `gcashNumber` (an updateMask, like the app's
# set(merge)) must succeed for a verified seller, and the owner still must not
# be able to change the verification fields themselves.
seed("/users", "verifiedseller", {"fields": {
    "uid": {"stringValue": "verifiedseller"},
    "name": {"stringValue": "Verified Seller"},
    "verified": {"booleanValue": True},
    "verifiedBy": {"stringValue": "admin:admin1"},
    "verifiedAt": {"integerValue": "1"},
}})
check("gcash: a verified seller can still save their number", "PATCH",
      "/users/verifiedseller", 200, uid="verifiedseller", mask="gcashNumber",
      body={"fields": {"gcashNumber": {"stringValue": "09171234567"}}})
check("gcash: a verified seller cannot unverify themselves", "PATCH",
      "/users/verifiedseller", 403, uid="verifiedseller", mask="verified",
      body={"fields": {"verified": {"booleanValue": False}}})

'''
marker = "\n\ndef main():"
if t.count(marker) != 1:
    sys.exit("main() marker is not unique")
t = t.replace(marker, "\n\n" + block + "def main():", 1)
write(TEST, t)
print("profile finish applied")
