package com.tetraploid.joyforold.agent.actionsets.dsl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionSetDslTest {
    @Test
    fun actionSet_buildsPhasesInOrder() {
        val def = actionSet("demo") {
            params {
                required("x", from = ParamSource.INPUT_TEXT)
            }
            flow {
                phase("a") { openApp("A") }
                phase("b") { capturePageTexts(into = "cands") }
                phase("c") {
                    askLlm(writeTo = listOf("x")) {
                        system("sys")
                        user { "u=${it["x"]}" }
                    }
                }
            }
        }
        assertEquals(listOf("a", "b", "c"), def.phases.keys.toList())
        assertEquals("a", def.startPhaseId)
        assertTrue(def.phases.getValue("b").kind is PhaseKind.CapturePageTexts)
        assertTrue(def.phases.getValue("c").kind is PhaseKind.AskLlm)
    }

    @Test
    fun askLlm_cannotMixWithActions() {
        assertThrows(IllegalArgumentException::class.java) {
            actionSet("bad") {
                flow {
                    phase("mixed") {
                        click("x")
                        askLlm(writeTo = listOf("y")) {
                            system("s")
                            user("u")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun capture_cannotMixWithAskLlm() {
        assertThrows(IllegalArgumentException::class.java) {
            actionSet("bad") {
                flow {
                    phase("mixed") {
                        capturePageTexts(into = "c")
                        askLlm(writeTo = listOf("y")) {
                            system("s")
                            user("u")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun resolveActions_emitsMarkersForSpecialPhases() {
        val def = actionSet("demo") {
            params { required("n", from = ParamSource.INPUT_TEXT) }
            flow {
                phase("collect") { capturePageTexts(into = "cands") }
                phase("resolve") {
                    askLlm(writeTo = listOf("n")) {
                        system("s")
                        user("u")
                    }
                }
            }
        }
        val params = ActionSetParams(mapOf("n" to "响"))
        val capture = def.resolveActions("collect", params).single()
        assertEquals(ACTION_CAPTURE_PAGE_TEXTS, capture.action)
        assertEquals("cands", capture.targetText)
        val ask = def.resolveActions("resolve", params).single()
        assertEquals(ACTION_ASK_LLM, ask.action)
        assertEquals("resolve", ask.targetText)
    }
}
