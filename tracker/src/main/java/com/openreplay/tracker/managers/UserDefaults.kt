package com.openreplay.tracker.managers

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

object UserDefaults {
    private const val PREF_NAME = "OpenReplayPreferences"
    private var preferences: SharedPreferences? = null

    suspend fun init(context: Context) {
        withContext(Dispatchers.IO) {
            preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }
    }

    var userUUID: String
        get() = preferences?.getString("userUUID", null) ?: run {
            val newUUID = UUID.randomUUID().toString()
            userUUID = newUUID
            newUUID
        }
        set(value) {
            preferences?.edit()?.putString("userUUID", value)?.apply()
        }

    var lastToken: String?
        get() = preferences?.getString("lastToken", null)
        set(value) {
            preferences?.edit()?.putString("lastToken", value)?.apply()
        }
}