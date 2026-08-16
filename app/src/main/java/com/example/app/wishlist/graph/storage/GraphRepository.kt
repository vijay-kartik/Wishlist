package com.example.app.wishlist.graph.storage

import android.content.Context
import com.example.app.wishlist.data.db.ObjectBoxProvider
import com.example.app.wishlist.data.db.entity.KgEdge
import com.example.app.wishlist.data.db.entity.KgEdge_
import com.example.app.wishlist.data.db.entity.KgNode
import com.example.app.wishlist.data.db.entity.KgNode_
import com.example.app.wishlist.graph.core.EdgeType
import com.example.app.wishlist.graph.core.GraphId
import com.example.app.wishlist.graph.core.LookupKey
import com.example.app.wishlist.graph.core.NodeType
import com.example.app.wishlist.graph.core.Predicate
import io.objectbox.Box
import io.objectbox.query.QueryBuilder
import org.json.JSONObject
import timber.log.Timber

/**
 * The only sanctioned way to write to the knowledge graph.
 *
 * Enforces the three invariants the boxes themselves cannot:
 *  - **resolve before create** — every entity is looked up by its namespaced
 *    `lookupKey` before a new node is allocated, so the same contact or product never
 *    ends up as two nodes
 *  - **one transaction per ingestion batch** — a half-written assertion (node saved,
 *    role edges not) would be a fact with no subject
 *  - **idempotency** — re-ingesting a message is a no-op, because `lookupKey` is the
 *    dedup key
 */
class GraphRepository(context: Context) {

    private val appContext = context.applicationContext
    private val store = ObjectBoxProvider.getBoxStore(appContext)
    private val nodes: Box<KgNode> = ObjectBoxProvider.getKgNodeBox(appContext)
    private val edges: Box<KgEdge> = ObjectBoxProvider.getKgEdgeBox(appContext)

    /**
     * Runs [block] inside one ObjectBox transaction.
     *
     * Everything mutating lives on [Tx], so it is not possible to write a node outside a
     * transaction by accident.
     */
    fun <T> write(block: (Tx) -> T): T = store.callInTx<T> { block(Tx()) }

    /** Read-only lookup, for callers that just need to check existence. */
    fun findByLookupKey(lookupKey: String): KgNode? = nodes.findByKey(lookupKey)

    inner class Tx internal constructor() {

        /**
         * Finds a node by lookup key or creates it.
         *
         * @return the existing or newly created node; [created] on the result tells the
         *   caller whether anything was allocated, which matters for ingestion counts.
         */
        fun upsert(
            type: NodeType,
            lookupKey: String,
            payload: JSONObject.() -> Unit = {},
        ): Upserted {
            nodes.findByKey(lookupKey)?.let { return Upserted(it, created = false) }

            val node = KgNode(
                graphId = GraphId.random().value,
                nodeTypeId = type.id,
                lookupKey = lookupKey,
                payload = JSONObject().apply(payload).toString(),
            )
            node.graphKey = nodes.put(node)
            return Upserted(node, created = true)
        }

        /**
         * Writes an assertion and its evidence edge.
         *
         * @return null when this exact fact from this exact evidence already exists —
         *   the caller should treat that as a successful no-op, not an error.
         */
        fun putAssertion(
            predicate: Predicate,
            subjectKey: Long,
            objectKey: Long,
            sourceKey: Long,
            observedAtEpochDay: Int,
            confidence: Float,
            attributes: JSONObject.() -> Unit = {},
        ): KgNode? {
            require(subjectKey != 0L) { "assertion needs a subject" }
            require(objectKey != 0L) { "assertion needs an object" }

            val key = LookupKey.assertion(sourceKey, predicate, subjectKey, objectKey)
            if (nodes.findByKey(key) != null) return null

            val assertion = KgNode(
                graphId = GraphId.random().value,
                nodeTypeId = NodeType.ASSERTION.id,
                lookupKey = key,
                payload = JSONObject().apply(attributes).toString(),
                predicateId = predicate.id,
                subjectKey = subjectKey,
                objectKey = objectKey,
                observedAtEpochDay = observedAtEpochDay,
                confidence = confidence,
            )
            assertion.graphKey = nodes.put(assertion)
            putEdge(assertion.graphKey, EdgeType.EVIDENCED_BY, sourceKey)
            return assertion
        }

        /** Writes an edge unless an identical one already exists. */
        fun putEdge(fromKey: Long, edgeType: EdgeType, toKey: Long, payload: String? = null) {
            if (fromKey == 0L || toKey == 0L) return
            val edge = KgEdge.of(fromKey, edgeType, toKey, payload)
            if (edges.findByDedupKey(edge.dedupKey) != null) return
            edges.put(edge)
        }

        /**
         * Resolves a display name to a PERSON, creating the person and its name variant
         * when the name is new.
         *
         * This is the v0 of the resolution ladder: exact normalised-variant match only.
         * The context disambiguation and kinship steps in the spec need chat participant
         * lists that a notification does not carry.
         */
        fun resolvePerson(displayName: String, isSelf: Boolean = false): KgNode? {
            val normalized = normalizeName(displayName)
            if (normalized.isEmpty()) return null

            val variantKey = LookupKey.nameVariant(normalized)
            nodes.findByKey(variantKey)?.let { variant ->
                personFor(variant.graphKey)?.let { return it }
                // Variant exists with no owner — a partially written earlier batch.
                // Adopt it rather than creating a second variant with the same key.
                Timber.w("Name variant '%s' had no PERSON; re-linking", normalized)
                val person = createPerson(displayName, isSelf)
                putEdge(person.graphKey, EdgeType.HAS_NAME_VARIANT, variant.graphKey)
                return person
            }

            val person = createPerson(displayName, isSelf)
            val variant = upsert(NodeType.NAME_VARIANT, variantKey) {
                put("text", displayName)
                put("normalizedText", normalized)
                put("variantKind", "GIVEN")
            }.node
            putEdge(person.graphKey, EdgeType.HAS_NAME_VARIANT, variant.graphKey)
            return person
        }

        private fun createPerson(displayName: String, isSelf: Boolean): KgNode {
            val person = KgNode(
                graphId = GraphId.random().value,
                nodeTypeId = NodeType.PERSON.id,
                lookupKey = "",
                payload = JSONObject().apply {
                    put("displayName", displayName)
                    put("isSelf", isSelf)
                }.toString(),
            )
            // A person has no natural key, so the lookup key is derived from its own
            // GraphId — which is only known after the UUID is generated.
            person.lookupKey = LookupKey.person(GraphId(person.graphId))
            person.graphKey = nodes.put(person)
            return person
        }

        /** Walks HAS_NAME_VARIANT backwards from a variant to its owner. */
        private fun personFor(variantKey: Long): KgNode? {
            val edge = edges.query(
                KgEdge_.toKey.equal(variantKey)
                    .and(KgEdge_.edgeTypeId.equal(EdgeType.HAS_NAME_VARIANT.id.toLong()))
            ).build().use { it.findFirst() } ?: return null
            return nodes.get(edge.fromKey)
        }
    }

    data class Upserted(val node: KgNode, val created: Boolean) {
        val graphKey: Long get() = node.graphKey
    }

    companion object {
        /** Matches `LookupKey.nameVariant`'s expectation: lowercase, collapsed whitespace. */
        fun normalizeName(raw: String): String =
            raw.trim().lowercase().replace(Regex("\\s+"), " ")
    }
}

// String equality in ObjectBox has no two-argument overload — the StringOrder argument is
// mandatory. Lookup keys are built by `LookupKey` and are always exact, so comparisons are
// case sensitive; a case-insensitive match here would silently merge distinct products.
private fun Box<KgNode>.findByKey(lookupKey: String): KgNode? =
    query(KgNode_.lookupKey.equal(lookupKey, QueryBuilder.StringOrder.CASE_SENSITIVE))
        .build().use { it.findFirst() }

private fun Box<KgEdge>.findByDedupKey(dedupKey: String): KgEdge? =
    query(KgEdge_.dedupKey.equal(dedupKey, QueryBuilder.StringOrder.CASE_SENSITIVE))
        .build().use { it.findFirst() }
