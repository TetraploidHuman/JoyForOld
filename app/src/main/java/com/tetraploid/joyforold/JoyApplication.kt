package com.tetraploid.joyforold

import android.app.Application
import com.tetraploid.joyforold.agent.AgentRuntime
import com.tetraploid.joyforold.di.appModules
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.getKoin

class JoyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@JoyApplication)
            modules(appModules)
        }
        getKoin().get<AgentRuntime>().initIfNeeded(this)
    }
}
