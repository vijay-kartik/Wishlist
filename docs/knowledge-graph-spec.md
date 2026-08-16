# Wishlist Knowledge Graph — Schema & Storage Specification

**Version:** 0.2 (Draft) · **Target platform:** Android, on-device
**Storage:** ObjectBox 5.4.2, single `BoxStore` · **Supersedes:** v0.1

---

## 0. Status — what exists, what is planned

v0.1 described several pieces as "existing" that are not in the repo. Current reality:

| Component | State |
|---|---|
| `ObjectBoxProvider`, single `BoxStore` | **exists** (`data/db/`) |
| `Product` entity, `ProductRepository` | **exists** — flat, pre-graph (see §12) |
| `WhatsAppNotificationListener`, `NotificationParser` | **exists** — regex extraction, no NER |
| `NodeType` / `EdgeType` / `Predicate` ontology, scoring | **exists** (`graph/core/`) — this spec's §3–5, §9 |
| `KgNode` / `KgEdge` entities | **exists** (`data/db/entity/`) |
| `GraphRepository`, `GraphBuilder`, `GraphTraversal`, `WishlistQueryService` | **planned** |
| Category taxonomy, intent classifier, URL resolution service | **planned** |

Everything marked planned is a design commitment, not a description. §13 proposes which of it to build first.

---

## 1. Purpose & Scope

This document specifies the knowledge graph schema, write paths, and read mechanisms behind two product questions:

1. **Gift wishlist for a contact** — "What should I gift Alex for his birthday?" Aggregate Alex's expressed interests, shared products, known sizes and brands, and suppress what he already owns.
2. **Personal wishlist for the user** — "What have people recommended to me, and what fits my established tastes?" Aggregate inbound recommendations and attribute affinities learned from the user's own orders.

Out of scope: NER/extraction internals, the URL-to-product resolution service, and embedding model selection (the dimension is now fixed at 384 — see §6.4 — but no vectors are written until a model ships).

---

## 2. Design Principles

**P1 — Reified assertions.** Every fact learned from data is an `ASSERTION` node, not a bare edge. Facts here are inherently n-ary ("Adam RECOMMENDS shoes FOR Amy") and carry provenance, confidence and time; they must be individually suppressible and forgettable. Structural, timeless links (product → category, alias → person) stay plain edges.

**P2 — Immutable facts, computed relevance.** `observedAt` and `confidence` are written once. Relevance decays at *read* time. Nothing on the write path revises an existing assertion to reflect current strength.

*Revised from v0.1:* purchase suppression is now **entirely read-time** (§9.3). v0.1 had an ingestion pass that back-filled `suppressedBy` on existing assertions, which contradicted this principle and made replenishment unreachable.

**P3 — Controlled category taxonomy.** Categories are a closed, curated vocabulary (~50–100 gift-relevant leaves) shipped with the app and versioned. Open-vocabulary extraction is *mapped into* it; unmappable mentions land on `CAT_UNRESOLVED`. This prevents the "jeans"/"denim"/"pants" fragmentation that would break category rollups.

**P4 — Attribute decomposition of purchases.** An order is not just "owns product X" — it decomposes into brand affinity, size, fit, price band and category affinity. Attributes outlive the product's relevance.

**P5 — Symmetric ontology.** Every predicate applies to any `PERSON` subject, including the user. The user is a `PERSON` with `isSelf = true`, not a special type.

**P6 — Stable ids, never ordinals.** *(new)* Every enum persisted to storage declares an explicit `id`. Storing `ordinal` means inserting a constant silently re-interprets existing rows — no error, just wrong data. Ids are append-only and never reused; retired ids leave a gap.

---

## 3. Node Types

Stored as `KgNode` with dual identity: UUID-backed `GraphId` (stable, export-safe) and the ObjectBox `graphKey` (long, internal, referenced by edges).

| NodeType | id | Role | Key payload fields |
|---|---|---|---|
| `PERSON` | 1 | A contact or the user | `displayName`, `isSelf`, `contactId` |
| `NAME_VARIANT` | 2 | One alias/nickname for a person | `text`, `normalizedText`, `variantKind`, `language` |
| `PHONE_NUMBER` | 3 | Identity satellite | `e164` |
| `EMAIL_ADDRESS` | 4 | Identity satellite | `address` |
| `CATEGORY` | 5 | Taxonomy leaf or branch | `slug`, `label`, `replenishCycleDays` (null = durable), `taxonomyVersion` |
| `PRODUCT` | 6 | A specific resolved item | `title`, `platform`, `platformProductId`, `canonicalUrl`, `imageRef` |
| `BRAND` | 7 | Brand entity | `name`, `normalizedName` |
| `SOURCE` | 8 | Evidence item | `sourceKind`, `externalRef`, `capturedAt`, `snippet` |
| `ASSERTION` | 9 | One reified fact | `attributes` (JSON) — everything else is a promoted column |
| `OCCASION` | 10 | Recurring gifting trigger | `occasionKind`, `month`, `day`, `label` |

### 3.1 `variantKind` no longer includes `KINSHIP`

v0.1 emitted `NAME_VARIANT(KINSHIP, "bhaiya")` attached to the user. This is unsound: §8 resolves by exact variant match *before* it considers kinship, so the next person to say "bhaiya" — Amy, a colleague, anyone — exact-matches that node and resolves to the user. A speaker-relative term stored as a speaker-independent node is globally resolvable by construction, which is precisely the mis-linking §8's threshold rule exists to prevent.

Kinship is carried entirely by `RELATED_AS_*` edges plus a static `KinshipLexicon` (term → relation). `VariantKind` is now `GIVEN | NICKNAME | TRANSLITERATION`.

### 3.2 `SOURCE` stores a bounded snippet

v0.1 said SOURCE stores a hash and **not** raw text, while §10 promised "SOURCE snippets for explainability." Both cannot hold. Explainability is what makes a gift suggestion credible — "Alex mentioned this on 12 Jun" with the actual line beats a bare score — so the snippet stays, bounded:

- at most 160 characters, truncated at a word boundary
- stored only for assertions with `confidence ≥ 0.5` (below that we would be quoting a line we misread)
- never stored for `SourceKind.SCREENSHOT` (OCR output is the highest-risk content and the least reliable quote)

Everything else about the message stays in the ingestion layer. `externalRef` (§7.5) identifies the original.

---

## 4. Edge Types

`KgEdge(fromKey, toKey, edgeType)` — structural only, no confidence, no time.

**Direction convention:** `A --TYPE--> B` reads **"B is A's TYPE"**. v0.1 said "directional where kinship is directional" without fixing the reading, which would have produced inverted edges. Stated once here, and encoded in `EdgeType.inverse`.

### 4.1 Assertion roles

| EdgeType | id | From → To | Meaning |
|---|---|---|---|
| `BENEFICIARY` | 1 | ASSERTION → PERSON | optional; who it is *for* |
| `STATED_BY` | 2 | ASSERTION → PERSON | optional; speaker, when ≠ subject |
| `EVIDENCED_BY` | 3 | ASSERTION → SOURCE | one per supporting evidence item |
| `DERIVED_FROM` | 4 | ASSERTION → ASSERTION | *(new)* this assertion was computed from that one |

`SUBJECT` and `OBJECT` are **no longer edges** — see §6.2.

`DERIVED_FROM` exists because v0.1's compaction rule ("PURCHASED assertions are exempt while any AFFINITY derived from them survives") was unimplementable: `EVIDENCED_BY` points at the SOURCE, so nothing recorded the derivation. It also gives Q4 a real explanation string ("suggested because of your March order").

### 4.2 Identity & product structure

| EdgeType | id | From → To |
|---|---|---|
| `HAS_PHONE` | 10 | PERSON → PHONE_NUMBER |
| `HAS_EMAIL` | 11 | PERSON → EMAIL_ADDRESS |
| `HAS_NAME_VARIANT` | 12 | PERSON → NAME_VARIANT |
| `INSTANCE_OF` | 20 | PRODUCT → CATEGORY |
| `MADE_BY` | 21 | PRODUCT → BRAND |
| `SUBCATEGORY_OF` | 22 | CATEGORY → CATEGORY |
| `RELATED_TO` | 23 | CATEGORY ↔ CATEGORY (symmetric; store both directions) |

### 4.3 Kinship — folded into the edge type

| EdgeType | id | | EdgeType | id |
|---|---|---|---|---|
| `RELATED_AS_BROTHER` | 30 | | `RELATED_AS_DAUGHTER` | 35 |
| `RELATED_AS_SISTER` | 31 | | `RELATED_AS_SPOUSE` | 36 |
| `RELATED_AS_MOTHER` | 32 | | `RELATED_AS_FRIEND` | 37 |
| `RELATED_AS_FATHER` | 33 | | `RELATED_AS_COLLEAGUE` | 38 |
| `RELATED_AS_SON` | 34 | | | |

v0.1 kept `relationKind` in `KgEdge.payload` — a JSON string — while §8 filtered on it. Entity resolution runs once per mention, the hottest path in ingestion, so that meant loading every edge from the speaker and parsing JSON per mention. Folding the kind into `edgeType` lets the existing index do the work at no storage cost.

Gendered terms stay distinct because gender changes gift selection. `EdgeType.inverse` returns null for sibling and parent/child relations: the inverse depends on the *other* party's gender, which the graph often does not know, and writing `RELATED_AS_SON` for a person of unknown gender is worse than writing nothing.

---

## 5. Predicate Ontology (v1)

Each predicate carries **two independent numbers**, which v0.1 conflated into one:

- **weight** — how strong this *kind* of claim is, semantically
- **half-life** — how fast it goes stale

Extractor certainty is a third, per-assertion value (`KgNode.confidence`). v0.1 multiplied confidence straight into relevance, so a crisply-extracted `SHARED` (0.95) outranked a hedged `INTERESTED_IN` (0.55) despite `SHARED` being described in the same table as a weak signal. Half-lives cannot fix that — they only differentiate with age.

| Predicate | id | Subject → Object | Weight | Half-life | Routing |
|---|---|---|---|---|---|
| `INTERESTED_IN` | 1 | person → category/product/brand | 0.80 | 60 d | Gift candidate for the subject |
| `RECOMMENDS` | 2 | person → product/category | 0.90 | 120 d | To beneficiary's list, else the user's |
| `SHARED` | 3 | person → product | 0.40 | 45 d | Weak; upgradeable on follow-up evidence |
| `PURCHASED` | 4 | person → product | 1.00 | none | Suppression, affinities, replenishment |
| `OWNS` | 5 | person → product/category | 0.90 | none | Suppression, weaker attribute yield |
| `DISLIKES` | 6 | person → category/brand | 1.00 | 540 d | Hard filter, applied before scoring |
| `AFFINITY` | 7 | person → brand/category | 0.60 | 365 d | Derived (§7.4) |
| `HAS_OCCASION` | 8 | person → occasion | 1.00 | none | Triggers surfacing N days ahead |
| `SIZE_KNOWN` | 9 | person → category | 1.00 | 730 d | `size`/`fit` in attributes |

`PURCHASED`, `OWNS` and `HAS_OCCASION` do not decay: a purchase happened, and it keeps having happened. Their *effect* on scoring changes with age (§9.3), but the fact does not.

**Ontology growth rule.** A new predicate is admitted only when (a) the extractor can distinguish it at acceptable precision on real data, and (b) at least one query routes differently because of it. Ceiling for v1: ≤ 10 predicates. Extractor precision, not graph expressiveness, is the binding constraint.

---

## 6. ObjectBox Data Model

```kotlin
@Entity
class KgNode(
    @Id var graphKey: Long = 0,
    @Index @Unique var graphId: String = "",
    @Index var nodeTypeId: Int = NodeType.UNKNOWN.id,
    @Index @Unique var lookupKey: String = "",
    var payload: String = "{}",

    // ASSERTION-only promoted columns
    @Index var predicateId: Int = Predicate.NONE.id,
    @Index var subjectKey: Long = 0,
    @Index var objectKey: Long = 0,
    var observedAtEpochDay: Int = 0,
    var confidence: Float = 0f,
    @Index var dismissedByKey: Long = 0,

    @HnswIndex(dimensions = 384, distanceType = VectorDistanceType.DOT_PRODUCT)
    var embedding: FloatArray? = null,
)

@Entity
data class KgEdge(
    @Id var edgeKey: Long = 0,
    @Index var fromKey: Long = 0,
    @Index var toKey: Long = 0,
    @Index var edgeTypeId: Int = EdgeType.UNKNOWN.id,
    @Index @Unique var dedupKey: String = "",   // "$fromKey:$edgeTypeId:$toKey"
    var payload: String? = null,
)
```

### 6.1 Nullability

v0.1 used `Int?` / `Long?` / `Float?` for the assertion columns. In Kotlin those are boxed types, which cost an allocation per row and complicate indexing for no benefit — these columns are meaningless on non-assertion nodes either way. Sentinel zeros (`Predicate.NONE.id`, `graphKey = 0`) carry the same information without the box.

### 6.2 `SUBJECT` and `OBJECT` are columns, not edges

This is the largest change from v0.1.

Every query in §10 begins by selecting a person's assertions. As edges, that meant: scan `KgEdge` on `toKey = X` filtered to `edgeType = SUBJECT`, collect assertion keys, then perform **one random node read per assertion**, and only then filter on predicate, dismissal and date. For a chatty contact that is hundreds of random reads before any selective filter applies.

Meanwhile v0.1's promoted columns (`predicate`, `observedAtEpochDay`, `suppressedByKey`) only served the *inverse* access path — "all assertions with predicate P" — which no query in §10 issues.

§4.3 of v0.1 already stated that `SUBJECT` is mandatory and single-valued. That makes it a functional property, not an n-ary spoke; modelling it as an edge paid reification cost for nothing. Same argument for `OBJECT`. Both are now indexed columns, and Q1 is a single index scan.

`BENEFICIARY`, `STATED_BY`, `EVIDENCED_BY` and `DERIVED_FROM` stay edges — genuinely optional and/or multi-valued.

### 6.3 `lookupKey` is namespaced and unique

ObjectBox has no composite indexes: it picks one index and filters the rest. A `lookupKey` shared across phone numbers, category slugs, ASINs and nicknames therefore both scans more than necessary *and* lets a category slug collide with someone's nickname.

Keys are namespaced by the `LookupKey` builders:

```
per:<uuid>            phone:+919...        email:alex@…
nv:alex               cat:running-shoes    brand:nike
prod:amazon:B0XXXX    produrl:<sha256>     occ:<owner>:BIRTHDAY:6-12
src:<kind>:<ref>:<postedAt>
asr:<sourceKey>:<predicate>:<subject>:<object>
```

One index, no collisions, and "resolve before create" becomes a single indexed lookup. For assertions the key doubles as the **idempotency key** (§7.5): re-ingesting a message is a uniqueness violation rather than a duplicated fact. Uniqueness is `FAIL`, never `REPLACE` — replacing would allocate a new `graphKey` and orphan every edge pointing at the old one.

### 6.4 Embeddings

`dimensions = 384`, targeting the MiniLM / bge-small class of on-device models: half the storage and index memory of 768, and product titles are short enough that the extra dimensions buy little. Changing this later means re-embedding every product.

`DOT_PRODUCT` assumes vectors are L2-normalised at write time — cheaper than cosine and equivalent for unit vectors. **Normalise before writing** or distances are silently wrong. Nulls are skipped by the index, so non-PRODUCT nodes cost nothing.

Requires ObjectBox ≥ 4.0; the project is now on **5.4.2** (was 3.7.1, which has no vector support at all).

### 6.5 Build constraints

- **All `@Entity` classes stay in one Gradle module.** The processor generates one `MyObjectBox` per module from the entities it sees; entities in two modules means two schemas, breaking the single-`BoxStore` invariant. `KgNode`/`KgEdge` therefore live in `data/db/entity/` beside `Product`, which also keeps the generated `MyObjectBox` package stable. If entities ever spread across packages, pin it with `objectbox { myObjectBoxPackage(...) }` first.
- **Still kapt, not KSP.** ObjectBox has no KSP processor yet (objectbox-java#1075), so `android.builtInKotlin=false` / `android.newDsl=false` stay in `gradle.properties`.
- The Gradle plugin is applied the classic way (`buildscript` classpath + `apply(plugin = "io.objectbox")`). Recent ObjectBox docs show a plugin-marker alias; it may work on 5.x, but the classic path is known-good here and the bump is enough change for one step.

---

## 7. Write Paths

All writes go through `GraphBuilder`, which enforces resolve-before-create, assertion reification, and one transaction per ingestion batch.

### 7.1 Contact sync

1. Upsert `PERSON` on `contactId`, falling back to phone/email match.
2. Emit `NAME_VARIANT` for display name, given name, phonetic name (`variantKind = GIVEN`).
3. Nickname expansion (Alex → Al, Lex) from a static locale-aware table; `variantKind = NICKNAME`, lower prior confidence in payload.
4. No kinship here — kinship is learned from messages (§7.2) because it is speaker-relative.

### 7.2 Message / NER ingestion

Input: one extracted mention batch per message — `(speaker, spans, timestamp, sourceRef)`.

1. Create `SOURCE`.
2. Resolve each person mention via §8.
3. Map product-noun mentions to `CATEGORY` via the taxonomy alias table. Unmapped → `CAT_UNRESOLVED`.
4. Emit `ASSERTION` with predicate from the intent classifier, `subjectKey`/`objectKey` set, role edges for beneficiary/speaker, `EVIDENCED_BY → SOURCE`, model confidence.
5. Kinship learning: possessive or vocative kinship patterns targeting a resolved person emit the corresponding `RELATED_AS_*` edge. No `NAME_VARIANT` is created (§3.1).

**Unresolvable mentions — drop the role, not the assertion.** v0.1 dropped any mention that would not resolve. But if the subject resolves and only the beneficiary does not, the assertion is still useful: "Alex is interested in running shoes" survives losing "for someone." Drop the whole assertion only when `SUBJECT` fails to resolve, since that is the one role with no meaning absent.

### 7.3 Shared URL ingestion

1. Create `SOURCE(SHARED_URL)`.
2. Resolve URL → `(platform, platformProductId, title, brand?, category?)`. Upsert `PRODUCT` on `LookupKey.product(platform, id)`, falling back to `productByUrl` when the platform id cannot be extracted. Link `INSTANCE_OF`, `MADE_BY`.
3. Emit `ASSERTION(SHARED)` with subject = sender. If accompanying text carries intent, the classifier upgrades to `RECOMMENDS` (+ `BENEFICIARY`) or `INTERESTED_IN` at write time.

### 7.4 Order import

1. `SOURCE(MANUAL_ORDER_ENTRY | EMAIL | SMS)`.
2. Upsert `PRODUCT`, `BRAND`, `INSTANCE_OF`.
3. Emit `ASSERTION(PURCHASED)` with full order attributes.
4. **Decomposition pass**, same transaction: emit derived `AFFINITY` assertions (brand, category) and `SIZE_KNOWN` if size is present; price band into AFFINITY attributes. Each links `DERIVED_FROM → PURCHASED` and `EVIDENCED_BY → SOURCE`.
5. **No suppression pass.** v0.1 walked open assertions and set `suppressedBy` on them. That is gone — suppression is computed at read time (§9.3).

**Derived confidence.** One AFFINITY per purchase, at fixed confidence. v0.1 also scaled AFFINITY confidence by evidence count while §9.2 summed relevances, which counted evidence twice and made the signal grow roughly quadratically — three Nike purchases would outrank thirty mixed signals. The sum does the reinforcing; the assertion just records one observation.

### 7.5 Idempotency

The `lookupKey` (§6.3) *is* the dedup key. For evidence it is built from the notification's stable identity — `StatusBarNotification.key` + `postTime` + kind — not from a hash of the text.

v0.1 keyed on `snippetHash`, which fails in both directions against a real `NotificationListenerService`: identical text legitimately sent twice ("yes", "ok") collapses into one source, while a summary notification that mutates in place ("3 new messages") hashes differently on every update and splits into several. Content hashing is retained only for pasted or manually entered text, which has no external identity.

---

## 8. Alias & Entity Resolution

Turning a text mention into a `PERSON` graphKey. A graph lookup, not fuzzy string matching.

For mention *m* by speaker *S* in a chat with participants *P*:

1. **Kinship first.** If `KinshipLexicon.relationFor(normalize(m))` is non-null, traverse `RELATED_AS_*` from *S* with that relation. Kinship resolves relative to the speaker, never globally.
2. **Exact variant match.** `KgNode(lookupKey = LookupKey.nameVariant(normalize(m)))` → follow `HAS_NAME_VARIANT` backwards. Exactly one PERSON → resolved.
3. **Context disambiguation.** On multiple candidates, prefer (a) chat participants *P*, then (b) highest recent-interaction score (assertions evidenced in this chat's sources, decayed).
4. **Threshold.** Still ambiguous → drop the mention. A wrong link poisons a wishlist; a dropped mention costs one signal.

**Order changed from v0.1**, which ran exact match first. With kinship terms no longer stored as name variants (§3.1) the ordering matters less, but kinship-first is still correct: a kinship word is *always* speaker-relative, and should never be allowed to fall through to a global match if someone happens to be nicknamed "Didi".

Normalisation: lowercase, strip diacritics, Indic transliteration for common kinship and name forms (भैया → bhaiya).

---

## 9. Scoring, Decay & Forgetting

Implemented in `graph/core/Relevance.kt`, free of ObjectBox types so it is unit-testable without a device — these formulas are the part of the system most likely to need tuning and the least pleasant to tune through an emulator.

### 9.1 Relevance of one assertion

```
relevance(a, now) = weight(a.predicate)          // how strong this kind of claim is
                  × a.confidence                  // how sure the extractor was
                  × 0.5 ^ (ageDays / halfLife(a.predicate))
```

Zero if the user explicitly dismissed it. Non-decaying predicates use a decay factor of 1.

### 9.2 Category score for a person

```
base(person, cat)  = Σ relevance(a) for a in assertions(subject = person,
                                                        object ∈ {cat}
                                                               ∪ products INSTANCE_OF cat
                                                               ∪ brands via AFFINITY)

adjusted(p, cat)   = base(p, cat) + λ_adj × Σ base(p, c′) for c′ RELATED_TO cat
                     + acquisitionEffect(…)                              (§9.3)

final(p, cat)      = normalize(adjusted)                                 (§9.4)
```

`λ_adj ≈ 0.3`. The adjacency term takes neighbours' **base** scores, never their adjusted ones. v0.1 wrote `score(...)` recursively on both sides while `RELATED_TO` is symmetric, so shoes → socks → shoes recursed forever; "one hop" lived in the prose but not the formula. Splitting `base` from `adjusted` puts the limit in the type signature.

Reinforcement is emergent: repeated mentions are separate assertions whose decayed relevances sum. Nothing tracks "how many times", which is what keeps assertions immutable.

### 9.3 Suppression & replenishment — one curve

For each `PURCHASED`/`OWNS` assertion on the category (or a subcategory ≤ 1 hop):

```
durable      (replenishCycleDays == null):
    effect = −CAP × 0.5 ^ (ageDays / 180)

replenishable:
    effect = CAP × (2 × sigmoid((ageDays − cycle) / 14) − 1)
```

The replenishable form is a single continuous curve from suppression through to boost, crossing zero exactly when the cycle elapses: coffee bought last week is suppressed, coffee bought two months ago is a suggestion. Durable goods only ever fade toward zero — owning a jacket is not a reason to suggest another jacket, only a weaker reason not to.

v0.1 specified this twice, incompatibly: §9.1 made `suppressedBy` a hard zero while §9.3 described a fading subtraction that inverts into a boost. Since the ingestion pass set `suppressedBy` first, relevance was already zero and could never fade back in — replenishment was unreachable. The field survives only as `dismissedByKey`, for deliberate user action ("I already have this"), which is a new fact rather than a revision of an old one.

`DISLIKES` is not part of this arithmetic. It is a hard filter applied before scoring — a disliked category never appears regardless of score.

### 9.4 Normalisation

```
final = s / (s + k),  k ≈ 3.0
```

Raw scores are sums, so they scale with how much someone talks. Without this a contact who messages daily outscores a quiet one at identical intent strength, and Q3's absolute threshold would only ever fire for the chattiest people in the address book. `k` is the raw score at which the normalised score reaches 0.5; 3.0 is a placeholder to be set from real data.

### 9.5 Compaction

A periodic WorkManager job hard-deletes:

- assertions whose relevance has been below 0.02 continuously for 90 days
- orphaned `SOURCE` nodes
- `CAT_UNRESOLVED` assertions older than 180 days — *after* exporting their mention text to the taxonomy backlog, since these are exactly the mentions that show where the taxonomy is thin
- `PURCHASED` assertions are exempt while any assertion still points at them via `DERIVED_FROM`

Deletion cascades role edges in the same transaction. This bounds store size and is the privacy story: old chatter genuinely disappears.

---

## 10. Query Patterns

**Q1 — Gift wishlist for person X.** Resolve X (§8) → index scan `subjectKey = X` filtered to `{INTERESTED_IN, SHARED, AFFINITY, SIZE_KNOWN}`, plus assertions where X is `BENEFICIARY` of `RECOMMENDS` → roll up to categories (§9.2) → apply `DISLIKES` filter and acquisition effects → return ranked categories carrying concrete PRODUCT anchors, known size/fit, brand affinities, price band and SOURCE snippets.

**Q2 — User's own wishlist.** Q1 with X = self, except `RECOMMENDS` assertions where the user is the *implicit* beneficiary (no `BENEFICIARY` edge, message directed at the user) rank first, and the affinity term is weighted higher given richer order history.

**Q3 — Occasion surfacing.** Daily: scan `HAS_OCCASION` assertions with an occasion date in the next 21 days → run Q1 per person → notify on `topMargin` (§9.4), not on the top score alone. A person with one standout category is actionable; a person with six equally plausible ones is not, however high the scores. Note the year boundary: 21 days from 20 Dec wraps into January.

**Q4 — "Similar to what I ordered."** From `PURCHASED`: collect (brand, category, price band) → expand one `RELATED_TO` hop → subtract suppressed → hand to the shopping-platform search intent. `DERIVED_FROM` supplies the explanation string.

**Q5 — Entity lookup.** §8 exposed directly; the extraction pipeline calls it per mention.

**Embedding hook.** Once a model ships, PRODUCT title embeddings add a semantic leg to Q4 via `nearestNeighbors`. No query above *depends* on embeddings — ship without them.

---

## 11. Worked Example

WhatsApp, from Alex, 2026-08-10: *"bhaiya, dekho ye — perfect for Amy"* + Amazon link → Nike running shoes, ₹4,999.

Writes:

- `SOURCE s1`, `lookupKey = src:1:<sbn.key>:<postTime>`
- `PRODUCT p1`, `lookupKey = prod:amazon:B0XXXX`; `p1 INSTANCE_OF cat:running-shoes`; `p1 MADE_BY brand:nike`
- `ASSERTION a1` — `predicate = RECOMMENDS`, `confidence = 0.86`, `subjectKey = alex`, `objectKey = p1`, `lookupKey = asr:<s1>:2:<alex>:<p1>`
  - `a1 BENEFICIARY → amy` (resolved via name variant "Amy")
  - `a1 EVIDENCED_BY → s1`
- "bhaiya", vocative, speaker = Alex → `alex --RELATED_AS_BROTHER--> user` ("the user is Alex's brother"). No `NAME_VARIANT` node.

Query effect: Amy's Q1 gains `running-shoes` weight anchored on p1 with Nike affinity, contributing `0.90 × 0.86 = 0.77` at age zero. The user's own list is untouched (beneficiary ≠ user). At 120 days the contribution halves. If an order for p1 is later observed for Amy, §9.3 subtracts against the category rather than zeroing a1 — so the signal that Amy liked running shoes survives her buying one pair, which is what makes the second pair suggestible a year later.

---

## 12. Migrating the existing `Product` entity

Unaddressed in v0.1. `Product` today is a denormalised PERSON + SOURCE + ASSERTION + PRODUCT in one row: it carries `sourceContact`, `messageContent`, `category`, `price`, `status`, `isPurchased`, `relevanceScore`.

Proposed: **keep `Product` as a read model, not a source of truth.**

- The graph becomes the write target for all new ingestion.
- A one-time backfill maps each existing row to `SOURCE` + `PRODUCT` + `ASSERTION(INTERESTED_IN | PURCHASED)` with `subjectKey` resolved from `sourceContact`, `confidence = 0.5` (these came from regex, not a model), and `observedAt` from `capturedAt`.
- `Product` rows are then regenerated from graph queries for the UI, which keeps existing screens working against a flat shape while the graph carries the semantics.

Rows whose `sourceContact` cannot be resolved to a `PERSON` keep working as `Product` rows and are simply not represented in the graph. There is no UI depending on the graph yet, so the backfill can be a debug action rather than a startup migration.

---

## 13. Build Order

Counting v0.1: 10 node types, 9 predicates, ~14 edge types, an 80-leaf curated taxonomy with synonym tables and adjacency, an intent classifier, an NER pipeline and a URL-resolution service — against a codebase with one entity, a regex parser and no UI.

The schedule risk is §14 item 2, the taxonomy: it is the input every score in §9 depends on, it cannot be bought, and nothing works without it.

**v0 — prove the loop end to end:**

| Keep | Defer |
|---|---|
| PERSON, PRODUCT, SOURCE, ASSERTION | CATEGORY taxonomy, BRAND, OCCASION, NAME_VARIANT |
| `INTERESTED_IN`, `SHARED`, `PURCHASED` | RECOMMENDS, AFFINITY, DISLIKES, SIZE_KNOWN, HAS_OCCASION |
| `subjectKey`/`objectKey`, `EVIDENCED_BY` | BENEFICIARY, STATED_BY, kinship, RELATED_AS |
| One global half-life | Per-predicate tuning, replenishment, adjacency |
| Contact-name matching (already built) | §8's full resolution ladder |
| Q1 | Q2–Q5 |

Categories in v0: reuse the free-text categories `NotificationParser` already emits (Electronics / Clothing / Books / Home / Other). A bad taxonomy, but a real one — and it measures how much the curated taxonomy actually buys before 80 nodes get authored.

Every deferred item then has a live query to justify itself against, which is the admission rule §5 already applies to predicates, extended to the schema as a whole.

---

## 14. Open Items

1. ~~Embedding dimension~~ — **resolved: 384, `DOT_PRODUCT`, normalise at write time** (§6.4). Model selection still open.
2. **Taxonomy v1 authoring** — ~80 leaves + synonym tables + `RELATED_TO` adjacency + replenish cycles. Owner: TBD. Highest schedule risk; §13 defers it out of v0.
3. Intent-classifier label set must be frozen against §5 predicates before extraction training.
4. `NORMALIZATION_K`, `ACQUISITION_CAP` and `λ_adj` are placeholders awaiting real data.
5. Ghost persons (unresolved mentions) — revisit post-v0 if dropped-signal volume is high.
6. Sibling/parent inverse edges need a gender signal before `EdgeType.inverse` can return anything for them.
7. Multi-device / export — `GraphId` UUIDs make the graph portable by design; sync protocol out of scope.
8. ObjectBox 5.4.2 against AGP 9.2.1 is unverified — the plugin documents AGP 8.1+ and has not been tested this far forward.
