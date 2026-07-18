package com.tetraploid.joyforold.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentCapabilityMatrixTest {

  @Test
  fun inferPageContextNeed_complexQueryIsNone() {
    assertEquals(
      IntentCapabilityMatrix.PageContextNeed.NONE,
      IntentCapabilityMatrix.inferPageContextNeed("煤气味很重怎么办"),
    )
    assertEquals(
      IntentCapabilityMatrix.PageContextNeed.NONE,
      IntentCapabilityMatrix.inferPageContextNeed("明天会降温吗我该穿啥"),
    )
  }

  @Test
  fun inferPageContextNeed_uiHintsNeedFull() {
    assertEquals(
      IntentCapabilityMatrix.PageContextNeed.UI_FULL,
      IntentCapabilityMatrix.inferPageContextNeed("帮我点击发送按钮"),
    )
    assertEquals(
      IntentCapabilityMatrix.PageContextNeed.UI_FULL,
      IntentCapabilityMatrix.inferPageContextNeed("上微信跟老战友视频"),
    )
  }

  @Test
  fun inferPageContextNeed_actionSetIntentsAreMinimal() {
    assertEquals(
      IntentCapabilityMatrix.PageContextNeed.MINIMAL,
      IntentCapabilityMatrix.inferPageContextNeed("淘宝搜一加手机"),
    )
    // 发消息（含微信）一律 UI_FULL，交给 LLM，不强制动作组
    assertEquals(
      IntentCapabilityMatrix.PageContextNeed.UI_FULL,
      IntentCapabilityMatrix.inferPageContextNeed("微信给儿子发消息"),
    )
    assertEquals(
      IntentCapabilityMatrix.PageContextNeed.UI_FULL,
      IntentCapabilityMatrix.inferPageContextNeed("给大女儿发消息说今晚回家吃饭"),
    )
    assertEquals(
      IntentCapabilityMatrix.PageContextNeed.UI_FULL,
      IntentCapabilityMatrix.inferPageContextNeed("去tim给三八老大发消息说你是猪"),
    )
  }

  @Test
  fun prefersActionSetEntry_matchesKnownSets() {
    assertTrue(IntentCapabilityMatrix.prefersActionSetEntry("帮我在淘宝找耳机"))
    assertTrue(IntentCapabilityMatrix.prefersActionSetEntry("带我去最近的肯德基"))
    assertFalse(IntentCapabilityMatrix.prefersActionSetEntry("微信给响发消息"))
    assertFalse(IntentCapabilityMatrix.prefersActionSetEntry("给响发微信"))
    assertFalse(IntentCapabilityMatrix.prefersActionSetEntry("给响发消息"))
    assertFalse(IntentCapabilityMatrix.prefersActionSetEntry("去tim给三八老大发消息"))
    assertFalse(IntentCapabilityMatrix.prefersActionSetEntry("导航回家"))
    assertFalse(IntentCapabilityMatrix.prefersActionSetEntry("给响发短信"))
    assertFalse(IntentCapabilityMatrix.prefersActionSetEntry("帮我点击发送按钮"))
  }

  @Test
  fun toolsPromptForMinimal_includesRunActionSet() {
    val prompt = IntentCapabilityMatrix.toolsPromptForContext(
      IntentCapabilityMatrix.PageContextNeed.MINIMAL,
    )
    assertTrue(prompt.contains("run_action_set"))
    assertTrue(prompt.contains("taobao_search") || prompt.contains("send_im_message"))
  }

  @Test
  fun passesOfflineNluGate_riskyIntentNeedsHigherConfidence() {
    assertFalse(
      IntentCapabilityMatrix.passesOfflineNluGate(
        intentId = "open_payment_code",
        modelConfidence = 0.90f,
        modelMargin = 0.30f,
      ),
    )
    assertTrue(
      IntentCapabilityMatrix.passesOfflineNluGate(
        intentId = "open_payment_code",
        modelConfidence = 0.93f,
        modelMargin = 0.40f,
      ),
    )
  }

  @Test
  fun isRouteAllowed_blocksCloudWhenOffline() {
    val route = CommandRouteResolver.Route(
      steps = listOf(AgentAction(action = "set_alarm", targetText = "7:00")),
      source = "system_ai",
      confidence = 0.9,
    )
    val offline = IntentCapabilityMatrix.RouteEnvironment(hasNetwork = false, hasAccessibility = true)
    assertFalse(IntentCapabilityMatrix.isRouteAllowed(route, offline))
  }

  @Test
  fun pageContextModeForNeed_noneOmitsContext() {
    assertEquals(
      PageContextMode.NONE,
      IntentCapabilityMatrix.pageContextModeForNeed(
        IntentCapabilityMatrix.PageContextNeed.NONE,
        PageContextMode.FULL,
      ),
    )
  }

  @Test
  fun isMultiStepUtterance_detectsOpenAppPlusMessage() {
    assertTrue(
      IntentCapabilityMatrix.isMultiStepUtterance("打开微信，给大女儿发消息说今晚回家吃饭"),
    )
    assertFalse(IntentCapabilityMatrix.isMultiStepUtterance("打开微信"))
  }

  @Test
  fun shouldExecuteRouteLocally_blocksOpenAppOnlyOnMultiStep() {
    val route = CommandRouteResolver.Route(
      steps = listOf(
        AgentAction(action = "open_app", targetText = "微信"),
        AgentAction(action = "finish", message = "已打开", finished = true),
      ),
      source = "offline_nlu",
      confidence = 0.94,
    )
    assertFalse(
      IntentCapabilityMatrix.shouldExecuteRouteLocally(
        "打开微信，给大女儿发消息说今晚回家吃饭",
        route,
      ),
    )
    assertTrue(
      IntentCapabilityMatrix.shouldExecuteRouteLocally("打开微信", route),
    )
  }

  @Test
  fun shouldExecuteRouteLocally_keepsExactTemplate() {
    val route = CommandRouteResolver.Route(
      steps = listOf(
        AgentAction(action = "open_app", targetText = "微信"),
        AgentAction(action = "finish", message = "已打开微信", finished = true),
      ),
      source = "template",
      confidence = 1.0,
    )
    assertTrue(
      IntentCapabilityMatrix.shouldExecuteRouteLocally("给儿子发微信", route),
    )
  }
}
