# Android Deployment Guide: Offline E-Commerce NER

This document provides complete instructions for integrating your offline, quantized Named Entity Recognition (NER) model natively into your Android application.

## 1. Asset Setup

Copy the following three exact files from your `/dataset/generated/litert/` directory into your Android project's `src/main/assets/` directory:

1. `ner_model_quantized.tflite` (105 MB) — The INT8 quantized model execution graph.
2. `vocab.txt` — The dictionary mapping strings/subwords to Token IDs (used by the tokenizer).
3. `tags.txt` — The dictionary mapping numeric model output IDs back to string labels (e.g., `B-COLOR`, `O`).

## 2. Gradle Dependencies

Add the official LiteRT (TensorFlow Lite) and text support libraries to your `app/build.gradle` or `build.gradle.kts`:

```groovy
dependencies {
    // Core LiteRT Engine
    implementation 'org.tensorflow:tensorflow-lite:2.16.1' // Or latest
    // Optional: For hardware acceleration (NNAPI / GPU)
    implementation 'org.tensorflow:tensorflow-lite-gpu:2.16.1'
}
```

## 3. The Inference Pipeline

Because the model operates entirely on mathematical matrices (tensors), you cannot directly pass a raw `String` or expect a `JSON` back. You must implement a three-step pipeline: **Tokenization**, **Inference**, and **Decoding**.

### Step A: Tokenization (Preparing the Input)

The model expects exactly **3 inputs** of shape `[1, 128]` (Batch Size 1, Max Sequence Length 128), all using the `Int32` data type.

You must implement a standard **BERT WordPiece Tokenizer** in Kotlin. 
*(Note: You can use existing open-source Kotlin BERT tokenizers, or port the logic from Python. You will feed it the `vocab.txt` file).*

Given an input string like `"Red dress for mom"`, your tokenizer should produce:

1. **`input_ids`** `[1, 128]`: 
   * `[101, 2412, 3814, 2005, 3566, 102, 0, 0, 0, ...]`
   * *101 is `[CLS]`, 102 is `[SEP]`, followed by `0` padding to fill out the 128 length.*
2. **`attention_mask`** `[1, 128]`:
   * `[1, 1, 1, 1, 1, 1, 0, 0, 0, ...]`
   * *1 for real tokens, 0 for padding.*
3. **`token_type_ids`** `[1, 128]`:
   * `[0, 0, 0, 0, 0, 0, 0, 0, 0, ...]`
   * *All zeros (used for sentence pairs, not relevant here).*

### Step B: Running the Model

Load the `.tflite` model from your assets into a `org.tensorflow.lite.Interpreter`.

```kotlin
// Prepare input arrays
val inputIds = IntArray(128) { ... }
val attentionMask = IntArray(128) { ... }
val tokenTypeIds = IntArray(128) { ... }

// Prepare input map mapped to the TFLite signature
val inputs = arrayOf(inputIds, attentionMask, tokenTypeIds)

// Prepare output buffer
// Shape: [1, 128, 17] -> [Batch, Sequence Length, Number of Tags]
val outputLogits = Array(1) { Array(128) { FloatArray(17) } }
val outputs = mutableMapOf<Int, Any>(0 to outputLogits)

// Run the model (Execute on device)
interpreter.runForMultipleInputsOutputs(inputs, outputs)
```

### Step C: Decoding (Parsing the Output)

The model output is `outputLogits`: an array of 128 rows, where each row contains 17 probability scores.

1. **Argmax:** For every token from index `1` to `N` (ignore the `[CLS]` and `[SEP]` boundaries), iterate through its 17 float scores and find the index with the highest value. Let's say the highest value is at index `5`.
2. **Lookup Tag:** Open `tags.txt`. Read line `5`. It says `B-COLOR`. You now know that token is `B-COLOR`.
3. **Merge Subwords:** 
   * Ignore tokens tagged `O` (Outside / No Entity).
   * If a token is a subword (e.g., starts with `##`), strip the `##` and append it to the previous token's string.
   * Group contiguous `B-` and `I-` tags of the same category together.

### Example Final Output Transformation
If the raw text was: `"red handbag for mom"`
The tokenizer outputs: `["red", "hand", "##bag", "for", "mom"]`
The model predicts tags: `[B-COLOR, B-CATEGORY, I-CATEGORY, O, B-RECIPIENT]`

Your Kotlin decoding logic stitches this into:
```json
{
  "COLOR": "red",
  "CATEGORY": "handbag",
  "RECIPIENT": "mom"
}
```
