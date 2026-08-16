package com.example.app.wishlist.graph.core

/**
 * The knowledge-graph ontology: node types, edge types and predicates.
 *
 * ## Why every enum carries an explicit [id]
 *
 * These values are persisted in ObjectBox as plain `Int` columns. If we stored
 * `ordinal`, inserting a new constant in the middle of an enum would silently
 * re-interpret every row already on disk — no error, just wrong data. So each
 * constant declares a stable id that is written to storage and never reused.
 *
 * Rules for changing these enums:
 *  - New constants get the next unused id. Append is always safe.
 *  - Never change an existing id.
 *  - Never reuse the id of a removed constant; leave a gap and note it here.
 *
 * Retired ids: (none yet)
 */

/** Kinds of node in the graph. */
enum class NodeType(val id: Int) {
    UNKNOWN(0),

    /** A contact, or the user themselves (`isSelf = true`). */
    PERSON(1),

    /** One alias/nickname for a person. Kinship terms are NOT stored here — see [KinshipLexicon]. */
    NAME_VARIANT(2),

    PHONE_NUMBER(3),
    EMAIL_ADDRESS(4),

    /** Curated taxonomy node (leaf or branch). */
    CATEGORY(5),

    /** A specific resolved item on a shopping platform. */
    PRODUCT(6),

    BRAND(7),

    /** Evidence: one message, screenshot, email or manual entry. */
    SOURCE(8),

    /** One reified fact. See [Predicate]. */
    ASSERTION(9),

    /** A recurring gifting trigger (birthday, anniversary, festival). */
    OCCASION(10),
    ;

    companion object {
        private val byId = entries.associateBy(NodeType::id)
        fun fromId(id: Int): NodeType = byId[id] ?: UNKNOWN
    }
}

/**
 * Kinds of edge.
 *
 * ## Direction convention
 *
 * For every edge, `A --TYPE--> B` reads **"B is A's TYPE"**.
 *
 * So `alex --RELATED_AS_BROTHER--> user` means *the user is Alex's brother*, which
 * is what we learn when Alex addresses the user as "bhaiya". Getting this backwards
 * inverts half the kinship graph, so [inverse] exists to make the flip explicit
 * rather than something each call site re-derives.
 *
 * ## Why relationKind is folded into the edge type
 *
 * Kinship used to live as `relationKind` inside `KgEdge.payload` (a JSON string).
 * Entity resolution runs once per mention — the hottest path in ingestion — and it
 * filters on relation kind, which would mean loading every edge from the speaker and
 * parsing JSON. Folding the kind into `edgeType` lets the existing index do that work.
 */
enum class EdgeType(val id: Int) {
    UNKNOWN(0),

    // --- Assertion roles (the n-ary spokes) ---------------------------------
    // NOTE: SUBJECT and OBJECT are mandatory and single-valued, so they are stored
    // as promoted columns on the assertion node itself, not as edges. They are kept
    // in this enum only for traversal code that wants a uniform edge-shaped view.

    /** ASSERTION -> PERSON. Optional: who the fact is *for*. */
    BENEFICIARY(1),

    /** ASSERTION -> PERSON. Optional: the speaker, when different from the subject. */
    STATED_BY(2),

    /** ASSERTION -> SOURCE. One per supporting evidence item. */
    EVIDENCED_BY(3),

    /**
     * ASSERTION -> ASSERTION. Records that this assertion was computed from another
     * (e.g. an AFFINITY derived from a PURCHASED). Compaction needs this to know which
     * purchases are still load-bearing.
     */
    DERIVED_FROM(4),

    // --- Identity ------------------------------------------------------------
    HAS_PHONE(10),
    HAS_EMAIL(11),
    HAS_NAME_VARIANT(12),

    // --- Product structure ---------------------------------------------------
    /** PRODUCT -> CATEGORY. */
    INSTANCE_OF(20),

    /** PRODUCT -> BRAND. */
    MADE_BY(21),

    /** CATEGORY -> CATEGORY, taxonomy tree. */
    SUBCATEGORY_OF(22),

    /** CATEGORY <-> CATEGORY, curated adjacency for cross-sell. Symmetric; store both directions. */
    RELATED_TO(23),

    // --- Kinship / social ----------------------------------------------------
    // Read as "B is A's X". Gendered terms are kept distinct because they matter
    // for gift selection.
    RELATED_AS_BROTHER(30),
    RELATED_AS_SISTER(31),
    RELATED_AS_MOTHER(32),
    RELATED_AS_FATHER(33),
    RELATED_AS_SON(34),
    RELATED_AS_DAUGHTER(35),
    RELATED_AS_SPOUSE(36),
    RELATED_AS_FRIEND(37),
    RELATED_AS_COLLEAGUE(38),
    ;

    /** True for the `RELATED_AS_*` family. */
    val isKinship: Boolean
        get() = id in 30..49

    /**
     * The edge that must exist in the opposite direction to describe the same
     * relationship, or null where the inverse is ambiguous.
     *
     * Parent/child inverses are gender-dependent on the *other* party, which this
     * graph does not always know, so they return null rather than guessing: writing
     * `RELATED_AS_SON` for a person whose gender is unknown is worse than writing nothing.
     */
    val inverse: EdgeType?
        get() = when (this) {
            RELATED_AS_BROTHER, RELATED_AS_SISTER -> null // sibling inverse needs the other's gender
            RELATED_AS_SPOUSE -> RELATED_AS_SPOUSE
            RELATED_AS_FRIEND -> RELATED_AS_FRIEND
            RELATED_AS_COLLEAGUE -> RELATED_AS_COLLEAGUE
            RELATED_TO -> RELATED_TO
            else -> null
        }

    companion object {
        private val byId = entries.associateBy(EdgeType::id)
        fun fromId(id: Int): EdgeType = byId[id] ?: UNKNOWN
    }
}

/**
 * What an assertion claims.
 *
 * Each predicate carries two independent numbers, which the original draft conflated:
 *
 *  - [weight] — how strong this *kind* of claim is, semantically. A forwarded link is
 *    weak evidence of desire even when the extractor is completely sure it saw a
 *    forwarded link.
 *  - [halfLifeDays] — how fast the claim goes stale.
 *
 * Extractor certainty is a third, per-assertion number (`KgNode.confidence`). Keeping
 * the three apart is what stops a crisply-extracted SHARED (confidence 0.95) from
 * outranking a hedged INTERESTED_IN (confidence 0.55).
 *
 * `halfLifeDays == null` means the fact does not decay: a purchase happened, and it
 * keeps having happened.
 */
enum class Predicate(
    val id: Int,
    val weight: Double,
    val halfLifeDays: Double?,
) {
    NONE(0, 0.0, null),

    /** Expressed desire or attention. Gift candidate for the subject. */
    INTERESTED_IN(1, weight = 0.80, halfLifeDays = 60.0),

    /** Advocacy. Routed to the beneficiary's list when one is known, else the user's. */
    RECOMMENDS(2, weight = 0.90, halfLifeDays = 120.0),

    /** Link forwarded, intent unresolved. Deliberately low weight — this is the noisiest signal. */
    SHARED(3, weight = 0.40, halfLifeDays = 45.0),

    /** Confirmed order, with attributes. Drives suppression, affinities and replenishment. */
    PURCHASED(4, weight = 1.00, halfLifeDays = null),

    /** Ownership observed without order details. */
    OWNS(5, weight = 0.90, halfLifeDays = null),

    /** Negative filter. Matching items are never surfaced, regardless of score. */
    DISLIKES(6, weight = 1.00, halfLifeDays = 540.0),

    /** Derived from purchase decomposition: brand/category/price-band affinity. */
    AFFINITY(7, weight = 0.60, halfLifeDays = 365.0),

    /** Standing fact: this person has this occasion. */
    HAS_OCCASION(8, weight = 1.00, halfLifeDays = null),

    /** Size/fit for a category, in the assertion's attributes JSON. */
    SIZE_KNOWN(9, weight = 1.00, halfLifeDays = 730.0),
    ;

    /** PURCHASED and OWNS both mean "already has it", and both drive suppression. */
    val isAcquisition: Boolean
        get() = this == PURCHASED || this == OWNS

    companion object {
        private val byId = entries.associateBy(Predicate::id)
        fun fromId(id: Int): Predicate = byId[id] ?: NONE
    }
}

/** How a name variant was obtained. Stored in the node payload, not indexed. */
enum class VariantKind(val id: Int) {
    GIVEN(0),
    NICKNAME(1),
    TRANSLITERATION(2),
    ;

    // Deliberately no KINSHIP member. Kinship terms are speaker-relative: "bhaiya"
    // means a different person depending on who says it. Storing them as name
    // variants makes them globally resolvable, so the first exact-match lookup wins
    // and every later speaker resolves to the wrong person. Kinship lives in
    // RELATED_AS_* edges plus KinshipLexicon instead.

    companion object {
        private val byId = entries.associateBy(VariantKind::id)
        fun fromId(id: Int): VariantKind? = byId[id]
    }
}

/** Where a piece of evidence came from. */
enum class SourceKind(val id: Int) {
    UNKNOWN(0),
    WHATSAPP_MSG(1),
    SMS(2),
    EMAIL(3),
    SCREENSHOT(4),
    SHARED_URL(5),
    MANUAL_ORDER_ENTRY(6),
    ;

    companion object {
        private val byId = entries.associateBy(SourceKind::id)
        fun fromId(id: Int): SourceKind = byId[id] ?: UNKNOWN
    }
}

/**
 * Maps a kinship term, as spoken, to the relationship edge it implies.
 *
 * Resolution is always relative to the speaker: given "bhaiya" uttered by Alex, we
 * look for `alex --RELATED_AS_BROTHER--> ?`. The term never identifies a person on
 * its own, which is exactly why these are not [NodeType.NAME_VARIANT] nodes.
 *
 * Terms are matched after normalisation (see `TextNormalizer`), so entries here are
 * lowercase, diacritic-free and transliterated.
 */
object KinshipLexicon {

    private val terms: Map<String, EdgeType> = buildMap {
        // English
        put("brother", EdgeType.RELATED_AS_BROTHER)
        put("bro", EdgeType.RELATED_AS_BROTHER)
        put("sister", EdgeType.RELATED_AS_SISTER)
        put("sis", EdgeType.RELATED_AS_SISTER)
        put("mom", EdgeType.RELATED_AS_MOTHER)
        put("mum", EdgeType.RELATED_AS_MOTHER)
        put("mother", EdgeType.RELATED_AS_MOTHER)
        put("dad", EdgeType.RELATED_AS_FATHER)
        put("father", EdgeType.RELATED_AS_FATHER)
        put("son", EdgeType.RELATED_AS_SON)
        put("daughter", EdgeType.RELATED_AS_DAUGHTER)
        put("husband", EdgeType.RELATED_AS_SPOUSE)
        put("wife", EdgeType.RELATED_AS_SPOUSE)

        // Hindi / common Indic forms, post-transliteration
        put("bhai", EdgeType.RELATED_AS_BROTHER)
        put("bhaiya", EdgeType.RELATED_AS_BROTHER)
        put("bhaiyya", EdgeType.RELATED_AS_BROTHER)
        put("behen", EdgeType.RELATED_AS_SISTER)
        put("didi", EdgeType.RELATED_AS_SISTER)
        put("maa", EdgeType.RELATED_AS_MOTHER)
        put("mummy", EdgeType.RELATED_AS_MOTHER)
        put("papa", EdgeType.RELATED_AS_FATHER)
        put("pitaji", EdgeType.RELATED_AS_FATHER)
        put("beta", EdgeType.RELATED_AS_SON)
        put("beti", EdgeType.RELATED_AS_DAUGHTER)
    }

    /** The relationship implied by a normalised term, or null if it is not a kinship word. */
    fun relationFor(normalizedTerm: String): EdgeType? = terms[normalizedTerm]

    fun isKinshipTerm(normalizedTerm: String): Boolean = terms.containsKey(normalizedTerm)
}
