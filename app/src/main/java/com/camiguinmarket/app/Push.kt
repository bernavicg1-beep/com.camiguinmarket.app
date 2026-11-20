package com.camiguinmarket.app

import com.google.firebase.auth.FirebaseAuth
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Calls the Worker (`POST /push`) to deliver a push notification.
 *
 * The Worker (same as the R2 upload) verifies the Firebase ID token,
 * picks the recipient from Firestore, and calls FCM using the
 * service account — so no secret lives in the app.
 */
/** The Worker accepts at most 30 URLs per call; longer lists are chunked. */
private const val MAX_DELETE_URLS = 30

/**
 * Deletes listing photos from R2 through the Worker (`POST /delete-images`).
 *
 * Called when a seller deletes a listing or replaces its photos, and when a
 * moderator removes a listing, so storage does not keep images nobody can
 * reach anymore. The Worker removes keys inside `listing_images/<uid>/` and,
 * for an admin, any listing photo folder. Best effort like [sendPush]: a
 * failure never blocks the change.
 *
 * [onDone] runs when the calls finish (or at once when there is nothing to do),
 * always off the main thread on the network path. Account deletion uses it to
 * remove the photos before the ID token disappears.
 *
 * Call it after the Firestore write succeeds, so a failed save never leaves a
 * listing pointing at photos that were already removed.
 */
fun deleteListingImages(urls: List<String>, onDone: () -> Unit = {}) {
    val base = BuildConfig.R2_UPLOAD_URL.trim().trimEnd('/')
    val user = FirebaseAuth.getInstance().currentUser
    val unique = urls.filter { it.isNotBlank() }.distinct()
    if (base.isBlank() || unique.isEmpty() || user == null) {
        onDone()
        return
    }

    user.getIdToken(false).addOnCompleteListener { result ->
        val token = if (result.isSuccessful) result.result?.token else null
        if (token.isNullOrBlank()) {
            onDone()
            return@addOnCompleteListener
        }
        Thread {
            try {
                unique.chunked(MAX_DELETE_URLS).forEach { chunk ->
                    runCatching {
                        val body = JSONObject().put("urls", JSONArray(chunk))
                        val conn = URL("$base/delete-images").openConnection() as HttpURLConnection
                        conn.requestMethod = "POST"
                        conn.connectTimeout = 10_000
                        conn.readTimeout = 10_000
                        conn.doOutput = true
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.setRequestProperty("Authorization", "Bearer $token")
                        conn.outputStream.use { it.write(body.toString().toByteArray()) }
                        val code = conn.responseCode
                        (if (code in 200..299) conn.inputStream else conn.errorStream)?.close()
                        conn.disconnect()
                    }
                }
            } finally {
                onDone()
            }
        }.start()
    }
}

fun sendPush(
    kind: String,
    orderId: String? = null,
    chatId: String? = null,
    savedSearchId: String? = null,
    text: String? = null
) {
    val base = BuildConfig.R2_UPLOAD_URL.trim().trimEnd('/')
    if (base.isBlank()) return
    val user = FirebaseAuth.getInstance().currentUser ?: return

    val body = JSONObject().put("kind", kind)
    orderId?.let { body.put("orderId", it) }
    chatId?.let { body.put("chatId", it) }
    savedSearchId?.let { body.put("savedSearchId", it) }
    text?.let { body.put("text", it.take(140)) }

    user.getIdToken(false).addOnSuccessListener { result ->
        val token = result.token ?: return@addOnSuccessListener
        Thread {
            // Best-effort: a failed push never blocks the order/chat.
            runCatching {
                val conn = URL("$base/push").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val code = conn.responseCode
                (if (code in 200..299) conn.inputStream else conn.errorStream)?.close()
                conn.disconnect()
            }
        }.start()
    }
}
