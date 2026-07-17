package com.tetraploid.joyforold.a11yplugin

import android.content.ComponentName

object A11yPluginContract {
    const val PACKAGE_NAME = "com.tetraploid.joyforold.a11yplugin"
    const val WHITELIST_READER_CLASS =
        "com.google.android.accessibility.selecttospeak.SelectToSpeakService"
    const val BIND_ACTION = "com.tetraploid.joyforold.a11yplugin.BIND_WHITELIST_READER"
    const val BIND_PERMISSION = "com.tetraploid.joyforold.permission.BIND_A11Y_PLUGIN"
    const val HOST_PACKAGE = "com.tetraploid.joyforold"

    fun whitelistReaderComponent(): ComponentName =
        ComponentName(PACKAGE_NAME, WHITELIST_READER_CLASS)
}
