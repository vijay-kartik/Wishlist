package com.example.app.wishlist.ml

import android.content.res.AssetManager
import java.text.Normalizer

/** A half-open character range `[start, end)` in the *original* message text. */
data class CharSpan(val start: Int, val end: Int) {
    fun textIn(source: String): String = source.substring(start, end)
}

/**
 * One tokenized message, ready to feed the model.
 *
 * @param spans per-token character range in the original text; null for `[CLS]`,
 *   `[SEP]` and padding. This is what makes it possible to hand back the user's own
 *   words rather than de-tokenized WordPiece fragments — "sneak ##ers" becomes
 *   "sneakers" because we slice the original string, not because we glue pieces back.
 * @param continuations true where the token is a `##` WordPiece continuation of the
 *   preceding token's word. The decoder needs this because such positions are masked
 *   out during training and so carry no supervised label — see [TokenizedInput] usage
 *   in `BioDecoder.decode`.
 */
data class TokenizedInput(
    val tokenIds: IntArray,
    val attentionMask: IntArray,
    val tokenTypeIds: IntArray,
    val spans: List<CharSpan?>,
    val continuations: List<Boolean>,
    val realTokenCount: Int,
    val truncated: Boolean,
) {
    // Arrays, so the generated equals/hashCode would compare by reference.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/**
 * BERT-style WordPiece tokenizer that keeps character offsets into the original text.
 *
 * Offset tracking is the whole reason this is hand-rolled rather than pulled from a
 * support library. Recovering entity text by concatenating WordPiece tokens and undoing
 * `##` loses the original casing, punctuation and spacing — "Nike Air Max" comes back as
 * "nike air max" — and the graph should store what the person actually wrote.
 *
 * Accent stripping is done character by character precisely so the offset map stays
 * exact: NFD-decomposing the whole string at once shifts every index after the first
 * accented character.
 */
class WordPieceTokenizer(
    private val vocab: Map<String, Int>,
    private val doLowerCase: Boolean,
    private val maxSequenceLength: Int,
) {
    private val unkId = requireVocab(UNK)
    private val clsId = requireVocab(CLS)
    private val sepId = requireVocab(SEP)
    private val padId = vocab[PAD] ?: 0

    fun encode(text: String): TokenizedInput {
        val normalized = normalize(text)

        val tokenIds = ArrayList<Int>(maxSequenceLength)
        val spans = ArrayList<CharSpan?>(maxSequenceLength)
        val continuations = ArrayList<Boolean>(maxSequenceLength)

        tokenIds += clsId
        spans += null
        continuations += false

        // Two slots reserved for [CLS] and [SEP].
        val budget = maxSequenceLength - 2
        var truncated = false

        outer@ for (word in splitWords(normalized.text)) {
            for ((token, range) in wordPiece(normalized.text, word)) {
                if (tokenIds.size - 1 >= budget) {
                    truncated = true
                    break@outer
                }
                tokenIds += vocab[token] ?: unkId
                spans += normalized.toOriginalSpan(range)
                continuations += token.startsWith(CONTINUATION_PREFIX)
            }
        }

        tokenIds += sepId
        spans += null
        continuations += false

        val realCount = tokenIds.size
        val ids = IntArray(maxSequenceLength) { if (it < realCount) tokenIds[it] else padId }
        val mask = IntArray(maxSequenceLength) { if (it < realCount) 1 else 0 }
        val types = IntArray(maxSequenceLength) // single-segment input: all zeros
        val paddedSpans = List(maxSequenceLength) { if (it < realCount) spans[it] else null }
        val paddedContinuations = List(maxSequenceLength) { it < realCount && continuations[it] }

        return TokenizedInput(
            tokenIds = ids,
            attentionMask = mask,
            tokenTypeIds = types,
            spans = paddedSpans,
            continuations = paddedContinuations,
            realTokenCount = realCount,
            truncated = truncated,
        )
    }

    // --- normalisation -------------------------------------------------------

    /** Normalised text plus, for each normalised char, the index of the char it came from. */
    private class Normalized(val text: String, private val origin: IntArray) {
        fun toOriginalSpan(range: IntRange): CharSpan? {
            if (range.isEmpty()) return null
            val start = origin.getOrNull(range.first) ?: return null
            val endInclusive = origin.getOrNull(range.last) ?: return null
            return CharSpan(start, endInclusive + 1)
        }
    }

    private fun normalize(raw: String): Normalized {
        val sb = StringBuilder(raw.length)
        val origin = ArrayList<Int>(raw.length)

        for (i in raw.indices) {
            val c = raw[i]
            // Drop control characters, but keep whitespace as a separator.
            if (c != '\t' && c != '\n' && c != '\r' && Character.isISOControl(c)) continue
            if (Character.isWhitespace(c)) {
                sb.append(' ')
                origin += i
                continue
            }
            val single = if (doLowerCase) c.lowercase() else c.toString()
            for (ch in Normalizer.normalize(single, Normalizer.Form.NFD)) {
                if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) continue
                sb.append(ch)
                origin += i
            }
        }
        return Normalized(sb.toString(), origin.toIntArray())
    }

    // --- splitting -----------------------------------------------------------

    /**
     * Whitespace split, then punctuation split — each punctuation mark becomes its own
     * token, matching BERT's basic tokenizer. Returns ranges into the normalised text.
     */
    private fun splitWords(text: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = 0
        while (i < text.length) {
            if (text[i] == ' ') { i++; continue }
            if (isPunctuation(text[i])) {
                out += i..i
                i++
                continue
            }
            val start = i
            while (i < text.length && text[i] != ' ' && !isPunctuation(text[i])) i++
            out += start until i
        }
        return out
    }

    /** BERT treats all ASCII non-alphanumerics as punctuation, not just Unicode P*. */
    private fun isPunctuation(c: Char): Boolean {
        val code = c.code
        if (code in 33..47 || code in 58..64 || code in 91..96 || code in 123..126) return true
        return when (Character.getType(c).toByte()) {
            Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION,
            Character.START_PUNCTUATION, Character.END_PUNCTUATION,
            Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
            Character.OTHER_PUNCTUATION -> true
            else -> false
        }
    }

    // --- WordPiece -----------------------------------------------------------

    /** Greedy longest-match-first, returning each piece with its range in the normalised text. */
    private fun wordPiece(text: String, word: IntRange): List<Pair<String, IntRange>> {
        val length = word.last - word.first + 1
        if (length > MAX_CHARS_PER_WORD) return listOf(UNK to word)

        val pieces = ArrayList<Pair<String, IntRange>>(4)
        var start = word.first
        while (start <= word.last) {
            var end = word.last + 1
            var match: String? = null
            while (start < end) {
                val candidate = buildString {
                    if (start > word.first) append(CONTINUATION_PREFIX)
                    append(text, start, end)
                }
                if (vocab.containsKey(candidate)) { match = candidate; break }
                end--
            }
            if (match == null) {
                // Unknown anywhere in the word makes the whole word [UNK], as in the
                // reference implementation — a partial match would misalign the labels.
                return listOf(UNK to word)
            }
            pieces += match to (start until end)
            start = end
        }
        return pieces
    }

    private fun requireVocab(token: String): Int =
        vocab[token] ?: throw NerConfigurationException("vocab.txt is missing the `$token` token")

    companion object {
        const val PAD = "[PAD]"
        const val UNK = "[UNK]"
        const val CLS = "[CLS]"
        const val SEP = "[SEP]"
        /** WordPiece marks every piece after a word's first with this prefix. */
        const val CONTINUATION_PREFIX = "##"
        private const val MAX_CHARS_PER_WORD = 100
        const val ASSET_PATH = "vocab.txt"

        /**
         * Vocabulary file: one token per line, line index is the token id.
         *
         * @param maxSequenceLength taken from the model's own input tensor, not assumed.
         */
        fun fromAssets(
            assets: AssetManager,
            maxSequenceLength: Int,
            path: String = ASSET_PATH,
        ): WordPieceTokenizer {
            val vocab = HashMap<String, Int>(32_000)
            assets.open(path).bufferedReader().useLines { lines ->
                lines.forEachIndexed { index, line ->
                    val token = line.trim()
                    if (token.isNotEmpty()) vocab[token] = index
                }
            }
            if (vocab.isEmpty()) throw NerConfigurationException("$path is empty")
            return WordPieceTokenizer(
                vocab = vocab,
                doLowerCase = NerConfig.DO_LOWER_CASE,
                maxSequenceLength = maxSequenceLength,
            )
        }
    }
}
