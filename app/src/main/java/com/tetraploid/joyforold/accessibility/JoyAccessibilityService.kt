package com.tetraploid.joyforold.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.tetraploid.joyforold.agent.AgentAction
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

  fun snapshotCompactForAgent(): String {
    val roots = collectExternalRoots()
    if (roots.isEmpty()) {
      return "无法读取页面，请切换到目标应用。"
    }
    return try {
      buildString {
        roots.forEachIndexed { index, root ->
          val pkg = root.packageName?.toString() ?: "unknown"
          appendLine("package: $pkg")
          append(UiPageProbe.buildSummary(root))
          if (index < roots.lastIndex) appendLine()
        }
      }
    } finally {
      roots.forEach { it.recycle() }
    }
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

  fun execute(action: AgentAction): String {
    return when (action.action.lowercase()) {
      "click" -> clickByText(action.targetText)
      "type" -> typeText(action.inputText)
      "send" -> clickSend()
      "scroll_down" -> scroll(true)
      "scroll_up" -> scroll(false)
      "back" -> global(AccessibilityService.GLOBAL_ACTION_BACK, "已执行返回")
      "home" -> global(AccessibilityService.GLOBAL_ACTION_HOME, "已回到桌面")
      "wait" -> "等待下一步"
      "finish" -> action.message ?: "任务结束"
      else -> "未知操作: ${action.action}"
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
