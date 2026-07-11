package com.tetraploid.joyforold.agent

import android.content.Context
import org.json.JSONArray

/**
 * 按应用包名保存 UI 操作经验，供后续 Agent prompt 注入（参考 Sanna accessibility-hint-store）。
 */
class AppHintStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ensureSeededDefaults() {
        seedIfEmpty(PKG_WECHAT, listOf("发消息可先 click 右上角搜索或通讯录中的联系人"))
        seedIfEmpty(PKG_QQ, listOf("发消息可先 click 联系人或顶部搜索"))
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

    fun formatForPrompt(packageName: String): String {
        val hints = hintsFor(packageName)
        if (hints.isEmpty()) return ""
        return hints.joinToString("；", prefix = "【本应用经验】")
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

    private fun key(packageName: String) = "hints_$packageName"

    companion object {
        private const val PREFS = "joy_app_hints"
        private const val MAX_HINTS_PER_APP = 6
        private const val MAX_HINT_CHARS = 120
        const val PKG_WECHAT = "com.tencent.mm"
        const val PKG_QQ = "com.tencent.mobileqq"
        const val PKG_ALIPAY = "com.eg.android.AlipayGphone"
    }
}
