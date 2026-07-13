package com.tetraploid.joyforold.testutil

import com.tetraploid.joyforold.util.NetworkStatus

object NetworkTestSupport {
    fun enableInternet() {
        NetworkStatus.forceOnline = true
    }

    fun reset() {
        NetworkStatus.forceOnline = null
    }
}
