package com.tetraploid.joyforold.accessibility

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.util.Log
import android.view.inputmethod.InputMethodManager
import com.google.android.accessibility.selecttospeak.SelectToSpeakService
import com.tetraploid.joyforold.app.InstalledAppResolver
import com.tetraploid.joyforold.agent.AgentContextLimits
import com.tetraploid.joyforold.accessibility.AccessibilityGateway
import com.tetraploid.joyforold.accessibility.AccessibilityGateways
import com.tetraploid.joyforold.agent.ActionExecutionResult
import com.tetraploid.joyforold.agent.AgentAction
import com.tetraploid.joyforold.agent.AgentToolRegistry
import com.tetraploid.joyforold.agent.ClickTargetNormalizer
import com.tetraploid.joyforold.agent.ClickTargetScorer
import com.tetraploid.joyforold.agent.ExternalWindowFilter
import com.tetraploid.joyforold.agent.PageObservation
import com.tetraploid.joyforold.agent.PageReadiness
import com.tetraploid.joyforold.agent.PageScreenshotCapture
import com.tetraploid.joyforold.agent.StructuredPageSnapshot
import com.tetraploid.joyforold.agent.UiNodeHeuristics
import com.tetraploid.joyforold.agent.UiPageProbe
import com.tetraploid.joyforold.agent.UiTreeSerializer
import com.tetraploid.joyforold.ime.JoyImeCoordinator
import com.tetraploid.joyforold.ime.JoyImeHelper
import com.tetraploid.joyforold.ime.JoyInputMethodService
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class JoyAccessibilityService : AccessibilityService(), AccessibilityGateway {
  override fun context(): Context = getApplicationContext()

  private var lastTapNormalized: Pair<Int, Int>? = null
  @Volatile
  private var continuousUiTreeLogcatEnabled = false
  private var lastUiTreeLogcatDump: String? = null
  private var lastUiTreeLogcatAtMs = 0L

  override fun onServiceConnected() {
    super.onServiceConnected()
    instance = this
    AccessibilityGateways.bind(this)
    continuousUiTreeLogcatEnabled = UiTreeLogcatStore(applicationContext).isEnabled()
    com.tetraploid.joyforold.di.agentRuntime().refreshAccessibilityState()
  }

  override fun onDestroy() {
    if (instance === this) {
      instance = null
      AccessibilityGateways.unbind(this)
      lastExternalRoot?.recycle()
      lastExternalRoot = null
      com.tetraploid.joyforold.di.agentRuntime().refreshAccessibilityState()
    }
    super.onDestroy()
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (event == null) return
    val packageName = event.packageName?.toString() ?: return
    if (packageName == applicationContext.packageName) return

    when (event.eventType) {
      AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
      AccessibilityEvent.TYPE_WINDOWS_CHANGED,
      AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
        val freshRoot = queryFreshExternalRoot() ?: return
        lastExternalPackage = packageName
        lastExternalRoot?.recycle()
        lastExternalRoot = AccessibilityNodeInfo.obtain(freshRoot)
        lastExternalUpdatedAt = System.currentTimeMillis()
        freshRoot.recycle()
        maybeLogUiTreeContinuously()
      }
    }
  }

  override fun onInterrupt() {
    // 系统临时打断，服务仍保持连接；勿清空 instance，否则 Agent 会误判无障碍已关闭。
  }

  fun isReady(): Boolean = instance != null

  override fun performGlobalHome(): Boolean =
      performGlobalAction(GLOBAL_ACTION_HOME)

  override fun captureStructuredSnapshots(): List<StructuredPageSnapshot> {
    val roots = collectExternalRoots()
    if (roots.isEmpty()) return emptyList()
    return try {
      roots.map { PageObservation.capture(it) }
          .filter { ExternalWindowFilter.isUsableSnapshot(it) }
    } finally {
      roots.forEach { it.recycle() }
    }
  }

  override fun snapshotCompactForAgent(): String {
    val snapshots = captureStructuredSnapshots()
    if (snapshots.isEmpty()) {
      return "无法读取页面，请切换到目标应用。"
    }
    return snapshots.joinToString("\n\n") { it.toCompactSummary() }
  }

  override fun mergeSnapshots(snapshots: List<StructuredPageSnapshot>): StructuredPageSnapshot? {
    val usable = snapshots.filter { ExternalWindowFilter.isUsableSnapshot(it) }
    if (usable.isEmpty()) return null
    if (usable.size == 1) return usable.first()
    val primary = usable.maxByOrNull { it.clickables.size + it.visibleTexts.size } ?: usable.first()
    return primary.copy(
      clickables = usable.flatMap { it.clickables }.distinct(),
      editables = usable.flatMap { it.editables }.distinct(),
      visibleTexts = usable.flatMap { it.visibleTexts }.distinct(),
      sendButtons = usable.flatMap { it.sendButtons }.distinct(),
      fingerprint = usable.joinToString("|") { it.fingerprint },
    )
  }

  /** merge 失败时回退到单窗口最佳 root（与 read_tree 同源），避免 open_app 后误判无页面。 */
  override fun captureBestStructuredSnapshot(): StructuredPageSnapshot? {
    mergeSnapshots(captureStructuredSnapshots())?.let { return it }
    val root = getTargetRoot() ?: return null
    return try {
      val snap = PageObservation.capture(root)
      if (ExternalWindowFilter.isUsableSnapshot(snap)) snap else null
    } finally {
      root.recycle()
    }
  }

  override suspend fun captureScreenshotBase64(forceFresh: Boolean): String? =
    PageScreenshotCapture.captureBase64Jpeg(this, forceFresh = forceFresh)

  override fun snapshotTreeForDebug(): String {
    val root = getTargetRoot() ?: return "(无法读取结构树：当前无外部窗口)"
    return try {
      UiTreeSerializer.serialize(root)
    } finally {
      root.recycle()
    }
  }

  override fun setContinuousUiTreeLogcatEnabled(enabled: Boolean) {
    continuousUiTreeLogcatEnabled = enabled
    if (!enabled) {
      lastUiTreeLogcatDump = null
      lastUiTreeLogcatAtMs = 0L
      Log.i(UI_TREE_LOGCAT_TAG, "持续 UI 树 Logcat 已关闭")
      return
    }
    lastUiTreeLogcatDump = null
    lastUiTreeLogcatAtMs = 0L
    Log.i(UI_TREE_LOGCAT_TAG, "持续 UI 树 Logcat 已开启（内容与「读取页面」/snapshotForAgent 相同）")
    maybeLogUiTreeContinuously(force = true)
  }

  /**
   * 与设置页「读取页面」同源：多窗口摘要 + [UiTreeSerializer] 结构树。
   * 去重 + 节流，避免 content_changed 刷屏。
   */
  private fun maybeLogUiTreeContinuously(force: Boolean = false) {
    if (!continuousUiTreeLogcatEnabled) return
    val dump = runCatching { snapshotForAgent() }.getOrElse { error ->
      "(读取 UI 树失败: ${error.message})"
    }
    val now = System.currentTimeMillis()
    if (!force) {
      if (dump == lastUiTreeLogcatDump) return
      if (now - lastUiTreeLogcatAtMs < MIN_UI_TREE_LOGCAT_INTERVAL_MS) return
    }
    lastUiTreeLogcatDump = dump
    lastUiTreeLogcatAtMs = now
    emitUiTreeLogcat(dump)
  }

  private fun emitUiTreeLogcat(text: String) {
    val redacted = com.tetraploid.joyforold.privacy.SafeLog.redact(text)
    val chunk = AgentContextLimits.DEBUG_LOG_CHUNK_CHARS
    if (redacted.length <= chunk) {
      Log.i(UI_TREE_LOGCAT_TAG, redacted)
      return
    }
    var offset = 0
    var part = 1
    val total = (redacted.length + chunk - 1) / chunk
    while (offset < redacted.length) {
      val end = (offset + chunk).coerceAtMost(redacted.length)
      Log.i(UI_TREE_LOGCAT_TAG, "[$part/$total] ${redacted.substring(offset, end)}")
      offset = end
      part++
    }
  }

  override fun snapshotForAgent(): String {
    val roots = collectExternalRoots()
    if (roots.isEmpty()) {
      return "当前无法读取任何窗口内容，请确认已开启无障碍权限，且已切换到目标应用（如 QQ）。"
    }
    return try {
      buildString {
        roots.forEachIndexed { index, root ->
          val pkg = root.packageName?.toString() ?: "unknown"
          appendLine("--- 窗口 ${index + 1} / package: $pkg ---")
          appendLine(UiPageProbe.buildSummary(root))
          appendLine()
          appendLine(UiTreeSerializer.serialize(root))
          if (index < roots.lastIndex) appendLine()
        }
      }
    } finally {
      roots.forEach { it.recycle() }
    }
  }

  fun execute(action: AgentAction): String = executeWithResult(action).summary

  override fun executeWithResult(action: AgentAction): ActionExecutionResult {
    val result = when (action.action.lowercase()) {
      "click" -> clickByTextResult(action.targetText)
      "tap" -> tapAtNormalizedResult(action.targetText)
      "type" -> typeTextResult(action.inputText, action.targetText)
      "send" -> clickSendResult(action.targetText)
      "scroll_down" -> scrollResult(true)
      "scroll_up" -> scrollResult(false)
      "back" -> globalResult(AccessibilityService.GLOBAL_ACTION_BACK, "已执行返回", "返回失败")
      "home" -> globalResult(AccessibilityService.GLOBAL_ACTION_HOME, "已回到桌面", "回桌面失败")
      "open_app" -> openAppResult(action.targetText)
      "list_apps" -> listAppsResult(action.targetText)
      "wait" -> ActionExecutionResult(true, "等待界面刷新")
      "finish" -> ActionExecutionResult(true, action.message ?: "任务结束")
      "find_on_page" -> findOnPage(action.targetText)
      "read_tree" -> readTreeSnippet()
      else -> ActionExecutionResult(
        success = false,
        summary = "未知操作: ${action.action}",
        suggestions = listOf("请使用已注册工具：${AgentToolRegistry.toolNames.joinToString()}"),
      )
    }
    if (mutatesUi(action.action)) {
      invalidateExternalRootCache()
    }
    return result
  }

  private fun mutatesUi(action: String): Boolean {
    return action.lowercase() in UI_MUTATING_ACTIONS
  }

  fun findOnPage(targetText: String?): ActionExecutionResult {
    val query = targetText?.trim().orEmpty()
    if (query.isEmpty()) {
      return ActionExecutionResult(false, "搜索失败", detail = "缺少 target_text")
    }

    val roots = collectExternalRoots()
    if (roots.isEmpty()) {
      return ActionExecutionResult(false, "搜索失败", detail = "无法读取页面")
    }

    return try {
      val matches = linkedSetOf<String>()
      for (root in roots) {
        matches += NodeFinder.findMatchingLabels(
          root,
          query,
          limit = AgentContextLimits.MATCHED_ELEMENTS_CAP,
        )
      }
      if (matches.isEmpty()) {
        ActionExecutionResult(
          success = false,
          summary = "未找到包含「$query」的元素",
          suggestions = listOf("尝试 scroll_down 或 swipe_down", "用 read_tree 查看结构", "检查同音字/谐音"),
        )
      } else {
        ActionExecutionResult(
          success = true,
          summary = "找到 ${matches.size} 个匹配项",
            matchedElements = matches.toList(),
        )
      }
    } finally {
      roots.forEach { it.recycle() }
    }
  }

  fun readTreeSnippet(): ActionExecutionResult {
    val root = getTargetRoot() ?: return ActionExecutionResult(
      success = false,
      summary = "读取失败",
      detail = "无法读取页面",
      suggestions = listOf("确认无障碍服务已开启", "确认目标应用已在前台"),
    )
    return try {
      val pkg = root.packageName?.toString().orEmpty()
      if (ExternalWindowFilter.isIgnoredPackage(pkg)) {
        return ActionExecutionResult(
          success = false,
          summary = "读到系统界面（$pkg），不是目标应用",
          detail = "当前窗口为系统状态栏/启动动画，请 wait 等待微信等应用完全打开",
          suggestions = listOf("先 wait", "不要连续 click", "确认目标应用已在前台"),
        )
      }
      val tree = UiTreeSerializer.serialize(root)
            val maxChars = AgentContextLimits.READ_TREE_SNIPPET_MAX_CHARS
            val snippet = if (tree.length > maxChars) tree.take(maxChars) + "\n...(已截断)" else tree
      val emptyTree = PageReadiness.isEmptyTreeSnippet(tree)
      val wrongChrome = PageReadiness.isWrongChromeTree(tree)
      if (emptyTree || wrongChrome) {
        ActionExecutionResult(
          success = false,
          summary = if (wrongChrome) "读到系统启动动画，不是目标应用" else "页面结构为空，应用可能仍在加载",
          detail = if (emptyTree) {
            "无障碍树为空（结构树内容已省略，请根据截图用 tap 操作）"
          } else {
            snippet
          },
          suggestions = listOf(
            "先 wait 等待界面刷新",
            "确认目标应用已在前台",
            "视觉模式下用 tap 坐标，勿 read_tree",
          ),
        )
      } else {
        ActionExecutionResult(
          success = true,
          summary = "已读取结构树片段",
          detail = snippet,
        )
      }
    } finally {
      root.recycle()
    }
  }

  private fun getTargetRoot(): AccessibilityNodeInfo? {
    val roots = collectExternalRoots()
    if (roots.isEmpty()) return null
    val best = roots
        .filter { !ExternalWindowFilter.isIgnoredPackage(it.packageName?.toString()) }
        .maxByOrNull { UiPageProbe.windowScore(it) }
        ?: return null
    roots.filter { it !== best }.forEach { it.recycle() }
    return best
  }

  /** 读 UI 树时优先用「微信支持」组件；手势与执行仍在本服务。 */
  private fun accessibilityTreeSource(): AccessibilityService {
    if (WeChatA11yComponent.isTreeReaderReady()) {
      SelectToSpeakService.instance?.let { return it }
    }
    return this
  }

  private fun collectExternalRoots(): List<AccessibilityNodeInfo> {
    val reader = accessibilityTreeSource()
    val cached = if (reader === this) findExternalRoot() else null
    return ExternalRootCollector.collect(reader, packageName, cached)
  }

  private fun queryFreshExternalRoot(): AccessibilityNodeInfo? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      windows?.forEach { window ->
        if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return@forEach
        val root = window.root ?: return@forEach
        val pkg = root.packageName?.toString()
        if (!pkg.isNullOrBlank() && pkg != packageName) {
          return root
        }
        root.recycle()
      }
    }
    return null
  }

  private fun findExternalRoot(): AccessibilityNodeInfo? {
    lastExternalRoot?.let { cached ->
      if (System.currentTimeMillis() - lastExternalUpdatedAt <= EXTERNAL_CACHE_MS) {
        return AccessibilityNodeInfo.obtain(cached)
      }
    }
    return queryFreshExternalRoot()
  }

  private fun openAppResult(targetText: String?): ActionExecutionResult {
    val query = targetText?.trim().orEmpty()
    if (query.isEmpty()) {
      return ActionExecutionResult(false, "打开失败", detail = "缺少 target_text（如 QQ、微信、电话）")
    }

    val packageName = InstalledAppResolver.resolvePackage(applicationContext, query)
    if (packageName == null) {
      val suggestions = InstalledAppResolver.suggestMatches(applicationContext, query, limit = 6)
          .joinToString("、") { it.label }
      val samples = InstalledAppResolver.getLaunchableApps(applicationContext)
          .take(8)
          .joinToString("、") { it.label }
      return ActionExecutionResult(
        success = false,
        summary = "未识别应用：$query",
        detail = buildString {
          append("请使用【本机可打开应用】列表中的中文名称（与桌面图标一致）。")
          if (suggestions.isNotBlank()) append("\n你可能想找：$suggestions")
          append("\n示例：$samples")
        },
        suggestions = listOf(
          "open_app 的 target_text 必须与列表名称完全一致",
          "用 read_tree 或回到桌面后重试",
        ),
      )
    }

    val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
      ?: return ActionExecutionResult(
        success = false,
        summary = "无法打开：$query",
        detail = "应用 $packageName 没有桌面启动入口",
        suggestions = listOf("换一个已安装应用名称重试"),
      )

    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
      startActivity(launchIntent)
      ActionExecutionResult(true, "已打开：$query（$packageName）")
    } catch (error: Exception) {
      ActionExecutionResult(false, "打开失败：${error.message ?: query}")
    }
  }

  fun listAppsResult(query: String?): ActionExecutionResult {
    InstalledAppResolver.invalidateCache()
    val apps = InstalledAppResolver.getLaunchableApps(applicationContext)
    if (apps.isEmpty()) {
      return ActionExecutionResult(
        success = false,
        summary = "无法读取已安装应用列表",
        detail = "请检查系统「查询已安装应用」权限或无障碍服务是否正常",
      )
    }
    val keyword = query?.trim().orEmpty()
    val detail = if (keyword.isBlank()) {
      InstalledAppResolver.formatForPrompt(applicationContext, limit = 30)
    } else {
      InstalledAppResolver.formatSearchMatches(applicationContext, keyword, limit = 20)
    }
    val summary = if (keyword.isBlank()) {
      "已读取 ${apps.size} 个可打开应用"
    } else {
      "已按「$keyword」筛选应用"
    }
    return ActionExecutionResult(success = true, summary = summary, detail = detail)
  }

  private fun clickByTextResult(targetText: String?): ActionExecutionResult {
    val msg = clickByText(targetText)
    val success = !msg.contains("失败")
    return ActionExecutionResult(
      success = success,
      summary = msg,
      suggestions = if (success) emptyList() else listOf("用 find_on_page 先确认文字", "尝试更短的关键词"),
    )
  }

  private fun tapAtNormalizedResult(targetText: String?): ActionExecutionResult {
    val coords = parseNormalizedCoords(targetText)
    if (coords == null) {
      return ActionExecutionResult(
        success = false,
        summary = "点击失败：tap 需要 target_text 为归一化坐标 x,y（0~1000）",
        suggestions = listOf("根据截图估算元素中心坐标", "例：target_text=\"520,340\""),
      )
    }
    val (xNorm, yNorm) = coords
    val metrics = resources.displayMetrics
    val x = (xNorm / 1000f * metrics.widthPixels).coerceIn(0f, metrics.widthPixels - 1f)
    val y = (yNorm / 1000f * metrics.heightPixels).coerceIn(0f, metrics.heightPixels - 1f)
    val msg = tapAt(x, y, "已在归一化坐标 ($xNorm,$yNorm) 点击")
    return if (msg != null) {
      lastTapNormalized = xNorm to yNorm
      ActionExecutionResult(success = true, summary = msg)
    } else {
      ActionExecutionResult(
        success = false,
        summary = "点击失败：系统未接受手势",
        suggestions = listOf("换附近坐标重试", "先 wait 等页面稳定"),
      )
    }
  }

  private fun parseNormalizedCoords(targetText: String?): Pair<Int, Int>? {
    val raw = targetText?.trim().orEmpty()
    if (raw.isBlank()) return null
    val parts = raw.split(',', '，', ' ').map { it.trim() }.filter { it.isNotBlank() }
    if (parts.size < 2) return null
    val x = parts[0].toIntOrNull() ?: return null
    val y = parts[1].toIntOrNull() ?: return null
    if (x !in 0..1000 || y !in 0..1000) return null
    return x to y
  }

    private fun typeTextResult(input: String?, fieldHint: String? = null): ActionExecutionResult {
        parseNormalizedCoords(fieldHint)?.let { (xNorm, yNorm) ->
            val msg = typeTextAtNormalized(xNorm, yNorm, input)
            val success = !msg.contains("失败")
            return ActionExecutionResult(
                success = success,
                summary = msg,
                suggestions = if (success) emptyList() else buildInputFailureSuggestions(),
            )
        }
        val msg = typeText(input, fieldHint)
        val success = !msg.contains("失败")
        return ActionExecutionResult(
            success = success,
            summary = msg,
            suggestions = if (success) emptyList() else buildInputFailureSuggestions(),
        )
    }

  private fun buildInputFailureSuggestions(): List<String> {
    val suggestions = mutableListOf(
      "先 tap 输入框坐标再 type",
      "根据截图估算输入框位置",
    )
    when {
      !JoyImeHelper.isEnabled(this) ->
        suggestions += "在设置中启用 Joy 输入助手并设为默认输入法"
      !JoyImeHelper.isSelectedAsDefault(this) ->
        suggestions += "在设置中将 Joy 输入助手设为默认输入法（无无障碍树时必需）"
    }
    return suggestions
  }

  private fun clickSendResult(targetText: String? = null): ActionExecutionResult {
    parseNormalizedCoords(targetText)?.let { (xNorm, yNorm) ->
      val msg = tapAtNormalizedCoords(xNorm, yNorm, "已在归一化坐标 ($xNorm,$yNorm) 点击发送")
      return if (msg != null) {
        ActionExecutionResult(success = true, summary = msg)
      } else {
        ActionExecutionResult(success = false, summary = "发送失败：tap 发送按钮未成功")
      }
    }
    val msg = clickSend()
    if (msg.contains("失败")) {
      tapSendNearLastTap()?.let { fallback ->
        return ActionExecutionResult(success = true, summary = fallback)
      }
    }
    val success = !msg.contains("失败")
    return ActionExecutionResult(
      success = success,
      summary = msg,
      suggestions = if (success) {
        emptyList()
      } else {
        listOf("视觉模式可 send + target_text=\"x,y\" 指定发送按钮坐标")
      },
    )
  }

  private fun scrollResult(down: Boolean): ActionExecutionResult {
    val msg = scroll(down)
    val success = !msg.contains("失败")
    return ActionExecutionResult(
      success = success,
      summary = msg,
      suggestions = if (success) emptyList() else listOf("尝试 swipe_down 手势滚动"),
    )
  }

  private fun globalResult(action: Int, okMsg: String, failMsg: String): ActionExecutionResult {
    val ok = performGlobalAction(action)
    return ActionExecutionResult(success = ok, summary = if (ok) okMsg else failMsg)
  }

  private fun clickSend(): String {
    val targets = listOf("发送", "send", "发表", "送出", "发送(按钮)")
    for (target in targets) {
      val result = clickByText(target)
      if (!result.contains("失败")) return result
    }

    val roots = collectExternalRoots()
    if (roots.isEmpty()) return "发送失败：无法读取页面"
    return try {
      for (root in roots) {
        val node = NodeFinder.findSendButton(root)
        if (node != null) {
          val ok = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
          node.recycle()
          if (ok) return "已点击发送按钮"
        }
      }

      val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
      if (focused != null) {
        val imeEnter = 0x00000010 // AccessibilityNodeInfo.ACTION_IME_ENTER (API 30+)
        val ok = focused.performAction(imeEnter)
        focused.recycle()
        if (ok) return "已通过键盘回车发送"
      }

      val tapResult = tapSendByInputHeuristic(roots)
      if (tapResult != null) return tapResult

      "发送失败：未找到发送按钮，可尝试说「点击发送」"
    } finally {
      roots.forEach { it.recycle() }
    }
  }

  private fun clickByText(targetText: String?): String {
    val text = targetText?.trim().orEmpty()
    if (text.isEmpty()) return "点击失败：缺少 target_text"

    val roots = collectExternalRoots()
    if (roots.isEmpty()) return "点击失败：无法读取页面"
    return try {
      var best: AccessibilityNodeInfo? = null
      var bestScore = Int.MIN_VALUE
      var matchedLabel = text
      val metrics = resources.displayMetrics
      for (candidate in ClickTargetNormalizer.clickCandidates(text)) {
        for (root in roots) {
          val hit = NodeFinder.findClickableByTextScored(
            root,
            candidate,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
          ) ?: continue
          if (hit.score > bestScore) {
            best?.recycle()
            best = hit.node
            bestScore = hit.score
            matchedLabel = candidate
          } else {
            hit.node.recycle()
          }
        }
      }
      val node = best ?: return "点击失败：未找到包含「$text」的可点击元素"
      try {
        clickNode(node, matchedLabel)
      } finally {
        node.recycle()
      }
    } finally {
      roots.forEach { it.recycle() }
    }
  }

  /**
   * 优先手势点击节点中心：高德等自定义控件常对 ACTION_CLICK 返回 true 但不真正响应。
   */
  private fun clickNode(node: AccessibilityNodeInfo, matchedLabel: String): String {
    recordTapFromNode(node)
    val rect = Rect()
    node.getBoundsInScreen(rect)
    if (rect.width() > 0 && rect.height() > 0) {
      val gesture = tapAt(
        rect.exactCenterX(),
        rect.exactCenterY(),
        "已点击：$matchedLabel",
      )
      if (gesture != null) return gesture
    }
    val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    return if (clicked) {
      "已点击：$matchedLabel"
    } else {
      "点击失败：系统未接受点击操作"
    }
  }

    private fun typeText(input: String?, fieldHint: String? = null): String {
        val text = input?.trim().orEmpty()
        if (text.isEmpty()) return "输入失败：缺少 input_text"

        val roots = collectExternalRoots()

        if (!fieldHint.isNullOrBlank()) {
            try {
                resolveEditableField(roots, fieldHint)?.let { target ->
                    return tryTypeIntoNode(target, text, recycleTarget = true)
                }
            } finally {
                roots.forEach { it.recycle() }
            }
        }

        if (lastTapNormalized != null) {
            hideThirdPartyKeyboard()
            tryImeTypeText(text)?.let { return it }
            pasteViaClipboard(text)?.let { return it }
        }

        var focused: AccessibilityNodeInfo? = null
        var editable: AccessibilityNodeInfo? = null
        return try {
            if (roots.isEmpty()) {
                hideThirdPartyKeyboard()
                tryImeTypeText(text)?.let { return it }
                return pasteViaClipboard(text)
                    ?: "输入失败：无法读取页面，请先 tap 输入框坐标再 type"
            }

            focused = findValidInputFocus()
            editable = focused
            if (editable == null) {
                editable = findEditableAcrossWindows()
            }
            if (editable == null) {
                editable = roots
                    .mapNotNull { root ->
                        NodeFinder.findBestEditable(root)?.also { candidate ->
                            if (UiNodeHeuristics.isImeKeyboardNode(candidate)) {
                                candidate.recycle()
                                return@mapNotNull null
                            }
                        }
                    }
                    .maxByOrNull { node ->
                        val screenHeight = UiNodeHeuristics.screenHeight(
                            roots.firstOrNull() ?: return@maxByOrNull Int.MIN_VALUE,
                        )
                        UiNodeHeuristics.inputScore(node, screenHeight)
                    }
            }

            if (editable == null) {
                editable = pollValidInputFocus(maxAttempts = 6, intervalMs = 250L)
            }

            if (editable == null) {
                editable = retryFocusViaLastTap()
            }

            if (editable == null) {
                hideThirdPartyKeyboard()
                tryImeTypeText(text)?.let { return it }
                return pasteViaClipboard(text) ?: buildKeyboardBlockingFailureMessage()
            }

            tryTypeIntoNode(editable, text, recycleTarget = false, alsoRecycle = focused)
        } finally {
            recycleInputNodes(focused, if (editable !== focused) editable else null)
            roots.forEach { it.recycle() }
        }
    }

    /**
     * 视觉模式：在归一化坐标处点击 → 等待焦点 → Joy IME / 剪贴板注入（原子输入）。
     */
    private fun typeTextAtNormalized(xNorm: Int, yNorm: Int, input: String?): String {
        val text = input?.trim().orEmpty()
        if (text.isEmpty()) return "输入失败：缺少 input_text"

        lastTapNormalized = xNorm to yNorm
        tapAtNormalizedCoords(xNorm, yNorm, successMessage = null)
        Thread.sleep(500L)
        tapAtNormalizedCoords(xNorm, yNorm, successMessage = null)
        Thread.sleep(350L)

        hideThirdPartyKeyboard()
        JoyImeCoordinator.agentInjectionActive = true
        try {
            refocusInputForImeInjection()
            tryImeTypeText(text, maxWaitMs = 5_000L)?.let { return it }
            pasteViaClipboard(text)?.let { return it }
        } finally {
            JoyImeCoordinator.agentInjectionActive = false
            JoyInputMethodService.switchBackToUserKeyboardIfActive()
        }
        return "输入失败：未能在坐标 ($xNorm,$yNorm) 注入文字，请确认 Joy 输入助手为默认输入法"
    }

  private fun buildKeyboardBlockingFailureMessage(): String {
    val imeOpen = isThirdPartyKeyboardVisible()
    return if (imeOpen) {
      "输入失败：当前焦点在系统键盘上，无法写入应用输入框。请先 back 收起键盘，或将 Joy 输入助手设为默认输入法后重试"
    } else {
      "输入失败：未找到输入区域，请先 tap 输入框坐标再 type"
    }
  }

  private fun tryTypeIntoNode(
    editable: AccessibilityNodeInfo,
    text: String,
    recycleTarget: Boolean,
    alsoRecycle: AccessibilityNodeInfo? = null,
  ): String {
    return try {
      recordTapFromNode(editable)
      editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
      editable.performAction(AccessibilityNodeInfo.ACTION_CLICK)

      val args = android.os.Bundle().apply {
        putCharSequence(
          AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
          text,
        )
      }
      var ok = editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
      if (!ok) {
        ok = editable.performAction(AccessibilityNodeInfo.ACTION_PASTE)
      }
      if (ok) {
        "已输入：$text"
      } else {
        hideThirdPartyKeyboard()
        tryImeTypeText(text)
          ?: pasteViaClipboard(text)
          ?: "输入失败：系统未接受输入，可先 tap 输入框再试"
      }
    } finally {
      if (recycleTarget) editable.recycle()
      alsoRecycle?.recycle()
    }
  }

  private fun resolveEditableField(
    roots: List<AccessibilityNodeInfo>,
    fieldHint: String,
  ): AccessibilityNodeInfo? {
    if (roots.isEmpty()) return null
    return NodeFinder.findEditableByTarget(roots, fieldHint)
  }

  /** Agent 注入前收起搜狗/Gboard 等，避免 InputConnection 在键盘侧。 */
  private fun hideThirdPartyKeyboard() {
    if (!isThirdPartyKeyboardVisible()) return
    val imm = getSystemService(InputMethodManager::class.java) ?: return
    @Suppress("DEPRECATION")
    imm.toggleSoftInput(InputMethodManager.HIDE_IMPLICIT_ONLY, 0)
    Thread.sleep(200L)
  }

  private fun isThirdPartyKeyboardVisible(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false
    return windows?.any { window ->
      val root = window.root ?: return@any false
      try {
        val pkg = root.packageName?.toString().orEmpty()
        pkg.contains("inputmethod", ignoreCase = true) ||
          NodeFinder.treeContainsImeKeyboard(root)
      } finally {
        root.recycle()
      }
    } == true
  }

  private fun recordTapFromNode(node: AccessibilityNodeInfo) {
    val rect = Rect()
    node.getBoundsInScreen(rect)
    if (rect.width() <= 0 || rect.height() <= 0) return
    val metrics = resources.displayMetrics
    val xNorm = (rect.exactCenterX() / metrics.widthPixels * 1000f).toInt().coerceIn(0, 1000)
    val yNorm = (rect.exactCenterY() / metrics.heightPixels * 1000f).toInt().coerceIn(0, 1000)
    lastTapNormalized = xNorm to yNorm
  }

  private fun findValidInputFocus(): AccessibilityNodeInfo? {
    val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
    if (UiNodeHeuristics.isImeKeyboardNode(focused)) {
      focused.recycle()
      return null
    }
    return focused
  }

  private fun pollValidInputFocus(maxAttempts: Int, intervalMs: Long): AccessibilityNodeInfo? {
    repeat(maxAttempts) { attempt ->
      if (attempt > 0) Thread.sleep(intervalMs)
      findValidInputFocus()?.let { return it }
    }
    return null
  }

  /**
   * 通过隐藏 IME 注入；需已启用且设为默认。
   * 必须先置 [JoyImeCoordinator.agentInjectionActive]，再聚焦输入框，否则
   * [JoyInputMethodService] 会在 onStartInputView 中立刻切回原键盘，导致无 InputConnection。
   */
  private fun tryImeTypeText(text: String, maxWaitMs: Long = 3_000L): String? {
    if (!JoyImeHelper.isEnabled(this) || !JoyImeHelper.isSelectedAsDefault(this)) return null
    hideThirdPartyKeyboard()
    JoyImeCoordinator.agentInjectionActive = true
    try {
      refocusInputForImeInjection()
      val deadline = SystemClock.uptimeMillis() + maxWaitMs
      while (SystemClock.uptimeMillis() < deadline) {
        if (JoyInputMethodService.typeText(text)) {
          return "已输入法注入：$text"
        }
        Thread.sleep(100L)
      }
      return null
    } finally {
      JoyImeCoordinator.agentInjectionActive = false
      JoyInputMethodService.switchBackToUserKeyboardIfActive()
    }
  }

  /** Agent 注入前重新聚焦；须在 agentInjectionActive=true 之后调用。 */
  private fun refocusInputForImeInjection() {
    if (lastTapNormalized != null) {
      prepareInputFocusAtLastTap()
      return
    }
    pollValidInputFocus(maxAttempts = 2, intervalMs = 100L)?.let { focused ->
      try {
        focused.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        focused.performAction(AccessibilityNodeInfo.ACTION_CLICK)
      } finally {
        focused.recycle()
      }
      Thread.sleep(400L)
    }
  }

  private fun pasteViaClipboard(text: String): String? {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val previousClip = runCatching { clipboard.primaryClip }.getOrNull()
    try {
      clipboard.setPrimaryClip(ClipData.newPlainText("joy_input", text))

      prepareInputFocusAtLastTap()

      repeat(10) { attempt ->
        if (attempt > 0) Thread.sleep(250L)

        findValidInputFocus()?.let { focused ->
          return try {
            focused.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
              "已粘贴输入：$text"
            } else {
              null
            }
          } finally {
            focused.recycle()
          }
        }

        findEditableAcrossWindows()?.let { editable ->
          return try {
            editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            editable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (editable.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
              "已粘贴输入：$text"
            } else {
              null
            }
          } finally {
            editable.recycle()
          }
        }
      }

      tryPasteFromContextMenu(text)?.let { return it }
      return null
    } finally {
      if (previousClip != null) {
        runCatching { clipboard.setPrimaryClip(previousClip) }
      }
    }
  }

  /** tap 后聚焦：先点上次坐标，必要时再长按唤出粘贴菜单。 */
  private fun prepareInputFocusAtLastTap() {
    val coords = lastTapNormalized ?: return
    tapAtNormalizedCoords(coords.first, coords.second, successMessage = null)
    Thread.sleep(350L)
    tapAtNormalizedCoords(coords.first, coords.second, successMessage = null)
    Thread.sleep(450L)
  }

  private fun findEditableAcrossWindows(): AccessibilityNodeInfo? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
    windows?.forEach { window ->
      val pkg = window.root?.packageName?.toString()
      if (ExternalWindowFilter.isIgnoredPackage(pkg)) return@forEach
      val root = window.root ?: return@forEach
      try {
        NodeFinder.findBestEditable(root)?.let { return it }
      } finally {
        root.recycle()
      }
    }
    return null
  }

  private fun tryPasteFromContextMenu(text: String): String? {
    val coords = lastTapNormalized ?: return null
    val metrics = resources.displayMetrics
    val x = (coords.first / 1000f * metrics.widthPixels).coerceIn(0f, metrics.widthPixels - 1f)
    val y = (coords.second / 1000f * metrics.heightPixels).coerceIn(0f, metrics.heightPixels - 1f)

    if (!longPressAtBlocking(x, y)) return null
    Thread.sleep(1000L)

    val pasteLabels = listOf("粘贴", "Paste", "paste")
    val roots = collectAllWindowRoots()
    return try {
      for (label in pasteLabels) {
        for (root in roots) {
          val node = NodeFinder.findClickableByText(root, label)
          if (node != null) {
            val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node.recycle()
            if (clicked) return "已通过粘贴菜单输入：$text"
          }
        }
      }
      null
    } finally {
      roots.forEach { it.recycle() }
    }
  }

  private fun collectAllWindowRoots(): List<AccessibilityNodeInfo> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
      return collectExternalRoots()
    }
    val fromWindows = windows?.mapNotNull { window ->
      window.root?.let { AccessibilityNodeInfo.obtain(it) }
    }.orEmpty()
    if (fromWindows.isNotEmpty()) return fromWindows
    return collectExternalRoots()
  }

  private fun dispatchGestureBlocking(gesture: GestureDescription, timeoutMs: Long = 3_000L): Boolean {
    val latch = CountDownLatch(1)
    var cancelled = false
    val dispatched = dispatchGesture(
      gesture,
      object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
          latch.countDown()
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
          cancelled = true
          latch.countDown()
        }
      },
      null,
    )
    if (!dispatched) return false
    val completed = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
    if (!completed || cancelled) {
      return false
    }
    return true
  }

  private fun longPressAtBlocking(x: Float, y: Float): Boolean {
    val path = Path().apply { moveTo(x, y) }
    val gesture = GestureDescription.Builder()
      .addStroke(GestureDescription.StrokeDescription(path, 0, 650))
      .build()
    return dispatchGestureBlocking(gesture)
  }

  private fun pollInputFocus(maxAttempts: Int, intervalMs: Long): AccessibilityNodeInfo? {
    repeat(maxAttempts) { attempt ->
      if (attempt > 0) Thread.sleep(intervalMs)
      val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
      if (focused != null) return focused
    }
    return null
  }

  private fun retryFocusViaLastTap(): AccessibilityNodeInfo? {
    prepareInputFocusAtLastTap()
    return pollValidInputFocus(maxAttempts = 10, intervalMs = 200L)
      ?: findEditableAcrossWindows()
  }

  private fun tapAtNormalizedCoords(xNorm: Int, yNorm: Int, successMessage: String?): String? {
    val metrics = resources.displayMetrics
    val x = (xNorm / 1000f * metrics.widthPixels).coerceIn(0f, metrics.widthPixels - 1f)
    val y = (yNorm / 1000f * metrics.heightPixels).coerceIn(0f, metrics.heightPixels - 1f)
    return tapAt(
      x,
      y,
      successMessage ?: "已在归一化坐标 ($xNorm,$yNorm) 点击",
    )
  }

  /** 无障碍树为空时，在上次 tap 输入区右侧尝试点击发送区域。 */
  private fun tapSendNearLastTap(): String? {
    val coords = lastTapNormalized ?: return null
    val sendX = (coords.first + 120).coerceAtMost(980)
    return tapAtNormalizedCoords(sendX, coords.second, "已在上次输入区域附近点击发送")
  }

  private fun recycleInputNodes(
    focused: AccessibilityNodeInfo?,
    editable: AccessibilityNodeInfo?,
  ) {
    when {
      focused != null && editable != null && focused === editable -> focused.recycle()
      else -> {
        focused?.recycle()
        editable?.recycle()
      }
    }
  }

  private fun tapSendByInputHeuristic(roots: List<AccessibilityNodeInfo>): String? {
    var input: AccessibilityNodeInfo? = null
    for (root in roots) {
      input = NodeFinder.findBestEditable(root)
      if (input != null) break
    }
    val editable = input ?: return null
    val rect = Rect()
    editable.getBoundsInScreen(rect)
    editable.recycle()

    val metrics = resources.displayMetrics
    val tapX = (metrics.widthPixels - rect.width() * 0.15f).coerceAtLeast(rect.right + 24f)
    val tapY = (rect.top + rect.bottom) / 2f
    return tapAt(tapX, tapY, "已点击输入框右侧发送区域")
  }

  private fun tapAt(x: Float, y: Float, successMessage: String): String? {
    val metrics = resources.displayMetrics
    Log.i(
      "JoyForOld/Tap",
      "dispatch tap px=(${x.toInt()},${y.toInt()}) screen=${metrics.widthPixels}x${metrics.heightPixels}",
    )
    val path = Path().apply {
      moveTo(x, y)
    }
    val gesture = GestureDescription.Builder()
      .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
      .build()
    return if (dispatchGestureBlocking(gesture)) successMessage else null
  }

  private fun scroll(down: Boolean): String {
    repeat(2) { attempt ->
      swipeVerticalBlocking(down)?.let { return it }
      if (attempt == 0) Thread.sleep(80)
    }
    return if (down) "向下滚动失败，请重试" else "向上滚动失败，请重试"
  }

  private fun swipeVerticalBlocking(down: Boolean): String? {
    val metrics = resources.displayMetrics
    val centerX = metrics.widthPixels / 2f
    val startY: Float
    val endY: Float
    if (down) {
      startY = metrics.heightPixels * 0.78f
      endY = metrics.heightPixels * 0.22f
    } else {
      startY = metrics.heightPixels * 0.22f
      endY = metrics.heightPixels * 0.78f
    }
    return swipeLineBlocking(centerX, startY, centerX, endY)?.let {
      if (down) "已向下滑动屏幕" else "已向上滑动屏幕"
    }
  }

  override fun swipeNormalizedBlocking(x1: Int, y1: Int, x2: Int, y2: Int): String =
      swipeNormalizedBlockingImpl(x1, y1, x2, y2) ?: "滑动手势失败"

  private fun swipeNormalizedBlockingImpl(x1: Int, y1: Int, x2: Int, y2: Int): String? {
    val metrics = resources.displayMetrics
    val maxX = (metrics.widthPixels - 1).coerceAtLeast(0).toFloat()
    val maxY = (metrics.heightPixels - 1).coerceAtLeast(0).toFloat()
    val sx = (x1 / 1000f * metrics.widthPixels).coerceIn(0f, maxX)
    val sy = (y1 / 1000f * metrics.heightPixels).coerceIn(0f, maxY)
    val ex = (x2 / 1000f * metrics.widthPixels).coerceIn(0f, maxX)
    val ey = (y2 / 1000f * metrics.heightPixels).coerceIn(0f, maxY)
    return swipeLineBlocking(sx, sy, ex, ey)?.let { "已滑动画面上对应区域" }
  }

  private fun swipeLineBlocking(startX: Float, startY: Float, endX: Float, endY: Float): String? {
    val path = Path().apply {
      moveTo(startX, startY)
      lineTo(endX, endY)
    }
    val distance = kotlin.math.hypot(
      (endX - startX).toDouble(),
      (endY - startY).toDouble(),
    ).toFloat()
    val metrics = resources.displayMetrics
    val durationMs = ((distance / metrics.heightPixels.coerceAtLeast(1)) * 420f)
      .toLong()
      .coerceIn(180L, 520L)
    val gesture = GestureDescription.Builder()
      .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
      .build()
    return if (dispatchGestureBlocking(gesture)) "ok" else null
  }

  private fun global(action: Int, successMessage: String): String {
    return if (performGlobalAction(action)) successMessage else "系统操作失败"
  }

  override suspend fun swipeDown(): String = swipeVerticalSuspend(down = true)

  override suspend fun swipeUp(): String = swipeVerticalSuspend(down = false)

  private suspend fun swipeVerticalSuspend(down: Boolean): String = suspendCoroutine { continuation ->
    val metrics = resources.displayMetrics
    val centerX = metrics.widthPixels / 2f
    val startY: Float
    val endY: Float
    if (down) {
      startY = metrics.heightPixels * 0.78f
      endY = metrics.heightPixels * 0.22f
    } else {
      startY = metrics.heightPixels * 0.22f
      endY = metrics.heightPixels * 0.78f
    }
    val path = Path().apply {
      moveTo(centerX, startY)
      lineTo(centerX, endY)
    }
    val gesture = GestureDescription.Builder()
      .addStroke(GestureDescription.StrokeDescription(path, 0, 420))
      .build()
    val dispatched = dispatchGesture(
      gesture,
      object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
          continuation.resume(if (down) "已执行向下滑动手势" else "已执行向上滑动手势")
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
          continuation.resume("滑动手势被取消")
        }
      },
      null,
    )
    if (!dispatched) {
      continuation.resume("滑动手势发送失败")
    }
  }

  companion object {
    private const val EXTERNAL_CACHE_MS = 8_000L
    private const val UI_TREE_LOGCAT_TAG = "JoyForOld/UiTree"
    private const val MIN_UI_TREE_LOGCAT_INTERVAL_MS = 1_000L
    private val UI_MUTATING_ACTIONS = setOf(
      "click", "tap", "type", "send",
      "scroll_down", "scroll_up", "back", "home", "open_app",
    )

    @Volatile
    var instance: JoyAccessibilityService? = null
      private set

    private var lastExternalPackage: String? = null
    private var lastExternalRoot: AccessibilityNodeInfo? = null
    private var lastExternalUpdatedAt: Long = 0L

    fun lastExternalPackageName(): String? = lastExternalPackage

    fun invalidateExternalRootCache() {
      lastExternalRoot?.recycle()
      lastExternalRoot = null
      lastExternalUpdatedAt = 0L
    }
  }
}

private object NodeFinder {
  fun findMatchingLabels(
    root: AccessibilityNodeInfo,
    query: String,
    limit: Int = AgentContextLimits.MATCHED_ELEMENTS_CAP,
  ): List<String> {
    val lower = query.lowercase()
    val tokens = lower.split(Regex("\\s+")).filter { it.isNotBlank() }
    val results = linkedSetOf<String>()
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(AccessibilityNodeInfo.obtain(root))
    var walked = 0

    while (queue.isNotEmpty() && results.size < limit && walked < AgentContextLimits.SNAPSHOT_WALK_MAX_NODES) {
      walked++
      val node = queue.removeFirst()
      val label = UiNodeHeuristics.displayLabel(node)
      val nodeLower = label.lowercase()
      val fullLabel = UiNodeHeuristics.nodeLabel(node)
      val fullLower = fullLabel.lowercase()
      val matched = nodeLower.contains(lower) ||
        fullLower.contains(lower) ||
        tokens.any { token -> token.length >= 1 && (nodeLower.contains(token) || fullLower.contains(token)) }

      if (matched && label.isNotBlank()) {
        val suffix = when {
          node.isClickable -> " [可点击]"
          UiNodeHeuristics.isInputLike(node, UiNodeHeuristics.screenHeight(root)) -> " [输入区]"
          else -> ""
        }
        results += label.take(80) + suffix
      }

      for (i in 0 until node.childCount) {
        node.getChild(i)?.let(queue::add)
      }
      node.recycle()
    }
    queue.forEach { it.recycle() }
    return results.toList()
  }

  data class ClickableHit(val node: AccessibilityNodeInfo, val score: Int)

  fun findClickableByText(
    root: AccessibilityNodeInfo,
    text: String,
    screenWidth: Int = 0,
    screenHeight: Int = 0,
  ): AccessibilityNodeInfo? = findClickableByTextScored(root, text, screenWidth, screenHeight)?.node

  fun findClickableByTextScored(
    root: AccessibilityNodeInfo,
    text: String,
    screenWidth: Int = 0,
    screenHeight: Int = 0,
  ): ClickableHit? {
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(AccessibilityNodeInfo.obtain(root))
    val lower = text.lowercase()
    var best: AccessibilityNodeInfo? = null
    var bestScore = Int.MIN_VALUE
    var walked = 0
    val resolvedScreenH = if (screenHeight > 0) screenHeight else UiNodeHeuristics.screenHeight(root)
    val resolvedScreenW = screenWidth

    while (queue.isNotEmpty() && walked < AgentContextLimits.SNAPSHOT_WALK_MAX_NODES) {
      walked++
      val node = queue.removeFirst()
      val nodeText = UiNodeHeuristics.nodeLabel(node)
      val matched = ClickTargetScorer.matches(lower, nodeText) ||
        (lower.contains("发送") && UiNodeHeuristics.isSendLike(node))

      if (matched) {
        val clickable = findClickableTarget(node)
        if (clickable != null) {
          val rect = android.graphics.Rect()
          clickable.getBoundsInScreen(rect)
          // 用命中文案节点打分（不是整行合并文案），避免点到「名字相近但更靠上」的项
          val score = ClickTargetScorer.score(
            ClickTargetScorer.Candidate(
              query = lower,
              nodeText = nodeText,
              displayLabel = UiNodeHeuristics.displayLabel(node),
              left = rect.left,
              top = rect.top,
              right = rect.right,
              bottom = rect.bottom,
              visibleToUser = clickable.isVisibleToUser,
              screenWidth = resolvedScreenW,
              screenHeight = resolvedScreenH,
            ),
          )
          if (score > bestScore) {
            best?.recycle()
            best = clickable
            bestScore = score
          } else if (clickable !== best) {
            clickable.recycle()
          }
        }
      }

      for (i in 0 until node.childCount) {
        node.getChild(i)?.let(queue::add)
      }
      node.recycle()
    }
    queue.forEach { it.recycle() }
    val node = best ?: return null
    return ClickableHit(node, bestScore)
  }

  fun findBestEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    val screenHeight = UiNodeHeuristics.screenHeight(root)
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(AccessibilityNodeInfo.obtain(root))
    var best: AccessibilityNodeInfo? = null
    var bestScore = Int.MIN_VALUE

    while (queue.isNotEmpty()) {
      val node = queue.removeFirst()
      if (!UiNodeHeuristics.isImeKeyboardNode(node)) {
        val score = UiNodeHeuristics.inputScore(node, screenHeight)
        if (score > bestScore) {
          best?.recycle()
          best = AccessibilityNodeInfo.obtain(node)
          bestScore = score
        }
      }
      for (i in 0 until node.childCount) {
        node.getChild(i)?.let(queue::add)
      }
      node.recycle()
    }
    return best
  }

  fun findEditableByTarget(roots: List<AccessibilityNodeInfo>, target: String): AccessibilityNodeInfo? {
    val candidates = ClickTargetNormalizer.clickCandidates(target)
    val tokens = candidates.map { it.lowercase() }.filter { it.isNotBlank() }
    if (tokens.isEmpty()) return null

    var best: AccessibilityNodeInfo? = null
    var bestScore = Int.MIN_VALUE
    for (root in roots) {
      val screenHeight = UiNodeHeuristics.screenHeight(root)
      val queue = ArrayDeque<AccessibilityNodeInfo>()
      queue.add(AccessibilityNodeInfo.obtain(root))
      while (queue.isNotEmpty()) {
        val node = queue.removeFirst()
        if (!UiNodeHeuristics.isImeKeyboardNode(node)) {
          val viewId = node.viewIdResourceName?.substringAfterLast('/').orEmpty().lowercase()
          val label = UiNodeHeuristics.nodeLabel(node).lowercase()
          val matched = tokens.any { token ->
            viewId.contains(token) ||
              label.contains(token) ||
              (token.length >= 2 && label.contains(token))
          }
          if (matched && (node.isEditable || UiNodeHeuristics.isInputLike(node, screenHeight))) {
            val score = UiNodeHeuristics.inputScore(node, screenHeight) + 10_000
            if (score > bestScore) {
              best?.recycle()
              best = AccessibilityNodeInfo.obtain(node)
              bestScore = score
            }
          }
        }
        for (i in 0 until node.childCount) {
          node.getChild(i)?.let(queue::add)
        }
        node.recycle()
      }
    }
    return best
  }

  fun treeContainsImeKeyboard(root: AccessibilityNodeInfo): Boolean {
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(AccessibilityNodeInfo.obtain(root))
    while (queue.isNotEmpty()) {
      val node = queue.removeFirst()
      if (UiNodeHeuristics.isImeKeyboardNode(node)) {
        node.recycle()
        return true
      }
      for (i in 0 until node.childCount) {
        node.getChild(i)?.let(queue::add)
      }
      node.recycle()
    }
    return false
  }

  fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    val screenHeight = UiNodeHeuristics.screenHeight(root)
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(AccessibilityNodeInfo.obtain(root))
    var best: AccessibilityNodeInfo? = null
    var bestScore = Int.MIN_VALUE

    while (queue.isNotEmpty()) {
      val node = queue.removeFirst()
      if (UiNodeHeuristics.isSendLike(node) || isSendCandidate(node)) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val score = rect.bottom * 2 + rect.width()
        if (score > bestScore) {
          best?.recycle()
          best = findClickableTarget(node)
          bestScore = score
        }
      }
      for (i in 0 until node.childCount) {
        node.getChild(i)?.let(queue::add)
      }
      node.recycle()
    }
    return best
  }

  private fun isSendCandidate(node: AccessibilityNodeInfo): Boolean {
    val viewId = node.viewIdResourceName?.lowercase().orEmpty()
    val label = UiNodeHeuristics.nodeLabel(node).lowercase()
    val hasSendHint = viewId.contains("send") || viewId.contains("btn_send") ||
      label.contains("发送") || label.contains("send")
  return hasSendHint && (node.isClickable || node.parent?.isClickable == true)
  }

  fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(AccessibilityNodeInfo.obtain(root))

    while (queue.isNotEmpty()) {
      val node = queue.removeFirst()
      if (node.isScrollable) {
        queue.forEach { it.recycle() }
        return AccessibilityNodeInfo.obtain(node)
      }
      for (i in 0 until node.childCount) {
        node.getChild(i)?.let(queue::add)
      }
      node.recycle()
    }
    return null
  }

  private fun findClickableTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(node)
    while (current != null) {
      if (current.isClickable) {
        return current
      }
      val parent = current.parent
      current.recycle()
      current = parent
    }
    return AccessibilityNodeInfo.obtain(node)
  }
}
