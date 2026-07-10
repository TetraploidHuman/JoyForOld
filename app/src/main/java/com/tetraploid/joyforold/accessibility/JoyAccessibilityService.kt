package com.tetraploid.joyforold.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.tetraploid.joyforold.app.InstalledAppResolver
import com.tetraploid.joyforold.agent.ActionExecutionResult
import com.tetraploid.joyforold.agent.AgentAction
import com.tetraploid.joyforold.agent.AgentToolRegistry
import com.tetraploid.joyforold.agent.PageObservation
import com.tetraploid.joyforold.agent.StructuredPageSnapshot
import com.tetraploid.joyforold.agent.UiNodeHeuristics
import com.tetraploid.joyforold.agent.UiPageProbe
import com.tetraploid.joyforold.agent.UiTreeSerializer
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class JoyAccessibilityService : AccessibilityService() {

  override fun onServiceConnected() {
    super.onServiceConnected()
    instance = this
  }

  override fun onDestroy() {
    if (instance === this) {
      instance = null
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
        val root = findExternalRoot() ?: return
        lastExternalPackage = packageName
        lastExternalRoot?.recycle()
        lastExternalRoot = AccessibilityNodeInfo.obtain(root)
        lastExternalUpdatedAt = System.currentTimeMillis()
        root.recycle()
      }
    }
  }

  override fun onInterrupt() = Unit

  fun isReady(): Boolean = instance != null

  fun captureStructuredSnapshots(): List<StructuredPageSnapshot> {
    val roots = collectExternalRoots()
    if (roots.isEmpty()) return emptyList()
    return try {
      roots.map { PageObservation.capture(it) }
    } finally {
      roots.forEach { it.recycle() }
    }
  }

  fun snapshotCompactForAgent(): String {
    val snapshots = captureStructuredSnapshots()
    if (snapshots.isEmpty()) {
      return "无法读取页面，请切换到目标应用。"
    }
    return snapshots.joinToString("\n\n") { it.toCompactSummary() }
  }

  fun mergeSnapshots(snapshots: List<StructuredPageSnapshot>): StructuredPageSnapshot? {
    if (snapshots.isEmpty()) return null
    if (snapshots.size == 1) return snapshots.first()
    val primary = snapshots.maxByOrNull { it.clickables.size + it.visibleTexts.size } ?: snapshots.first()
    return primary.copy(
      clickables = snapshots.flatMap { it.clickables }.distinct(),
      editables = snapshots.flatMap { it.editables }.distinct(),
      visibleTexts = snapshots.flatMap { it.visibleTexts }.distinct(),
      sendButtons = snapshots.flatMap { it.sendButtons }.distinct(),
      fingerprint = snapshots.joinToString("|") { it.fingerprint },
    )
  }

  fun snapshotForAgent(): String {
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

  fun executeWithResult(action: AgentAction): ActionExecutionResult {
    return when (action.action.lowercase()) {
      "click" -> clickByTextResult(action.targetText)
      "type" -> typeTextResult(action.inputText)
      "send" -> clickSendResult()
      "scroll_down" -> scrollResult(true)
      "scroll_up" -> scrollResult(false)
      "back" -> globalResult(AccessibilityService.GLOBAL_ACTION_BACK, "已执行返回", "返回失败")
      "home" -> globalResult(AccessibilityService.GLOBAL_ACTION_HOME, "已回到桌面", "回桌面失败")
      "open_app" -> openAppResult(action.targetText)
      "list_apps" -> listAppsResult(action.targetText)
      "wait" -> ActionExecutionResult(true, "等待界面刷新")
      "finish" -> ActionExecutionResult(true, action.message ?: "任务结束")
      else -> ActionExecutionResult(
        success = false,
        summary = "未知操作: ${action.action}",
        suggestions = listOf("请使用已注册工具：${AgentToolRegistry.toolNames.joinToString()}"),
      )
    }
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
        matches += NodeFinder.findMatchingLabels(root, query, limit = 20)
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
    )
    return try {
      val tree = UiTreeSerializer.serialize(root)
      val snippet = if (tree.length > 1200) tree.take(1200) + "\n...(已截断)" else tree
      ActionExecutionResult(
        success = true,
        summary = "已读取结构树片段",
        detail = snippet,
      )
    } finally {
      root.recycle()
    }
  }

  private fun getTargetRoot(): AccessibilityNodeInfo? {
    val roots = collectExternalRoots()
    if (roots.isEmpty()) return null
    val best = roots.maxByOrNull { UiPageProbe.windowScore(it) } ?: return null
    roots.filter { it !== best }.forEach { it.recycle() }
    return best
  }

  private fun collectExternalRoots(): List<AccessibilityNodeInfo> {
    val results = mutableListOf<AccessibilityNodeInfo>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      windows?.forEach { window ->
        if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@forEach
        val root = window.root ?: return@forEach
        val pkg = root.packageName?.toString().orEmpty()
        if (pkg == packageName || pkg.isBlank()) {
          root.recycle()
          return@forEach
        }
        results += AccessibilityNodeInfo.obtain(root)
        root.recycle()
      }
    }

    if (results.isEmpty()) {
      rootInActiveWindow?.let { active ->
        val pkg = active.packageName?.toString()
        if (!pkg.isNullOrBlank() && pkg != packageName) {
          results += AccessibilityNodeInfo.obtain(active)
        }
        active.recycle()
      }
    }
    return results
  }

  private fun findExternalRoot(): AccessibilityNodeInfo? {
    lastExternalRoot?.let { cached ->
      if (System.currentTimeMillis() - lastExternalUpdatedAt <= EXTERNAL_CACHE_MS) {
        return cached
      }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      windows?.forEach { window ->
        if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) return@forEach
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

  private fun typeTextResult(input: String?): ActionExecutionResult {
    val msg = typeText(input)
    val success = !msg.contains("失败")
    return ActionExecutionResult(
      success = success,
      summary = msg,
      suggestions = if (success) emptyList() else listOf("先 click 输入框", "用 read_tree 找输入区"),
    )
  }

  private fun clickSendResult(): ActionExecutionResult {
    val msg = clickSend()
    val success = !msg.contains("失败")
    return ActionExecutionResult(success = success, summary = msg)
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
      var found: AccessibilityNodeInfo? = null
      for (root in roots) {
        found = NodeFinder.findClickableByText(root, text)
        if (found != null) break
      }
      val node = found ?: return "点击失败：未找到包含「$text」的可点击元素"
      val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
      node.recycle()
      if (clicked) "已点击：$text" else "点击失败：系统未接受点击操作"
    } finally {
      roots.forEach { it.recycle() }
    }
  }

  private fun typeText(input: String?): String {
    val text = input?.trim().orEmpty()
    if (text.isEmpty()) return "输入失败：缺少 input_text"

    val roots = collectExternalRoots()
    if (roots.isEmpty()) return "输入失败：无法读取页面"
    return try {
      val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
      var editable = focused
      if (editable == null) {
        editable = roots
          .mapNotNull { NodeFinder.findBestEditable(it) }
          .maxByOrNull { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            rect.bottom * 3 + rect.width()
          }
      }

      if (editable == null) {
        return "输入失败：未找到输入区域，可先 click 底部输入框或联系人"
      }

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
      if (focused == null) editable.recycle()
      if (ok) "已输入：$text" else "输入失败：系统未接受输入，可先 click 输入框再试"
    } finally {
      roots.forEach { it.recycle() }
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
    val path = Path().apply {
      moveTo(x, y)
    }
    val gesture = GestureDescription.Builder()
      .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
      .build()
    val dispatched = dispatchGesture(gesture, null, null)
    return if (dispatched) successMessage else null
  }

  private fun scroll(down: Boolean): String {
    val roots = collectExternalRoots()
    if (roots.isEmpty()) return "滚动失败：无法读取页面"
    return try {
      var scrollable: AccessibilityNodeInfo? = null
      for (root in roots) {
        scrollable = NodeFinder.findScrollable(root)
        if (scrollable != null) break
      }
      val node = scrollable ?: return "滚动失败：未找到可滚动区域"
      val action = if (down) {
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
      } else {
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
      }
      val ok = node.performAction(action)
      node.recycle()
      if (ok) {
        if (down) "已向下滚动" else "已向上滚动"
      } else {
        "滚动失败：系统未接受滚动"
      }
    } finally {
      roots.forEach { it.recycle() }
    }
  }

  private fun global(action: Int, successMessage: String): String {
    return if (performGlobalAction(action)) successMessage else "系统操作失败"
  }

  suspend fun swipeDown(): String = suspendCoroutine { continuation ->
    val metrics = resources.displayMetrics
    val centerX = metrics.widthPixels / 2f
    val startY = metrics.heightPixels * 0.7f
    val endY = metrics.heightPixels * 0.3f
    val path = Path().apply {
      moveTo(centerX, startY)
      lineTo(centerX, endY)
    }
    val gesture = GestureDescription.Builder()
      .addStroke(GestureDescription.StrokeDescription(path, 0, 350))
      .build()
    val dispatched = dispatchGesture(
      gesture,
      object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
          continuation.resume("已执行滑动手势")
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
    private const val EXTERNAL_CACHE_MS = 30_000L

    @Volatile
    var instance: JoyAccessibilityService? = null
      private set

    private var lastExternalPackage: String? = null
    private var lastExternalRoot: AccessibilityNodeInfo? = null
    private var lastExternalUpdatedAt: Long = 0L

    fun lastExternalPackageName(): String? = lastExternalPackage
  }
}

private object NodeFinder {
  fun findMatchingLabels(
    root: AccessibilityNodeInfo,
    query: String,
    limit: Int = 20,
  ): List<String> {
    val lower = query.lowercase()
    val tokens = lower.split(Regex("\\s+")).filter { it.isNotBlank() }
    val results = linkedSetOf<String>()
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(AccessibilityNodeInfo.obtain(root))
    var walked = 0

    while (queue.isNotEmpty() && results.size < limit && walked < 1200) {
      walked++
      val node = queue.removeFirst()
      val label = UiNodeHeuristics.nodeLabel(node)
      val nodeLower = label.lowercase()
      val matched = nodeLower.contains(lower) ||
        tokens.any { token -> token.length >= 1 && nodeLower.contains(token) }

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

  fun findClickableByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(AccessibilityNodeInfo.obtain(root))
    val lower = text.lowercase()
    val tokens = lower.split(Regex("\\s+")).filter { it.length >= 1 }

    while (queue.isNotEmpty()) {
      val node = queue.removeFirst()
      val nodeText = UiNodeHeuristics.nodeLabel(node).lowercase()
      val matched = nodeText.contains(lower) ||
        tokens.any { token -> token.length >= 2 && nodeText.contains(token) } ||
        (lower.contains("发送") && UiNodeHeuristics.isSendLike(node))

      if (matched) {
        val clickable = findClickableTarget(node)
        queue.forEach { it.recycle() }
        return clickable
      }

      for (i in 0 until node.childCount) {
        node.getChild(i)?.let(queue::add)
      }
      node.recycle()
    }
    return null
  }

  fun findBestEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    val screenHeight = UiNodeHeuristics.screenHeight(root)
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(AccessibilityNodeInfo.obtain(root))
    var best: AccessibilityNodeInfo? = null
    var bestScore = Int.MIN_VALUE

    while (queue.isNotEmpty()) {
      val node = queue.removeFirst()
      val score = UiNodeHeuristics.inputScore(node, screenHeight)
      if (score > bestScore) {
        best?.recycle()
        best = AccessibilityNodeInfo.obtain(node)
        bestScore = score
      }
      for (i in 0 until node.childCount) {
        node.getChild(i)?.let(queue::add)
      }
      node.recycle()
    }
    return best
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
