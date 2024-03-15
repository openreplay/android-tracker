package com.openreplay.managers

import com.openreplay.OpenReplay

object DebugUtils {

    fun error(str: String) {
        // TODO: Implement message sending or logging as needed
        println("OpenReplay Error: $str")
    }

    fun log(str: String) {
        if (OpenReplay.options.debugLogs) {
            println(str)
        }
    }
}
