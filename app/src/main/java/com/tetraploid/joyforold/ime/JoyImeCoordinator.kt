package com.tetraploid.joyforold.ime

/**
 * 协调「Agent 正在注入文字」与「用户自己要打字」：
 * - Agent 注入期间保持 Joy IME 连接；
 * - 用户手动点输入框时自动切回上一个键盘。
 */
object JoyImeCoordinator {
    @Volatile
    var agentInjectionActive: Boolean = false
}
