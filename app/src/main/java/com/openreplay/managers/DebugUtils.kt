package com.openreplay.managers

object DebugUtils {
    fun error(str: String) {
        println("OpenReplay Error: $str")
    }

    fun log(str: String) {
//        if (OpenReplay.options.debugLogs) {
//            println(str)
//        }
    }
}
