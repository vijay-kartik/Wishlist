package com.example.app.wishlist.data.db.entity

import com.example.app.wishlist.graph.core.GraphId
import com.example.app.wishlist.graph.core.NodeType
import com.example.app.wishlist.graph.core.Predicate
import io.objectbox.annotation.Entity
import io.objectbox.annotation.HnswIndex
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import io.objectbox.annotation.Unique
import io.objectbox.annotation.VectorDistanceType

/**
 * Every node in the knowledge graph: people, products, categories, evidence and facts.
 *
 * ## Why one table
 *
 * ObjectBox has no joins and no graph traversal. Traversal is index scans over
 * [KgEdge], and every extra entity type would mean another box to check on every hop.
 * A single node table with a type discriminator keeps traversal to two indexes.
 *
 * ## Why this file lives in `data.db.entity`
 *
 * kapt generates `MyObjectBox` into the *common package of all `@Entity` classes*.
 * `Product` lives here, so putting graph entities anywhere else would move the
 * generated class up to `com.example.app.wishlist` and break the existing
 * `ObjectBoxProvider` import. If entities ever do need to spread across packages, pin
 * the location with `objectbox { myObjectBoxPackage(...) }` in app/build.gradle.kts
 * first. (Related: all `@Entity` classes must stay in one Gradle module — two modules
 * would mean two `MyObjectBox` classes and two schemas, breaking the single-BoxStore
 * invariant.)
 *
 * ## Promoted columns
 *
 * Most typed fields live in [payload] as JSON. A field is promoted to a real column
 * only when a hot query filters or sorts on it, because ObjectBox cannot index inside
 * JSON. The assertion columns ([predicateId], [subjectKey], [objectKey],
 * [observedAtEpochDay]) are null-ish/zero for every other node type — the cost of a few
 * unused columns is far below the cost of a second entity type.
 */
@Entity
class KgNode(

    @Id
    var graphKey: Long = 0,

    /**
     * Stable UUID. Edges use [graphKey] for speed; anything crossing the app boundary
     * (export, backup, eventual sync) uses this.
     */
    @Index
    @Unique
    var graphId: String = "",

    /** [NodeType.id]. Stored as the explicit id, never an ordinal — see NodeType's docs. */
    @Index
    var nodeTypeId: Int = NodeType.UNKNOWN.id,

    /**
     * The namespaced key this node is found by — `"phone:+919..."`, `"cat:running-shoes"`,
     * `"prod:amazon:B0XXXX"`. Built by `LookupKey`; see that class for why the namespace
     * matters.
     *
     * Unique, and mandatory in practice: a node saved without one collides with every
     * other keyless node, which is a deliberate fail-fast. For assertions this is also
     * the ingestion dedup key, so replaying a message is a uniqueness violation rather
     * than a duplicated fact.
     */
    @Index
    @Unique
    var lookupKey: String = "",

    /** Everything not promoted to a column, as JSON. */
    var payload: String = "{}",

    // --- ASSERTION-only columns ---------------------------------------------
    // Zero/default on every other node type.

    /** [Predicate.id]. */
    @Index
    var predicateId: Int = Predicate.NONE.id,

    /**
     * The PERSON this assertion is about.
     *
     * A column rather than a `SUBJECT` edge because it is mandatory and single-valued —
     * a functional property, not an n-ary spoke. Every wishlist query starts by
     * selecting a person's assertions, so as an edge this would cost an edge scan plus
     * one random node read per assertion *before* any predicate filter could apply.
     * As a column it is a single index scan.
     */
    @Index
    var subjectKey: Long = 0,

    /** What the assertion concerns: PRODUCT, CATEGORY, BRAND or OCCASION. Also mandatory. */
    @Index
    var objectKey: Long = 0,

    /**
     * When the fact was observed, in epoch days. Day precision is all decay needs, and
     * it keeps the column narrow. Note the existing [Product] entity uses epoch millis —
     * convert at the boundary.
     */
    var observedAtEpochDay: Int = 0,

    /** Extractor certainty in [0,1]. Semantic strength lives on the predicate, not here. */
    var confidence: Float = 0f,

    /**
     * Set when the *user* explicitly dismissed this fact ("I already have this").
     * Points at the assertion node recording the dismissal; 0 means not dismissed.
     *
     * Purchase-based suppression deliberately does **not** write here. That is computed
     * at read time from the PURCHASED assertions themselves, so that it can fade for
     * durable goods and invert into a replenishment boost for consumables — neither of
     * which is expressible by a flag that zeroes relevance outright.
     */
    @Index
    var dismissedByKey: Long = 0,

    /**
     * Product-title embedding, for semantic "similar products".
     *
     * 384 dimensions targets the MiniLM/bge-small class of on-device models: half the
     * storage and index memory of a 768-dim model, and product titles are short enough
     * that the extra dimensions buy little. Changing this later means re-embedding
     * every product.
     *
     * `DOT_PRODUCT` assumes vectors are L2-normalised at write time — cheaper than
     * cosine and equivalent for unit vectors. **Normalise before writing** or distances
     * will be silently wrong.
     *
     * Null on every non-PRODUCT node; ObjectBox skips nulls when building the index.
     */
    @HnswIndex(dimensions = 384, distanceType = VectorDistanceType.DOT_PRODUCT)
    var embedding: FloatArray? = null,
) {
    val nodeType: NodeType
        get() = NodeType.fromId(nodeTypeId)

    val predicate: Predicate
        get() = Predicate.fromId(predicateId)

    /** Named `identity`, not `id`, to keep it clearly distinct from the ObjectBox `@Id`. */
    val identity: GraphId
        get() = GraphId(graphId)

    val isDismissed: Boolean
        get() = dismissedByKey != 0L

    // Not a data class: it holds a FloatArray, and generated equals/hashCode would
    // compare it by reference, which is a subtle trap in tests and sets.
    override fun toString(): String =
        "KgNode(key=$graphKey, type=$nodeType, lookupKey='$lookupKey')"
}
