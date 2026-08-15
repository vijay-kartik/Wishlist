package com.example.app.wishlist.data.db.entity

import android.os.Parcelable
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import kotlinx.parcelize.Parcelize

/**
 * Product entity representing a wish-listed product.
 * Stored in ObjectBox for fast, on-device queries and semantic search.
 *
 * Each product captures:
 * - Product metadata (name, category, price)
 * - Context (who suggested it, when, why)
 * - Association with events (birthdays, trips, etc.)
 * - Status (wishlisted, purchased, archived)
 */
@Entity
@Parcelize
data class Product(
    @Id
    var id: Long = 0,

    // Product Information
    @Index
    var name: String = "",
    var category: String = "Other", // Electronics, Clothing, Books, Home, etc.
    var price: Double? = null,
    var currency: String = "INR", // INR, USD, etc.

    // Product source and context
    @Index
    var source: String = "", // "whatsapp", "screenshot", "manual"
    var sourceContact: String? = null, // Who suggested it (from WhatsApp or stored contact)
    var messageContent: String? = null, // Full WhatsApp message content
    var productUrl: String? = null, // Link to product (e.g., Amazon URL)
    var productImagePath: String? = null, // Local path to product image/screenshot

    // Timestamps
    var capturedAt: Long = System.currentTimeMillis(), // When the product was added to wishlist
    var messageTimestamp: Long? = null, // When the original message was sent
    var lastModified: Long = System.currentTimeMillis(),

    // Product notes and metadata
    var notes: String? = null, // User's custom notes about the product
    var tags: String? = null, // Comma-separated tags for additional context

    // Status
    var status: String = "active", // active, wishlisted, purchased, archived
    var isPurchased: Boolean = false,
    var purchaseDate: Long? = null,

    // Event association
    var associatedEventId: Long? = null, // Links to Event entity for smart recommendations
    var eventName: String? = null, // Cached event name for quick display

    // Relevance and ranking (for recommendations)
    var relevanceScore: Double = 1.0, // Higher = more relevant for upcoming events
    var viewCount: Int = 0, // Number of times user viewed this product
    var lastViewed: Long? = null,

    // Search optimization
    var searchText: String = "", // Denormalized text for faster full-text search
) : Parcelable {

    /**
     * Generate optimized search text for better full-text search.
     * Includes product name, category, source contact, notes.
     */
    fun generateSearchText() {
        searchText = listOf(
            name,
            category,
            sourceContact,
            notes,
            eventName
        ).filterNotNull()
            .joinToString(" ")
            .lowercase()
    }

    /**
     * Check if product is from a specific contact.
     */
    fun isFromContact(contactName: String): Boolean {
        return sourceContact?.equals(contactName, ignoreCase = true) == true
    }

    /**
     * Check if product is from WhatsApp.
     */
    fun isFromWhatsApp(): Boolean = source == "whatsapp"

    /**
     * Check if product is from a screenshot.
     */
    fun isFromScreenshot(): Boolean = source == "screenshot"

    /**
     * Check if product is from manual entry.
     */
    fun isManualEntry(): Boolean = source == "manual"

    /**
     * Get display name for the source.
     */
    fun getSourceDisplay(): String {
        return when {
            isFromWhatsApp() && sourceContact != null -> "Suggested by $sourceContact"
            isFromScreenshot() -> "From Screenshot"
            isManualEntry() -> "Manually Added"
            else -> source
        }
    }

    /**
     * Get formatted price string.
     */
    fun getFormattedPrice(): String? {
        return price?.let { price ->
            val currencySymbol = when (currency) {
                "USD" -> "$"
                "INR" -> "₹"
                else -> currency
            }
            val formattedPrice = String.format("%,.0f", price)
            "$currencySymbol$formattedPrice"
        }
    }

    /**
     * Check if product is ready to recommend (has enough context).
     */
    fun isReadyForRecommendation(): Boolean {
        return !name.isBlank() && (category != "Other" || price != null)
    }
}
