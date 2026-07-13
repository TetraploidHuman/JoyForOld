package com.tetraploid.joyforold.di

import android.content.Context
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.module.Module

object KoinTestSupport {
    fun startAppKoin(context: Context, extraModules: List<Module> = emptyList()) {
        stopKoin()
        startKoin {
            androidContext(context)
            allowOverride(true)
            modules(appModules + extraModules)
        }
    }

    fun stopAppKoin() {
        stopKoin()
    }
}
