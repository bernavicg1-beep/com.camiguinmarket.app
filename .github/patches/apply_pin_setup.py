"""Let the admin set their own PIN once (rules allow a single pinHash write),
bump the version to 0.42, and cover the new rule with emulator checks."""
import sys

EXTRAS = "app/src/main/java/com/camiguinmarket/app/Extras.kt"
ADMIN = "app/src/main/java/com/camiguinmarket/app/Admin.kt"
GRADLE = "app/build.gradle.kts"
RULES = "firestore.rules"
TESTS = "scripts/test-firestore-rules.py"


def read(path):
    return open(path, encoding="utf-8").read()


def write(path, text):
    open(path, "w", encoding="utf-8").write(text)


# --- 1. version bump -------------------------------------------------------
g = read(GRADLE)
for old, new in (("versionCode = 41", "versionCode = 42"), ('versionName = "0.41"', 'versionName = "0.42"')):
    if old not in g:
        sys.exit(f"version line missing: {old}")
    g = g.replace(old, new)
write(GRADLE, g)

# --- 2. firestore.rules: one-time PIN hash write ---------------------------
r = read(RULES)
old_rule = '''    // The admins list is readable by signed-in users (so the app can check it),
    // but it cannot be written from the app.
    match /admins/{userId} {
      allow read: if request.auth != null;
      allow write: if false;
    }'''
new_rule = '''    // The admins list is readable by signed-in users (so the app can check it).
    // It cannot be created or deleted from the app. The single write the admin
    // has is setting their own PIN hash, and only while it is still absent: a
    // fresh install therefore has to use the stored PIN instead of choosing its
    // own, and the hash can only be replaced from the Console.
    match /admins/{userId} {
      allow read: if request.auth != null;
      allow create, delete: if false;
      allow update: if request.auth != null
        && request.auth.uid == userId
        && isAdmin()
        && !resource.data.keys().hasAny(["pinHash"])
        && request.resource.data.diff(resource.data).affectedKeys().hasOnly(["pinHash"])
        && request.resource.data.pinHash is string
        && request.resource.data.pinHash.matches("^[0-9a-f]{64}$");
    }'''
if r.count(old_rule) != 1:
    sys.exit("admins rule block not unique")
write(RULES, r.replace(old_rule, new_rule))

# --- 3. rules tests for the PIN hash ---------------------------------------
t = read(TESTS)
tests = '''# --- admin PIN hash: settable once from the app, then frozen --------------
# The PIN lives on admins/{uid}.pinHash, so a fresh install cannot pick its own.
# The admin may add it once to their own document; after that only the Console
# can change it, and nobody else can touch it.
seed("/admins", "pinsetter", {"fields": {"active": {"booleanValue": True}}})
check("pin: the admin can set their own PIN hash once", "PATCH", "/admins/pinsetter", 200,
      uid="pinsetter", mask="pinHash",
      body={"fields": {"pinHash": {"stringValue": "a" * 64}}})
check("pin: the PIN hash cannot be replaced from the app", "PATCH", "/admins/pinsetter", 403,
      uid="pinsetter", mask="pinHash",
      body={"fields": {"pinHash": {"stringValue": "b" * 64}}})
check("pin: another user cannot set the PIN hash", "PATCH", "/admins/pinsetter", 403,
      uid="u1", mask="pinHash",
      body={"fields": {"pinHash": {"stringValue": "c" * 64}}})
seed("/admins", "pinunset", {"fields": {"active": {"booleanValue": True}}})
check("pin: a non-hash value is rejected", "PATCH", "/admins/pinunset", 403,
      uid="pinunset", mask="pinHash",
      body={"fields": {"pinHash": {"stringValue": "1234"}}})
check("pin: the PIN hash cannot be cleared", "PATCH", "/admins/pinunset", 403,
      uid="pinunset", mask="pinHash",
      body={"fields": {"pinHash": {"nullValue": None}}})
check("pin: setting the hash does not unlock other writes", "PATCH", "/admins/pinunset", 403,
      uid="pinunset", mask="active",
      body={"fields": {"active": {"booleanValue": False}}}),
'''

if "def main():" not in t:
    sys.exit("main() missing in the rules test")
t = t.replace("def main():", tests.replace(",\n", "\n") + "\ndef main():", 1)
write(TESTS, t)

# --- 4. Extras.kt: the gate can also create the PIN ------------------------
e = read(EXTRAS)
start = e.index("@Composable\nfun AdminPinGate(")
end = e.index("// ---------------------------------------------------------------------------\n// In-app update:")
gate = '''@Composable
fun AdminPinGate(
    uid: String,
    expectedHash: String?,
    serverError: String? = null,
    onUnlocked: () -> Unit,
    onSetPin: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
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
            if (configured) "Enter admin PIN" else "Set admin PIN",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(4.dp))
        Text(
            if (configured) "The admin dashboard is locked."
            else "Choose a PIN for this admin account. It is stored in Firebase, so every install (a fresh one too) has to use it, and it can only be changed from the Console.",
            style = MaterialTheme.typography.bodySmall,
            color = BrandMuted,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = pin,
            onValueChange = { typed -> pin = typed.filter { it.isDigit() }.take(12) },
            label = { Text(if (configured) "PIN" else "New PIN") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth()
        )
        if (!configured) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = confirm,
                onValueChange = { typed -> confirm = typed.filter { it.isDigit() }.take(12) },
                label = { Text("Confirm PIN") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth()
            )
        }
        (error ?: serverError)?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                val typed = pin.trim()
                if (configured) {
                    if (sha256Hex(typed) == expectedHash!!.trim().lowercase()) {
                        onUnlocked()
                    } else {
                        error = "Wrong PIN."
                    }
                } else if (typed.length < 4) {
                    error = "Use at least 4 digits."
                } else if (typed != confirm.trim()) {
                    error = "The PINs do not match."
                } else {
                    error = null
                    onSetPin(sha256Hex(typed))
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (configured) "Unlock" else "Save PIN") }
    }
}

'''
e = e[:start] + gate + e[end:]
write(EXTRAS, e)

# --- 5. Admin.kt: save the hash to admins/{uid} ----------------------------
a = read(ADMIN)
old = '''    var unlocked by remember { mutableStateOf(false) }
    if (!unlocked) {
        if (!pinLoaded) {
            Text("Loading…", Modifier.fillMaxSize().padding(16.dp))
            return
        }
        AdminPinGate(uid = uid, expectedHash = pinHash, onUnlocked = { unlocked = true })
        return
    }'''
new_block = '''    var pinWriteError by remember { mutableStateOf<String?>(null) }
    var unlocked by remember { mutableStateOf(false) }
    if (!unlocked) {
        if (!pinLoaded) {
            Text("Loading…", Modifier.fillMaxSize().padding(16.dp))
            return
        }
        AdminPinGate(
            uid = uid,
            expectedHash = pinHash,
            serverError = pinWriteError,
            onUnlocked = { unlocked = true },
            onSetPin = { hash ->
                // The rules accept this once, on the admin's own document.
                pinWriteError = null
                adminDb.collection("admins").document(uid)
                    .set(
                        mapOf("pinHash" to hash),
                        com.google.firebase.firestore.SetOptions.merge()
                    )
                    .addOnSuccessListener { unlocked = true }
                    .addOnFailureListener {
                        pinWriteError = "Could not save the PIN. Check your connection and try again."
                    }
            }
        )
        return
    }'''
if a.count(old) != 1:
    sys.exit("AdminScreen PIN gate block not unique")
write(ADMIN, a.replace(old, new_block))
print("pin setup + version bump applied")
