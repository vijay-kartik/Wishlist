# Prompt: Debug/Inspector UI for the Wishlist app

*(Copy everything below the line into Claude.)*

---

Build a **developer debug screen** for an existing Android app. This is an internal
inspection tool for verifying a machine-learning pipeline and a knowledge-graph store —
it is **not** a product feature and must not look or behave like one.

## What the app does

Wishlist is an Android app that reads incoming WhatsApp messages, runs an on-device NER
model over them, and writes the extracted facts into a local knowledge graph, so it can
later answer "what should I gift Alex?". The ingestion pipeline and graph storage are
built. **There is no UI at all yet** — `MainActivity` is still the `Hello Android`
template.

I need a debug screen to answer three questions while developing:

1. **Is the NER model producing sensible entities?** Type text, see exactly what came back.
2. **What actually got written to the graph?** Browse the nodes and edges.
3. **Do the scoring/query rules behave?** Inspect assertions and their computed relevance.

## Stack

Kotlin, Jetpack Compose, Material 3, single-activity. Already on the classpath:
`androidx.compose.material3`, `navigation-compose`, `lifecycle-viewmodel-compose`,
`kotlinx-coroutines`, ObjectBox 5.4.2, Timber. `minSdk 31`, JVM target 17.

Do not add dependencies without saying why.

---

# Screens

One activity, three tabs (or a nav rail — your call, but keep it one screen deep where
possible). Density over beauty: this is a tool, and I will be reading a lot of small text.
Monospace for anything machine-generated — ids, keys, JSON, spans.

## Tab 1 — Pipeline

The main working surface.

**Input area (top, always visible)**
- Multi-line text field for the message body
- A "sender" text field, defaulting to something like `Debug Contact`
- A few one-tap sample messages that exercise different paths. Suggestions:
  - `red dress for mom` — recipient, no cue phrase
  - `bhaiya dekho ye — perfect for Amy https://www.amazon.in/dp/B0XXXXXXX` — recipient + URL
  - `i want those black running shoes size 9 under 5000` — desire + size + budget
  - `just bought the sony headphones` — acquisition
- Two buttons: **Run NER only** (inference, no writes) and **Run full pipeline**
  (inference + graph writes). Keeping them separate matters — I need to iterate on
  extraction without polluting the graph.

**Results area (below, scrolling)**

*Entities* — one row per `EntityMention`: type chip, the extracted text, the character
span, and confidence. Color the chip by entity type consistently.

*Highlighted source text* — render the original message with each entity span
highlighted inline, colored to match its type chip. This is the single most useful view
on the screen: it makes offset bugs visible immediately, which is otherwise the hardest
class of bug here to notice. An entity whose highlight is off by a character or two is a
tokenizer offset bug, and there is no other way to see it.

*Intent* — the predicate, its confidence, the matched cue string, the beneficiary, and
whether the beneficiary is the recipient.

*Timing* — `inferenceMillis`, and whether the input was truncated (show truncation as a
warning, not a footnote).

*Ingestion outcome* — render each `IngestionOutcome` variant distinctly. `Duplicate` and
`NothingExtracted` are normal outcomes, not errors; `NerFailed` is an error and should
show the exception with a stack trace behind a disclosure toggle.

## Tab 2 — Graph browser

Raw inspection of what is in ObjectBox.

- Counts by `NodeType` and by `EdgeType` at the top
- Filterable list of nodes: filter by node type, free-text search over `lookupKey`
- A node row shows: `graphKey`, node type, `lookupKey`, and a truncated `payload`
- Tapping a node opens a detail view showing:
  - all scalar columns, pretty-printed `payload` JSON
  - for an ASSERTION: the resolved subject and object nodes (follow `subjectKey` /
    `objectKey`), the predicate, `observedAtEpochDay` rendered as a real date, confidence
  - **incoming and outgoing edges**, each showing the edge type and the node on the other
    end, tappable to navigate there. Being able to walk the graph by tapping is the whole
    point of this tab.
- A **Clear graph** button, behind a confirmation. Wipes `KgNode` and `KgEdge` only.

## Tab 3 — Scoring

Where I check the relevance maths against real stored data.

- Pick a PERSON node from a dropdown
- List that person's assertions (`subjectKey` == their `graphKey`), each showing:
  predicate, object node label, raw confidence, age in days, and **computed relevance**
  from `Relevance.of(...)`
- Group by the object node and show the `Scoring.baseScore(...)` per group, plus the
  normalized score
- An **age slider** ("simulate N days from now") that recomputes every relevance live.
  Decay is the thing I most need to see behaving, and waiting real days is not an option.
  Note that assertion `observedAtEpochDay` must not be mutated — just offset `ageDays` at
  display time.
- A small panel exercising `Scoring.acquisitionEffect(ageDays, replenishCycleDays)` across
  a range, so I can see the durable-suppression curve and the replenishment curve. A
  simple line rendered with Canvas is fine.

---

# API surface you will use

These all exist. Package root is `com.example.app.wishlist`.

### Inference — `ml/`

```kotlin
interface NerEngine {
    fun infer(text: String): NerResult
    fun close()
}

// Process-wide instance. Loads a 110 MB model — call off the main thread.
// Throws NerUnavailableException if assets are missing.
ShoppingNerModel.getOrCreate(context): ShoppingNerModel
ShoppingNerModel.release()

data class NerResult(
    val sourceText: String,
    val entities: List<EntityMention>,
    val truncated: Boolean,
    val inferenceMillis: Long,
)

data class EntityMention(
    val type: String,        // PRODUCT, CATEGORY, COLOR, SIZE, BUDGET, RECIPIENT, OCCASION, TIME
    val text: String,
    val span: CharSpan,      // CharSpan(start, end) — half-open, into NerResult.sourceText
    val confidence: Float,   // 0..1
)
```

The eight entity types above are the complete set — there is **no** BRAND, PRICE,
MATERIAL or PERSON type. Read `app/src/main/assets/tags.txt` to confirm.

### Ingestion — `graph/ingest/`

```kotlin
class MessageIngestionPipeline(context: Context) {
    fun ingest(message: IncomingMessage): IngestionOutcome   // blocking
}

data class IncomingMessage(
    val senderName: String,
    val text: String,
    val postedAtMillis: Long,
    val notificationKey: String,   // dedup key — see note below
    val sourceKind: SourceKind = SourceKind.WHATSAPP_MSG,
)

sealed interface IngestionOutcome {
    data object Duplicate : IngestionOutcome
    data object NothingExtracted : IngestionOutcome
    data class NerFailed(val cause: Throwable) : IngestionOutcome
    data class Written(
        val assertionCount: Int,
        val predicate: Predicate,
        val entities: List<EntityMention>,
        val inferenceMillis: Long,
    ) : IngestionOutcome
}

object IntentHeuristic {
    fun infer(ner: NerResult): IntentInference
    fun extractUrl(text: String): String?

    data class IntentInference(
        val predicate: Predicate,
        val confidence: Float,
        val beneficiary: EntityMention?,
        val beneficiaryIsRecipient: Boolean,
        val matchedCue: String?,
    )
}
```

**`notificationKey` matters for the debug screen.** Ingestion is idempotent on it — the
same key returns `Duplicate` and writes nothing. For manual runs, generate a unique key
per invocation (e.g. `"debug:${System.currentTimeMillis()}"`) so repeated runs of the same
text actually write. Also expose a "reuse last key" toggle so I can deliberately test the
duplicate path.

### Storage — `graph/storage/`, `data/db/`

```kotlin
ObjectBoxProvider.initialize(context)
ObjectBoxProvider.getKgNodeBox(context): Box<KgNode>
ObjectBoxProvider.getKgEdgeBox(context): Box<KgEdge>
ObjectBoxProvider.getBoxStore(context): BoxStore

class GraphRepository(context: Context) {
    fun <T> write(block: (Tx) -> T): T
    fun findByLookupKey(lookupKey: String): KgNode?
}
```

`KgNode` columns: `graphKey`, `graphId`, `nodeTypeId`, `lookupKey`, `payload` (JSON
string), `predicateId`, `subjectKey`, `objectKey`, `observedAtEpochDay` (Int),
`confidence` (Float), `dismissedByKey`, `embedding`. Convenience getters: `nodeType`,
`predicate`, `identity`, `isDismissed`.

`KgEdge` columns: `edgeKey`, `fromKey`, `toKey`, `edgeTypeId`, `dedupKey`, `payload`.
Convenience getter: `edgeType`.

### Ontology & scoring — `graph/core/`

```kotlin
enum class NodeType { PERSON, NAME_VARIANT, PHONE_NUMBER, EMAIL_ADDRESS,
                      CATEGORY, PRODUCT, BRAND, SOURCE, ASSERTION, OCCASION, UNKNOWN }
enum class EdgeType { BENEFICIARY, STATED_BY, EVIDENCED_BY, DERIVED_FROM,
                      HAS_PHONE, HAS_EMAIL, HAS_NAME_VARIANT, INSTANCE_OF, MADE_BY,
                      SUBCATEGORY_OF, RELATED_TO, RELATED_AS_*, UNKNOWN }
enum class Predicate { INTERESTED_IN, RECOMMENDS, SHARED, PURCHASED, OWNS,
                       DISLIKES, AFFINITY, HAS_OCCASION, SIZE_KNOWN, NONE }
```

Each has a stable `id: Int` and a `fromId(id: Int)` companion — **use `fromId`, never
`values()[ordinal]`**. `Predicate` also carries `weight: Double` and
`halfLifeDays: Double?` (null = does not decay).

```kotlin
data class AssertionSignal(
    val predicate: Predicate,
    val confidence: Float,
    val ageDays: Double,
    val dismissed: Boolean = false,
)

object Relevance {
    fun of(signal: AssertionSignal): Double
    fun decayFactor(predicate: Predicate, ageDays: Double): Double
}

object Scoring {
    fun baseScore(signals: List<AssertionSignal>): Double
    fun withAdjacency(base: Double, neighbourBaseScores: List<Double>): Double
    fun acquisitionEffect(ageDays: Double, replenishCycleDays: Double?): Double
    fun normalize(score: Double, k: Double = NORMALIZATION_K): Double
    fun topMargin(rankedScores: List<Double>): Double
}
```

---

# Things you must handle (these will bite you otherwise)

**1. The database is never initialized.** `ObjectBoxProvider.initialize()` has no callers
anywhere in the app, and there is no `Application` subclass. Create one, call
`initialize()` in `onCreate`, and register it via `android:name` in `AndroidManifest.xml`.
Nothing works until this exists.

**2. There is no query layer.** No `WishlistQueryService`, no `GraphTraversal`. Tabs 2 and
3 must query the ObjectBox boxes directly. Put those queries in a small
`DebugGraphQueries` class rather than inlining them in composables — I will likely promote
them into the real query service later.

**3. ObjectBox string queries require an explicit `StringOrder`.** There is no two-argument
`equal()` overload for `String` properties:

```kotlin
import io.objectbox.query.QueryBuilder
KgNode_.lookupKey.equal(key, QueryBuilder.StringOrder.CASE_SENSITIVE)
```

Omitting it produces a misleading overload-resolution error pointing at unrelated
Long/Boolean candidates. Also: the range methods are `greater` / `less` /
`greaterOrEqual` / `lessOrEqual`, **not** `greaterThan` / `lessThan`.

**4. Int properties need `.toLong()`.** ObjectBox exposes `equal(long)` for Int columns and
Kotlin will not widen implicitly: `KgNode_.nodeTypeId.equal(NodeType.PERSON.id.toLong())`.

**5. Everything is blocking.** `infer()` loads and runs a 110 MB model; `ingest()` opens an
ObjectBox transaction. All of it goes in a ViewModel on `Dispatchers.IO`, never on the
main thread. Show a loading state for the first inference specifically — model load can
take seconds.

**6. The model may be absent.** If `assets/ner_model_quantized.tflite` is missing,
`ShoppingNerModel.getOrCreate` throws `NerUnavailableException`. Surface that as a clear
banner explaining the asset is missing, and keep the rest of the screen usable — Tabs 2
and 3 do not need the model.

**7. `MainActivity` is the template scaffold.** Replace its content. Keep `WishlistTheme`
(`ui/theme/`) as the wrapper.

---

# Out of scope — do not build these

- Any actual wishlist, gift-suggestion, contact-picker or onboarding UI
- Anything touching the legacy `Product` entity or `ProductRepository`
- Notification-listener permission flows, settings, or authentication
- Polished visual design, animation, empty-state illustration, dark-mode theming work
- Tests (I will add tokenizer tests separately)

If a product feature seems necessary to make the debug screen work, say so instead of
building it.

# Definition of done

- Type a message, tap **Run full pipeline**, and see: entities, the highlighted source
  text, the inferred predicate, the ingestion outcome
- Switch to Tab 2 and find the nodes that message just created, and walk from the
  assertion to its subject, object and source by tapping
- Switch to Tab 3, pick that person, and watch relevance fall as the age slider moves
- Nothing blocks the main thread; a missing model degrades gracefully rather than crashing

Ask me before adding a dependency, changing anything in `ml/` or `graph/`, or altering the
ingestion contract. If something in the API surface above does not match the code, trust
the code and tell me what differs.
