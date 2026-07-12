package com.tetraploid.joyforold.offline.nlu

import android.content.Context
import com.tetraploid.joyforold.agent.AgentAction
import com.tetraploid.joyforold.agent.IntentCapabilityMatrix

/**
 * 路线 A：端侧 ONNX 意图分类 + 规则槽位抽取，离线可用。
 *
 * 精度优先：宁可不匹配（交给 DeepSeek / 其他路由），不接受低置信误匹配。
 * 门控阈值见 [IntentCapabilityMatrix]。
 */
object OfflineNluRouter {
    data class Match(
        val steps: List<AgentAction>,
        val confidence: Double,
        val clarifyMessage: String? = null,
        val intent: String? = null,
        val modelConfidence: Float = 0f,
        val modelMargin: Float = 0f,
    )

    suspend fun match(command: String, context: Context?): Match? {
        if (context == null) return null
        val trimmed = command.trim()
        if (trimmed.isBlank()) return null

        val classifier = OfflineNluModelManager.getClassifier(context) ?: return null
        val prediction = classifier.predict(trimmed) ?: return null
        val config = classifier.config()

        if (prediction.intent.equals("none", ignoreCase = true)) return null

        val margin = prediction.topAlternatives.firstOrNull()?.second?.let { alt ->
            prediction.confidence - alt
        } ?: prediction.confidence

        if (prediction.confidence < config.clarifyThreshold) return null
        if (margin < config.marginThreshold) return null
        if (prediction.confidence < config.autoExecuteThreshold) return null

        if (!IntentCapabilityMatrix.passesOfflineNluGate(
                intentId = prediction.intent,
                modelConfidence = prediction.confidence,
                modelMargin = margin,
            )
        ) {
            return null
        }

        val steps = IntentActionMapper.toSteps(prediction.intent, trimmed, context) ?: return null

        return Match(
            steps = steps,
            confidence = 0.94,
            clarifyMessage = null,
            intent = prediction.intent,
            modelConfidence = prediction.confidence,
            modelMargin = margin,
        )
    }
}
