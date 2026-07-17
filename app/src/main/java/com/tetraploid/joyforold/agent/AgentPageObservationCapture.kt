package com.tetraploid.joyforold.agent

import com.tetraploid.joyforold.accessibility.AccessibilityGateway
import com.tetraploid.joyforold.overlay.VisionOverlayGuard
import com.tetraploid.joyforold.overlay.VisionOverlaySuppressors
import com.tetraploid.joyforold.privacy.PageContextRedactor

/**
 * 封装 Agent 循环中的页面观察采集：无障碍树、视觉兜底截图、diff 与上下文格式化。
 */
class AgentPageObservationCapture(
    private val appHintStore: AppHintStore?,
    private val visionDebugStore: VisionDebugStore?,
) {
    /** 供规划器做 diff 的上一帧基线 */
    var previousSnapshot: StructuredPageSnapshot? = null
        private set

    /** 最近一次实际采到的快照（含「执行后」验证，不必然已写入基线） */
    var lastCapturedSnapshot: StructuredPageSnapshot? = null
        private set

    fun seedPreviousSnapshot(snapshot: StructuredPageSnapshot?) {
        previousSnapshot = snapshot
        lastCapturedSnapshot = snapshot
    }

    /** 下一步规划强制 FULL（失败 / 无进展回拉） */
    fun requestFullContext() {
        forceFullNext = true
    }

    private var previousVisionFingerprint: String? = null
    private var forceFullNext: Boolean = false
    private var stepsSinceLastFull: Int = 0

    /**
     * @param updatePlannerBaseline false 时仅采快照/diff 供验证，不推动规划基线与 mode 计数
     * （避免「执行后」与「规划续步」双采导致续规划误进 DIFF_ONLY）。
     */
    suspend fun capture(
        service: AccessibilityGateway,
        session: AgentConversationSession,
        stepNo: Int,
        pageContextNeed: IntentCapabilityMatrix.PageContextNeed,
        phase: String = "规划前",
        updatePlannerBaseline: Boolean = true,
    ): PageObservationPayload {
        if (pageContextNeed == IntentCapabilityMatrix.PageContextNeed.NONE) {
            return PageObservationPayload(
                pageContext = "",
                pageDiff = "",
                minimalPageContext = "",
                mode = PageContextMode.NONE,
            )
        }

        val merged = service.captureBestStructuredSnapshot()
        if (merged == null) {
            return captureWithoutA11yTree(
                service = service,
                session = session,
                stepNo = stepNo,
                pageContextNeed = pageContextNeed,
                phase = phase,
                updatePlannerBaseline = updatePlannerBaseline,
            )
        }

        val enriched = enrichWithAppHints(merged)
        lastCapturedSnapshot = enriched
        val readable = PageReadiness.isReadable(enriched)
        val visionFallback = PageReadiness.needsVisionFallback(enriched)
        val screenshot = if (visionFallback) captureVisionScreenshot(service) else null
        val visionMode = PageReadiness.shouldEnterVisionMode(enriched, screenshot)
        val currentVisionFp = VisionScreenChange.fingerprint(screenshot)

        val pageDiff = PageContextRedactor.redact(
            if (visionFallback) {
                VisionPageContext.formatPageDiff(
                    packageName = enriched.packageName,
                    previousSnapshot = previousSnapshot,
                    previousVisionFingerprint = previousVisionFingerprint,
                    currentVisionFingerprint = currentVisionFp,
                )
            } else {
                PageObservation.diff(previousSnapshot, enriched)
            },
        )
        if (currentVisionFp != null && updatePlannerBaseline) {
            previousVisionFingerprint = currentVisionFp
        }

        val mode = if (updatePlannerBaseline) {
            val forceFull = forceFullNext
            forceFullNext = false
            val dynamicMode = PageContextSelector.modeFor(
                previous = previousSnapshot,
                current = enriched,
                pageDiff = pageDiff,
                forceFull = forceFull,
                stepsSinceLastFull = stepsSinceLastFull,
                a11yUnavailable = visionFallback,
            )
            previousSnapshot = enriched
            val resolved = IntentCapabilityMatrix.pageContextModeForNeed(pageContextNeed, dynamicMode)
            rememberModeUsage(resolved)
            resolved
        } else {
            PageContextMode.FULL
        }

        AgentPageDebugLog.logObservation(
            stepNo = stepNo,
            phase = phase,
            service = service,
            snapshot = enriched,
            pageDiff = pageDiff,
            visionMode = visionMode,
            a11yUnavailable = visionFallback,
            screenshotChars = screenshot?.length ?: 0,
        )

        val payload = PageObservationPayload(
            pageContext = PageContextRedactor.redact(
                buildString {
                    if (visionFallback) {
                        append(
                            VisionPageContext.formatPageContext(
                                enriched,
                                hasScreenshot = !screenshot.isNullOrBlank(),
                            ),
                        )
                        appendLine()
                        appendLine(
                            if (visionMode) {
                                "【系统提示】当前应用不提供可用无障碍 UI 信息；" +
                                    "请以截图识别界面，使用 tap/type/send，禁止 click/read_tree/find_on_page。"
                            } else {
                                "【系统提示】当前应用不提供可用无障碍 UI 信息，但截屏暂不可用；请 wait 后重试。"
                            },
                        )
                        VisionTaskHint.pageContextSupplement(
                            command = session.rootCommand,
                            steps = session.stepRecords,
                            visionMode = visionMode,
                        ).takeIf { it.isNotBlank() }?.let { supplement ->
                            appendLine()
                            append(supplement)
                        }
                    } else {
                        append(enriched.toCompactSummary())
                        if (!readable) {
                            appendLine()
                            appendLine(
                                "【系统提示】当前读到的是系统壳层或应用仍在启动，请 wait；" +
                                    "确认 pageContext 已是目标应用后再规划下一步。",
                            )
                        }
                        SearchTaskHeuristics.plannerSupplement(
                            command = session.rootCommand,
                            snapshot = enriched,
                        ).takeIf { it.isNotBlank() }?.let { supplement ->
                            appendLine()
                            append(supplement)
                        }
                    }
                },
            ),
            pageDiff = pageDiff,
            minimalPageContext = PageContextRedactor.redact(
                if (visionFallback) {
                    val pkg = enriched.packageName.ifBlank { "未知应用" }
                    if (visionMode) "$pkg | 视觉观察（无无障碍 UI）" else "$pkg | 无无障碍 UI，截屏未就绪"
                } else if (readable) {
                    enriched.toMinimalSummary()
                } else {
                    "${enriched.packageName.ifBlank { "未知应用" }} | 页面未就绪"
                },
            ),
            mode = mode,
            screenshotBase64 = screenshot,
            visionMode = visionMode,
            a11yUnavailable = visionFallback,
        )
        maybeActivateVisionAgent(payload)
        return payload
    }

    fun rememberLlmScreenshot(stepNo: Int, phase: String, observation: PageObservationPayload) {
        val shot = observation.screenshotBase64?.takeIf { it.isNotBlank() } ?: return
        VisionDebugRecorder.recordLlmInput(
            store = visionDebugStore,
            stepNo = stepNo,
            phase = phase,
            screenshotBase64 = shot,
        )
    }

    private suspend fun captureWithoutA11yTree(
        service: AccessibilityGateway,
        session: AgentConversationSession,
        stepNo: Int,
        pageContextNeed: IntentCapabilityMatrix.PageContextNeed,
        phase: String,
        updatePlannerBaseline: Boolean,
    ): PageObservationPayload {
        val screenshot = captureVisionScreenshot(service)
        val visionMode = PageReadiness.shouldEnterVisionMode(null, screenshot)
        val currentVisionFp = VisionScreenChange.fingerprint(screenshot)
        val baseDiff = "无法读取页面"
        val pageDiff = if (visionMode) {
            PageContextRedactor.redact(
                VisionScreenChange.augmentPageDiff(
                    baseDiff,
                    previousVisionFingerprint,
                    currentVisionFp,
                ),
            )
        } else {
            baseDiff
        }
        if (currentVisionFp != null && updatePlannerBaseline) {
            previousVisionFingerprint = currentVisionFp
        }
        AgentPageDebugLog.logObservation(
            stepNo = stepNo,
            phase = phase,
            service = service,
            snapshot = null,
            pageDiff = pageDiff,
            visionMode = visionMode,
            a11yUnavailable = true,
            screenshotChars = screenshot?.length ?: 0,
        )
        val payload = PageObservationPayload(
            pageContext = if (visionMode) {
                "无法读取无障碍树，已附带屏幕截图供视觉识别。"
            } else {
                "无法读取页面，请切换到目标应用。"
            },
            pageDiff = pageDiff,
            minimalPageContext = if (visionMode) "视觉观察" else "无法读取页面",
            mode = PageContextMode.FULL,
            screenshotBase64 = screenshot,
            visionMode = visionMode,
            a11yUnavailable = true,
        )
        if (updatePlannerBaseline) {
            rememberModeUsage(PageContextMode.FULL)
        }
        maybeActivateVisionAgent(payload)
        return payload
    }

    private fun rememberModeUsage(mode: PageContextMode) {
        when (mode) {
            PageContextMode.FULL -> stepsSinceLastFull = 0
            PageContextMode.COMPACT, PageContextMode.DIFF_ONLY -> stepsSinceLastFull++
            PageContextMode.NONE -> Unit
        }
    }

    private suspend fun captureVisionScreenshot(service: AccessibilityGateway): String? =
        VisionOverlayGuard.withHiddenForCapture {
            service.captureScreenshotBase64(forceFresh = true)
        }

    private suspend fun maybeActivateVisionAgent(payload: PageObservationPayload) {
        if (payload.visionMode) {
            VisionOverlaySuppressors.current.activateVisionAgentMode()
        } else {
            // 启动瞬间树为空会先进入视觉闩锁；恢复可读后必须清掉，否则无障碍全程不再显示悬浮卡片
            VisionOverlaySuppressors.current.clearVisionAgentModeUi()
        }
    }

    private fun enrichWithAppHints(snapshot: StructuredPageSnapshot): StructuredPageSnapshot {
        val a11yReadable = PageReadiness.isReadable(snapshot)
        val stored = appHintStore?.formatForPrompt(snapshot.packageName, a11yReadable).orEmpty()
        if (stored.isBlank()) return snapshot
        val combined = listOf(snapshot.appHint, stored).filter { it.isNotBlank() }.joinToString("\n")
        return snapshot.copy(appHint = combined)
    }
}
