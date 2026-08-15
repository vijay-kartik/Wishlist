package com.example.app.wishlist.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.app.wishlist.data.db.entity.Product
import com.example.app.wishlist.data.repository.ProductRepository
import com.example.app.wishlist.util.NotificationParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * NotificationListenerService that captures WhatsApp messages from favorite contacts.
 *
 * This service runs in the background and listens to all notifications.
 * When a WhatsApp message from a favorite contact is detected, it extracts
 * product information and stores it in the ObjectBox database.
 *
 * **Permissions Required:**
 * - BIND_NOTIFICATION_LISTENER_SERVICE (declared in manifest)
 * - User must manually enable this app in Settings > Notifications > Notification Access
 */
class WhatsAppNotificationListener : NotificationListenerService() {

    private lateinit var productRepository: ProductRepository
    private val scope = CoroutineScope(Dispatchers.Default)
    private val notificationParser = NotificationParser()

    override fun onCreate() {
        super.onCreate()
        Timber.d("WhatsAppNotificationListener created")
        productRepository = ProductRepository(applicationContext)
    }

    /**
     * Called when a new notification is posted.
     * Filter for WhatsApp, extract messages, and save products.
     */
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        try {
            val packageName = sbn.packageName

            // Only process WhatsApp notifications
            if (!isWhatsAppNotification(packageName)) {
                return
            }

            Timber.d("WhatsApp notification received from: $packageName")

            val notificationData = extractNotificationData(sbn)
            if (notificationData != null) {
                scope.launch {
                    processWhatsAppMessage(notificationData)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error processing notification")
        }
    }

    /**
     * Called when a notification is removed.
     * We don't need to do anything here for this use case.
     */
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Not needed for this implementation
    }

    /**
     * Extract relevant data from the notification.
     * Returns: [NotificationData] containing sender, message, and timestamp
     */
    private fun extractNotificationData(sbn: StatusBarNotification): NotificationData? {
        return try {
            val notification = sbn.notification
            val extras = notification.extras

            // Extract sender name from notification title
            val senderName = extras.getString(android.app.Notification.EXTRA_TITLE)
                ?: return null

            // Extract message from notification content
            val messageText = extras.getString(android.app.Notification.EXTRA_TEXT)
                ?: extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()
                ?: return null

            // Extract timestamp
            val timestamp = sbn.postTime

            NotificationData(
                senderName = senderName.trim(),
                messageText = messageText.trim(),
                timestamp = timestamp,
                packageName = sbn.packageName
            )
        } catch (e: Exception) {
            Timber.e(e, "Error extracting notification data")
            null
        }
    }

    /**
     * Process a WhatsApp message and extract product information.
     * If products are detected, save them to the database.
     */
    private suspend fun processWhatsAppMessage(data: NotificationData) {
        try {
            Timber.d("Processing message from ${data.senderName}: ${data.messageText}")

            // Check if sender is in favorite contacts (optional validation)
            val isFavoriteContact = productRepository.isFavoriteContact(data.senderName)
            if (!isFavoriteContact) {
                Timber.d("${data.senderName} is not in favorite contacts, skipping")
                return
            }

            // Parse message for product mentions
            val detectedProducts = notificationParser.extractProductsFromText(data.messageText)

            if (detectedProducts.isEmpty()) {
                Timber.d("No products detected in message")
                return
            }

            Timber.d("Found ${detectedProducts.size} products in message")

            // Save each detected product to database
            for (productInfo in detectedProducts) {
                val product = Product(
                    name = productInfo.name,
                    category = productInfo.category,
                    price = productInfo.price,
                    currency = "INR",
                    source = "whatsapp",
                    sourceContact = data.senderName,
                    messageContent = data.messageText,
                    capturedAt = System.currentTimeMillis(),
                    messageTimestamp = data.timestamp
                )

                productRepository.insertProduct(product)
                Timber.d("Saved product: ${product.name} from ${product.sourceContact}")
            }

            // Optional: Send user notification that products were captured
            notifyUserProductsCaptured(
                contactName = data.senderName,
                productCount = detectedProducts.size
            )

        } catch (e: Exception) {
            Timber.e(e, "Error processing WhatsApp message")
        }
    }

    /**
     * Check if this is a WhatsApp notification.
     * Package name: com.whatsapp or com.whatsapp.w4b (Business)
     */
    private fun isWhatsAppNotification(packageName: String): Boolean {
        return packageName.contains("com.whatsapp")
    }

    /**
     * Send a notification to the user that products were captured.
     * This provides feedback without being intrusive.
     */
    private fun notifyUserProductsCaptured(contactName: String, productCount: Int) {
        try {
            val intent = Intent(this, Class.forName("com.example.app.wishlist.MainActivity"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                getPendingIntentFlags()
            )

            // Create a simple notification (can be enhanced later)
            val message = "Captured $productCount item${if (productCount > 1) "s" else ""} from $contactName"
            Timber.d(message)

            // Optional: Emit an event that can be observed by the app UI
            // This can be implemented using EventBus, LiveData, or StateFlow

        } catch (e: Exception) {
            Timber.e(e, "Error creating user notification")
        }
    }

    private fun getPendingIntentFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("WhatsAppNotificationListener destroyed")
    }

    /**
     * Data class to hold extracted notification information.
     */
    data class NotificationData(
        val senderName: String,
        val messageText: String,
        val timestamp: Long,
        val packageName: String
    )

    companion object {
        const val TAG = "WhatsAppListener"

        /**
         * Check if notification listener is enabled in system settings.
         * User must manually enable this in Settings > Notifications > Notification Access
         */
        fun isNotificationListenerEnabled(context: Context): Boolean {
            val enabledServices = android.provider.Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false

            return enabledServices.contains(context.packageName)
        }

        /**
         * Request user to enable notification listener.
         * Opens the system notification access settings.
         */
        fun requestNotificationListenerAccess(context: Context) {
            val intent = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
            context.startActivity(intent)
        }
    }
}
