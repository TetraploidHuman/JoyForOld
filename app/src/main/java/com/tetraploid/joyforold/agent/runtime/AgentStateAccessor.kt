package com.tetraploid.joyforold.agent.runtime

import com.tetraploid.joyforold.agent.AgentUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** AgentRuntime 状态读写的薄封装，供子控制器共享。 */
internal class AgentStateAccessor(
    private val stateFlow: MutableStateFlow<AgentUiState>,
) {
    fun read(): AgentUiState = stateFlow.value

    fun update(transform: (AgentUiState) -> AgentUiState) {
        stateFlow.update(transform)
    }
}
