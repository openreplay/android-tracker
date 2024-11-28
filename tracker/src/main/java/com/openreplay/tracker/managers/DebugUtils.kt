package com.openreplay.tracker.managers

object DebugUtils {
    fun error(str: String) {
        println("OpenReplay Error: $str")
    }

    fun log(str: String) {
        println(str)
    }

    fun error(e: Throwable) {
        println("OpenReplay Error: ${e.localizedMessage}")
    }
}
