# NER → Knowledge Graph Ingestion Pipeline

**Conforms to:** `android_inference_guide.md` · **Status:** written, not compiled.

---

## Assets, as shipped

| File | Size | Role |
|---|---|---|
| `assets/ner_model_quantized.tflite` | 110 MB | INT8 token classifier |
| `assets/vocab.txt` | 30,522 lines | `bert-base-uncased` WordPiece vocabulary |
| `assets/tags.txt` | 17 lines | BIO labels; line index = model label id |

`vocab.txt` is confirmed uncased — `[PAD]`=0, `[UNK]`=100, `[CLS]`=101, `[SEP]`=102,
matching the guide's worked example, and only five entries contain uppercase. So
`doLowerCase = true`.

**Eight entity types:** `PRODUCT`, `CATEGORY`, `COLOR`, `SIZE`, `BUDGET`, `RECIPIENT`,
`OCCASION`, `TIME`. There is no `BRAND` tag, so no `BRAND` nodes are written and the
graph's `MADE_BY` edge stays unused until brand extraction exists.

## Flow

```
WhatsApp notification
  │
  ├─ WhatsAppNotificationListener      binder thread: extract sender/text/key, no work
  │     └─ scope.launch(Dispatchers.IO)
  │
  ├─ MessageIngestionPipeline.ingest()
  │     ├─ ShoppingNerModel.infer()
  │     │     ├─ WordPieceTokenizer      → 3 × [1,128] int32 + char spans
  │     │     ├─ Interpreter             → [1,128,17] float32 logits
  │     │     └─ BioDecoder              → argmax, softmax, merge BIO runs
  │     ├─ IntentHeuristic               → predicate + beneficiary
  │     └─ GraphRepository.write { }     one transaction
  │
  └─ legacy Product row + user notification
```

| File | Role |
|---|---|
| `ml/NerTagSet.kt` | Loads `tags.txt`; BIO helpers, validation, `NerConfig` |
| `ml/WordPieceTokenizer.kt` | WordPiece with **character offsets** into the original text |
| `ml/BioDecoder.kt` | Argmax + softmax + BIO run merging |
| `ml/ShoppingNerModel.kt` | `Interpreter` wrapper; process-wide, lock-serialised |
| `ml/NerResult.kt` | `EntityMention`, `NerResult` |
| `graph/ingest/IntentHeuristic.kt` | Predicate + beneficiary |
| `graph/ingest/MessageIngestionPipeline.kt` | Orchestration and graph writes |
| `graph/storage/GraphRepository.kt` | Resolve-before-create upserts, edges, transactions |

---

## Where the implementation departs from the guide, and why

### 1. Input tensors are `[1, 128]`, not `[128]` — the guide's snippet would throw

Guide, Step B:

```kotlin
val inputIds = IntArray(128) { ... }
val inputs = arrayOf(inputIds, attentionMask, tokenTypeIds)
```

A bare `IntArray(128)` is shape `[128]`. The model declares `[1, 128]`, and the
Interpreter validates shape, not element count — this raises
`IllegalArgumentException: Cannot copy to a TensorFlowLite tensor (serving_default_input_ids:0) with 128 bytes from a Java Buffer with ...`
The batch dimension is not optional.

Implemented as:

```kotlin
private val inputIds = Array(1) { IntArray(maxSequenceLength) }
```

Everything else about Step B is as written, including the input order
(`input_ids`, `attention_mask`, `token_type_ids`), the output buffer shape, and
`runForMultipleInputsOutputs`.

### 2. Entity text comes from character offsets, not `##` stitching

Guide, Step C.3: *"If a token is a subword (e.g. starts with `##`), strip the `##` and
append it to the previous token's string."*

That reconstructs the text from the **tokenizer's** view, which is lowercased and
punctuation-split. `"Nike Air Max"` comes back as `"nike air max"`; `"Levi's 501"` as
`"levi ' s 501"`. Since this text is stored in the graph and eventually shown back to the
user as the reason for a suggestion, it should be what they actually wrote.

So the tokenizer records, for every WordPiece token, the character range it came from in
the original string, and entity text is sliced out of that string. Same entities, original
spelling.

This is also why accent stripping runs character by character: NFD-normalising the whole
string at once shifts every index after the first accented character, and the offsets
silently stop pointing at the right words.

### 3. Dimensions are read from the model, not hardcoded to 128 and 17

The guide states `[1, 128]` and 17 tags. Both are read from
`interpreter.getInputTensor(0).shape()` and `getOutputTensor(0).shape()` at load, and
cross-checked against `tags.txt`. A retrained model with a different sequence length or
tag count then fails at load with both numbers named, instead of silently misaligning
every label. The guide's numbers become an assertion rather than an assumption.

### 4. Confidence is a softmax probability, not just an argmax

Step C.1 only needs the argmax. But the winning score lands on `KgNode.confidence` and is
multiplied into relevance scoring, where an unbounded logit is meaningless — a model with
larger activations would dominate every ranking regardless of correctness. `BioDecoder`
computes the softmax probability of the winning label (max-subtracted for stability) and
drops entities below `MIN_ENTITY_CONFIDENCE = 0.5`.

### 5. `I-` without a preceding `B-` starts a new entity

Not covered by the guide. Models emit this regularly at truncation boundaries and on rare
types; discarding it loses real spans. The cost is occasional over-segmentation of one
entity into two, which the graph handles far better (two assertions that sum) than a
missing one.

### 6. GPU delegate not added

The guide marks `tensorflow-lite-gpu` optional. Skipped: the model is INT8, which GPU
delegates handle poorly, and this runs in a background service where delegate
availability varies by device. Four CPU threads instead. Revisit only if profiling shows
a need.

### 7. Model is memory-mapped out of the APK

`androidResources { noCompress += "tflite" }` keeps AAPT from compressing the asset, so
`FileChannel.map` works and 110 MB stays off the Java heap. Without it the Interpreter
would have to inflate the whole model into memory — an `OutOfMemoryError` in a background
service rather than a slow load.

---

## The predicate still does not come from the model

`RECIPIENT` turned out to be a real gain here: the model names *who something is for*
directly, so beneficiary resolution no longer guesses from `"for <Name>"` phrasing. A
named recipient who is not the sender is also strong evidence of gift intent, so it routes
to `RECOMMENDS` with a `BENEFICIARY` edge even when no cue phrase appears — `"red dress
for mom"` has no cue word at all. `"for me"` routes to `INTERESTED_IN`; `"for you"` marks
the reader as beneficiary.

What is still missing is the predicate itself: wanting vs recommending vs already owning
vs forwarding. `IntentHeuristic` supplies it from cue phrases ("i want", "just bought",
"perfect for", plus some Hinglish) and will be wrong on sarcasm, negation it does not
enumerate, and indirect phrasing. Two mitigations:

- heuristic inferences carry confidence 0.35–0.65, well below a trained classifier's, and
  that multiplies into assertion confidence and then into relevance — the graph
  systematically under-weights them
- assertions record `intentSource: "heuristic"` and the matched cue, so they can be found
  and re-scored later

**The clean fix is a second head on the same model** — sequence classification over the
nine predicates in §5 of the graph spec. One extra output tensor, and `IntentHeuristic`
deletes entirely. If you are retraining anyway, this is the highest-value addition.

---

## Two things to deal with

### The 110 MB model is staged in git

`git status` shows `A app/src/main/assets/ner_model_quantized.tflite`. Committing it puts
110 MB in history permanently — git keeps every version, so each retrain adds another
110 MB that no later commit can remove. `.gitignore` now excludes it, but that does not
affect an already-staged file:

```
git rm --cached app/src/main/assets/ner_model_quantized.tflite
```

Left staged deliberately rather than unstaged, since it is your call. Worth considering
Git LFS, or distributing the model out of band and downloading on first run — which also
sidesteps the Play Store's 150 MB APK limit that a 110 MB asset comes uncomfortably close
to.

### `PRODUCT` spans are stored as fine-grained categories

The tag set has `PRODUCT` as a text mention (`"handbag"`, `"running shoes"`), not a
resolved listing — there is no platform id, and only a URL can supply one. So a product
mention without a link is written as a `CATEGORY` node: it behaves like one for scoring
and rollups. Everything written this way carries `taxonomyVersion: "extracted-v0"` and
`extractedAs: "PRODUCT"`, so the taxonomy mapping step can find and re-point it.

When a URL *is* present, a real `PRODUCT` node is created keyed by URL hash, with the
`PRODUCT` span as its title, and `INSTANCE_OF` linking it to the category.

---

## Before first build

1. **Verify the input tensor order.** The wrapper writes `input_ids`, `attention_mask`,
   `token_type_ids` by position, per the guide. If the exported signature orders them
   differently, `infer()` is the single place to fix — and it will fail loudly at the
   first message rather than produce wrong output, since the tensors have distinct shapes
   only if the model declares them so. Worth confirming against
   `interpreter.getInputTensor(i).name()` on first run; the load path already logs the
   resolved dimensions.
2. **Build.** Nothing here has been compiled — no Android SDK in the session that wrote
   it. Expect ObjectBox to regenerate `app/objectbox-models/default.json` with UIDs for
   `KgNode`/`KgEdge`; commit that file.
3. **Watch first-load time.** Mapping and preparing 110 MB is not instant. The model is
   warmed in `onCreate` rather than on the first message, so the cost lands before any
   message arrives, but confirm it on a real device.

## Worth adding next

- **JVM unit tests for the tokenizer.** Offset correctness is the most likely thing to be
  subtly wrong and the least likely to be noticed — it fails as slightly-off entity text,
  never as an exception. A dozen cases with accents, emoji, Devanagari and punctuation
  would cover it, and none of it needs a device.
- **An intent head on the model**, replacing `IntentHeuristic`.
- **URL resolution** to `(platform, platformProductId, title, brand)`, so the same Amazon
  link shared by two people collapses to one node.
- **Category taxonomy mapping**, re-pointing every `extracted-v0` node.
