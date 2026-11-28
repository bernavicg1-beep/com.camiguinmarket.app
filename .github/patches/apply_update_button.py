"""Add a manual "Check for updates" row to the profile and bump to 0.43."""
import re
import sys

EXTRAS = "app/src/main/java/com/camiguinmarket/app/Extras.kt"
GRADLE = "app/build.gradle.kts"

# --- version ---------------------------------------------------------------
g = open(GRADLE, encoding="utf-8").read()
for old, new in (("versionCode = 42", "versionCode = 43"), ('versionName = "0.42"', 'versionName = "0.43"')):
    if old not in g:
        sys.exit(f"version line missing: {old}")
    g = g.replace(old, new)
open(GRADLE, "w", encoding="utf-8").write(g)

e = open(EXTRAS, encoding="utf-8").read()

# --- 1. split the fetch helper so a manual check can report a failure ------
helper_start = e.index("/** Latest GitHub release when it is newer than this build, otherwise null. */")
prompt_start = e.index("@Composable\nfun AppUpdatePrompt() {")

HELPERS = '''/** Latest GitHub release when it is newer than this build; throws when offline. */
private fun latestUpdateOrNull(): AppUpdate? {
    val conn = java.net.URL(RELEASES_API).openConnection() as java.net.HttpURLConnection
    conn.connectTimeout = 8000
    conn.readTimeout = 8000
    conn.setRequestProperty("Accept", "application/vnd.github+json")
    conn.setRequestProperty("User-Agent", "CamiguinMarket")
    try {
        if (conn.responseCode != 200) return null
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val o = JSONObject(body)
        val tag = o.optString("tag_name")
        val page = o.optString("html_url")
        return if (page.isNotBlank() && isNewerVersion(tag, BuildConfig.VERSION_NAME)) {
            AppUpdate(tag.trim().removePrefix("v"), page)
        } else null
    } finally {
        conn.disconnect()
    }
}

/** Background check on launch: a failure (offline, rate limit) is silent. */
fun fetchAppUpdate(): AppUpdate? = try { latestUpdateOrNull() } catch (e: Exception) { null }

'''
e = e[:helper_start] + HELPERS + e[prompt_start:]

# --- 2. dialog + auto prompt + the manual row ------------------------------
prompt_start = e.index("@Composable\nfun AppUpdatePrompt() {")
UI = '''@Composable
fun AppUpdateDialog(update: AppUpdate, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Update available") },
        text = {
            Text(
                "Version ${update.version} is ready. Install it over this app and your listings, chats and settings stay."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(update.pageUrl)))
                }
            }) { Text("Get the update") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } }
    )
}

/** Automatic check, once per launch. */
@Composable
fun AppUpdatePrompt() {
    var update by remember { mutableStateOf<AppUpdate?>(null) }
    LaunchedEffect(Unit) {
        val found = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { fetchAppUpdate() }
        if (found != null) update = found
    }
    update?.let { u -> AppUpdateDialog(u) { update = null } }
}

/** Profile row: check now instead of waiting for the launch prompt. */
@Composable
private fun UpdateCheckRow() {
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var update by remember { mutableStateOf<AppUpdate?>(null) }
    val scope = rememberCoroutineScope()

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !checking) {
                checking = true
                status = null
                scope.launch {
                    val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { latestUpdateOrNull() }
                    }
                    checking = false
                    result
                        .onSuccess { found ->
                            if (found != null) update = found else status = "You're up to date."
                        }
                        .onFailure { status = "Could not reach GitHub. Check your connection." }
                }
            }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(BrandSky),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Refresh, contentDescription = null, tint = BrandBlue, modifier = Modifier.size(19.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Check for updates", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                status ?: if (checking) "Checking…" else "Installed version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.labelSmall,
                color = BrandMuted
            )
        }
        if (checking) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
    }
    update?.let { u -> AppUpdateDialog(u) { update = null } }
}
'''
e = e[:prompt_start] + UI

# --- 3. wire the row into ProfileScreen -----------------------------------
anchor = '''                if (isAdmin) {
                    ProfileDivider()
                    ProfileRow(Icons.Filled.Security, "Admin dashboard", onAdmin)
                }
'''
if e.count(anchor) != 1:
    sys.exit("ProfileScreen admin row anchor not unique")
e = e.replace(anchor, anchor + '''                ProfileDivider()
                UpdateCheckRow()
''', 1)

# --- 4. imports -----------------------------------------------------------
needed = [
    "androidx.compose.material.icons.filled.Refresh",
    "kotlinx.coroutines.launch",
]
for imp in needed:
    line = f"import {imp}\n"
    if line in e:
        continue
    matches = list(re.finditer(r"^import .+$", e, re.M))
    if not matches:
        sys.exit("no imports found")
    end = matches[-1].end()
    e = e[:end] + "\n" + line.rstrip() + e[end:]
    e = e.replace("\n\n", "\n", 1) if e.count("\n\n") > 0 and False else e

open(EXTRAS, "w", encoding="utf-8").write(e)

src = open(EXTRAS, encoding="utf-8").read()
if src.count("{") != src.count("}") or src.count("(") != src.count(")"):
    sys.exit("unbalanced brackets in Extras.kt")
for must in ("fun UpdateCheckRow()", "fun AppUpdateDialog(", "fun AppUpdatePrompt()", "latestUpdateOrNull()"):
    if must not in src:
        sys.exit(f"missing {must}")
print("manual update check added")
