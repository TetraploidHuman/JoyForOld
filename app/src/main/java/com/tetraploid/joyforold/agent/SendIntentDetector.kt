package com.tetraploid.joyforold.agent

object SendIntentDetector {
    private val sendKeywords = Regex("""(发消息|发送消息|发送|发给|发一条|留言|说出|回复)""", RegexOption.IGNORE_CASE)

    fun isSendCommand(command: String): Boolean = sendKeywords.containsMatchIn(command.trim())
}
