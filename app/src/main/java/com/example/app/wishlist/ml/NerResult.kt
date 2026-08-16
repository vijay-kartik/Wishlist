package com.example.app.wishlist.ml

/**
 * One entity the model found, anchored back to the original message text.
 *
 * [text] is sliced from the user's own string, so casing and punctuation survive —
 * "Nike Air Max", not "nike air max" reassembled from WordPiece fragments.
 */
data class EntityMention(
    /** Entity type without the BIO prefix: `CATEGORY`, `BRAND`, `COLOR`, … */
    val type: String,
    val text: String,
    val span: CharSpan,
    /** Mean per-token probability across the tokens making up this entity, in [0,1]. */
    val confidence: Float,
)

/** Everything the NER stage learned about one message. */
data class NerResult(
    val sourceText: String,
    val entities: List<EntityMention>,
    /** True when the message exceeded the model's sequence length and was cut short. */
    val truncated: Boolean,
    val inferenceMillis: Long,
) {
    fun ofType(type: String): List<EntityMention> = entities.filter { it.type == type }

    fun firstOfType(type: String): EntityMention? = entities.firstOrNull { it.type == type }

    val isEmpty: Boolean get() = entities.isEmpty()

    companion object {
        fun empty(text: String) = NerResult(text, emptyList(), truncated = false, inferenceMillis = 0)
    }
}
