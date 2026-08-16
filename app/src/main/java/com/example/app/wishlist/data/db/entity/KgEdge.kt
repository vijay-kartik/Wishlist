package com.example.app.wishlist.data.db.entity

import com.example.app.wishlist.graph.core.EdgeType
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index
import io.objectbox.annotation.Unique

/**
 * A structural link between two nodes.
 *
 * Edges carry no confidence and no timestamp. Anything with epistemic weight — who
 * said it, how sure we are, when, and whether it still holds — is an ASSERTION node
 * instead. That split is what lets a single fact be individually scored, suppressed and
 * forgotten, which a bare edge could never support.
 *
 * ## Direction
 *
 * `from --edgeType--> to` reads **"`to` is `from`'s edgeType"**. See [EdgeType] for why
 * this convention has to be written down once rather than inferred per call site.
 *
 * ## Traversal
 *
 * ObjectBox has no native traversal, so expansion is a breadth-limited loop over the
 * [fromKey] and [toKey] indexes. Wishlist queries stay within about three hops, and at
 * personal scale (10^3–10^4 nodes, 10^4–10^5 edges) index-backed loops are entirely
 * sufficient.
 */
@Entity
data class KgEdge(

    @Id
    var edgeKey: Long = 0,

    @Index
    var fromKey: Long = 0,

    @Index
    var toKey: Long = 0,

    /** [EdgeType.id]. Relationship kind is part of the type — see EdgeType's docs. */
    @Index
    var edgeTypeId: Int = EdgeType.UNKNOWN.id,

    /**
     * `"$fromKey:$edgeTypeId:$toKey"`. Makes edge writes idempotent, so re-running
     * ingestion over the same message cannot fan out duplicate edges.
     */
    @Index
    @Unique
    var dedupKey: String = "",

    /** Rarely used. Anything queried belongs in [edgeTypeId], not in here. */
    var payload: String? = null,
) {
    val edgeType: EdgeType
        get() = EdgeType.fromId(edgeTypeId)

    companion object {
        fun of(fromKey: Long, edgeType: EdgeType, toKey: Long, payload: String? = null): KgEdge =
            KgEdge(
                fromKey = fromKey,
                toKey = toKey,
                edgeTypeId = edgeType.id,
                dedupKey = "$fromKey:${edgeType.id}:$toKey",
                payload = payload,
            )
    }
}
