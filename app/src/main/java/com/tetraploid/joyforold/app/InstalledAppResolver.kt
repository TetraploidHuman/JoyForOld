package com.tetraploid.joyforold.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

data class LaunchableApp(
    val label: String,
    val packageName: String,
)

object InstalledAppResolver {
    private const val CACHE_TTL_MS = 120_000L

    @Volatile
    private var cachedApps: List<LaunchableApp>? = null

    @Volatile
    private var cachedAtMs: Long = 0L

    private val APP_ALIASES = mapOf(
        "qq" to "com.tencent.mobileqq",
        "腾讯qq" to "com.tencent.mobileqq",
        "微信" to "com.tencent.mm",
        "wechat" to "com.tencent.mm",
        "电话" to "com.android.dialer",
        "拨号" to "com.android.dialer",
        "联系人" to "com.android.contacts",
        "短信" to "com.android.mms",
        "设置" to "com.android.settings",
        "相册" to "com.android.gallery3d",
        "相机" to "com.android.camera",
        "浏览器" to "com.android.browser",
        "抖音" to "com.ss.android.ugc.aweme",
        "快手" to "com.smile.gifmaker",
        "淘宝" to "com.taobao.taobao",
        "支付宝" to "com.eg.android.AlipayGphone",
        "京东" to "com.jingdong.app.mall",
        "美团" to "com.sankuai.meituan",
        "高德地图" to "com.autonavi.minimap",
        "地图" to "com.autonavi.minimap",
        "小红书" to "com.xingin.xhs",
        "b站" to "tv.danmaku.bili",
        "bilibili" to "tv.danmaku.bili",
        "哔哩哔哩" to "tv.danmaku.bili",
    )

    private val DIALER_PACKAGES = listOf(
        "com.android.dialer",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.huawei.contacts",
        "com.hihonor.contacts",
        "com.miui.dialer",
        "com.coloros.dialer",
        "com.vivo.dialer",
    )

    fun getLaunchableApps(context: Context): List<LaunchableApp> {
        val now = System.currentTimeMillis()
        cachedApps?.let { apps ->
            if (now - cachedAtMs < CACHE_TTL_MS) return apps
        }

        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }
        val apps = resolved.mapNotNull { info ->
            val label = info.loadLabel(pm)?.toString()?.trim().orEmpty()
            val pkg = info.activityInfo.packageName
            if (label.isBlank() || pkg == context.packageName) return@mapNotNull null
            LaunchableApp(label = label, packageName = pkg)
        }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }

        cachedApps = apps
        cachedAtMs = now
        return apps
    }

    fun resolvePackage(context: Context, query: String): String? {
        val candidates = buildQueryCandidates(query)
        for (candidate in candidates) {
            resolvePackageInternal(context, candidate)?.let { return it }
        }
        return null
    }

    private fun resolvePackageInternal(context: Context, normalized: String): String? {
        if (normalized.isBlank()) return null

        APP_ALIASES[normalized]?.let { return resolveInstalledAlias(context, it) }
        APP_ALIASES.entries.firstOrNull { (alias, _) -> normalized.contains(alias) }
            ?.value
            ?.let { return resolveInstalledAlias(context, it) }

        if (normalized.contains("电话") || normalized.contains("拨号")) {
            resolveDialerPackage(context)?.let { return it }
        }

        val apps = getLaunchableApps(context)
        apps.firstOrNull { normalizeQuery(it.label) == normalized }
            ?.packageName
            ?.let { return it }

        apps.filter { app ->
            val label = normalizeQuery(app.label)
            label.contains(normalized) || normalized.contains(label)
        }.maxByOrNull { scoreAppMatch(normalized, it) }
            ?.packageName
            ?.let { return it }

        return null
    }

    private fun buildQueryCandidates(query: String): List<String> {
        val base = normalizeQuery(query)
        if (base.isBlank()) return emptyList()
        val variants = linkedSetOf(base)
        val stripped = base
            .removePrefix("打开")
            .removePrefix("启动")
            .removePrefix("运行")
            .removePrefix("进入")
            .removeSuffix("app")
            .removeSuffix("应用")
            .removeSuffix("软件")
            .removeSuffix("客户端")
            .trim()
        if (stripped.isNotBlank()) variants += stripped
        return variants.toList()
    }

    fun suggestMatches(context: Context, query: String, limit: Int = 5): List<LaunchableApp> {
        val normalized = normalizeQuery(query)
        if (normalized.isBlank()) return emptyList()
        return getLaunchableApps(context)
            .map { app -> scoreAppMatch(normalized, app) to app }
            .filter { it.first > 0 }
            .sortedByDescending { it.first }
            .map { it.second }
            .distinctBy { it.packageName }
            .take(limit)
    }

    fun formatForPrompt(context: Context, limit: Int = 30): String {
        val apps = getLaunchableApps(context)
        if (apps.isEmpty()) {
            return "（未能读取已安装应用列表，请检查系统包可见性权限）"
        }
        val shown = apps.take(limit)
        val header = "共 ${apps.size} 个可打开应用，以下为名称（open_app 必须逐字使用）："
        val body = shown.joinToString("\n") { app -> "- ${app.label}" }
        val tail = if (apps.size > limit) {
            "\n... 还有 ${apps.size - limit} 个未列出，可用 list_apps 并在 target_text 填关键词筛选"
        } else {
            ""
        }
        return "$header\n$body$tail"
    }

    fun formatSearchMatches(context: Context, query: String, limit: Int = 20): String {
        val matches = suggestMatches(context, query, limit)
        if (matches.isEmpty()) {
            return "未找到与「$query」匹配的应用。请换关键词，或调用 list_apps（不传 target_text）查看完整列表。"
        }
        return buildString {
            appendLine("与「$query」匹配的应用（open_app 须逐字使用下列名称）：")
            matches.forEach { appendLine("- ${it.label}") }
        }.trimEnd()
    }

    fun invalidateCache() {
        cachedApps = null
        cachedAtMs = 0L
    }

    private fun normalizeQuery(query: String): String {
        return query.trim()
            .lowercase()
            .replace(Regex("\\s+"), "")
            .replace(Regex("[，,。；;：:！!？?]"), "")
    }

    private fun resolveInstalledAlias(context: Context, preferredPackage: String): String? {
        val pm = context.packageManager
        if (pm.getLaunchIntentForPackage(preferredPackage) != null) return preferredPackage
        if (preferredPackage == "com.android.dialer") {
            return resolveDialerPackage(context)
        }
        return null
    }

    private fun resolveDialerPackage(context: Context): String? {
        val pm = context.packageManager
        return DIALER_PACKAGES.firstOrNull { pkg ->
            pm.getLaunchIntentForPackage(pkg) != null
        } ?: getLaunchableApps(context).firstOrNull { app ->
            app.label.contains("电话") || app.label.contains("拨号")
        }?.packageName
    }

    private fun scoreAppMatch(query: String, app: LaunchableApp): Int {
        val label = normalizeQuery(app.label)
        return when {
            label == query -> 100
            label.startsWith(query) -> 80
            label.contains(query) -> 60
            query.contains(label) -> 40
            else -> 0
        }
    }
}
