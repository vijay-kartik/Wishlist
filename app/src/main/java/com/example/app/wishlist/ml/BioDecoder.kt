package com.example.app.wishlist.ml

import kotlin.math.exp

/**
 * Turns the model's `[1, seqLen, numLabels]` logit tensor into character-anchored entities.
 *
 * Three things happen here, in order: argmax per token, softmax for a usable confidence,
 * and a merge of BIO runs into spans.
 */
object BioDecoder {

    /**
     * @param logits `[seqLen][numLabels]` — the batch dimension is stripped by the
     *   caller, matching the `Array(1) { Array(seqLen) { FloatArray(numLabels) } }`
     *   buffer the Interpreter fills.
     */
    fun decode(
        logits: Array<FloatArray>,
        tokenized: TokenizedInput,
        tags: NerTagSet,
        sourceText: String,
    ): List<EntityMention> {
        val entities = ArrayList<EntityMention>()

        var openType: String? = null
        var openStart = 0
        var openEnd = 0
        var openProbSum = 0f
        var openTokens = 0

        fun closeOpen() {
            val type = openType ?: return
            // Averaged over supervised positions only. Continuation tokens never reach
            // here, so an unsupervised guess cannot drag a real entity under the floor.
            val confidence = openProbSum / openTokens
            if (confidence >= NerConfig.MIN_ENTITY_CONFIDENCE) {
                entities += EntityMention(
                    type = type,
                    text = sourceText.substring(openStart, openEnd),
                    span = CharSpan(openStart, openEnd),
                    confidence = confidence,
                )
            }
            openType = null
        }

        for (t in 0 until tokenized.realTokenCount) {
            // Null span means [CLS] or [SEP]; whatever the model predicts there is not
            // about any text the user wrote.
            val span = tokenized.spans.getOrNull(t) ?: continue

            // A WordPiece continuation inherits its parent word's tag; whatever the
            // model emitted here is discarded unread.
            //
            // Training supervises only the first piece of each word and masks the rest,
            // so a `##` position is an unsupervised guess. Read literally it splits
            // words: "headphones" tokenizes to ["head", "##phones"], the model guesses
            // B-CATEGORY on the second piece, and two B- tags in a row decode as two
            // entities — "head" and "phones" — instead of one.
            //
            // Extending the open span is the entire fix. Nothing is stitched from token
            // text: the entity is sliced out of the original string by offset, so the
            // user's own spelling and casing survive.
            if (tokenized.continuations.getOrElse(t) { false }) {
                if (openType != null) openEnd = span.end
                continue
            }

            val row = logits.getOrNull(t) ?: break
            val (labelId, probability) = argmaxWithSoftmax(row)
            val type = tags.entityTypeAt(labelId)

            when {
                type == null -> closeOpen()

                tags.isBeginAt(labelId) -> {
                    closeOpen()
                    openType = type
                    openStart = span.start
                    openEnd = span.end
                    openProbSum = probability
                    openTokens = 1
                }

                // I- continuing the same type: extend. Note the span end comes from the
                // token, so intervening whitespace inside a multi-word entity is kept.
                tags.isInsideAt(labelId) && openType == type -> {
                    openEnd = span.end
                    openProbSum += probability
                    openTokens++
                }

                // I- with no matching open entity. Models emit this regularly at
                // truncation points and on rare types; treating it as a B- recovers the
                // entity instead of discarding it. Dropping it would lose real spans.
                else -> {
                    closeOpen()
                    openType = type
                    openStart = span.start
                    openEnd = span.end
                    openProbSum = probability
                    openTokens = 1
                }
            }
        }
        closeOpen()

        return entities
    }

    /**
     * A compact per-token dump of what the model actually predicted.
     *
     * Exists because an empty entity list has several very different causes that look
     * identical from the outside: the model predicting `O` everywhere (wrong inputs, or
     * genuinely nothing there), every candidate falling under
     * [NerConfig.MIN_ENTITY_CONFIDENCE], or the tokenizer handing back no spans. Seeing
     * the label and probability per token separates them at a glance.
     *
     * Format: `token[start,end]=LABEL:0.87`, one per line, real tokens only.
     */
    fun describePredictions(
        logits: Array<FloatArray>,
        tokenized: TokenizedInput,
        tags: NerTagSet,
    ): String = buildString {
        for (t in 0 until tokenized.realTokenCount) {
            val row = logits.getOrNull(t) ?: break
            val (labelId, probability) = argmaxWithSoftmax(row)
            val span = tokenized.spans.getOrNull(t)
            append("  [").append(t).append("] ")
            append(if (span == null) "<special>" else "${span.start},${span.end}")
            append(" = ").append(tags.tagAt(labelId))
            append(" p=").append("%.3f".format(probability))
            if (tokenized.continuations.getOrElse(t) { false }) {
                append("  <- ##subword, label discarded")
            }
            appendLine()
        }
        if (isEmpty()) append("  (tokenizer produced no tokens)")
    }

    /**
     * Argmax plus the softmax probability of the winning label.
     *
     * Softmax is computed rather than returning the raw logit because the confidence
     * ends up on an assertion and is multiplied into relevance scoring — an unbounded
     * logit there would be meaningless. Max-subtraction keeps `exp` from overflowing.
     */
    private fun argmaxWithSoftmax(row: FloatArray): Pair<Int, Float> {
        var bestIndex = 0
        var maxLogit = row[0]
        for (i in 1 until row.size) {
            val v = row[i]
            if (v > maxLogit) { maxLogit = v; bestIndex = i }
        }
        var sum = 0.0
        for (value in row) {
            sum += exp((value - maxLogit).toDouble())
        }
        // The winner's numerator is exp(0) = 1 after max-subtraction.
        return bestIndex to (1.0 / sum).toFloat()
    }
}
