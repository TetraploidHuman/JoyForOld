package com.tetraploid.joyforold.di

import com.tetraploid.joyforold.agent.AgentRuntime
import org.koin.core.context.GlobalContext

/** 供 Service / 无障碍等非 Compose 入口获取已注入的 [AgentRuntime]。 */
fun agentRuntime(): AgentRuntime = GlobalContext.get().get()
