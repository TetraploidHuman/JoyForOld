package com.tetraploid.joyforold.di

import com.tetraploid.joyforold.agent.AgentMemoryStore
import com.tetraploid.joyforold.agent.AgentSessionStore
import com.tetraploid.joyforold.agent.AppHintStore
import com.tetraploid.joyforold.agent.ContextConsentStore
import com.tetraploid.joyforold.agent.ProactiveAssistantEngine
import com.tetraploid.joyforold.agent.VisionDebugStore
import com.tetraploid.joyforold.agent.VoiceInteractionConfigStore
import com.tetraploid.joyforold.caregiver.CaregiverSupportStore
import com.tetraploid.joyforold.collaboration.AssistPairingStore
import com.tetraploid.joyforold.data.ApiKeyStore
import com.tetraploid.joyforold.preset.PresetCommandStore
import com.tetraploid.joyforold.speech.AndroidTtsOutput
import com.tetraploid.joyforold.speech.AsrSpeakerProfileStore
import com.tetraploid.joyforold.wakeword.WakeWordConfigStore
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val dataModule = module {
    single { ApiKeyStore(androidContext()) }
    single { WakeWordConfigStore(androidContext()) }
    single { AgentMemoryStore(androidContext()) }
    single { AgentSessionStore(androidContext()) }
    single { AppHintStore(androidContext()).also { it.ensureSeededDefaults() } }
    single { CaregiverSupportStore(androidContext()).also { it.ensureSeededDefaults() } }
    single { PresetCommandStore(androidContext()).also { it.ensureSeededDefaults() } }
    single { ContextConsentStore(androidContext()) }
    single { VisionDebugStore(androidContext()) }
    single { AssistPairingStore(androidContext()) }
    single { VoiceInteractionConfigStore(androidContext()) }
    single { AsrSpeakerProfileStore(androidContext()) }
    single { ProactiveAssistantEngine(androidContext()) }
    single { AndroidTtsOutput(androidContext()) }
}
