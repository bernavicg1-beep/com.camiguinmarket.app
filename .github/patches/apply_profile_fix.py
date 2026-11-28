"""Profile fixes.

1. firestore.rules: the owner branch compared every key of the merged
   document, so the `verified` fields an admin writes made every profile
   save fail with PERMISSION_DENIED. Compare only the changed keys instead.
2. Extras.kt: the phone-number block and the Telegram/Messenger/WhatsApp
   inputs are removed from the profile; the GCash number stays.
"""
import sys

FIELDS = "['uid', 'name', 'email', 'photoUrl', 'phone', 'phoneVerified', 'gcashNumber', 'telegram', 'messenger', 'whatsapp', 'telegramVerified', 'messengerVerified']"

RULES = "firestore.rules"
rules = open(RULES, encoding="utf-8").read()
old_rule = "          && request.resource.data.keys().hasOnly(" + FIELDS + ")" + chr(10)
if rules.count(old_rule) != 1:
    sys.exit("rules anchor found %d times" % rules.count(old_rule))
new_rule = """          // Compare only the keys this write changes: the merged document can
          // carry fields the owner must not touch (verified/verifiedBy/
          // verifiedAt set by an admin), and those used to fail this check
          // with PERMISSION_DENIED on every profile save.
          && (resource == null
            ? request.resource.data.keys().hasOnly(""" + FIELDS + """)
            : request.resource.data.diff(resource.data).affectedKeys().hasOnly(""" + FIELDS + """))
"""
rules = rules.replace(old_rule, new_rule)
open(RULES, "w", encoding="utf-8").write(rules)
print("firestore.rules patched")

EXTRAS = "app/src/main/java/com/camiguinmarket/app/Extras.kt"
src = open(EXTRAS, encoding="utf-8").read()

calls_old = """        PhoneVerifySection(uid)
        Spacer(Modifier.height(12.dp))
        RequestVerificationSection(uid)
        Spacer(Modifier.height(12.dp))
        ContactLinksSection(uid)
"""
calls_new = """        RequestVerificationSection(uid)
        Spacer(Modifier.height(12.dp))
        GcashSection(uid)
"""
if src.count(calls_old) != 1:
    sys.exit("profile call block found %d times" % src.count(calls_old))
src = src.replace(calls_old, calls_new)

start_marker = "fun ContactLinksSection(uid: String) {"
end_marker = "/** The hosting Activity for the Firebase phone verification callbacks. */"
if src.count(start_marker) != 1 or src.count(end_marker) != 1:
    sys.exit("function markers not unique")
start = src.index(start_marker)
end = src.index(end_marker)

NEW = """@Composable
fun GcashSection(uid: String) {
    val db = remember { FirebaseFirestore.getInstance() }
    var gcashNumber by remember { mutableStateOf("") }
    var info by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(uid) {
        db.collection("users").document(uid).get().addOnSuccessListener { d ->
            gcashNumber = d.getString("gcashNumber") ?: ""
        }
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                "GCash number (buyers send payments here)",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = gcashNumber,
                onValueChange = { typed -> gcashNumber = typed.filter { it.isDigit() }.take(11) },
                label = { Text("09XX XXX XXXX") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val gcash = gcashNumber.trim()
                    if (gcash.isNotEmpty() && !Regex("^09[0-9]{9}$").matches(gcash)) {
                        info = "GCash number must be an 11-digit mobile starting with 09, e.g. 09171234567."
                        return@Button
                    }
                    busy = true
                    db.collection("users").document(uid).set(
                        mapOf("gcashNumber" to gcash),
                        SetOptions.merge()
                    ).addOnSuccessListener {
                        busy = false
                        info = "Saved."
                    }.addOnFailureListener { e ->
                        busy = false
                        info = e.localizedMessage ?: "Failed."
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (busy) "Saving…" else "Save GCash number") }
            info?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

"""
src = src[:start] + NEW + src[end:]
open(EXTRAS, "w", encoding="utf-8").write(src)
print("Extras.kt patched: %d lines" % src.count(chr(10)))
