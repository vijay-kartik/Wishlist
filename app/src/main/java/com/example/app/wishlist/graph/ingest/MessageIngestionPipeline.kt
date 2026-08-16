package com.example.app.wishlist.graph.ingest

import android.content.Context
import com.example.app.wishlist.graph.core.EdgeType
import com.example.app.wishlist.graph.core.LookupKey
import com.example.app.wishlist.graph.core.NodeType
import com.example.app.wishlist.graph.core.Predicate
import com.example.app.wishlist.graph.core.SourceKind
import com.example.app.wishlist.graph.storage.GraphRepository
import com.example.app.wishlist.ml.EntityMention
import com.example.app.wishlist.ml.NerEngine
import com.example.app.wishlist.ml.NerResult
import com.example.app.wishlist.ml.NerUnavailableException
import com.example.app.wishlist.ml.ShoppingNerModel
import timber.log.Timber
import java.time.Instant
import java.time.ZoneId

/** One captured message, as it arrives from the notification listener. */
data class IncomingMessage(
    val senderName: String,
    val text: String,
    val postedAtMillis: Long,
    /** `StatusBarNotification.key` — the stable identity dedup relies on. */
    val notificationKey: String,
    val sourceKind: SourceKind = SourceKind.WHATSAPP_MSG,
)

/** What one ingestion attempt did. */
sealed interface IngestionOutcome {
    /** This message was already ingested; nothing was written. */
    data object Duplicate : IngestionOutcome

    /** Nothing worth storing — no entities and no link. */
    data object NothingExtracted : IngestionOutcome

    /** The model could not run. The caller should fall back to the regex parser. */
    data class NerFailed(val cause: Throwable) : IngestionOutcome

    data class Written(
        val assertionCount: Int,
        val predicate: Predicate,
        val entities: List<EntityMention>,
        val inferenceMillis: Long,
    ) : IngestionOutcome
}

/**
 * WhatsApp message in, knowledge-graph writes out.
 *
 * ```
 * notification ─▶ NER inference ─▶ intent heuristic ─▶ one transaction:
 *                                                        SOURCE
 *                                                        PERSON  (sender, beneficiary)
 *                                                        PRODUCT / CATEGORY
 *                                                        ASSERTION + role edges
 * ```
 *
 * The whole graph write is a single transaction, so a message either lands completely
 * or not at all — an assertion whose subject edge failed to write would be a fact about
 * nobody.
 */
class MessageIngestionPipeline(
    context: Context,
    private val graph: GraphRepository = GraphRepository(context),
    private val engineProvider: () -> NerEngine = { ShoppingNerModel.getOrCreate(context) },
) {
    /**
     * Runs NER and writes the result to the graph.
     *
     * Blocking: model inference and an ObjectBox transaction. Call from a background
     * dispatcher, never from `onNotificationPosted` directly.
     */
    fun ingest(message: IncomingMessage): IngestionOutcome {
        if (message.text.isBlank()) return IngestionOutcome.NothingExtracted

        val ner = try {
            engineProvider().infer(message.text)
        } catch (e: NerUnavailableException) {
            Timber.w(e, "NER unavailable; caller should fall back")
            return IngestionOutcome.NerFailed(e)
        } catch (e: Exception) {
            Timber.e(e, "NER inference failed")
            return IngestionOutcome.NerFailed(e)
        }

        val url = IntentHeuristic.extractUrl(message.text)
        if (ner.isEmpty && url == null) return IngestionOutcome.NothingExtracted

        val intent = IntentHeuristic.infer(ner)
        return writeToGraph(message, ner, intent, url)
    }

    private fun writeToGraph(
        message: IncomingMessage,
        ner: NerResult,
        intent: IntentHeuristic.IntentInference,
        url: String?,
    ): IngestionOutcome = graph.write { tx ->

        val sourceKey = LookupKey.source(
            kind = message.sourceKind,
            externalRef = message.notificationKey,
            postedAtMillis = message.postedAtMillis,
        )
        val source = tx.upsert(NodeType.SOURCE, sourceKey) {
            put("sourceKind", message.sourceKind.name)
            put("externalRef", message.notificationKey)
            put("capturedAt", message.postedAtMillis)
            // Bounded snippet for explainability. Only above the confidence floor, so we
            // never quote a line the model plainly misread back to the user.
            if (ner.entities.any { it.confidence >= SNIPPET_CONFIDENCE_FLOOR }) {
                put("snippet", snippet(message.text))
            }
        }
        if (!source.created) return@write IngestionOutcome.Duplicate

        val sender = tx.resolvePerson(message.senderName)
            ?: return@write IngestionOutcome.NothingExtracted

        val observedAtEpochDay = Instant.ofEpochMilli(message.postedAtMillis)
            .atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay().toInt()

        // Confidence multiplies extraction certainty by intent certainty: the graph
        // should be no more sure a fact holds than it is sure of either half of it.
        val entityConfidence = ner.entities
            .filter { it.type in OBJECT_TYPES }
            .maxOfOrNull { it.confidence } ?: FALLBACK_ENTITY_CONFIDENCE
        val confidence = entityConfidence * intent.confidence

        val beneficiary = intent.beneficiary
            ?.let { tx.resolvePerson(it.text) }
            ?.takeIf { it.graphKey != sender.graphKey }

        val objectKeys = resolveObjects(tx, ner, url)
        if (objectKeys.isEmpty()) return@write IngestionOutcome.NothingExtracted

        // Attributes describe the whole claim, not one object, so they are copied onto
        // each assertion rather than split across them.
        val attributes = ner.entities.filter { it.type in ATTRIBUTE_TYPES }

        var written = 0
        for (objectKey in objectKeys) {
            val assertion = tx.putAssertion(
                predicate = intent.predicate,
                subjectKey = sender.graphKey,
                objectKey = objectKey,
                sourceKey = source.graphKey,
                observedAtEpochDay = observedAtEpochDay,
                confidence = confidence,
            ) {
                attributes.forEach { put(it.type.lowercase(), it.text) }
                intent.matchedCue?.let { put("intentCue", it) }
                put("intentSource", "heuristic")
                if (ner.truncated) put("truncated", true)
            } ?: continue

            written++
            if (beneficiary != null) {
                tx.putEdge(assertion.graphKey, EdgeType.BENEFICIARY, beneficiary.graphKey)
            }
        }

        if (written == 0) {
            IngestionOutcome.Duplicate
        } else {
            IngestionOutcome.Written(
                assertionCount = written,
                predicate = intent.predicate,
                entities = ner.entities,
                inferenceMillis = ner.inferenceMillis,
            )
        }
    }

    /**
     * Picks what the assertions are about.
     *
     * A shared link is the most specific thing a message can be about, so it wins; the
     * category and brand nodes still get written when present, because a link that later
     * resolves to a product needs somewhere to hang its category.
     */
    private fun resolveObjects(
        tx: GraphRepository.Tx,
        ner: NerResult,
        url: String?,
    ): List<Long> {
        // PRODUCT here is a *text mention* ("handbag", "running shoes"), not a resolved
        // listing — the tag set has no platform id, and only a URL can supply one. So an
        // unresolved product mention is stored as a fine-grained CATEGORY node: it
        // behaves like one for scoring and rollups, and the taxonomy mapping step will
        // re-point it later. Everything written this way is marked `extracted-v0` so it
        // can be found again.
        val categoryMentions = ner.ofType("CATEGORY") + ner.ofType("PRODUCT")

        val categories = categoryMentions.map { mention ->
            val slug = slugify(mention.text)
            tx.upsert(NodeType.CATEGORY, LookupKey.category(slug)) {
                put("slug", slug)
                put("label", mention.text)
                put("taxonomyVersion", "extracted-v0")
                put("extractedAs", mention.type)
            }
        }

        if (url != null) {
            val product = tx.upsert(NodeType.PRODUCT, LookupKey.productByUrl(url)) {
                put("canonicalUrl", url)
                // No URL-resolution service yet. The best available title is whatever the
                // model called the item, preferring an explicit PRODUCT span.
                put(
                    "title",
                    ner.firstOfType("PRODUCT")?.text
                        ?: ner.firstOfType("CATEGORY")?.text
                        ?: "Shared link",
                )
                put("resolved", false)
            }
            categories.firstOrNull()?.let {
                tx.putEdge(product.graphKey, EdgeType.INSTANCE_OF, it.graphKey)
            }
            return listOf(product.graphKey)
        }

        // Without a link, the most specific mention is the object. Taking only the first
        // avoids fanning one message into an assertion per noun, which would let a chatty
        // message outweigh a deliberate one.
        return listOfNotNull(categories.firstOrNull()?.graphKey)
    }

    private fun slugify(raw: String): String =
        raw.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "unresolved" }

    private fun snippet(text: String): String {
        if (text.length <= SNIPPET_MAX_CHARS) return text
        val cut = text.take(SNIPPET_MAX_CHARS)
        val lastSpace = cut.lastIndexOf(' ')
        return (if (lastSpace > SNIPPET_MAX_CHARS / 2) cut.take(lastSpace) else cut) + "…"
    }

    private companion object {
        const val SNIPPET_MAX_CHARS = 160
        const val SNIPPET_CONFIDENCE_FLOOR = 0.5f
        const val FALLBACK_ENTITY_CONFIDENCE = 0.4f

        // Types are those in assets/tags.txt: PRODUCT, CATEGORY, COLOR, SIZE, BUDGET,
        // RECIPIENT, OCCASION, TIME. Note there is no BRAND tag, so no BRAND nodes are
        // written and MADE_BY stays unused until brand extraction exists.

        /** Entity types that can be the *object* of an assertion. */
        val OBJECT_TYPES = setOf("PRODUCT", "CATEGORY")

        /** Entity types that describe the claim and ride along in the attributes JSON. */
        val ATTRIBUTE_TYPES = setOf("COLOR", "SIZE", "BUDGET", "OCCASION", "TIME")
    }
}
