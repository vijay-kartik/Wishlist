package com.example.app.wishlist.ui.debug

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.app.wishlist.data.db.entity.KgNode
import com.example.app.wishlist.graph.core.EdgeType
import com.example.app.wishlist.graph.core.NodeType
import com.example.app.wishlist.graph.core.Predicate
import com.example.app.wishlist.graph.core.Relevance
import com.example.app.wishlist.graph.core.AssertionSignal
import com.example.app.wishlist.graph.core.Scoring
import com.example.app.wishlist.graph.core.SourceKind
import com.example.app.wishlist.graph.ingest.IncomingMessage
import com.example.app.wishlist.graph.ingest.IngestionOutcome
import com.example.app.wishlist.graph.ingest.IntentHeuristic
import com.example.app.wishlist.graph.ingest.MessageIngestionPipeline
import com.example.app.wishlist.ml.NerResult
import com.example.app.wishlist.ml.NerUnavailableException
import com.example.app.wishlist.ml.ShoppingNerModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class DebugTab { PIPELINE, GRAPH, SCORING, QUERY, VISUALIZE }

enum class QueryMode { GIFT, RAW, TRAVERSAL }

enum class VizLayout { FORCE, RADIAL }

/** One factor line in a gift candidate's score breakdown. */
data class GiftTerm(val expression: String, val value: String, val negative: Boolean)

data class GiftRow(
    val graphKey: Long,
    val rank: String,
    val type: String,
    val label: String,
    val score: String,
    val base: String,
    val detail: String,
    val barFraction: Float,
    /**
     * True when the raw base score is below zero. Worth surfacing separately from the
     * normalised score because [Scoring.normalize] floors at zero — a candidate the
     * person actively dislikes and one they have never mentioned both read 0.000, and
     * only this flag tells them apart.
     */
    val negative: Boolean,
    val terms: List<GiftTerm>,
    val expanded: Boolean,
)

data class RawRow(
    val graphKey: Long,
    val type: String,
    val lookupKey: String,
    val meta: String,
)

data class TravRow(
    val graphKey: Long,
    val indent: String,
    val via: String,
    val type: String,
    val label: String,
    val exists: Boolean,
)

/** A node placed by [DebugGraphLayout], in the layout's own coordinate space. */
data class VizNodeModel(
    val graphKey: Long,
    val type: String,
    val lookupKey: String,
    val label: String,
    val x: Float,
    val y: Float,
    val radius: Float,
    val degree: Int,
)

data class VizEdgeModel(
    val fromKey: Long,
    val toKey: Long,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val edgeType: String,
    /** Provenance edges are drawn dashed: they explain *why* a fact exists, not what it relates. */
    val provenance: Boolean,
)

/**
 * The laid-out graph.
 *
 * Selection is deliberately *not* baked in here. Highlighting is derived in the composable
 * from [DebugUiState.vizSelected], because the force layout is O(n²) over 320 iterations
 * and rebuilding this on every tap would make selection feel broken.
 */
data class VizGraph(
    val nodes: List<VizNodeModel> = emptyList(),
    val edges: List<VizEdgeModel> = emptyList(),
    /** Nodes withheld by [DebugGraphLayout.MAX_RENDERED_NODES]; surfaced, never silent. */
    val dropped: Int = 0,
)

data class EntityRow(val type: String, val text: String, val span: String, val confidence: String)

data class HighlightSegment(val text: String, val type: String?)

data class NodeRow(
    val graphKey: Long,
    val type: String,
    val lookupKey: String,
    val payloadPreview: String,
)

data class NodeDetail(
    val graphKey: Long,
    val type: String,
    val lookupKey: String,
    val scalars: List<Pair<String, String>>,
    val payload: String,
    val isAssertion: Boolean,
    val predicate: String,
    val confidence: String,
    val observedDate: String,
    val subjectKey: Long,
    val subjectLabel: String,
    val objectKey: Long,
    val objectLabel: String,
    val edges: List<EdgeRow>,
)

data class PersonOption(val graphKey: Long, val label: String)

data class AssertionRow(
    val predicate: String,
    val objectLabel: String,
    val confidence: String,
    val ageDays: String,
    val relevance: String,
    val relevanceValue: Double,
)

data class GroupRow(
    val label: String,
    val count: Int,
    val base: String,
    val normalized: String,
    val barFraction: Float,
)

data class DebugUiState(
    val tab: DebugTab = DebugTab.PIPELINE,
    val modelMissing: Boolean = false,

    // Pipeline
    val msgText: String = SAMPLES.first(),
    val sender: String = "Debug Contact",
    val reuseKey: Boolean = false,
    val lastKey: String? = null,
    val running: Boolean = false,
    val modelLoaded: Boolean = false,
    val hasResult: Boolean = false,
    val segments: List<HighlightSegment> = emptyList(),
    val entities: List<EntityRow> = emptyList(),
    val intentPredicate: String = "—",
    val intentConfidence: String = "",
    val intentCue: String = "—",
    val intentBeneficiary: String = "—",
    val intentIsRecipient: String = "—",
    val inferenceMillis: String = "—",
    val truncated: Boolean = false,
    val outcome: IngestionOutcome? = null,
    val outcomeKey: String = "",
    val outcomeSummary: String = "",
    val failureName: String = "",
    val stackTrace: String = "",
    val showStack: Boolean = false,

    // Graph
    val nodeTotal: Long = 0,
    val edgeTotal: Long = 0,
    val nodeCounts: List<TypeCount> = emptyList(),
    val edgeCounts: List<TypeCount> = emptyList(),
    val filterType: String = DebugGraphQueries.FILTER_ALL,
    val search: String = "",
    val nodeRows: List<NodeRow> = emptyList(),
    val selectedNode: NodeDetail? = null,
    val confirmClear: Boolean = false,

    // Scoring
    val persons: List<PersonOption> = emptyList(),
    val selectedPerson: Long = 0,
    val ageOffset: Int = 0,
    val assertionRows: List<AssertionRow> = emptyList(),
    val groupRows: List<GroupRow> = emptyList(),

    /** Every node, for the traversal-start and viz-centre pickers. */
    val nodeOptions: List<PersonOption> = emptyList(),

    // Query · gift ranking
    val queryMode: QueryMode = QueryMode.GIFT,
    val giftPerson: Long = 0,
    val suppressOwned: Boolean = true,
    val expandedGift: Long? = null,
    val giftRows: List<GiftRow> = emptyList(),
    val giftTopMargin: String = "—",

    // Query · raw node query
    val rawType: String = "ASSERTION",
    val rawPredicate: String = DebugGraphQueries.PREDICATE_ANY,
    val rawKey: String = "",
    val rawConfPct: Int = 0,
    val rawDays: Int = 365,
    val rawRows: List<RawRow> = emptyList(),
    val rawKotlin: String = "",

    // Query · traversal
    val travStart: Long = 0,
    val travDepth: Int = 2,
    val travRows: List<TravRow> = emptyList(),
    val travTruncated: Boolean = false,

    // Visualize
    val vizLayout: VizLayout = VizLayout.FORCE,
    val vizCenter: Long = 0,
    val vizSeed: Int = 1,
    /** Node type names currently hidden. SOURCE starts hidden: it is the noisiest type
     *  and dominates the picture without saying much about what the graph knows. */
    val vizHidden: Set<String> = setOf("SOURCE"),
    val vizSelected: Long? = null,
    val vizGraph: VizGraph = VizGraph(),
    val vizComputing: Boolean = false,
) {
    val runningLabel: String
        get() = if (modelLoaded) "Running inference…"
        else "Loading model (110 MB) — first inference takes a few seconds…"

    val entityCount: Int get() = entities.size
    val hasEntities: Boolean get() = entities.isNotEmpty()

    val rawConfidence: Float get() = rawConfPct / 100f
    val vizLegendTypes: List<String> get() = VIZ_TYPES.filter { it !in vizHidden }
}

/** Node types offered as visibility toggles on the Visualize tab, in drawing priority order. */
val VIZ_TYPES = listOf("PERSON", "PRODUCT", "CATEGORY", "OCCASION", "ASSERTION", "SOURCE", "NAME_VARIANT")

/** Edge types that record provenance rather than structure; drawn dashed. */
val PROVENANCE_EDGES = setOf("EVIDENCED_BY", "STATED_BY", "DERIVED_FROM")

val SAMPLES = listOf(
    "red dress for mom",
    "bhaiya dekho ye — perfect for Amy https://www.amazon.in/dp/B0XXXXXXX",
    "i want those black running shoes size 9 under 5000",
    "just bought the sony headphones",
)

/**
 * State for the debug inspector.
 *
 * Everything blocking — model load, inference, ObjectBox transactions — runs on
 * [Dispatchers.IO]. The first inference in particular maps and prepares a 110 MB model
 * and takes seconds, which is why [DebugUiState.runningLabel] says so explicitly rather
 * than showing a bare spinner.
 */
class DebugViewModel(app: Application) : AndroidViewModel(app) {

    private val queries = DebugGraphQueries(app)
    private val pipeline by lazy { MessageIngestionPipeline(app) }

    private val _state = MutableStateFlow(DebugUiState())
    val state: StateFlow<DebugUiState> = _state.asStateFlow()

    init {
        refreshGraph()
        warmModel()
    }

    // --- pipeline ------------------------------------------------------------

    fun setTab(tab: DebugTab) {
        _state.update { it.copy(tab = tab) }
        if (tab != DebugTab.PIPELINE) refreshGraph()
        when (tab) {
            DebugTab.QUERY -> refreshQuery()
            // The layout is expensive, so it is computed on entering the tab rather than
            // kept current in the background on every graph write.
            DebugTab.VISUALIZE -> refreshViz()
            else -> Unit
        }
    }

    fun setMessage(text: String) = _state.update { it.copy(msgText = text) }
    fun setSender(text: String) = _state.update { it.copy(sender = text) }
    fun setReuseKey(value: Boolean) = _state.update { it.copy(reuseKey = value) }
    fun toggleStack() = _state.update { it.copy(showStack = !it.showStack) }

    fun runNerOnly() = run(full = false)
    fun runFullPipeline() = run(full = true)

    private fun run(full: Boolean) {
        if (_state.value.running) return
        _state.update { it.copy(running = true, showStack = false) }

        viewModelScope.launch {
            val current = _state.value
            val text = current.msgText

            val ner: NerResult? = withContext(Dispatchers.IO) {
                runCatching { ShoppingNerModel.getOrCreate(getApplication()).infer(text) }
                    .onFailure { Timber.w(it, "NER run failed") }
                    .getOrNull()
            }

            if (ner == null) {
                _state.update { it.copy(running = false, modelMissing = true) }
                showFailure(NerUnavailableException("Could not load the NER model from assets"))
                return@launch
            }

            val intent = IntentHeuristic.infer(ner)

            // Idempotency is keyed on notificationKey, so a fresh key per run is what makes
            // repeated runs of the same text actually write. The reuse toggle exists to
            // exercise the duplicate path deliberately.
            var key = ""
            var outcome: IngestionOutcome? = null
            if (full) {
                key = if (current.reuseKey && current.lastKey != null) {
                    current.lastKey
                } else {
                    "debug:${System.currentTimeMillis()}"
                }
                outcome = withContext(Dispatchers.IO) {
                    pipeline.ingest(
                        IncomingMessage(
                            senderName = current.sender.ifBlank { "Debug Contact" },
                            text = text,
                            postedAtMillis = System.currentTimeMillis(),
                            notificationKey = key,
                            sourceKind = SourceKind.WHATSAPP_MSG,
                        )
                    )
                }
            }

            applyResult(ner, intent, outcome, key, full)
            if (full) refreshGraph()
        }
    }

    private fun applyResult(
        ner: NerResult,
        intent: IntentHeuristic.IntentInference,
        outcome: IngestionOutcome?,
        key: String,
        full: Boolean,
    ) {
        val rows = ner.entities.map {
            EntityRow(
                type = it.type,
                text = it.text,
                span = "[${it.span.start},${it.span.end})",
                confidence = String.format("%.2f", it.confidence),
            )
        }

        val summary = (outcome as? IngestionOutcome.Written)?.let {
            "${it.assertionCount} assertion(s) · ${it.predicate} · ${it.entities.size} entities"
        }.orEmpty()

        val failure = (outcome as? IngestionOutcome.NerFailed)?.cause

        _state.update {
            it.copy(
                running = false,
                modelLoaded = true,
                modelMissing = false,
                hasResult = true,
                segments = buildSegments(ner),
                entities = rows,
                intentPredicate = intent.predicate.name,
                intentConfidence = "(${String.format("%.2f", intent.confidence)})",
                intentCue = intent.matchedCue ?: "null",
                intentBeneficiary = intent.beneficiary?.text ?: "null (sender)",
                intentIsRecipient = intent.beneficiaryIsRecipient.toString(),
                inferenceMillis = ner.inferenceMillis.toString(),
                truncated = ner.truncated,
                outcome = if (full) outcome else null,
                outcomeKey = key,
                outcomeSummary = summary,
                failureName = failure?.let { c -> c::class.java.simpleName }.orEmpty(),
                stackTrace = failure?.let(::stackTraceOf).orEmpty(),
                lastKey = if (full && key.isNotEmpty()) key else it.lastKey,
            )
        }
    }

    private fun showFailure(cause: Throwable) {
        _state.update {
            it.copy(
                hasResult = true,
                segments = listOf(HighlightSegment(it.msgText, null)),
                entities = emptyList(),
                intentPredicate = "—", intentConfidence = "", intentCue = "—",
                intentBeneficiary = "—", intentIsRecipient = "—",
                inferenceMillis = "—", truncated = false,
                outcome = IngestionOutcome.NerFailed(cause),
                failureName = cause::class.java.simpleName,
                stackTrace = stackTraceOf(cause),
            )
        }
    }

    /**
     * Splits the message into highlighted and plain runs.
     *
     * This is the view that makes tokenizer offset bugs visible — an entity highlighted
     * one character off is otherwise invisible in a table of extracted strings. Spans are
     * clamped and skipped if they overlap, because a malformed span should show as a
     * missing highlight, never as a crash in the debug tool itself.
     */
    private fun buildSegments(ner: NerResult): List<HighlightSegment> {
        val text = ner.sourceText
        val segments = mutableListOf<HighlightSegment>()
        var cursor = 0
        ner.entities
            .sortedBy { it.span.start }
            .forEach { entity ->
                val start = entity.span.start.coerceIn(0, text.length)
                val end = entity.span.end.coerceIn(start, text.length)
                if (start < cursor) return@forEach
                if (start > cursor) segments += HighlightSegment(text.substring(cursor, start), null)
                segments += HighlightSegment(text.substring(start, end), entity.type)
                cursor = end
            }
        if (cursor < text.length) segments += HighlightSegment(text.substring(cursor), null)
        return segments
    }

    private fun warmModel() {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { ShoppingNerModel.getOrCreate(getApplication()) }.isSuccess
            }
            _state.update { it.copy(modelLoaded = ok, modelMissing = !ok) }
        }
    }

    // --- graph ---------------------------------------------------------------

    fun setFilterType(type: String) {
        _state.update { it.copy(filterType = type) }
        refreshNodeRows()
    }

    fun setSearch(query: String) {
        _state.update { it.copy(search = query) }
        refreshNodeRows()
    }

    fun openNode(graphKey: Long) {
        viewModelScope.launch {
            val detail = withContext(Dispatchers.IO) { loadDetail(graphKey) }
            _state.update { it.copy(selectedNode = detail, tab = DebugTab.GRAPH) }
        }
    }

    fun closeNode() = _state.update { it.copy(selectedNode = null) }

    fun askClear() = _state.update { it.copy(confirmClear = true) }
    fun cancelClear() = _state.update { it.copy(confirmClear = false) }

    fun doClear() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { queries.clearGraph() }
            _state.update { it.copy(confirmClear = false, selectedNode = null) }
            refreshGraph()
        }
    }

    private fun loadDetail(graphKey: Long): NodeDetail? {
        val node = queries.node(graphKey) ?: return null
        val type = NodeType.fromId(node.nodeTypeId)
        val isAssertion = type == NodeType.ASSERTION

        val scalars = buildList {
            add("graphKey" to node.graphKey.toString())
            add("graphId" to node.graphId)
            add("nodeTypeId" to "${node.nodeTypeId} · ${type.name}")
            add("lookupKey" to node.lookupKey)
            if (isAssertion) {
                add("predicateId" to "${node.predicateId} · ${Predicate.fromId(node.predicateId).name}")
                add("subjectKey" to node.subjectKey.toString())
                add("objectKey" to node.objectKey.toString())
                add("observedAtEpochDay" to "${node.observedAtEpochDay} · ${formatEpochDay(node.observedAtEpochDay)}")
                add("confidence" to String.format("%.3f", node.confidence))
            }
            if (node.dismissedByKey != 0L) add("dismissedByKey" to node.dismissedByKey.toString())
        }

        val subject = if (isAssertion) queries.node(node.subjectKey) else null
        val obj = if (isAssertion) queries.node(node.objectKey) else null

        return NodeDetail(
            graphKey = node.graphKey,
            type = type.name,
            lookupKey = node.lookupKey,
            scalars = scalars,
            payload = DebugGraphQueries.prettyPayload(node.payload),
            isAssertion = isAssertion,
            predicate = Predicate.fromId(node.predicateId).name,
            confidence = String.format("%.2f", node.confidence),
            observedDate = formatEpochDay(node.observedAtEpochDay),
            subjectKey = node.subjectKey,
            subjectLabel = "${DebugGraphQueries.label(subject)}  (${subject?.lookupKey ?: node.subjectKey})",
            objectKey = node.objectKey,
            objectLabel = "${DebugGraphQueries.label(obj)}  (${obj?.lookupKey ?: node.objectKey})",
            edges = queries.edgesFor(node.graphKey),
        )
    }

    fun refreshGraph() {
        viewModelScope.launch {
            data class Snapshot(
                val nodeTotal: Long, val edgeTotal: Long,
                val nodeCounts: List<TypeCount>, val edgeCounts: List<TypeCount>,
                val rows: List<NodeRow>, val persons: List<PersonOption>,
                val nodeOptions: List<PersonOption>,
            )
            val current = _state.value
            val snap = withContext(Dispatchers.IO) {
                Snapshot(
                    nodeTotal = queries.nodeTotal(),
                    edgeTotal = queries.edgeTotal(),
                    nodeCounts = queries.nodeCounts(),
                    edgeCounts = queries.edgeCounts(),
                    rows = queries.listNodes(current.filterType, current.search).map(::toRow),
                    persons = queries.persons().map {
                        PersonOption(it.graphKey, "${DebugGraphQueries.label(it)}  ·  ${it.lookupKey}")
                    },
                    nodeOptions = queries.listNodes(DebugGraphQueries.FILTER_ALL, "").map {
                        PersonOption(
                            it.graphKey,
                            "${NodeType.fromId(it.nodeTypeId).name}  ${it.lookupKey}",
                        )
                    },
                )
            }
            val person = if (snap.persons.any { it.graphKey == current.selectedPerson }) {
                current.selectedPerson
            } else {
                snap.persons.firstOrNull()?.graphKey ?: 0L
            }
            // Every picker keeps its selection if the node still exists and falls back to
            // the first available otherwise — a cleared graph must not leave the Query tab
            // pointing at a key that no longer resolves.
            fun keepOrFirst(current: Long, options: List<PersonOption>): Long =
                if (options.any { it.graphKey == current }) current else options.firstOrNull()?.graphKey ?: 0L

            _state.update {
                it.copy(
                    nodeTotal = snap.nodeTotal, edgeTotal = snap.edgeTotal,
                    nodeCounts = snap.nodeCounts, edgeCounts = snap.edgeCounts,
                    nodeRows = snap.rows, persons = snap.persons, selectedPerson = person,
                    nodeOptions = snap.nodeOptions,
                    giftPerson = keepOrFirst(it.giftPerson, snap.persons),
                    travStart = keepOrFirst(it.travStart, snap.nodeOptions),
                    vizCenter = keepOrFirst(it.vizCenter, snap.nodeOptions),
                )
            }
            refreshScoring()
            if (_state.value.tab == DebugTab.QUERY) refreshQuery()
            if (_state.value.tab == DebugTab.VISUALIZE) refreshViz()
        }
    }

    private fun refreshNodeRows() {
        viewModelScope.launch {
            val current = _state.value
            val rows = withContext(Dispatchers.IO) {
                queries.listNodes(current.filterType, current.search).map(::toRow)
            }
            _state.update { it.copy(nodeRows = rows) }
        }
    }

    private fun toRow(node: KgNode) = NodeRow(
        graphKey = node.graphKey,
        type = NodeType.fromId(node.nodeTypeId).name,
        lookupKey = node.lookupKey,
        payloadPreview = node.payload.let { if (it.length > 52) it.take(52) + "…" else it },
    )

    // --- scoring -------------------------------------------------------------

    fun selectPerson(graphKey: Long) {
        _state.update { it.copy(selectedPerson = graphKey) }
        refreshScoring()
    }

    fun setAgeOffset(days: Int) {
        _state.update { it.copy(ageOffset = days) }
        refreshScoring()
    }

    private fun refreshScoring() {
        viewModelScope.launch {
            val current = _state.value
            if (current.selectedPerson == 0L) {
                _state.update { it.copy(assertionRows = emptyList(), groupRows = emptyList()) }
                return@launch
            }

            data class Scored(val rows: List<AssertionRow>, val groups: List<GroupRow>)

            val scored = withContext(Dispatchers.IO) {
                val assertions = queries.assertionsForSubject(current.selectedPerson)
                val today = LocalDate.now().toEpochDay()

                fun signalFor(node: KgNode): Pair<AssertionSignal, KgNode?> {
                    val age = (today - node.observedAtEpochDay).coerceAtLeast(0) + current.ageOffset
                    val signal = AssertionSignal(
                        predicate = Predicate.fromId(node.predicateId),
                        confidence = node.confidence,
                        ageDays = age.toDouble(),
                        dismissed = node.dismissedByKey != 0L,
                    )
                    return signal to queries.node(node.objectKey)
                }

                val rows = assertions.map { node ->
                    val (signal, obj) = signalFor(node)
                    val relevance = Relevance.of(signal)
                    AssertionRow(
                        predicate = signal.predicate.name,
                        objectLabel = DebugGraphQueries.label(obj),
                        confidence = String.format("%.2f", node.confidence),
                        ageDays = signal.ageDays.toInt().toString(),
                        relevance = String.format("%.3f", relevance),
                        relevanceValue = relevance,
                    )
                }

                val groups = assertions.groupBy { it.objectKey }.map { (objectKey, group) ->
                    val signals = group.map { signalFor(it).first }
                    val base = Scoring.baseScore(signals)
                    val normalized = Scoring.normalize(base)
                    GroupRow(
                        label = DebugGraphQueries.label(queries.node(objectKey)),
                        count = group.size,
                        base = String.format("%.3f", base),
                        normalized = String.format("%.3f", normalized),
                        barFraction = normalized.toFloat().coerceIn(0f, 1f),
                    )
                }.sortedByDescending { it.base.toDoubleOrNull() ?: 0.0 }

                Scored(rows, groups)
            }

            _state.update { it.copy(assertionRows = scored.rows, groupRows = scored.groups) }
        }
    }

    // --- query · gift ranking ------------------------------------------------

    fun setQueryMode(mode: QueryMode) {
        _state.update { it.copy(queryMode = mode) }
        refreshQuery()
    }

    fun selectGiftPerson(graphKey: Long) {
        _state.update { it.copy(giftPerson = graphKey, expandedGift = null) }
        refreshGift()
    }

    fun setSuppressOwned(value: Boolean) {
        _state.update { it.copy(suppressOwned = value) }
        refreshGift()
    }

    /** Expansion is folded into the existing rows rather than re-queried — a disclosure
     *  toggle should never wait on the database. */
    fun toggleGiftRow(graphKey: Long) = _state.update { st ->
        val next = if (st.expandedGift == graphKey) null else graphKey
        st.copy(
            expandedGift = next,
            giftRows = st.giftRows.map { it.copy(expanded = it.graphKey == next) },
        )
    }

    private fun refreshQuery() {
        when (_state.value.queryMode) {
            QueryMode.GIFT -> refreshGift()
            QueryMode.RAW -> refreshRaw()
            QueryMode.TRAVERSAL -> refreshTraversal()
        }
    }

    /**
     * Ranks what could be given to one person.
     *
     * This is the first end-to-end exercise of the scoring stack against stored data, so
     * it deliberately calls the real [Scoring] and [Relevance] functions rather than
     * reimplementing the formulas for display. A debug view that computes its own version
     * of the maths can agree with itself while disagreeing with production, which is the
     * one failure mode that would make this whole tab worthless.
     */
    private fun refreshGift() {
        viewModelScope.launch {
            val current = _state.value
            if (current.giftPerson == 0L) {
                _state.update { it.copy(giftRows = emptyList(), giftTopMargin = "\u2014") }
                return@launch
            }

            data class Candidate(
                val node: KgNode,
                val base: Double,
                val normalized: Double,
                val terms: List<GiftTerm>,
            )
            data class Result(val rows: List<GiftRow>, val topMargin: String)

            val result = withContext(Dispatchers.IO) {
                val today = LocalDate.now().toEpochDay()
                val assertions = queries.assertionsForSubject(current.giftPerson)

                val candidates = assertions.groupBy { it.objectKey }.mapNotNull { (objectKey, group) ->
                    val obj = queries.node(objectKey) ?: return@mapNotNull null
                    val objType = NodeType.fromId(obj.nodeTypeId)
                    // Only things that can be given. An assertion whose object is a PERSON
                    // or an OCCASION is real and useful, just not a gift candidate.
                    if (objType != NodeType.PRODUCT && objType != NodeType.CATEGORY) return@mapNotNull null

                    val signals = group.map { node ->
                        AssertionSignal(
                            predicate = Predicate.fromId(node.predicateId),
                            confidence = node.confidence,
                            ageDays = (today - node.observedAtEpochDay).coerceAtLeast(0).toDouble(),
                            dismissed = node.dismissedByKey != 0L,
                        )
                    }

                    val terms = signals.mapTo(mutableListOf()) { signal ->
                        val relevance = Relevance.of(signal)
                        val halfLife = signal.predicate.halfLifeDays
                        GiftTerm(
                            expression = "${signal.predicate.name} w=${signal.predicate.weight} " +
                                "\u00d7 conf=${String.format("%.2f", signal.confidence)} " +
                                "\u00d7 decay(${signal.ageDays.toInt()}d, " +
                                (halfLife?.let { "hl=${it.toInt()}" } ?: "none") + ")",
                            value = String.format("%.3f", relevance),
                            negative = relevance < 0,
                        )
                    }

                    var base = Scoring.baseScore(signals)

                    // Suppression is computed at read time, per P2 — nothing is written back
                    // when a purchase lands, so the effect is derived from the PURCHASED
                    // assertion's own age on every query.
                    val owned = signals
                        .filter { it.predicate == Predicate.PURCHASED || it.predicate == Predicate.OWNS }
                        .minByOrNull { it.ageDays }
                    if (current.suppressOwned && owned != null) {
                        // null cycle = durable. There is no category taxonomy yet, so
                        // nothing can be known to replenish; passing a made-up cycle here
                        // would show a curve the product does not actually use.
                        val effect = Scoring.acquisitionEffect(owned.ageDays, null)
                        terms += GiftTerm(
                            expression = "acquisitionEffect(${owned.ageDays.toInt()}d, durable)",
                            value = String.format("%.3f", effect),
                            negative = effect < 0,
                        )
                        base += effect
                    }

                    Candidate(obj, base, Scoring.normalize(base), terms)
                }.sortedWith(
                    // Normalised first, base as the tie-break: normalize() floors at zero,
                    // so every negative candidate ties at 0.000 and would otherwise order
                    // arbitrarily.
                    compareByDescending<Candidate> { it.normalized }.thenByDescending { it.base }
                )

                val rows = candidates.mapIndexed { index, candidate ->
                    GiftRow(
                        graphKey = candidate.node.graphKey,
                        rank = "#${index + 1}",
                        type = NodeType.fromId(candidate.node.nodeTypeId).name,
                        label = DebugGraphQueries.label(candidate.node),
                        score = String.format("%.3f", candidate.normalized),
                        base = String.format("%.3f", candidate.base),
                        detail = String.format("%.3f", candidate.base) + " \u2192 " +
                            String.format("%.3f", candidate.normalized),
                        barFraction = candidate.normalized.toFloat().coerceIn(0f, 1f),
                        negative = candidate.base < 0,
                        terms = candidate.terms,
                        expanded = current.expandedGift == candidate.node.graphKey,
                    )
                }
                Result(
                    rows = rows,
                    topMargin = if (candidates.isEmpty()) "\u2014"
                    else String.format("%.3f", Scoring.topMargin(candidates.map { it.normalized })),
                )
            }

            _state.update { it.copy(giftRows = result.rows, giftTopMargin = result.topMargin) }
        }
    }

    // --- query · raw node query ----------------------------------------------

    fun setRawType(type: String) {
        _state.update { it.copy(rawType = type) }
        refreshRaw()
    }

    fun setRawPredicate(predicate: String) {
        _state.update { it.copy(rawPredicate = predicate) }
        refreshRaw()
    }

    fun setRawKey(key: String) {
        _state.update { it.copy(rawKey = key) }
        refreshRaw()
    }

    fun setRawConfidence(percent: Int) {
        _state.update { it.copy(rawConfPct = percent) }
        refreshRaw()
    }

    fun setRawDays(days: Int) {
        _state.update { it.copy(rawDays = days) }
        refreshRaw()
    }

    private fun refreshRaw() {
        viewModelScope.launch {
            val current = _state.value
            val today = LocalDate.now().toEpochDay().toInt()
            val rows = withContext(Dispatchers.IO) {
                queries.rawQuery(
                    typeFilter = current.rawType,
                    predicateFilter = current.rawPredicate,
                    keySubstring = current.rawKey,
                    minConfidence = current.rawConfidence,
                    maxAgeDays = current.rawDays,
                    today = today,
                ).map { node ->
                    RawRow(
                        graphKey = node.graphKey,
                        type = NodeType.fromId(node.nodeTypeId).name,
                        lookupKey = node.lookupKey,
                        meta = if (node.observedAtEpochDay > 0) {
                            String.format("%.2f", node.confidence) +
                                " \u00b7 ${today - node.observedAtEpochDay}d"
                        } else {
                            "#${node.graphKey}"
                        },
                    )
                }
            }
            _state.update { it.copy(rawRows = rows, rawKotlin = rawQueryKotlin(current, rows.size)) }
        }
    }

    /**
     * The equivalent Kotlin for the current filter set, for lifting into the real query
     * service later.
     *
     * It shows the indexed clauses as an ObjectBox query and the rest as a `filter`,
     * matching what [DebugGraphQueries.rawQuery] actually executes. Emitting one all-in-one
     * query would read better and be a lie: `lookupKey` is uniquely indexed for exact
     * matches so `contains` cannot use it, and `confidence` has no index at all.
     */
    private fun rawQueryKotlin(state: DebugUiState, matched: Int): String {
        val clauses = buildList {
            if (state.rawType != DebugGraphQueries.FILTER_ALL) {
                add("KgNode_.nodeTypeId.equal(NodeType.${state.rawType}.id.toLong())")
            }
            if (state.rawPredicate != DebugGraphQueries.PREDICATE_ANY) {
                add("KgNode_.predicateId.equal(Predicate.${state.rawPredicate}.id.toLong())")
            }
        }

        val query = if (clauses.isEmpty()) {
            "nodeBox.all"
        } else {
            buildString {
                append("nodeBox.query(\n  ")
                append(clauses.first())
                clauses.drop(1).forEach { append("\n    .and($it)") }
                append("\n).build().use { it.find() }")
            }
        }

        val filters = buildList {
            if (state.rawKey.isNotBlank()) {
                add("it.lookupKey.contains(\"${state.rawKey.trim()}\", ignoreCase = true)")
            }
            if (state.rawConfPct > 0) {
                add("it.confidence >= " + String.format("%.2f", state.rawConfidence) + "f")
            }
            add("(it.observedAtEpochDay == 0 || today - it.observedAtEpochDay <= ${state.rawDays})")
        }

        return query + "\n  .filter {\n    " + filters.joinToString("\n      && ") +
            "\n  }   // $matched rows"
    }

    // --- query · traversal ---------------------------------------------------

    fun setTravStart(graphKey: Long) {
        _state.update { it.copy(travStart = graphKey) }
        refreshTraversal()
    }

    fun setTravDepth(depth: Int) {
        _state.update { it.copy(travDepth = depth) }
        refreshTraversal()
    }

    private fun refreshTraversal() {
        viewModelScope.launch {
            val current = _state.value
            if (current.travStart == 0L) {
                _state.update { it.copy(travRows = emptyList(), travTruncated = false) }
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                queries.traverse(current.travStart, current.travDepth)
            }
            val rows = result.hops.map { hop ->
                TravRow(
                    graphKey = hop.graphKey,
                    indent = "\u2502 ".repeat((hop.depth - 1).coerceAtLeast(0)) +
                        if (hop.depth > 0) "\u2514 " else "",
                    via = hop.via,
                    type = hop.type,
                    label = hop.label,
                    exists = hop.exists,
                )
            }
            _state.update { it.copy(travRows = rows, travTruncated = result.truncated) }
        }
    }

    // --- visualize -----------------------------------------------------------

    fun setVizLayout(layout: VizLayout) {
        _state.update { it.copy(vizLayout = layout) }
        refreshViz()
    }

    fun setVizCenter(graphKey: Long) {
        _state.update { it.copy(vizCenter = graphKey) }
        if (_state.value.vizLayout == VizLayout.RADIAL) refreshViz()
    }

    fun reseedViz() {
        _state.update { it.copy(vizSeed = it.vizSeed + 1) }
        refreshViz()
    }

    fun toggleVizType(type: String) {
        _state.update {
            it.copy(vizHidden = if (type in it.vizHidden) it.vizHidden - type else it.vizHidden + type)
        }
        refreshViz()
    }

    /** Selection never re-runs the layout; see [VizGraph]. */
    fun selectVizNode(graphKey: Long?) = _state.update {
        it.copy(vizSelected = if (it.vizSelected == graphKey) null else graphKey)
    }

    private fun refreshViz() {
        viewModelScope.launch {
            _state.update { it.copy(vizComputing = true) }
            val current = _state.value
            val snapshot = withContext(Dispatchers.IO) { queries.graphSnapshot() }
            // Default, not IO: past the read this is a CPU-bound n-body simulation, and
            // parking it on the IO pool would hold a thread meant for blocking work.
            val graph = withContext(Dispatchers.Default) { buildVizGraph(snapshot, current) }
            _state.update { it.copy(vizGraph = graph, vizComputing = false) }
        }
    }

    private fun buildVizGraph(snapshot: GraphSnapshot, state: DebugUiState): VizGraph {
        val visible = snapshot.nodes.filter { NodeType.fromId(it.nodeTypeId).name !in state.vizHidden }
        val visibleKeys = visible.mapTo(HashSet()) { it.graphKey }
        val visibleEdges = snapshot.edges.filter { it.fromKey in visibleKeys && it.toKey in visibleKeys }

        val degree = HashMap<Long, Int>()
        visibleEdges.forEach { edge ->
            degree[edge.fromKey] = (degree[edge.fromKey] ?: 0) + 1
            degree[edge.toKey] = (degree[edge.toKey] ?: 0) + 1
        }

        // Degree is the right thing to cap on: if the graph is too dense to draw, the
        // best-connected nodes are the ones that explain its shape. The count that was
        // dropped is reported on screen.
        val kept = if (visible.size <= DebugGraphLayout.MAX_RENDERED_NODES) {
            visible
        } else {
            visible.sortedByDescending { degree[it.graphKey] ?: 0 }
                .take(DebugGraphLayout.MAX_RENDERED_NODES)
        }
        val keptKeys = kept.mapTo(HashSet()) { it.graphKey }
        val edges = visibleEdges.filter { it.fromKey in keptKeys && it.toKey in keptKeys }

        val positions = when (state.vizLayout) {
            VizLayout.FORCE -> DebugGraphLayout.force(kept, edges, state.vizSeed)
            VizLayout.RADIAL -> DebugGraphLayout.radial(kept, edges, state.vizCenter)
        }

        val nodes = kept.mapNotNull { node ->
            val point = positions[node.graphKey] ?: return@mapNotNull null
            val nodeDegree = degree[node.graphKey] ?: 0
            val type = NodeType.fromId(node.nodeTypeId).name
            VizNodeModel(
                graphKey = node.graphKey,
                type = type,
                lookupKey = node.lookupKey,
                label = shortLabel(node),
                x = point.x,
                y = point.y,
                // Degree-scaled, capped: one hub node should read as bigger, not swallow
                // the canvas.
                radius = 6f + minOf(6f, nodeDegree * 1.3f) + if (type == "PERSON") 2f else 0f,
                degree = nodeDegree,
            )
        }

        val placed = nodes.associateBy { it.graphKey }
        val edgeModels = edges.mapNotNull { edge ->
            val from = placed[edge.fromKey] ?: return@mapNotNull null
            val to = placed[edge.toKey] ?: return@mapNotNull null
            val type = EdgeType.fromId(edge.edgeTypeId).name
            VizEdgeModel(
                fromKey = edge.fromKey,
                toKey = edge.toKey,
                x1 = from.x, y1 = from.y, x2 = to.x, y2 = to.y,
                edgeType = type,
                provenance = type in PROVENANCE_EDGES,
            )
        }

        return VizGraph(nodes = nodes, edges = edgeModels, dropped = visible.size - kept.size)
    }

    /** Node caption: the payload name if there is one, else the unprefixed lookup key. */
    private fun shortLabel(node: KgNode): String {
        val full = DebugGraphQueries.label(node)
        val trimmed = if (full == node.lookupKey) full.substringAfterLast(':') else full
        return if (trimmed.length > 14) trimmed.take(13) + "\u2026" else trimmed
    }

    private fun formatEpochDay(epochDay: Int): String =
        runCatching { LocalDate.ofEpochDay(epochDay.toLong()).toString() }
            .getOrElse {
                Instant.ofEpochMilli(0).atZone(ZoneId.systemDefault()).toLocalDate().toString()
            }

    private fun stackTraceOf(t: Throwable): String = StringWriter().also { sw ->
        PrintWriter(sw).use { t.printStackTrace(it) }
    }.toString()
}
