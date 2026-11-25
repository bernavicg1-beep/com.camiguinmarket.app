p = "app/src/main/java/com/camiguinmarket/app/MainActivity.kt"
s = open(p).read()

# 1. readPushTarget: add saved/saved_search case
old1 = '    "chat" -> intent.getStringExtra("chatId")?.let { "chat:$it" }\n            else -> null'
new1 = '    "chat" -> intent.getStringExtra("chatId")?.let { "chat:$it" }\n            "saved", "saved_search" -> intent.getStringExtra("savedSearchId")?.let { "saved:$it" }\n            else -> null'
assert old1 in s, "old1 not found"
s = s.replace(old1, new1)

# 2. readDeepLinkTarget: replace entire function body
old2 = """    /** Read a shared listing deep link (camiguinmarket://listing/{id}) from the intent. */
    private fun readDeepLinkTarget(intent: Intent?): String? {
        val uri = intent?.data ?: return null
        if (uri.scheme != "camiguinmarket" || uri.host != "listing") return null
        val id = uri.lastPathSegment?.let { android.net.Uri.decode(it) } ?: return null
        if (id.isBlank()) return null
        return "listing:$id"
    }"""
new2 = """    /** Read a deep link (camiguinmarket://listing|order|chat/{id}, or the
     *  matching https://<worker>/... app link) from the intent. */
    private fun readDeepLinkTarget(intent: Intent?): String? {
        val uri = intent?.data ?: return null
        // Custom scheme: camiguinmarket://<host>/<id>
        if (uri.scheme == "camiguinmarket") {
            val host = uri.host ?: return null
            val id = uri.lastPathSegment?.let { android.net.Uri.decode(it) } ?: return null
            if (id.isBlank()) return null
            return when (host) {
                "listing" -> "listing:$id"
                "order" -> "order:$id"
                "chat" -> "chat:$id"
                else -> null
            }
        }
        // Verified HTTPS app links on the worker origin.
        if (uri.scheme == "https" && uri.host == "camiguin-market-images.bernavicg1.workers.dev") {
            val segs = uri.pathSegments
            if (segs.size >= 2) {
                val kind = segs[0]
                val id = android.net.Uri.decode(segs[1])
                if (id.isNotBlank()) {
                    return when (kind) {
                        "listing" -> "listing:$id"
                        "order" -> "order:$id"
                        "chat" -> "chat:$id"
                        else -> null
                    }
                }
            }
        }
        return null
    }"""
assert old2 in s, "old2 not found"
s = s.replace(old2, new2)

# 3. LaunchedEffect: add "saved" case before the when-block closing brace
needle = "        }\n        onPushHandled()"
assert needle in s, "needle not found for saved case"
assert '"saved" -> {' not in s, "already applied"
saved_case = '''
            "saved" -> {
                // Open the saved searches screen; the id is informational.
                overlay = "saved"
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("CamiguinPush", "saved searches opened from $id")
                }
            }
'''
# Only replace the FIRST occurrence (the push-target when block)
s = s.replace(needle, saved_case + needle, 1)

open(p, "w").write(s)
print("Done")
