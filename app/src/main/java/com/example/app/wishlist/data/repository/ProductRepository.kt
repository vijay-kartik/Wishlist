package com.example.app.wishlist.data.repository

import android.content.Context
import com.example.app.wishlist.data.db.ObjectBoxProvider
import com.example.app.wishlist.data.db.entity.Product
import com.example.app.wishlist.data.db.entity.Product_
import io.objectbox.Box
import io.objectbox.kotlin.query
import io.objectbox.query.QueryBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Repository for managing Product data persistence.
 *
 * Handles all database operations for products:
 * - Insert, update, delete products
 * - Query products by various criteria
 * - Semantic search
 * - Filtering and sorting
 * - Contact management
 *
 * Note: ObjectBox's `equal`/`contains` overloads for String properties always require
 * an explicit `QueryBuilder.StringOrder` argument (there's no 2-arg default) — every
 * string comparison below passes CASE_INSENSITIVE explicitly for that reason.
 */
class ProductRepository(private val context: Context) {

    private val productBox: Box<Product> = ObjectBoxProvider.getProductBox(context)

    /**
     * Insert a new product into the database.
     */
    suspend fun insertProduct(product: Product): Long = withContext(Dispatchers.IO) {
        try {
            product.generateSearchText()
            product.lastModified = System.currentTimeMillis()
            val id = productBox.put(product)
            Timber.d("Product inserted with ID: $id")
            id
        } catch (e: Exception) {
            Timber.e(e, "Error inserting product")
            -1
        }
    }

    /**
     * Update an existing product.
     */
    suspend fun updateProduct(product: Product) = withContext(Dispatchers.IO) {
        try {
            product.generateSearchText()
            product.lastModified = System.currentTimeMillis()
            productBox.put(product)
            Timber.d("Product updated: ${product.name}")
        } catch (e: Exception) {
            Timber.e(e, "Error updating product")
        }
    }

    /**
     * Delete a product by ID.
     */
    suspend fun deleteProduct(productId: Long) = withContext(Dispatchers.IO) {
        try {
            productBox.remove(productId)
            Timber.d("Product deleted: $productId")
        } catch (e: Exception) {
            Timber.e(e, "Error deleting product")
        }
    }

    /**
     * Get all products (unfiltered).
     */
    suspend fun getAllProducts(): List<Product> = withContext(Dispatchers.IO) {
        try {
            productBox.all
        } catch (e: Exception) {
            Timber.e(e, "Error fetching all products")
            emptyList()
        }
    }

    /**
     * Get products from a specific contact.
     */
    suspend fun getProductsByContact(contactName: String): List<Product> =
        withContext(Dispatchers.IO) {
            try {
                productBox.query {
                    equal(Product_.sourceContact, contactName, QueryBuilder.StringOrder.CASE_INSENSITIVE)
                    orderDesc(Product_.capturedAt)
                }.find()
            } catch (e: Exception) {
                Timber.e(e, "Error fetching products by contact: $contactName")
                emptyList()
            }
        }

    /**
     * Get products from a specific source (whatsapp, screenshot, manual).
     */
    suspend fun getProductsBySource(source: String): List<Product> =
        withContext(Dispatchers.IO) {
            try {
                productBox.query {
                    equal(Product_.source, source, QueryBuilder.StringOrder.CASE_INSENSITIVE)
                    orderDesc(Product_.capturedAt)
                }.find()
            } catch (e: Exception) {
                Timber.e(e, "Error fetching products by source: $source")
                emptyList()
            }
        }

    /**
     * Get products within a specific price range.
     */
    suspend fun getProductsByPriceRange(minPrice: Double, maxPrice: Double): List<Product> =
        withContext(Dispatchers.IO) {
            try {
                productBox.query {
                    between(Product_.price, minPrice, maxPrice)
                    orderDesc(Product_.price)
                }.find()
            } catch (e: Exception) {
                Timber.e(e, "Error fetching products by price range")
                emptyList()
            }
        }

    /**
     * Get products by category.
     */
    suspend fun getProductsByCategory(category: String): List<Product> =
        withContext(Dispatchers.IO) {
            try {
                productBox.query {
                    equal(Product_.category, category, QueryBuilder.StringOrder.CASE_INSENSITIVE)
                    orderDesc(Product_.capturedAt)
                }.find()
            } catch (e: Exception) {
                Timber.e(e, "Error fetching products by category: $category")
                emptyList()
            }
        }

    /**
     * Get recent products (last N days).
     */
    suspend fun getRecentProducts(days: Int = 7): List<Product> =
        withContext(Dispatchers.IO) {
            try {
                val timeThreshold = System.currentTimeMillis() - (days * 24L * 60L * 60L * 1000L)
                productBox.query {
                    greater(Product_.capturedAt, timeThreshold)
                    orderDesc(Product_.capturedAt)
                }.find()
            } catch (e: Exception) {
                Timber.e(e, "Error fetching recent products")
                emptyList()
            }
        }

    /**
     * Semantic search for products.
     * Searches across name and the denormalized searchText field (name, category,
     * contact, notes, event name — see Product.generateSearchText()).
     */
    suspend fun searchProducts(query: String): List<Product> =
        withContext(Dispatchers.IO) {
            try {
                val nameMatches = productBox.query {
                    contains(Product_.name, query, QueryBuilder.StringOrder.CASE_INSENSITIVE)
                }.find()

                val searchTextMatches = productBox.query {
                    contains(Product_.searchText, query.lowercase(), QueryBuilder.StringOrder.CASE_INSENSITIVE)
                }.find()

                (nameMatches + searchTextMatches)
                    .distinctBy { it.id }
                    .sortedByDescending { it.capturedAt }
            } catch (e: Exception) {
                Timber.e(e, "Error searching products: $query")
                try {
                    productBox.query {
                        contains(Product_.name, query, QueryBuilder.StringOrder.CASE_INSENSITIVE)
                        orderDesc(Product_.capturedAt)
                    }.find()
                } catch (fallbackError: Exception) {
                    Timber.e(fallbackError, "Fallback search also failed")
                    emptyList()
                }
            }
        }

    /**
     * Get product count.
     */
    suspend fun getProductCount(): Long = withContext(Dispatchers.IO) {
        try {
            productBox.count()
        } catch (e: Exception) {
            Timber.e(e, "Error getting product count")
            0
        }
    }

    /**
     * Check if a contact is in the favorite contacts list.
     * (Implementation depends on how favorite contacts are stored)
     */
    suspend fun isFavoriteContact(contactName: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                // Check if we have any products from this contact
                val hasExistingProducts = productBox.query {
                    equal(Product_.sourceContact, contactName, QueryBuilder.StringOrder.CASE_INSENSITIVE)
                }.find().isNotEmpty()

                // Or check against a stored favorite contacts list
                // This can be enhanced with a separate FavoriteContact entity
                hasExistingProducts || getFavoriteContacts().any { it.equals(contactName, ignoreCase = true) }
            } catch (e: Exception) {
                Timber.e(e, "Error checking if contact is favorite")
                false
            }
        }

    /**
     * Get list of all unique contacts that have suggested products.
     */
    suspend fun getAllContacts(): List<String> = withContext(Dispatchers.IO) {
        try {
            productBox.all
                .mapNotNull { it.sourceContact }
                .distinct()
                .sorted()
        } catch (e: Exception) {
            Timber.e(e, "Error fetching all contacts")
            emptyList()
        }
    }

    /**
     * Get favorite contacts list.
     * (Can be stored in SharedPreferences or a separate database entity)
     */
    suspend fun getFavoriteContacts(): List<String> = withContext(Dispatchers.IO) {
        try {
            // TODO: Implement persistent storage of favorite contacts
            // For now, return all contacts with products
            getAllContacts()
        } catch (e: Exception) {
            Timber.e(e, "Error fetching favorite contacts")
            emptyList()
        }
    }

    /**
     * Add a contact to favorites.
     */
    suspend fun addFavoriteContact(contactName: String) = withContext(Dispatchers.IO) {
        try {
            // TODO: Store in SharedPreferences or database
            Timber.d("Added favorite contact: $contactName")
        } catch (e: Exception) {
            Timber.e(e, "Error adding favorite contact")
        }
    }

    /**
     * Remove a contact from favorites.
     */
    suspend fun removeFavoriteContact(contactName: String) = withContext(Dispatchers.IO) {
        try {
            // TODO: Remove from SharedPreferences or database
            Timber.d("Removed favorite contact: $contactName")
        } catch (e: Exception) {
            Timber.e(e, "Error removing favorite contact")
        }
    }

    /**
     * Mark a product as purchased.
     */
    suspend fun markAsPurchased(productId: Long) = withContext(Dispatchers.IO) {
        try {
            val product = productBox.get(productId)
            if (product != null) {
                product.isPurchased = true
                product.purchaseDate = System.currentTimeMillis()
                product.status = "purchased"
                updateProduct(product)
            }
        } catch (e: Exception) {
            Timber.e(e, "Error marking product as purchased")
        }
    }

    /**
     * Get statistics about products.
     */
    suspend fun getProductStats(): ProductStats = withContext(Dispatchers.IO) {
        try {
            val allProducts = getAllProducts()
            val purchasedProducts = allProducts.filter { it.isPurchased }
            val totalSpent = purchasedProducts.mapNotNull { it.price }.sum()

            ProductStats(
                totalProducts = allProducts.size,
                purchasedProducts = purchasedProducts.size,
                wishedProducts = allProducts.count { it.status == "active" },
                totalSpent = totalSpent,
                uniqueContacts = getAllContacts().size,
                averagePrice = allProducts.mapNotNull { it.price }.let { prices ->
                    if (prices.isNotEmpty()) prices.average() else 0.0
                }
            )
        } catch (e: Exception) {
            Timber.e(e, "Error calculating product stats")
            ProductStats()
        }
    }

    /**
     * Clear all products (use with caution!).
     */
    suspend fun clearAllProducts() = withContext(Dispatchers.IO) {
        try {
            productBox.removeAll()
            Timber.w("All products cleared from database!")
        } catch (e: Exception) {
            Timber.e(e, "Error clearing products")
        }
    }

    data class ProductStats(
        val totalProducts: Int = 0,
        val purchasedProducts: Int = 0,
        val wishedProducts: Int = 0,
        val totalSpent: Double = 0.0,
        val uniqueContacts: Int = 0,
        val averagePrice: Double = 0.0
    )
}
