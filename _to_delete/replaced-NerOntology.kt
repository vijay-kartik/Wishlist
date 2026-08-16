package com.example.app.wishlist.ml

import android.content.res.AssetManager
import org.json.JSONObject

/**
 * The label set the NER model emits, loaded from `assets/ner/ontology.json`.
 *
 * Labels are **not** hardcoded. The index of a label in the JSON array is the label id
 * the model outputs, so the two have to be exported together — hardcoding a tag order
 * here would mean a silent, confident mislabelling every time the model is retrained
 * with a different label order.
 */
class NerOntology private constructor(
    val modelVersion: String,
    val maxSequenceLength: Int,
    val doLowerCase: Boolean,
    val minEntityConfidence: Float,
    private val labels: List<String>,
) {
    val labelCount: Int get() = labels.size

    /** Raw BIO tag for a model label id, e.g. `"B-COLOR"`. */
    fun labelAt(id: Int): String = labels.getOrElse(id) { OUTSIDE }

    /** The entity type for a label id, or null for `O` / out-of-range. */
    fun entityTypeAt(id: Int): String? = when (val label = labelAt(id)) {
        OUTSIDE -> null
        else -> label.substringAfter('-', missingDelimiterValue = "").takeIf { it.isNotEmpty() }
    }

    /** True when the label id starts a new entity (`B-*`). */
    fun isBeginAt(id: Int): Boolean = labelAt(id).startsWith("B-")

    /** True when the label id continues the previous entity (`I-*`). */
    fun isInsideAt(id: Int): Boolean = labelAt(id).startsWith("I-")

    fun isOutsideAt(id: Int): Boolean = labelAt(id) == OUTSIDE

    /** All entity types in the ontology, for diagnostics and mapping tables. */
    fun entityTypes(): Set<String> =
        labels.mapNotNull { it.substringAfter('-', "").takeIf(String::isNotEmpty) }.toSet()

    companion object {
        const val OUTSIDE = "O"
        private const val DEFAULT_PATH = "ner/ontology.json"

        fun load(assets: AssetManager, path: String = DEFAULT_PATH): NerOntology {
            val json = assets.open(path).bufferedReader().use { it.readText() }
            val root = JSONObject(json)

            val labelArray = root.optJSONArray("labels")
                ?: throw NerConfigurationException("$path has no `labels` array")
            val labels = List(labelArray.length()) { labelArray.getString(it) }

            validate(labels, path)

            return NerOntology(
                modelVersion = root.optString("modelVersion", "unknown"),
                maxSequenceLength = root.optInt("maxSequenceLength", 128),
                doLowerCase = root.optBoolean("doLowerCase", true),
                minEntityConfidence = root.optDouble("minEntityConfidence", 0.5).toFloat(),
                labels = labels,
            )
        }

        /**
         * Catches the malformed label sets that would otherwise fail as bad extractions
         * rather than as errors. It cannot catch a *permuted* label array — nothing on
         * device can — which is why the README states the ordering contract so loudly.
         */
        private fun validate(labels: List<String>, path: String) {
            if (labels.isEmpty()) {
                throw NerConfigurationException("$path has an empty `labels` array")
            }
            if (labels.count { it == OUTSIDE } != 1) {
                throw NerConfigurationException(
                    "$path must contain exactly one `$OUTSIDE` label, found ${labels.count { it == OUTSIDE }}"
                )
            }
            val malformed = labels.filter { it != OUTSIDE && !it.startsWith("B-") && !it.startsWith("I-") }
            if (malformed.isNotEmpty()) {
                throw NerConfigurationException("$path has non-BIO labels: $malformed")
            }
            val beginTypes = labels.filter { it.startsWith("B-") }.map { it.removePrefix("B-") }.toSet()
            val insideTypes = labels.filter { it.startsWith("I-") }.map { it.removePrefix("I-") }.toSet()
            val orphanedInside = insideTypes - beginTypes
            if (orphanedInside.isNotEmpty()) {
                throw NerConfigurationException(
                    "$path has I- labels with no matching B-: $orphanedInside"
                )
            }
            if (labels.size != labels.distinct().size) {
                throw NerConfigurationException("$path has duplicate labels")
            }
        }
    }
}

/** The model assets are missing, malformed, or disagree with each other. */
class NerConfigurationException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/** Inference could not run. Callers should fall back rather than drop the message. */
class NerUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)
