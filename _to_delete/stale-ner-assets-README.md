# NER model assets

Drop three files here. All are loaded at runtime by `com.example.app.wishlist.ml`.

| File | What it is |
|---|---|
| `model.tflite` | The token-classification model. Input `[1, maxSequenceLength]` int32 token ids (plus optional attention-mask / token-type inputs); output `[1, maxSequenceLength, labels.size]` float32 logits. |
| `vocab.txt` | The WordPiece vocabulary the model was trained with — one token per line, line number = token id. Must include `[PAD] [UNK] [CLS] [SEP]`. |
| `ontology.json` | Label set and tokenizer settings. See the checked-in template. |

## The `labels` array is a contract

Index in `labels` **is** the model's output label id. If the exported model emits id 5
for `B-COLOR`, then `labels[5]` must be `"B-COLOR"`. Get this wrong and inference
silently produces confident nonsense rather than failing — `NerOntology` validates BIO
well-formedness on load, but it cannot detect a permutation.

`maxSequenceLength` must equal the model's actual input length. `NerOntology` checks it
against the tensor shape at load time and throws if they disagree.

## What this model does not provide

The tag set above is *attribute* NER: it labels what a message is about, not what the
message is doing. The knowledge graph also needs:

- **a predicate** — is this wanting, recommending, or just forwarding? Supplied for now
  by `IntentHeuristic`, a lexical stopgap. This is the single biggest accuracy gap in
  the pipeline; a real intent classifier (or an extra sequence-classification head on
  this model) should replace it.
- **a beneficiary** — "get this for Amy" needs the person *role*, not just the person
  span. `B-PERSON` gives the span; the heuristic guesses the role.

## Model is not committed

`model.tflite` and `vocab.txt` are gitignored — they are large binaries. Fetch them from
wherever the trained artefacts live and place them here before building, or the app
throws `NerUnavailableException` on first message and falls back to the regex parser.
