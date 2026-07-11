package com.tetraploid.joyforold.offline.nlu

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONArray
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.exp

data class EncoderConfig(
    val modelType: String,
    val featureDim: Int,
    val minNgram: Int,
    val maxNgram: Int,
    val maxLength: Int,
    val clsTokenId: Long,
    val sepTokenId: Long,
    val padTokenId: Long,
    val unkTokenId: Long,
    val unkToken: String,
    val autoExecuteThreshold: Float,
    val clarifyThreshold: Float,
    val marginThreshold: Float,
    val version: Int,
    val baseModel: String?,
) {
    val isTransformer: Boolean get() = modelType.equals("transformer", ignoreCase = true)

    companion object {
        fun fromJson(raw: String): EncoderConfig {
            val json = org.json.JSONObject(raw)
            return EncoderConfig(
                modelType = json.optString("model_type", "hash"),
                featureDim = json.optInt("feature_dim", IntentFeatureEncoder.DEFAULT_FEATURE_DIM),
                minNgram = json.optInt("min_ngram", IntentFeatureEncoder.DEFAULT_MIN_NGRAM),
                maxNgram = json.optInt("max_ngram", IntentFeatureEncoder.DEFAULT_MAX_NGRAM),
                maxLength = json.optInt("max_length", 64),
                clsTokenId = json.optLong("cls_token_id", 101L),
                sepTokenId = json.optLong("sep_token_id", 102L),
                padTokenId = json.optLong("pad_token_id", 0L),
                unkTokenId = json.optLong("unk_token_id", 100L),
                unkToken = json.optString("unk_token", "[UNK]"),
                autoExecuteThreshold = json.optDouble("auto_execute_threshold", 0.72).toFloat(),
                clarifyThreshold = json.optDouble("clarify_threshold", 0.45).toFloat(),
                marginThreshold = json.optDouble("margin_threshold", 0.12).toFloat(),
                version = json.optInt("version", 1),
                baseModel = json.optString("base_model").ifBlank { null },
            )
        }
    }
}

data class IntentPrediction(
    val intent: String,
    val confidence: Float,
    val topAlternatives: List<Pair<String, Float>> = emptyList(),
)

class OnnxIntentClassifier private constructor(
    private val labels: List<String>,
    private val config: EncoderConfig,
    private val session: OrtSession,
    private val env: OrtEnvironment,
    private val tokenizer: BertWordPieceTokenizer?,
) : AutoCloseable {
    fun predict(text: String): IntentPrediction? {
        if (text.isBlank() || labels.isEmpty()) return null
        val logits = if (config.isTransformer) {
            predictTransformer(text)
        } else {
            predictHash(text)
        } ?: return null

        val probs = softmax(logits)
        if (probs.isEmpty()) return null
        val ranked = probs.indices
            .map { index -> labels.getOrElse(index) { "unknown" } to probs[index] }
            .sortedByDescending { it.second }
        val top = ranked.first()
        return IntentPrediction(
            intent = top.first,
            confidence = top.second,
            topAlternatives = ranked.drop(1).take(2),
        )
    }

    fun labels(): List<String> = labels

    fun config(): EncoderConfig = config

    override fun close() {
        session.close()
        env.close()
    }

    private fun predictTransformer(text: String): FloatArray? {
        val bertTokenizer = tokenizer ?: return null
        val encoded = bertTokenizer.encode(text)
        val shape = longArrayOf(1L, config.maxLength.toLong())

        val idsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(encoded.inputIds), shape)
        val maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(encoded.attentionMask), shape)
        return idsTensor.use { ids ->
            maskTensor.use { mask ->
                val inputs = buildInputMap(ids, mask)
                session.run(inputs).use { output ->
                    extractLogits(output)
                }
            }
        }
    }

    private fun buildInputMap(
        idsTensor: OnnxTensor,
        maskTensor: OnnxTensor,
    ): Map<String, OnnxTensor> {
        val names = session.inputNames.toSet()
        val map = linkedMapOf<String, OnnxTensor>()
        when {
            "input_ids" in names -> map["input_ids"] = idsTensor
            else -> map[session.inputNames.first()] = idsTensor
        }
        if ("attention_mask" in names) {
            map["attention_mask"] = maskTensor
        }
        return map
    }

    private fun predictHash(text: String): FloatArray? {
        val features = IntentFeatureEncoder.encode(
            text = text,
            featureDim = config.featureDim,
            minNgram = config.minNgram,
            maxNgram = config.maxNgram,
        )
        val inputName = session.inputNames.first()
        val shape = longArrayOf(1L, config.featureDim.toLong())
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(features), shape)
        return tensor.use { input ->
            session.run(mapOf(inputName to input)).use { output ->
                extractLogits(output)
            }
        }
    }

    private fun extractLogits(output: OrtSession.Result): FloatArray? {
        val value = output[0].value
        return when (value) {
            is Array<*> -> {
                when (val row = value.firstOrNull()) {
                    is FloatArray -> row
                    is Array<*> -> (row.firstOrNull() as? FloatArray)
                    else -> null
                }
            }
            is FloatArray -> value
            else -> null
        }
    }

    private fun softmax(logits: FloatArray): FloatArray {
        if (logits.isEmpty()) return logits
        val max = logits.max()
        val expValues = DoubleArray(logits.size) { index ->
            exp((logits[index] - max).toDouble())
        }
        val sum = expValues.sum()
        if (sum <= 0.0) return FloatArray(logits.size) { 1f / logits.size }
        return FloatArray(logits.size) { index -> (expValues[index] / sum).toFloat() }
    }

    companion object {
        fun create(
            modelBytes: ByteArray,
            labelsJson: String,
            configJson: String,
            vocabLines: List<String>? = null,
        ): OnnxIntentClassifier {
            val labels = parseLabels(labelsJson)
            val config = EncoderConfig.fromJson(configJson)
            val env = OrtEnvironment.getEnvironment()
            val session = env.createSession(modelBytes, OrtSession.SessionOptions())
            val tokenizer = if (config.isTransformer && !vocabLines.isNullOrEmpty()) {
                BertWordPieceTokenizer.fromVocabLines(
                    vocabLines = vocabLines,
                    unkToken = config.unkToken,
                    clsId = config.clsTokenId,
                    sepId = config.sepTokenId,
                    padId = config.padTokenId,
                    unkId = config.unkTokenId,
                    maxLength = config.maxLength,
                )
            } else {
                null
            }
            return OnnxIntentClassifier(labels, config, session, env, tokenizer)
        }

        private fun parseLabels(raw: String): List<String> {
            val array = JSONArray(raw)
            return buildList {
                for (i in 0 until array.length()) {
                    add(array.optString(i))
                }
            }
        }
    }
}
