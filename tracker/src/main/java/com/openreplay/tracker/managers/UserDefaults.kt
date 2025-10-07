package com.openreplay.tracker.managers

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

object UserDefaults {
    private const val PREF_NAME = "OpenReplayPreferences"
    private const val KEY_USER_UUID = "userUUID"
    private const val KEY_LAST_TOKEN = "lastToken"
    
    private var preferences: SharedPreferences? = null
    private val lock = ReentrantReadWriteLock()
    private var cachedUserUUID: String? = null

    suspend fun init(context: Context) {
        withContext(Dispatchers.IO) {
            lock.write {
                try {
                    preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    // Pre-load UUID if exists
                    cachedUserUUID = preferences?.getString(KEY_USER_UUID, null)
                    if (cachedUserUUID == null) {
                        cachedUserUUID = UUID.randomUUID().toString()
                        preferences?.edit()?.putString(KEY_USER_UUID, cachedUserUUID)?.commit()
                    }
                } catch (e: Exception) {
                    DebugUtils.error("Error initializing UserDefaults: ${e.message}")
                }
            }
        }
    }

    var userUUID: String
        get() = lock.read {
            cachedUserUUID ?: run {
                lock.write {
                    // Double-check locking pattern
                    if (cachedUserUUID == null) {
                        cachedUserUUID = preferences?.getString(KEY_USER_UUID, null) ?: run {
                            val newUUID = UUID.randomUUID().toString()
                            preferences?.edit()?.putString(KEY_USER_UUID, newUUID)?.commit()
                            newUUID
                        }
                    }
                    cachedUserUUID!!
                }
            }
        }
        set(value) = lock.write {
            cachedUserUUID = value
            try {
                preferences?.edit()?.putString(KEY_USER_UUID, value)?.commit()
            } catch (e: Exception) {
                DebugUtils.error("Error setting userUUID: ${e.message}")
            }
        }

    var lastToken: String?
        get() = lock.read {
            try {
                preferences?.getString(KEY_LAST_TOKEN, null)
            } catch (e: Exception) {
                DebugUtils.error("Error getting lastToken: ${e.message}")
                null
            }
        }
        set(value) = lock.write {
            try {
                if (value != null) {
                    preferences?.edit()?.putString(KEY_LAST_TOKEN, value)?.commit()
                } else {
                    preferences?.edit()?.remove(KEY_LAST_TOKEN)?.commit()
                }
            } catch (e: Exception) {
                DebugUtils.error("Error setting lastToken: ${e.message}")
            }
        }
}