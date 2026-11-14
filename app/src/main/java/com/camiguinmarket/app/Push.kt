package com.camiguinmarket.app

import com.google.firebase.auth.FirebaseAuth
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Nagpakilo sa Worker (`POST /push`) aron makapadala og push notification.
 *
 * Ang Worker (pareho sa R2 upload) maoy mo-verify sa Firebase ID token,
 * mopili sa recipient gikan sa Firestore, ug mokontak sa FCM gamit ang
 * service account — busa walay secret dinhi sa app.
 */
fun sendPush(
    kind: String,
    orderId: String? = null,
    chatId: String? = null,
    text: String? = null
) {
    val base = BuildConfig.R2_UPLOAD_URL.trim().trimEnd('/')
    if (base.isBlank()) return
    val user = FirebaseAuth.getInstance().currentUser ?: return

    val body = JSONObject().put("kind", kind)
    orderId?.let { body.put("orderId", it) }
    chatId?.let { body.put("chatId", it) }
    text?.let { body.put("text", it.take(140)) }

    user.getIdToken(false).addOnSuccessListener { result ->
        val token = result.token ?: return@addOnSuccessListener
        Thread {
            // Best-effort: kon mapakyas ang push, dili maapektohan ang order/chat.
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
