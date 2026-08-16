package com.example.app.wishlist.ml

import android.content.res.AssetManager

/**
 * The BIO label set the model emits, loaded from `assets/tags.txt`.
 *
 * Per the deployment guide the file is one tag per line and **the line index is the
 * label id** the model outputs — line 0 is `O`, line 5 is `B-COLOR`, and so on. Nothing
 * about the tag set is hardcoded here, because retraining with a different tag order
 * would otherwise produce confident, silent mislabelling.
 *
 * The shipped model has 17 tags covering 8 entity types:
 * PRODUCT, CATEGORY, COLOR, SIZE, BUDGET, RECIPIENT, OCCASION, TIME.
 */
class NerTagSet private constructor(private val tags: List<String>) {

    val size: Int get() = tags.size

    fun tagAt(id: Int): String = tags.getOrElse(id) { OUTSIDE }

    /** Entity type without the BIO prefix, or null for `O`. */
    fun entityTypeAt(id: Int): String? = when (val tag = tagAt(id)) {
        OUTSIDE -> null
        else -> tag.substringAfter('-', missingDelimiterValue = "").takeIf { it.isNotEmpty() }
    }

    fun isBeginAt(id: Int): Boolean = tagAt(id).startsWith("B-")

    fun isInsideAt(id: Int): Boolean = tagAt(id).startsWith("I-")

    /** Every entity type in the set, for diagnostics. */
    fun entityTypes(): Set<String> =
        tags.mapNotNull { it.substringAfter('-', "").takeIf(String::isNotEmpty) }.toSet()

    companion object {
        const val OUTSIDE = "O"
        const val ASSET_PATH = "tags.txt"

        fun load(assets: AssetManager, path: String = ASSET_PATH): NerTagSet {
            val tags = assets.open(path).bufferedReader().useLines { lines ->
                lines.map(String::trim).filter(String::isNotEmpty).toList()
            }
            validate(tags, path)
            return NerTagSet(tags)
        }

        /**
         * Catches malformed tag files. It cannot catch a *permuted* one — no on-device
         * check can — so `tags.txt` must always be exported with the model it belongs to.
         */
        private fun validate(tags: List<String>, path: String) {
            if (tags.isEmpty()) throw NerConfigurationException("$path is empty")

            val outsideCount = tags.count { it == OUTSIDE }
            if (outsideCount != 1) {
                throw NerConfigurationException(
                    "$path must contain exactly one `$OUTSIDE` tag, found $outsideCount"
                )
            }
            val malformed = tags.filter { it != OUTSIDE && !it.startsWith("B-") && !it.startsWith("I-") }
            if (malformed.isNotEmpty()) {
                throw NerConfigurationException("$path has non-BIO tags: $malformed")
            }
            val beginTypes = tags.filter { it.startsWith("B-") }.map { it.removePrefix("B-") }.toSet()
            val orphans = tags.filter { it.startsWith("I-") }.map { it.removePrefix("I-") }.toSet() - beginTypes
            if (orphans.isNotEmpty()) {
                throw NerConfigurationException("$path has I- tags with no matching B-: $orphans")
            }
            if (tags.size != tags.distinct().size) {
                throw NerConfigurationException("$path has duplicate tags")
            }
        }
    }
}

/**
 * Tokenizer settings that the shipped assets do not carry.
 *
 * `vocab.txt` is the 30,522-entry `bert-base-uncased` vocabulary (`[PAD]`=0,
 * `[UNK]`=100, `[CLS]`=101, `[SEP]`=102, only five entries containing uppercase), which
 * fixes [DO_LOWER_CASE]. Sequence length and label count are **not** hardcoded from the
 * guide's 128 and 17 — they are read from the model's own tensor shapes at load time and
 * cross-checked against `tags.txt`, so a retrained model with different dimensions fails
 * loudly instead of producing garbage.
 */
object NerConfig {
    /** bert-base-uncased. Casing is preserved in extracted text via character offsets. */
    const val DO_LOWER_CASE = true

    /** Entities below this mean confidence are dropped. */
    const val MIN_ENTITY_CONFIDENCE = 0.5f

    /** Fallback only; the real value comes from the input tensor shape. */
    const val DEFAULT_MAX_SEQUENCE_LENGTH = 128
}

/** The model assets are missing, malformed, or disagree with each other. */
class NerConfigurationException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/** Inference could not run. Callers should fall back rather than drop the message. */
class NerUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
