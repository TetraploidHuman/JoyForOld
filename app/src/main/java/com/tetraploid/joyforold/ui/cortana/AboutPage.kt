package com.tetraploid.joyforold.ui.cortana

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tetraploid.joyforold.BuildConfig
import com.tetraploid.joyforold.agent.AgentUiState
import com.tetraploid.joyforold.ui.theme.CortanaColors
import com.tetraploid.joyforold.ui.theme.JoyTextSizes

@Composable
fun AboutPage(
    uiState: AgentUiState,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CortanaColors.Background)
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CortanaOrb(size = 72.dp, mood = CortanaOrbMood.Idle)
        Text(
            text = "JoyForOld",
            color = CortanaColors.OnBackground,
            fontSize = JoyTextSizes.Display,
            lineHeight = 34.sp,
        )
        Text(
            text = "版本 ${BuildConfig.VERSION_NAME}　专为长辈设计的语音助手",
            color = CortanaColors.OnBackgroundMuted,
            fontSize = JoyTextSizes.Caption,
            lineHeight = JoyTextSizes.CaptionLineHeight,
        )

        AboutDivider()
        AboutSectionTitle("它是做什么的")
        AboutBody(
            "JoyForOld 帮您用说话或打字完成日常手机操作。" +
                "您可以说「打电话给女儿」「给儿子发消息」「我要回家」「今天天气怎么样」，" +
                "助手会听懂意思，并尽量帮您在手机上一步步做完。",
        )
        AboutBody(
            "它不只是陪您聊天，更重视把事情办成：" +
                "听懂您的话、看懂当前屏幕、想清楚怎么做、再替您点按操作，必要时还会再问您一声确认。",
        )

        AboutDivider()
        AboutSectionTitle("能帮您做什么")
        AboutBullet("打电话、发微信消息（重要操作会先跟您确认）")
        AboutBullet("导航回家、查附近地点、看天气、问时间")
        AboutBullet("读未读通知、打开常用应用")
        AboutBullet("家人远程协助：子女可在征得同意后帮您一起操作手机")
        AboutBullet("语音唤醒：说唤醒词就能开始对话（可在「设置」里开关）")

        AboutDivider()
        AboutSectionTitle("怎么用更方便")
        AboutBody("1. 先到「设置」页打开无障碍服务，助手才能帮您点屏幕。")
        AboutBody("2. 需要说话时，允许使用麦克风；若要按姓名打电话，可再打开联系人权限。")
        AboutBody("3. 在「助手」页按麦克风说话，或直接输入文字后发送。")
        AboutBody("4. 也可以点页面上的常用说法，少打字、少记步骤。")
        AboutBody("5. 不确定时可以说慢一点，说完稍停一下，助手会继续听。")

        AboutDivider()
        AboutSectionTitle("安全与确认")
        AboutBody(
            "涉及发消息、拨打电话等重要操作时，助手会先用语音或屏幕再问您一次。" +
                "您可以说「确认」或「取消」，也可以点按钮选择。",
        )
        AboutBody(
            "拿不准时，宁可多问一句，也不贸然替您发出去。" +
                "若操作中途想停下，可以说取消，或点「停止」。",
        )

        AboutDivider()
        AboutSectionTitle("隐私说明")
        AboutBody(
            "麦克风、联系人、无障碍、悬浮窗等权限，只在对应功能需要时使用。" +
                "若开启「允许云端理解屏幕内容」，助手才会在办事时把当前界面信息发到云端帮助理解；" +
                "不开启则尽量走本地能力。您可随时在「设置」里关闭。",
        )

        AboutDivider()
        AboutSectionTitle("给家人的话")
        AboutBody(
            "子女可在「协作」页与您建立协助关系，在您需要时远程帮忙。" +
                "建议先一起把回家地址、紧急联系人等常用信息填好，" +
                "这样您说「我要回家」「打电话给女儿」时会更省心。",
        )

        AboutDivider()
        AboutSectionTitle("版本信息")
        AboutBody("应用版本：${BuildConfig.VERSION_NAME}")
        AboutBody("当前智能模型：${uiState.modelName}")
        AboutBody(
            "本应用面向老年用户的日常手机协助场景。" +
                "健康提醒等仅为生活提醒，不能代替医院诊断或用药医嘱。",
        )

        if (uiState.recentMemories.isNotEmpty()) {
            AboutDivider()
            AboutSectionTitle("近期记忆")
            AboutBody("助手记住的一些近期事项，方便下次更好地帮您：")
            uiState.recentMemories.take(5).forEach { memory ->
                AboutBullet(memory)
            }
        }

        AboutDivider()
        Text(
            text = "感谢使用 JoyForOld。有事情，跟助手说一声就好。",
            color = CortanaColors.AccentMuted,
            fontSize = JoyTextSizes.BodySecondary,
            lineHeight = JoyTextSizes.BodyLineHeight,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
        )
    }
}

@Composable
private fun AboutSectionTitle(text: String) {
    Text(
        text = text,
        color = CortanaColors.Accent,
        fontSize = JoyTextSizes.Label,
        letterSpacing = 0.4.sp,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
    )
}

@Composable
private fun AboutBody(text: String) {
    Text(
        text = text,
        color = CortanaColors.OnBackground,
        fontSize = JoyTextSizes.Body,
        lineHeight = JoyTextSizes.BodyLineHeight,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun AboutBullet(text: String) {
    Text(
        text = "· $text",
        color = CortanaColors.OnBackgroundSecondary,
        fontSize = JoyTextSizes.BodySecondary,
        lineHeight = JoyTextSizes.BodyLineHeight,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 2.dp),
    )
}

@Composable
private fun AboutDivider() {
    HorizontalDivider(
        color = CortanaColors.Divider,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}
