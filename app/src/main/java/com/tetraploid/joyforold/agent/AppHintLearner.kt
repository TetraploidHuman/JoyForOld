package com.tetraploid.joyforold.agent

/**
 * 从成功步骤中提取可复用的 App 级 Hint。
 */
object AppHintLearner {
    fun maybeLearn(store: AppHintStore?, packageName: String, action: AgentAction, success: Boolean) {
        if (store == null || !success || packageName.isBlank()) return
        when (action.action.lowercase()) {
            "click" -> {
                val target = action.targetText?.trim().orEmpty()
                if (target.length in 2..24) {
                    store.addHint(packageName, "曾通过 click「$target」完成操作")
                }
            }
            "open_app" -> {
                val app = action.targetText?.trim().orEmpty()
                if (app.isNotBlank()) {
                    store.addHint(packageName, "打开 $app 后需继续在应用内操作")
                }
            }
            "find_on_page" -> {
                val query = action.targetText?.trim().orEmpty()
                if (query.length in 2..20) {
                    store.addHint(packageName, "找不到时可 find_on_page「$query」")
                }
            }
        }
    }
}
