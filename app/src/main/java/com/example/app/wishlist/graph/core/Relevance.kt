package com.example.app.wishlist.graph.core

import kotlin.math.exp
import kotlin.math.pow

/**
 * One assertion, reduced to the numbers scoring cares about.
 *
 * Deliberately free of ObjectBox types so the scoring rules can be unit-tested without
 * a database — these formulas are the part of the system most likely to need tuning,
 * and the least pleasant to tune through an emulator.
 */
data class AssertionSignal(
    val predicate: Predicate,
    val confidence: Float,
    val ageDays: Double,
    /** Set when the *user* explicitly dismissed this fact. Not used for purchase suppression. */
    val dismissed: Boolean = false,
)

/**
 * Read-time relevance and category scoring.
 *
 * ## Why nothing here is written back to storage
 *
 * Assertions are immutable: `observedAt` and `confidence` are written once and never
 * updated to reflect "current strength". Everything time-dependent is computed here, at
 * query time, from the assertion's age. That keeps ingestion cheap (no fan-out writes
 * when a purchase lands) and makes tuning a code change rather than a data migration.
 *
 * The one exception is [AssertionSignal.dismissed], which records a deliberate user
 * action ("I already have this, stop showing it"). That is a new fact, not a revision
 * of an old one.
 */
object Relevance {

    /**
     * How much a single assertion contributes right now.
     *
     * Three independent factors:
     *  - [Predicate.weight] — how strong this kind of claim is
     *  - [AssertionSignal.confidence] — how sure the extractor was that it saw the claim
     *  - decay — how stale it has become
     */
    fun of(signal: AssertionSignal): Double {
        if (signal.dismissed) return 0.0
        return signal.predicate.weight * signal.confidence * decayFactor(signal.predicate, signal.ageDays)
    }

    /** Exponential decay on the predicate's half-life. Non-decaying predicates return 1.0. */
    fun decayFactor(predicate: Predicate, ageDays: Double): Double {
        val halfLife = predicate.halfLifeDays ?: return 1.0
        if (halfLife <= 0.0) return 1.0
        return 0.5.pow(ageDays.coerceAtLeast(0.0) / halfLife)
    }
}

/**
 * Rolls individual assertions up into a per-category score for one person.
 *
 * The scoring pipeline, in order:
 *  1. [baseScore] — sum of decayed relevances attached directly to the category
 *  2. [withAdjacency] — add a discounted contribution from curated neighbour categories
 *  3. [acquisitionEffect] — subtract for things already owned, or add back for
 *     replenishables whose cycle has elapsed
 *  4. [normalize] — squash to [0,1) so thresholds mean the same thing for a chatty
 *     contact and a quiet one
 */
object Scoring {

    /** Discount applied to a neighbouring category's contribution, one hop only. */
    const val ADJACENCY_LAMBDA: Double = 0.3

    /**
     * Half-saturation constant for [normalize]: the raw score at which the normalised
     * score reaches 0.5. Set from real data once there is some; 3.0 is a placeholder
     * that treats "three solid, fresh signals" as a decent result.
     */
    const val NORMALIZATION_K: Double = 3.0

    /** How strongly a fresh purchase suppresses (or, later, boosts) its own category. */
    const val ACQUISITION_CAP: Double = 2.0

    /** Suppression half-life for durable goods, i.e. categories with no replenish cycle. */
    const val DURABLE_SUPPRESSION_HALF_LIFE_DAYS: Double = 180.0

    /** Width of the transition from "just bought it" to "time to buy again", in days. */
    const val REPLENISH_TRANSITION_DAYS: Double = 14.0

    /**
     * Sum of relevances attached directly to one category.
     *
     * Reinforcement is emergent: three separate mentions are three assertions whose
     * decayed relevances add up. Nothing needs to track "how many times" anything was
     * said, which is what keeps assertions immutable.
     */
    fun baseScore(signals: List<AssertionSignal>): Double =
        signals.sumOf { Relevance.of(it) }

    /**
     * Adds a discounted contribution from curated adjacent categories (shoes -> socks).
     *
     * Takes neighbours' **base** scores, never their adjusted ones. `RELATED_TO` is
     * symmetric, so feeding adjusted scores back in would recurse forever; the "one hop"
     * limit has to live in the type signature, not in a comment.
     */
    fun withAdjacency(base: Double, neighbourBaseScores: List<Double>): Double =
        base + ADJACENCY_LAMBDA * neighbourBaseScores.sum()

    /**
     * The effect of already owning something in this category: negative while the
     * purchase is fresh, drifting back toward zero — or, for replenishables, past zero
     * into a boost once the cycle has elapsed.
     *
     * @param ageDays how long ago the purchase happened
     * @param replenishCycleDays the category's typical repurchase interval, or null for
     *   durable goods (a jacket, a speaker) that are not rebought on a schedule
     */
    fun acquisitionEffect(ageDays: Double, replenishCycleDays: Double?): Double {
        if (replenishCycleDays == null || replenishCycleDays <= 0.0) {
            // Durable: pure suppression that fades. Never becomes a boost — owning a
            // jacket is not a reason to suggest another jacket, only a weaker reason
            // not to.
            val fade = 0.5.pow(ageDays.coerceAtLeast(0.0) / DURABLE_SUPPRESSION_HALF_LIFE_DAYS)
            return -ACQUISITION_CAP * fade
        }
        // Replenishable: one continuous curve from suppression through to boost, crossing
        // zero exactly when the cycle elapses. Coffee bought last week is suppressed;
        // coffee bought two months ago is a suggestion.
        val s = sigmoid((ageDays - replenishCycleDays) / REPLENISH_TRANSITION_DAYS)
        return ACQUISITION_CAP * (2.0 * s - 1.0)
    }

    /**
     * Squashes an unbounded score into [0,1).
     *
     * Raw scores are sums, so they scale with how much someone talks. Without this, a
     * contact who messages daily outscores a quiet one at identical intent strength, and
     * any absolute threshold (occasion notifications, for one) only ever fires for the
     * chattiest people in the address book.
     */
    fun normalize(score: Double, k: Double = NORMALIZATION_K): Double {
        val positive = score.coerceAtLeast(0.0)
        return positive / (positive + k)
    }

    /**
     * Confidence that there is a clear winner, from the top two normalised scores.
     *
     * This, rather than the top score alone, is the right trigger for "should we notify
     * about this occasion": a person with one standout category is actionable, a person
     * with six equally plausible ones is not, however high the scores.
     */
    fun topMargin(rankedScores: List<Double>): Double = when {
        rankedScores.isEmpty() -> 0.0
        rankedScores.size == 1 -> rankedScores[0]
        else -> rankedScores[0] - rankedScores[1]
    }

    private fun sigmoid(x: Double): Double = 1.0 / (1.0 + exp(-x))
}
