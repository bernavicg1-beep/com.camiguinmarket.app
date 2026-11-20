package com.camiguinmarket.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MarketMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        FirebaseFirestore.getInstance()
            .collection("users").document(user.uid)
            .collection("tokens").document(token)
            .set(mapOf("token" to token, "updatedAt" to System.currentTimeMillis()))
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val title = message.notification?.title
            ?: message.data["title"]
            ?: "Camiguin Market"
        val body = message.notification?.body
            ?: message.data["message"]
            ?: return
        show(title, body, message.data)
    }

    private fun channelFor(data: Map<String, String>): String = when (data["type"]) {
        "chat" -> CHANNEL_CHATS
        "order", "status", "cart", "rating", "moderation" -> CHANNEL_ORDERS
        else -> CHANNEL_SAVED_SEARCHES
    }

    private fun stableId(data: Map<String, String>): Int {
        val key = data["chatId"] ?: data["orderId"] ?: data["savedSearchId"]
            ?: data["listingId"] ?: data["id"] ?: ""
        return if (key.isBlank()) (System.currentTimeMillis() and Int.MAX_VALUE.toLong()).toInt()
        else key.hashCode()
    }

    private fun show(title: String, body: String, data: Map<String, String>) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = channelFor(data)
        ensureChannels(manager)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, "android.permission.POST_NOTIFICATIONS") !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            data.forEach { (k, v) -> putExtra(k, v) }
        }
        val tap = PendingIntent.getActivity(
            this,
            stableId(data),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(channelId)
            .setContentIntent(tap)
            .build()

        manager.notify(stableId(data), notification)
    }

    private fun ensureChannels(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channels = listOf(
            NotificationChannel(
                CHANNEL_CHATS,
                "Chat messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "New chat messages from buyers and sellers"
                enableVibration(true)
            },
            NotificationChannel(
                CHANNEL_ORDERS,
                "Order updates",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Order status changes"
                enableVibration(true)
            },
            NotificationChannel(
                CHANNEL_SAVED_SEARCHES,
                "Saved search alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "New listings matching a saved search"
            }
        )
        manager.createNotificationChannels(channels)
    }

    companion object {
        const val CHANNEL_CHATS = "chat_messages"
        const val CHANNEL_ORDERS = "order_updates"
        const val CHANNEL_SAVED_SEARCHES = "saved_search_alerts"
    }
}
