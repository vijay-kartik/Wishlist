package com.example.app.wishlist.ml

import android.content.Context
import android.content.res.AssetFileDescriptor
import org.tensorflow.lite.Interpreter
import timber.log.Timber
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.system.measureTimeMillis

/** Text in, typed entity spans out. Implemented so the runtime can be swapped. */
interface NerEngine {
    fun infer(text: String): NerResult
    fun close()
}

/**
 * On-device shopping NER, per `android_inference_guide.md`.
 *
 * Three int32 inputs of shape `[1, maxLen]` — `input_ids`, `attention_mask`,
 * `token_type_ids` — and one float32 output of shape `[1, maxLen, numTags]`, run through
 * `Interpreter.runForMultipleInputsOutputs`.
 *
 * ## Lifecycle
 *
 * One instance per process, created lazily and kept warm. The model is 110 MB; building
 * a second Interpreter while the first is alive would roughly double native memory in a
 * process Android is already willing to kill. [release] is what makes repeated service
 * restarts safe.
 *
 * `Interpreter` is not safe for concurrent use and notifications arrive on binder
 * threads, so [infer] serialises on a lock. The input and output buffers are allocated
 * once and reused, since per-message allocation of a `[1, 128, 17]` array is pure churn.
 */
class ShoppingNerModel private constructor(
    private val interpreter: Interpreter,
    private val tokenizer: WordPieceTokenizer,
    private val tags: NerTagSet,
    private val maxSequenceLength: Int,
    inputLayout: InputLayout,
) : NerEngine {

    private val lock = ReentrantLock()
    private var closed = false

    // Shape [1, maxLen] per input, as the model declares. A bare IntArray(maxLen) is
    // shape [maxLen] and the Interpreter rejects it — the batch dimension is not optional.
    private val inputIds = Array(1) { IntArray(maxSequenceLength) }
    private val attentionMask = Array(1) { IntArray(maxSequenceLength) }
    private val tokenTypeIds = Array(1) { IntArray(maxSequenceLength) }
    private val outputLogits = Array(1) { Array(maxSequenceLength) { FloatArray(tags.size) } }

    /**
     * The three buffers arranged in the order *this model* declares its inputs.
     *
     * Feeding them in the guide's written order (input_ids, attention_mask,
     * token_type_ids) is a coin flip: the TFLite converter orders signature inputs by
     * the exported SavedModel's own convention, which is frequently not that. All three
     * are int32 `[1, maxLen]`, so a wrong order is accepted silently by the Interpreter
     * and simply produces nonsense — most visibly when token_type_ids (all zeros) lands
     * in attention_mask, masking the entire sequence so every token decodes as `O`.
     * Resolved by tensor name at load time instead; see [InputLayout.resolve].
     */
    private val orderedInputs: Array<Any> = arrayOfNulls<Any>(EXPECTED_INPUTS).apply {
        this[inputLayout.inputIds] = inputIds
        this[inputLayout.attentionMask] = attentionMask
        this[inputLayout.tokenTypeIds] = tokenTypeIds
    }.requireNoNulls()

    override fun infer(text: String): NerResult {
        if (text.isBlank()) return NerResult.empty(text)

        return lock.withLock {
            check(!closed) { "ShoppingNerModel has been closed" }

            val tokenized = tokenizer.encode(text)

            val elapsed = measureTimeMillis {
                tokenized.tokenIds.copyInto(inputIds[0])
                tokenized.attentionMask.copyInto(attentionMask[0])
                tokenized.tokenTypeIds.copyInto(tokenTypeIds[0])

                interpreter.runForMultipleInputsOutputs(
                    orderedInputs,
                    mutableMapOf<Int, Any>(0 to outputLogits),
                )
            }

            // Batch dimension stripped; the decoder works in [token][label].
            val entities = BioDecoder.decode(outputLogits[0], tokenized, tags, text)

            // "0 entities" is otherwise indistinguishable between a model that predicted
            // nothing, a decoder that dropped everything on the confidence floor, and a
            // tokenizer that produced no usable spans. Debug builds only — Timber has no
            // tree planted in release, so this costs a null check there.
            if (entities.isEmpty()) {
                Timber.d(
                    "NER found nothing in %s\n%s",
                    text,
                    BioDecoder.describePredictions(outputLogits[0], tokenized, tags),
                )
            }

            if (tokenized.truncated) {
                Timber.d("NER input truncated at %d tokens", maxSequenceLength)
            }

            NerResult(
                sourceText = text,
                entities = entities,
                truncated = tokenized.truncated,
                inferenceMillis = elapsed,
            )
        }
    }

    override fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            runCatching { interpreter.close() }
                .onFailure { Timber.w(it, "Error closing NER interpreter") }
        }
    }

    companion object {
        const val MODEL_ASSET = "ner_model_quantized.tflite"

        /**
         * Four threads is a reasonable default for a BERT-sized graph on a mid-range
         * phone. NNAPI and GPU delegates are deliberately not used: the model is INT8,
         * which GPU delegates handle poorly, and NNAPI's device coverage is too uneven to
         * depend on inside a background service. Revisit only if profiling shows a need.
         */
        private const val NUM_THREADS = 4

        @Volatile
        private var instance: ShoppingNerModel? = null

        /**
         * Returns the process-wide instance, loading it on first call.
         *
         * Call off the main thread — mapping and preparing a 110 MB model is slow. Throws
         * [NerUnavailableException] when the assets are missing so callers can fall back
         * to the regex parser rather than dropping the message.
         */
        fun getOrCreate(context: Context): ShoppingNerModel =
            instance ?: synchronized(this) {
                instance ?: create(context.applicationContext).also { instance = it }
            }

        private fun create(context: Context): ShoppingNerModel {
            try {
                val tags = NerTagSet.load(context.assets)
                Timber.d("NER: tags loaded (%d), mapping model...", tags.size)

                val modelBuffer = loadModelFile(context, MODEL_ASSET)
                Timber.d("NER: model mapped (%d bytes), creating Interpreter...", modelBuffer.capacity())

                val interpreter = Interpreter(
                    modelBuffer,
                    Interpreter.Options().apply { numThreads = NUM_THREADS },
                )

                val inputLayout = InputLayout.resolve(interpreter)

                // Dimensions come from the model, not from the guide's prose. A retrained
                // model with a different sequence length or tag count then fails here with
                // both numbers named, instead of silently misaligning labels.
                val inputShape = interpreter.getInputTensor(0).shape()
                val outputShape = interpreter.getOutputTensor(0).shape()

                if (interpreter.inputTensorCount != EXPECTED_INPUTS) {
                    interpreter.close()
                    throw NerConfigurationException(
                        "Model declares ${interpreter.inputTensorCount} inputs, expected " +
                            "$EXPECTED_INPUTS (input_ids, attention_mask, token_type_ids)"
                    )
                }
                if (inputShape.size != 2 || outputShape.size != 3) {
                    interpreter.close()
                    throw NerConfigurationException(
                        "Unexpected tensor ranks: input=${inputShape.toList()} " +
                            "output=${outputShape.toList()}"
                    )
                }

                val maxSequenceLength = inputShape[1]
                if (outputShape[1] != maxSequenceLength) {
                    interpreter.close()
                    throw NerConfigurationException(
                        "Input sequence length $maxSequenceLength does not match output " +
                            "sequence length ${outputShape[1]}"
                    )
                }
                if (outputShape[2] != tags.size) {
                    interpreter.close()
                    throw NerConfigurationException(
                        "Model emits ${outputShape[2]} labels but ${NerTagSet.ASSET_PATH} " +
                            "has ${tags.size} tags. They must be exported together."
                    )
                }

                val tokenizer = WordPieceTokenizer.fromAssets(context.assets, maxSequenceLength)

                Timber.i(
                    "NER model loaded: maxLen=%d tags=%d types=%s",
                    maxSequenceLength, tags.size, tags.entityTypes().sorted(),
                )
                return ShoppingNerModel(
                    interpreter, tokenizer, tags, maxSequenceLength, inputLayout,
                )
            } catch (e: NerConfigurationException) {
                throw e
            } catch (t: Throwable) {
                // Throwable, not Exception: a broken runtime dependency surfaces as
                // NoClassDefFoundError / UnsatisfiedLinkError, which are Errors and
                // would otherwise escape this handler unlogged.
                Timber.e(t, "NER model load failed (%s)", t.javaClass.name)
                throw NerUnavailableException("Could not load the NER model from assets", t)
            }
        }

        private const val EXPECTED_INPUTS = 3

        /**
         * Which positional input slot each of the three tensors occupies in this model.
         *
         * Resolved from `getInputTensor(i).name()` rather than assumed, because the
         * export order is not part of any contract and a mismatch cannot be detected at
         * runtime — the tensors are all int32 `[1, maxLen]`, so the Interpreter accepts
         * any permutation and quietly returns garbage.
         */
        internal data class InputLayout(
            val inputIds: Int,
            val attentionMask: Int,
            val tokenTypeIds: Int,
        ) {
            companion object {
                fun resolve(interpreter: Interpreter): InputLayout {
                    val names = (0 until interpreter.inputTensorCount)
                        .map { interpreter.getInputTensor(it).name() }

                    // Matched on the full tensor name, not a loose "ids" suffix: the
                    // exported names carry a wrapper ("serving_default_input_ids:0"),
                    // and the three full names are mutually non-overlapping, so order
                    // of resolution does not matter.
                    fun slotOf(needle: String): Int =
                        names.indexOfFirst { it.contains(needle, ignoreCase = true) }
                            .also {
                                if (it < 0) throw NerConfigurationException(
                                    "Model has no input named '$needle'. Inputs are $names. " +
                                        "The exported signature must expose input_ids, " +
                                        "attention_mask and token_type_ids by name."
                                )
                            }

                    val layout = InputLayout(
                        inputIds = slotOf("input_ids"),
                        attentionMask = slotOf("attention_mask"),
                        tokenTypeIds = slotOf("token_type_ids"),
                    )
                    Timber.i("NER input layout resolved from names %s -> %s", names, layout)
                    return layout
                }
            }
        }

        /**
         * Memory-maps the model straight out of the APK.
         *
         * Mapping rather than reading keeps 110 MB off the Java heap — the pages stay in
         * the file cache and the kernel evicts them under pressure. This only works
         * because `androidResources { noCompress += "tflite" }` in app/build.gradle.kts
         * stops AAPT compressing the asset; a compressed asset cannot be mapped and would
         * have to be inflated onto the heap instead.
         */
        private fun loadModelFile(context: Context, assetName: String): MappedByteBuffer {
            val fd: AssetFileDescriptor = context.assets.openFd(assetName)
            return fd.use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use { input ->
                    input.channel.map(
                        FileChannel.MapMode.READ_ONLY,
                        descriptor.startOffset,
                        descriptor.declaredLength,
                    )
                }
            }
        }

        /** Releases the process-wide instance. Safe to call when nothing was loaded. */
        fun release() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }
    }
}
