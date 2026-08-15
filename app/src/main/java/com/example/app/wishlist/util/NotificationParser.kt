package com.example.app.wishlist.util

import timber.log.Timber

/**
 * Utility to parse WhatsApp messages and extract product information.
 *
 * This uses pattern matching to detect product mentions, links, and prices.
 * Can be enhanced with ML-based entity extraction or keyword databases.
 */
class NotificationParser {

    data class DetectedProduct(
        val name: String,
        val category: String = "Other",
        val price: Double? = null,
        val url: String? = null
    )

    /**
     * Extract potential products from message text.
     * Looks for:
     * 1. Common e-commerce URLs (Amazon, Flipkart, etc.)
     * 2. Price mentions (₹XXX or $XXX)
     * 3. Common product keywords (phone, laptop, book, etc.)
     * 4. Brand names with product mentions
     */
    fun extractProductsFromText(messageText: String): List<DetectedProduct> {
        val products = mutableListOf<DetectedProduct>()

        try {
            // Extract URLs (most reliable indicator of products)
            val urlMatches = Regex(
                "https?://(?:www\\.)?(?:amazon|flipkart|myntra|ajio|snapdeal|ebay|aliexpress|etsy)[^\\s]+"
            ).findAll(messageText)

            for (match in urlMatches) {
                val url = match.value
                val productName = extractProductNameFromUrl(url) ?: "Product from link"
                val category = detectCategoryFromUrl(url)

                products.add(
                    DetectedProduct(
                        name = productName,
                        category = category,
                        url = url
                    )
                )
            }

            // Extract prices and associated product names
            val pricePattern = Regex("([₹$])\\s*([0-9,]+(?:\\.[0-9]{2})?)")
            val priceMatches = pricePattern.findAll(messageText)

            for (match in priceMatches) {
                val currency = match.groupValues[1]
                val priceStr = match.groupValues[2]
                val price = parsePrice(priceStr)

                // Try to find the product name near the price
                val priceStartIndex = match.range.first
                val contextStart = maxOf(0, priceStartIndex - 150)
                val contextEnd = minOf(messageText.length, priceStartIndex + 150)
                val context = messageText.substring(contextStart, contextEnd)

                val productName = extractProductNameFromContext(context)
                if (productName != null && products.none { it.name == productName }) {
                    products.add(
                        DetectedProduct(
                            name = productName,
                            price = price,
                            category = "Other"
                        )
                    )
                }
            }

            // Extract brand + product combinations (e.g., "Sony WH-1000XM5", "iPhone 15")
            val brandProductPattern = Regex(
                "(?:Apple|Sony|Samsung|Nike|Adidas|Microsoft|Google|OnePlus|Realme)\\s+[A-Za-z0-9\\-\\s]+"
            )
            val brandMatches = brandProductPattern.findAll(messageText)

            for (match in brandMatches) {
                val productName = match.value.trim()
                if (products.none { it.name.equals(productName, ignoreCase = true) }) {
                    val category = detectCategoryFromProductName(productName)
                    products.add(
                        DetectedProduct(
                            name = productName,
                            category = category
                        )
                    )
                }
            }

            // Extract common product keywords (fallback)
            val keywords = mapOf(
                "phone" to "Electronics",
                "laptop" to "Electronics",
                "headphones" to "Electronics",
                "book" to "Books",
                "t-shirt" to "Clothing",
                "shoes" to "Clothing",
                "watch" to "Electronics",
                "camera" to "Electronics",
                "tablet" to "Electronics",
                "earbuds" to "Electronics",
                "speaker" to "Electronics",
                "charger" to "Electronics"
            )

            for ((keyword, category) in keywords) {
                if (messageText.lowercase().contains(keyword)) {
                    // Extract surrounding text as product name
                    val pattern = Regex("[A-Za-z0-9\\s\\-]+$keyword[A-Za-z0-9\\s\\-]+")
                    val matches = pattern.findAll(messageText.lowercase())

                    for (match in matches) {
                        val productName = messageText.substring(
                            match.range.first,
                            match.range.last + 1
                        ).trim()

                        if (products.none { it.name.equals(productName, ignoreCase = true) }
                            && productName.length > keyword.length + 2
                        ) {
                            products.add(
                                DetectedProduct(
                                    name = productName,
                                    category = category
                                )
                            )
                        }
                    }
                }
            }

        } catch (e: Exception) {
            Timber.e(e, "Error parsing message text: $messageText")
        }

        // Remove duplicates and limit results
        return products
            .distinctBy { it.name.lowercase() }
            .take(5) // Limit to 5 products per message to avoid noise
    }

    /**
     * Extract product name from URL.
     * Example: https://amazon.in/Sony-WH-1000XM5/... -> "Sony WH-1000XM5"
     */
    private fun extractProductNameFromUrl(url: String): String? {
        return try {
            // Simple approach: extract the main product identifier from URL
            val pathSegments = url.split("/")
            val productSegment = pathSegments.find { segment ->
                segment.contains(Regex("[A-Za-z0-9]+-[A-Za-z0-9]+"))
            }

            productSegment?.replace("-", " ")?.trim()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Detect product category from URL domain.
     */
    private fun detectCategoryFromUrl(url: String): String {
        return when {
            url.contains("amazon") || url.contains("flipkart") -> "Electronics"
            url.contains("myntra") || url.contains("ajio") -> "Clothing"
            url.contains("goodreads") || url.contains("flipkart/s?q=book") -> "Books"
            else -> "Other"
        }
    }

    /**
     * Detect product category from product name.
     */
    private fun detectCategoryFromProductName(name: String): String {
        return when {
            name.contains(Regex("(?i)(phone|laptop|tablet|headphone|watch|camera|speaker)")) -> "Electronics"
            name.contains(Regex("(?i)(shirt|pants|dress|shoe|jacket)")) -> "Clothing"
            name.contains(Regex("(?i)(book|novel)")) -> "Books"
            name.contains(Regex("(?i)(sofa|lamp|pillow|chair)")) -> "Home"
            else -> "Other"
        }
    }

    /**
     * Extract product name from surrounding context.
     * Looks for capitalized words near the price.
     */
    private fun extractProductNameFromContext(context: String): String? {
        return try {
            // Look for capitalized product names
            val pattern = Regex("([A-Z][a-zA-Z0-9\\s\\-]+)")
            val matches = pattern.findAll(context)

            // Return the longest match that looks like a product name
            matches.maxByOrNull { it.value.length }?.value?.trim()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse price string to Double.
     * Handles formats like: 25,999 or 25999 or 25.99
     */
    private fun parsePrice(priceStr: String): Double? {
        return try {
            priceStr.replace(",", "").toDoubleOrNull()
        } catch (e: Exception) {
            null
        }
    }
}
