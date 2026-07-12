package com.tetraploid.joyforold.agent

import android.content.Context
import org.json.JSONArray

/**
 * 按应用包名保存 UI 操作经验，供后续 Agent prompt 注入（参考 Sanna accessibility-hint-store）。
 */
class AppHintStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ensureSeededDefaults() {
        migrateAllLegacyClickHints()
        migrateAllStaleCoordinateHints()
        seedIfEmpty(PKG_ALIPAY, listOf("付款码/健康码通常在首页顶部入口"))
    }

    fun hintsFor(packageName: String): List<String> {
        if (packageName.isBlank()) return emptyList()
        val raw = prefs.getString(key(packageName), null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val item = arr.optString(i).trim()
                    if (item.isNotBlank()) add(item)
                }
            }
        }.getOrDefault(emptyList())
    }

    fun formatForPrompt(packageName: String, a11yReadable: Boolean = false): String {
        val hints = if (a11yReadable) {
            hintsFor(packageName).filterNot(::isStaleVisionOnlyHint)
        } else {
            hintsFor(packageName)
        }
        if (hints.isEmpty()) return ""
        return hints.joinToString("；", prefix = "【本应用经验】")
    }

    /** 无障碍可用时勿注入「禁用 click」类旧经验，避免误导 Agent 盲目 tap。 */
    private fun isStaleVisionOnlyHint(hint: String): Boolean {
        val lower = hint.lowercase()
        return lower.contains("无障碍树不可用") ||
            lower.contains("勿用 click") ||
            lower.contains("勿用 read_tree") ||
            (lower.contains("tap 坐标") && lower.contains("勿用"))
    }

    fun addHint(packageName: String, hint: String) {
        val normalized = hint.trim().take(MAX_HINT_CHARS)
        if (packageName.isBlank() || normalized.isBlank()) return
        val current = hintsFor(packageName).toMutableList()
        if (current.any { it.equals(normalized, ignoreCase = true) }) return
        current.add(0, normalized)
        val trimmed = current.take(MAX_HINTS_PER_APP)
        prefs.edit().putString(key(packageName), JSONArray(trimmed).toString()).apply()
    }

    private fun seedIfEmpty(packageName: String, defaults: List<String>) {
        if (hintsFor(packageName).isNotEmpty()) return
        defaults.forEach { addHint(packageName, it) }
    }

    /** 旧版 hint 写 click/read_tree，需升级为 tap 视觉指引。 */
    private fun migrateLegacyClickHints(packageName: String) {
        val hints = hintsFor(packageName)
        if (hints.isEmpty()) return
        val stale = hints.any {
            it.contains("click", ignoreCase = true) &&
                !it.contains("tap", ignoreCase = true)
        }
        if (!stale) return
        prefs.edit().remove(key(packageName)).apply()
        addHint(packageName, GENERIC_VISION_HINT)
    }

    /** 清除含固定 UI 位置描述的旧 hint（坐标由 LLM 看截图决定）。 */
    private fun migrateStaleCoordinateHints(packageName: String) {
        val hints = hintsFor(packageName)
        if (hints.isEmpty()) return
        val stale = hints.any { hint ->
            STALE_POSITION_MARKERS.any { marker -> hint.contains(marker) }
        }
        if (!stale) return
        prefs.edit().remove(key(packageName)).apply()
        addHint(packageName, GENERIC_VISION_HINT)
    }

    private fun migrateAllLegacyClickHints() {
        allStoredPackageNames().forEach { migrateLegacyClickHints(it) }
    }

    private fun migrateAllStaleCoordinateHints() {
        allStoredPackageNames().forEach { migrateStaleCoordinateHints(it) }
    }

    private fun allStoredPackageNames(): List<String> =
        prefs.all.keys
            .mapNotNull { rawKey ->
                if (!rawKey.startsWith(HINT_KEY_PREFIX)) return@mapNotNull null
                rawKey.removePrefix(HINT_KEY_PREFIX).trim().takeIf { it.isNotBlank() }
            }

    private fun key(packageName: String) = "$HINT_KEY_PREFIX$packageName"

    companion object {
        private const val PREFS = "joy_app_hints"
        private const val HINT_KEY_PREFIX = "hints_"
        private const val MAX_HINTS_PER_APP = 6
        private const val MAX_HINT_CHARS = 120
        private const val GENERIC_VISION_HINT =
            "快览为空时用 tap 坐标操作；有可点击项时优先 click，输入前聚焦输入框再 type"
        private val STALE_POSITION_MARKERS = listOf(
            "右上角",
            "左上角",
            "右下角",
            "左下角",
            "会话列表在中部",
        )
        const val PKG_WECHAT = "com.tencent.mm"
        const val PKG_QQ = "com.tencent.mobileqq"
        const val PKG_ALIPAY = "com.eg.android.AlipayGphone"
    }
}
