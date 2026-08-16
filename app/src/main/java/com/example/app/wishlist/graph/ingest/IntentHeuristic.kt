package com.example.app.wishlist.graph.ingest

import com.example.app.wishlist.graph.core.Predicate
import com.example.app.wishlist.ml.EntityMention
import com.example.app.wishlist.ml.NerResult

/**
 * Decides *what a message is doing* — the predicate — and who it is for.
 *
 * ## Why this exists, and why it should not exist for long
 *
 * The model labels what a message is *about* (PRODUCT, CATEGORY, COLOR, SIZE, BUDGET,
 * RECIPIENT, OCCASION, TIME). The knowledge graph additionally needs to know whether the
 * sender wants the thing, is recommending it to someone, already owns it, or is just
 * forwarding a link — and a span tagger cannot answer that.
 *
 * The `RECIPIENT` tag does carry real signal though: a message that names who something
 * is for is almost always gift intent rather than idle forwarding, so its presence both
 * supplies the beneficiary directly and raises confidence in the predicate.
 *
 * So this is a lexical stopgap: cue phrases, no learning, no context beyond one message.
 * It will be wrong on sarcasm, on negation it does not enumerate, on code-mixed
 * Hinglish beyond the handful of phrases listed, and on anything phrased indirectly.
 * Every classification carries a [IntentInference.confidence] well below what a trained
 * classifier would produce, and that number flows into assertion confidence and then
 * into relevance scoring, so the graph systematically under-weights heuristic
 * predicates. That is the intended behaviour until a real intent head exists.
 *
 * The clean fix is a second output head on the same model (sequence classification over
 * the §5 predicate set), which costs one extra tensor and removes this file.
 */
object IntentHeuristic {

    data class IntentInference(
        val predicate: Predicate,
        val confidence: Float,
        /** The RECIPIENT span this is for, when the model named a third party. */
        val beneficiary: EntityMention?,
        /** True when the message addresses the reader rather than naming a third party. */
        val beneficiaryIsRecipient: Boolean,
        val matchedCue: String?,
    )

    // Ordered by specificity: acquisition beats desire beats advocacy, because
    // "I just bought the shoes I wanted" should read as an acquisition.
    private val ACQUISITION = listOf(
        "just bought", "just ordered", "just got", "i bought", "i ordered",
        "i got myself", "picked up", "delivered", "arrived today", "kharid liya",
    )
    private val DESIRE = listOf(
        "i want", "i need", "want this", "need this", "looking for", "planning to buy",
        "thinking of buying", "on my wishlist", "wish list", "i'd love", "i would love",
        "chahiye", "lena hai", "dhundh raha", "dhundh rahi",
    )
    private val ADVOCACY = listOf(
        "you should", "you'd love", "you would love", "perfect for you", "check this out",
        "check this", "you might like", "get this", "buy this", "recommend", "must buy",
        "perfect for", "great for", "would suit", "le lo", "dekho ye", "dekho yeh",
    )
    private val NEGATION = listOf(
        "don't like", "dont like", "do not like", "hate", "not a fan", "never buy",
        "don't want", "dont want", "pasand nahi",
    )

    private val URL_PATTERN = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)

    /** Recipient spans that mean "the person I am messaging" rather than a third party. */
    private val SELF_REFERENTIAL_RECIPIENT = setOf(
        "you", "u", "yourself", "ur", "tumhe", "tujhe", "aapko", "aapke liye", "tumhare liye",
    )

    /** Recipient spans that mean the sender themselves. */
    private val SENDER_REFERENTIAL_RECIPIENT = setOf("me", "myself", "mujhe", "mere liye", "khud")

    fun infer(ner: NerResult): IntentInference {
        val text = ner.sourceText
        val lower = text.lowercase()

        // The model names the beneficiary directly, which is far better than inferring it
        // from "for <Name>" phrasing. Only the *role* of that name still needs deciding.
        val recipient = ner.firstOfType("RECIPIENT")
        val recipientText = recipient?.text?.trim()?.lowercase()
        val recipientIsReader = recipientText in SELF_REFERENTIAL_RECIPIENT
        val recipientIsSender = recipientText in SENDER_REFERENTIAL_RECIPIENT
        val namedRecipient = recipient?.takeIf { !recipientIsReader && !recipientIsSender }

        matchCue(lower, NEGATION)?.let {
            return IntentInference(Predicate.DISLIKES, 0.55f, null, false, it)
        }
        matchCue(lower, ACQUISITION)?.let {
            return IntentInference(Predicate.OWNS, 0.60f, null, false, it)
        }

        // A named recipient who is not the sender means the message is about buying for
        // someone else — the app's whole reason for existing. That routes to RECOMMENDS
        // with a beneficiary regardless of whether an advocacy phrase happens to appear,
        // because "red dress for mom" carries no cue word at all.
        if (namedRecipient != null) {
            return IntentInference(
                predicate = Predicate.RECOMMENDS,
                confidence = 0.65f,
                beneficiary = namedRecipient,
                beneficiaryIsRecipient = false,
                matchedCue = matchCue(lower, ADVOCACY) ?: "RECIPIENT:${namedRecipient.text}",
            )
        }

        matchCue(lower, DESIRE)?.let {
            return IntentInference(Predicate.INTERESTED_IN, 0.60f, null, false, it)
        }

        // "for me" is the sender expressing their own desire, whatever else the phrasing.
        if (recipientIsSender) {
            return IntentInference(Predicate.INTERESTED_IN, 0.60f, null, false, "RECIPIENT:self")
        }

        matchCue(lower, ADVOCACY)?.let { cue ->
            return IntentInference(
                predicate = Predicate.RECOMMENDS,
                confidence = if (recipientIsReader) 0.58f else 0.50f,
                beneficiary = null,
                // Addressed at whoever received the message — for now that is the user,
                // since this pipeline only sees messages sent *to* them.
                beneficiaryIsRecipient = recipientIsReader,
                matchedCue = cue,
            )
        }

        // No cue and no recipient. A bare link is a forward — the graph's weakest signal,
        // which is exactly what SHARED is for. Text with entities but no cue gets the same
        // treatment rather than a guess.
        val hasUrl = URL_PATTERN.containsMatchIn(text)
        return IntentInference(
            predicate = Predicate.SHARED,
            confidence = if (hasUrl) 0.45f else 0.35f,
            beneficiary = null,
            beneficiaryIsRecipient = false,
            matchedCue = null,
        )
    }

    /** The first URL in the message, if any — the product-resolution step's input. */
    fun extractUrl(text: String): String? = URL_PATTERN.find(text)?.value

    private fun matchCue(lowerText: String, cues: List<String>): String? =
        cues.firstOrNull { lowerText.contains(it) }

}
