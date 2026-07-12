package com.tetraploid.joyforold.agent

import android.content.Context
import com.tetraploid.joyforold.offline.nlu.IntentActionMapper
import com.tetraploid.joyforold.offline.nlu.OfflineNluModelManager

data class DisambiguationOption(
    val intentId: String,
    val label: String,
    val confidence: Float,
)

data class DisambiguationOffer(
    val command: String,
    val options: List<DisambiguationOption>,
)

/**
 * 当离线 NLU top-2 意图接近且均可执行时，提示用户点选消歧。
 */
object IntentDisambiguationHelper {
    private const val MAX_MARGIN_FOR_DISAMBIGUATION = 0.18f
    private const val MIN_CONFIDENCE = 0.62f

    suspend fun peek(command: String, context: Context?): DisambiguationOffer? {
        if (context == null) return null
        val trimmed = command.trim()
        if (trimmed.isBlank()) return null
        if (IntentCapabilityMatrix.isComplexQuery(trimmed)) return null

        val classifier = OfflineNluModelManager.getClassifier(context) ?: return null
        val prediction = classifier.predict(trimmed) ?: return null
        if (prediction.intent.equals("none", ignoreCase = true)) return null
        if (prediction.confidence < MIN_CONFIDENCE) return null

        val second = prediction.topAlternatives.firstOrNull() ?: return null
        val margin = prediction.confidence - second.second
        if (margin >= MAX_MARGIN_FOR_DISAMBIGUATION) return null

        val firstOption = toOption(trimmed, prediction.intent, prediction.confidence, context) ?: return null
        val secondOption = toOption(trimmed, second.first, second.second, context) ?: return null
        if (firstOption.intentId == secondOption.intentId) return null

        return DisambiguationOffer(
            command = trimmed,
            options = listOf(firstOption, secondOption),
        )
    }

    fun stepsForIntent(command: String, intentId: String, context: Context?): List<AgentAction>? {
        return com.tetraploid.joyforold.offline.nlu.IntentActionMapper.toSteps(intentId, command, context)
    }

    private fun toOption(
        command: String,
        intentId: String,
        confidence: Float,
        context: Context,
    ): DisambiguationOption? {
        val cap = IntentCapabilityMatrix.forIntent(intentId) ?: return null
        if (!cap.allowedOfflineNlu) return null
        if (confidence < cap.offlineNluMinConfidence * 0.85f) return null
        val steps = IntentActionMapper.toSteps(intentId, command, context) ?: return null
        if (steps.isEmpty()) return null
        return DisambiguationOption(
            intentId = intentId,
            label = IntentActionMapper.describeIntent(intentId, steps),
            confidence = confidence,
        )
    }
}
