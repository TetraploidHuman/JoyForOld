package com.tetraploid.joyforold.speech

/**
 * 本地 ASR（离线语音识别）可行性评估。
 *
 * 当前生产路径：豆包云端 ASR（[com.tetraploid.joyforold.speech.DoubaoAsrClient]）。
 * Sherpa-onnx 已用于唤醒词（KWS）+ Silero VAD，尚未接入 ASR。
 */
object LocalAsrFeasibility {

    enum class Verdict {
        /** 已上线 */
        CLOUD_ONLY,
        /** 技术可行，建议作为无网降级 */
        FEASIBLE_DEGRADED,
        /** 需要更强硬件或更小模型 */
        MARGINAL,
        /** 不建议在目标机上做实时 ASR */
        NOT_RECOMMENDED,
    }

    data class Assessment(
        val verdict: Verdict,
        val summary: String,
        val modelOptions: List<ModelOption>,
        val blockers: List<String>,
        val nextSteps: List<String>,
    )

    data class ModelOption(
        val name: String,
        val approxSizeMb: Int,
        val latencyHint: String,
        val notes: String,
    )

    /** 供设置页 / 调试日志展示，不触发下载。 */
    fun assess(targetDevice: String = "Snapdragon 835, 4GB RAM"): Assessment {
        val models = listOf(
            ModelOption(
                name = "sherpa-onnx Paraformer 中文 int8（流式）",
                approxSizeMb = 45,
                latencyHint = "835 上约 0.3–0.8× RTF（需实测）",
                notes = "与现有 sherpa-onnx JNI 同栈；需新增 OfflineRecognizer 管线，与 KWS 麦克风仲裁",
            ),
            ModelOption(
                name = "sherpa-onnx Zipformer 中文 small int8",
                approxSizeMb = 25,
                latencyHint = "835 上 RTF 略优，准确率略降",
                notes = "适合无网降级；方言/口音需 hold-out 验证",
            ),
            ModelOption(
                name = "SenseVoiceSmall（sherpa 集成）",
                approxSizeMb = 90,
                latencyHint = "835 上可能 >1× RTF",
                notes = "多语言/情绪；体积与延迟对老人机偏紧",
            ),
        )

        val blockers = listOf(
            "当前 AgentRuntime 在无网时直接拦截语音输入（NetworkStatus.offlineHint）",
            "麦克风与 WakeWordService 共用，需 ASR 时段暂停 KWS 或统一 AudioRecord 仲裁",
            "无网时 DeepSeek 不可用，离线 ASR 只能接本地 NLU/模板，不能替代复杂问答",
            "中文口音 + 老人语速需单独 hold-out（与 NLU hold-out 配套录 wav）",
        )

        val nextSteps = listOf(
            "实现 SherpaOnnxSpeechInput : SpeechInput（对齐 DoubaoSpeechInput 接口）",
            "设置项：ASR 提供商 = 自动（有网豆包 / 无网 Sherpa）",
            "在 835 真机测 RTF、内存峰值、与 NLU 端到端延迟",
            "无网路径：ASR → 离线 NLU / 模板 → TTS 反馈（复杂句提示连网）",
        )

        return Assessment(
            verdict = Verdict.FEASIBLE_DEGRADED,
            summary = "在 $targetDevice 上，sherpa-onnx 中文 small/int8 流式 ASR 作为**无网降级**可行；" +
                "不宜替代豆包作为主路径。复杂指令仍须联网走 DeepSeek。",
            modelOptions = models,
            blockers = blockers,
            nextSteps = nextSteps,
        )
    }

    /** 是否应在无网时尝试本地 ASR（功能开关，默认 false 直至实现）。 */
    const val LOCAL_ASR_ENABLED = false
}
