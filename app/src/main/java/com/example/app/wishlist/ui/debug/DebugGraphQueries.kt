package com.example.app.wishlist.ui.debug

import android.content.Context
import com.example.app.wishlist.data.db.ObjectBoxProvider
import com.example.app.wishlist.data.db.entity.KgEdge
import com.example.app.wishlist.data.db.entity.KgEdge_
import com.example.app.wishlist.data.db.entity.KgNode
import com.example.app.wishlist.data.db.entity.KgNode_
import com.example.app.wishlist.graph.core.EdgeType
import com.example.app.wishlist.graph.core.NodeType
import com.example.app.wishlist.graph.core.Predicate
import io.objectbox.query.QueryBuilder
import org.json.JSONObject

/**
 * Read queries for the debug screen.
 *
 * Kept out of the composables and the ViewModel on purpose: there is no `GraphTraversal`
 * or `WishlistQueryService` yet, so these are the first real read paths over the graph and
 * are the obvious seed for that service later.
 *
 * Everything here loads eagerly rather than paging. At the scale this graph is designed
 * for — 10^3–10^4 nodes — that is simpler and fast enough, and a debug tool that hides
 * rows behind pagination is worse at its job.
 */
class DebugGraphQueries(context: Context) {

    private val appContext = context.applicationContext
    private val nodes get() = ObjectBoxProvider.getKgNodeBox(appContext)
    private val edges get() = ObjectBoxProvider.getKgEdgeBox(appContext)

    fun nodeCounts(): List<TypeCount> =
        nodes.all.groupingBy { NodeType.fromId(it.nodeTypeId).name }.eachCount()
            .map { TypeCount(it.key, it.value) }
            .sortedBy { it.type }

    fun edgeCounts(): List<TypeCount> =
        edges.all.groupingBy { EdgeType.fromId(it.edgeTypeId).name }.eachCount()
            .map { TypeCount(it.key, it.value) }
            .sortedBy { it.type }

    fun nodeTotal(): Long = nodes.count()

    fun edgeTotal(): Long = edges.count()

    /**
     * @param typeFilter a [NodeType] name, or [FILTER_ALL].
     * @param search case-insensitive substring of `lookupKey`.
     */
    fun listNodes(typeFilter: String, search: String): List<KgNode> {
        val all = if (typeFilter == FILTER_ALL) {
            nodes.all
        } else {
            val typeId = NodeType.entries.firstOrNull { it.name == typeFilter }?.id ?: return emptyList()
            // ObjectBox exposes equal(long) for Int columns and Kotlin will not widen Int
            // implicitly, hence the toLong().
            nodes.query(KgNode_.nodeTypeId.equal(typeId.toLong())).build().use { it.find() }
        }
        val q = search.trim().lowercase()
        return if (q.isEmpty()) all else all.filter { it.lookupKey.lowercase().contains(q) }
    }

    fun node(graphKey: Long): KgNode? = nodes.get(graphKey)

    fun nodeByLookupKey(lookupKey: String): KgNode? =
        nodes.query(
            // CASE_SENSITIVE deliberately: lookup keys are exact, and a case-insensitive
            // match would merge distinct products. String comparisons have no two-argument
            // overload in ObjectBox, so the StringOrder is mandatory.
            KgNode_.lookupKey.equal(lookupKey, QueryBuilder.StringOrder.CASE_SENSITIVE)
        ).build().use { it.findFirst() }

    /** Every edge touching this node, in either direction, with the node at the far end. */
    fun edgesFor(graphKey: Long): List<EdgeRow> {
        val touching: List<KgEdge> = edges.query(
            KgEdge_.fromKey.equal(graphKey).or(KgEdge_.toKey.equal(graphKey))
        ).build().use { it.find() }

        return touching.map { edge ->
            val outgoing = edge.fromKey == graphKey
            val otherKey = if (outgoing) edge.toKey else edge.fromKey
            val other = nodes.get(otherKey)
            EdgeRow(
                direction = if (outgoing) "→" else "←",
                edgeType = EdgeType.fromId(edge.edgeTypeId).name,
                otherKey = otherKey,
                otherType = other?.let { NodeType.fromId(it.nodeTypeId).name } ?: "?",
                otherLabel = other?.lookupKey ?: "(dangling)",
                otherExists = other != null,
            )
        }
    }

    fun persons(): List<KgNode> =
        nodes.query(KgNode_.nodeTypeId.equal(NodeType.PERSON.id.toLong())).build().use { it.find() }

    fun assertionsForSubject(subjectKey: Long): List<KgNode> =
        nodes.query(
            KgNode_.nodeTypeId.equal(NodeType.ASSERTION.id.toLong())
                .and(KgNode_.subjectKey.equal(subjectKey))
        ).build().use { it.find() }

    /**
     * Everything, in one read, for the Visualize tab.
     *
     * The layout needs the whole node and edge set at once — a force simulation cannot be
     * computed incrementally — so paging would buy nothing here.
     */
    fun graphSnapshot(): GraphSnapshot = GraphSnapshot(nodes.all, edges.all)

    /**
     * The Query tab's raw node filter.
     *
     * The indexed clauses (`nodeTypeId`, `predicateId`) go to ObjectBox; the substring and
     * range filters are applied in memory afterwards. That split is deliberate rather than
     * lazy: `lookupKey` is `@Unique`-indexed for exact lookups, so `contains` cannot use
     * the index anyway, and `confidence` has no index at all. Pushing them into the query
     * would read the same rows while making the generated Kotlin on screen imply an index
     * that is not there.
     *
     * @param typeFilter a [NodeType] name, or [FILTER_ALL]
     * @param predicateFilter a [Predicate] name, or [PREDICATE_ANY]
     * @param keySubstring case-insensitive substring of `lookupKey`; blank matches all
     * @param minConfidence 0f matches all, including nodes that carry no confidence
     * @param maxAgeDays only assertions observed within this many days of [today]
     */
    fun rawQuery(
        typeFilter: String,
        predicateFilter: String,
        keySubstring: String,
        minConfidence: Float,
        maxAgeDays: Int,
        today: Int,
    ): List<KgNode> {
        val typeId = if (typeFilter == FILTER_ALL) null else {
            NodeType.entries.firstOrNull { it.name == typeFilter }?.id ?: return emptyList()
        }
        val predicateId = if (predicateFilter == PREDICATE_ANY) null else {
            Predicate.entries.firstOrNull { it.name == predicateFilter }?.id ?: return emptyList()
        }

        val indexed: List<KgNode> = when {
            typeId != null && predicateId != null -> nodes.query(
                KgNode_.nodeTypeId.equal(typeId.toLong())
                    .and(KgNode_.predicateId.equal(predicateId.toLong()))
            ).build().use { it.find() }

            typeId != null -> nodes.query(KgNode_.nodeTypeId.equal(typeId.toLong()))
                .build().use { it.find() }

            predicateId != null -> nodes.query(KgNode_.predicateId.equal(predicateId.toLong()))
                .build().use { it.find() }

            else -> nodes.all
        }

        val needle = keySubstring.trim().lowercase()
        return indexed.filter { node ->
            if (needle.isNotEmpty() && !node.lookupKey.lowercase().contains(needle)) return@filter false
            if (minConfidence > 0f && node.confidence < minConfidence) return@filter false
            // Non-assertions have observedAtEpochDay == 0 and are not time-filtered; a
            // PERSON node has no observation date and dropping it here would make the
            // age slider silently hide half the graph.
            if (node.observedAtEpochDay > 0 && (today - node.observedAtEpochDay) > maxAgeDays) {
                return@filter false
            }
            true
        }
    }

    /**
     * Depth-limited walk from a node, following edges in both directions.
     *
     * A global visited set means each node appears once, at the shallowest depth the walk
     * reached it by — so this is a spanning tree of the neighbourhood, not an enumeration
     * of every path. That is the right shape for "what is connected to this", and it is
     * also what keeps a cycle from running forever.
     *
     * @return hops in visit order, capped at [MAX_TRAVERSAL_ROWS]
     */
    fun traverse(startKey: Long, maxDepth: Int): TraversalResult {
        val allEdges = edges.all
        val byNode = HashMap<Long, MutableList<KgEdge>>()
        allEdges.forEach { edge ->
            byNode.getOrPut(edge.fromKey) { mutableListOf() } += edge
            if (edge.toKey != edge.fromKey) byNode.getOrPut(edge.toKey) { mutableListOf() } += edge
        }

        val hops = ArrayList<TraversalHop>()
        val visited = HashSet<Long>()
        var truncated = false

        fun step(key: Long, depth: Int, via: String?) {
            if (depth > maxDepth || !visited.add(key)) return
            if (hops.size >= MAX_TRAVERSAL_ROWS) { truncated = true; return }

            val node = nodes.get(key)
            hops += TraversalHop(
                graphKey = key,
                depth = depth,
                via = via ?: "root",
                type = node?.let { NodeType.fromId(it.nodeTypeId).name } ?: "?",
                label = node?.lookupKey ?: "(dangling)",
                exists = node != null,
            )

            byNode[key].orEmpty().forEach { edge ->
                val type = EdgeType.fromId(edge.edgeTypeId).name
                if (edge.fromKey == key) step(edge.toKey, depth + 1, "→$type")
                else step(edge.fromKey, depth + 1, "←$type")
            }
        }

        step(startKey, 0, null)
        return TraversalResult(hops, truncated)
    }

    /**
     * Assertions naming this person as the BENEFICIARY.
     *
     * Ingestion always writes `subjectKey = sender`, so "red dress for mom" is stored as
     * an assertion *by the sender* with a BENEFICIARY edge to mom. Asking what to gift mom
     * therefore cannot be answered from `subjectKey` alone — this is the other half of the
     * question, and without it the strongest gift signal the pipeline produces is the one
     * signal the ranking never sees.
     */
    fun assertionsForBeneficiary(personKey: Long): List<KgNode> {
        val incoming = edges.query(
            KgEdge_.toKey.equal(personKey)
                .and(KgEdge_.edgeTypeId.equal(EdgeType.BENEFICIARY.id.toLong()))
        ).build().use { it.find() }

        return incoming.mapNotNull { nodes.get(it.fromKey) }
            .filter { NodeType.fromId(it.nodeTypeId) == NodeType.ASSERTION }
    }

    /**
     * For each of [assertionKeys] that has one, the person the assertion is *for*.
     *
     * Used to tell "Amy wants this" from "Amy picked this out for someone else" — the two
     * are indistinguishable by subject alone, and conflating them credits the wrong person.
     */
    fun beneficiaryTargets(assertionKeys: Set<Long>): Map<Long, Long> {
        if (assertionKeys.isEmpty()) return emptyMap()
        return edges.query(KgEdge_.edgeTypeId.equal(EdgeType.BENEFICIARY.id.toLong()))
            .build().use { it.find() }
            .filter { it.fromKey in assertionKeys }
            .associate { it.fromKey to it.toKey }
    }

    /** Wipes graph nodes and edges. Leaves the legacy `Product` table alone. */
    fun clearGraph() {
        edges.removeAll()
        nodes.removeAll()
    }

    companion object {
        const val FILTER_ALL = "ALL"
        const val PREDICATE_ANY = "ANY"

        /**
         * Traversal row cap. Depth 4 on a well-connected graph can reach most of it; a
         * list long enough to scroll for a minute is not an answer to "what is near this".
         * The UI says when this bites rather than just stopping.
         */
        const val MAX_TRAVERSAL_ROWS = 400

        val predicateOptions: List<String> =
            listOf(PREDICATE_ANY) + Predicate.entries.filter { it != Predicate.NONE }.map { it.name }

        /**
         * All node types are offered, not just the ones the design listed. Ingestion
         * writes NAME_VARIANT nodes, and omitting a type from the filter would make those
         * rows unreachable — precisely the kind of blind spot this screen exists to avoid.
         */
        val filterOptions: List<String> =
            listOf(FILTER_ALL) + NodeType.entries.filter { it != NodeType.UNKNOWN }.map { it.name }

        /**
         * A human-readable name for a node, pulled from whichever payload field carries
         * one. Falls back to the lookup key, which is always present and always unique.
         */
        fun label(node: KgNode?): String {
            if (node == null) return "(missing)"
            val payload = runCatching { JSONObject(node.payload) }.getOrNull()
            val named = payload?.let { json ->
                LABEL_KEYS.firstNotNullOfOrNull { key ->
                    json.optString(key).takeIf { it.isNotBlank() }
                }
            }
            return named ?: node.lookupKey
        }

        fun prettyPayload(raw: String): String =
            runCatching { JSONObject(raw).toString(2) }.getOrElse { raw }

        private val LABEL_KEYS = listOf("label", "displayName", "title", "name", "text")
    }
}

data class TypeCount(val type: String, val count: Int)

data class GraphSnapshot(val nodes: List<KgNode>, val edges: List<KgEdge>)

data class TraversalHop(
    val graphKey: Long,
    val depth: Int,
    val via: String,
    val type: String,
    val label: String,
    val exists: Boolean,
)

data class TraversalResult(val hops: List<TraversalHop>, val truncated: Boolean)

data class EdgeRow(
    val direction: String,
    val edgeType: String,
    val otherKey: Long,
    val otherType: String,
    val otherLabel: String,
    val otherExists: Boolean,
)
