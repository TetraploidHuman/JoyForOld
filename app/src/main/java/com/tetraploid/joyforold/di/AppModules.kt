package com.tetraploid.joyforold.di

import com.tetraploid.joyforold.agent.AgentLlmClient
import com.tetraploid.joyforold.agent.AgentOrchestrator
import com.tetraploid.joyforold.agent.AgentRuntime
import com.tetraploid.joyforold.agent.DeepSeekClient
import com.tetraploid.joyforold.ui.DemoViewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val agentModule = module {
    single<AgentLlmClient> { DeepSeekClient() }
    singleOf(::AgentOrchestrator)
    singleOf(::AgentRuntime)
}

val viewModelModule = module {
    viewModelOf(::DemoViewModel)
}

val appModules = listOf(dataModule, agentModule, viewModelModule)
