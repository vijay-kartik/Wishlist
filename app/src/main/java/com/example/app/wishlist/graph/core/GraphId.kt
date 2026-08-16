package com.example.app.wishlist.graph.core

import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * Stable, export-safe identity for a node.
 *
 * The graph carries two identifiers per node:
 *  - [GraphId] — a UUID that survives export, re-import and eventual multi-device sync.
 *  - `graphKey` — the ObjectBox `@Id` long, used for every edge and every join. Fast,
 *    but local to one database file and meaningless outside it.
 *
 * Edges reference `graphKey`. Anything crossing the app boundary references [GraphId].
 */
@JvmInline
value class GraphId(val value: String) {

    override fun toString(): String = value

    companion object {
        fun random(): GraphId = GraphId(UUID.randomUUID().toString())

        fun of(value: String): GraphId = GraphId(value)
    }
}

/**
 * Builders for `KgNode.lookupKey` — the single indexed, unique string that every
 * node is found by.
 *
 * ObjectBox has no composite indexes: it picks one index and filters the rest. A bare
 * `lookupKey` shared across phone numbers, category slugs, ASINs and nicknames would
 * therefore both scan more than it needs to *and* let a category slug collide with
 * someone's nickname. Namespacing the key fixes both, and makes the key unique, which
 * in turn makes "resolve before create" a single indexed lookup.
 *
 * For assertions the key doubles as the idempotency key (see [assertion]), so
 * re-ingesting the same message is a uniqueness violation rather than a duplicate fact.
 */
object LookupKey {

    fun person(graphId: GraphId): String = "per:$graphId"

    fun phone(e164: String): String = "phone:$e164"

    fun email(address: String): String = "email:${address.lowercase(Locale.ROOT)}"

    fun nameVariant(normalizedText: String): String = "nv:$normalizedText"

    fun category(slug: String): String = "cat:$slug"

    fun brand(normalizedName: String): String = "brand:$normalizedName"

    /**
     * Products are identified by platform + platform id, which is what makes the same
     * Amazon link shared twice resolve to one node.
     */
    fun product(platform: String, platformProductId: String): String =
        "prod:${platform.lowercase(Locale.ROOT)}:$platformProductId"

    /** A product we could not resolve to a platform id; keyed by canonical URL instead. */
    fun productByUrl(canonicalUrl: String): String = "produrl:${sha256(canonicalUrl)}"

    fun occasion(ownerKey: Long, occasionKind: String, month: Int, day: Int): String =
        "occ:$ownerKey:$occasionKind:$month-$day"

    /**
     * Evidence identity.
     *
     * Keyed on the *notification's* identity rather than a hash of its text. Android's
     * NotificationListenerService updates notifications in place, replays them on
     * reconnect, and collapses them into summaries ("3 new messages"), so a text hash
     * both merges genuinely distinct messages (someone sends "yes" twice) and splits a
     * single mutating summary into several sources.
     *
     * [externalRef] should be `StatusBarNotification.key` for notifications, the message
     * id for email/SMS, and a content hash only for pasted or manually entered text.
     */
    fun source(kind: SourceKind, externalRef: String, postedAtMillis: Long): String =
        "src:${kind.id}:$externalRef:$postedAtMillis"

    /**
     * Assertion identity, and therefore the deduplication key for ingestion.
     *
     * Two assertions are the same fact when the same evidence supports the same claim
     * about the same pair. Re-running ingestion over an already-processed message
     * produces identical keys and is a no-op.
     */
    fun assertion(
        sourceKey: Long,
        predicate: Predicate,
        subjectKey: Long,
        objectKey: Long,
    ): String = "asr:$sourceKey:${predicate.id}:$subjectKey:$objectKey"

    /** Derived assertions share a source with their parent, so the parent is part of the key. */
    fun derivedAssertion(
        parentAssertionKey: Long,
        predicate: Predicate,
        subjectKey: Long,
        objectKey: Long,
    ): String = "asrd:$parentAssertionKey:${predicate.id}:$subjectKey:$objectKey"

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return buildString(digest.size * 2) {
            digest.forEach { append("%02x".format(it)) }
        }
    }
}
