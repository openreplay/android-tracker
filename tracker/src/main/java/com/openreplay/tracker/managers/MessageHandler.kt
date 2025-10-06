package com.openreplay.tracker.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.openreplay.tracker.models.script.ORMobileGraphQL

class MessageHandler {

    companion object {
        private val gson = Gson()

        private fun String.toMap(): Map<String, Any> {
            val type = object : TypeToken<Map<String, Any>>() {}.type
            return gson.fromJson(this, type)
        }

        private fun ByteArray.toHexString(): String {
            return joinToString("") { "%02x".format(it) }
        }

        fun sendMessage(type: String, msg: Any) {
            CoroutineScope(Dispatchers.IO).launch {
                if (type == "gql") {
                    val messageString = try {
                        when (msg) {
                            is String -> {
                                // Validate and escape JSON string
                                JsonParser.parseString(msg) // Throws exception if invalid
                                msg.trim()
                            }

                            else -> gson.toJson(msg) // Serialize other types to JSON
                        }
                    } catch (e: Exception) {
                        DebugUtils.error("Error serializing or validating GraphQL message", e)
                        return@launch
                    }

                    val dict = try {
                        messageString.toMap()
                    } catch (e: Exception) {
                        DebugUtils.error("Error parsing GraphQL message", e)
                        return@launch
                    }

                    val operationKind = dict["operationKind"] as? String ?: ""
                    val operationName = dict["operationName"] as? String ?: ""
                    val duration = (dict["duration"] as? Double)?.toLong()?.toULong() ?: 0UL

                    var variablesString = ""
                    dict["variables"]?.let { variablesObj ->
                        variablesString = try {
                            gson.toJson(variablesObj)
                        } catch (e: Exception) {
                            ""
                        }
                    }

                    var responseString = ""
                    dict["response"]?.let { responseObj ->
                        responseString = try {
                            gson.toJson(responseObj)
                        } catch (e: Exception) {
                            ""
                        }
                    }

                    val gqlMessage = ORMobileGraphQL(
                        operationKind,
                        operationName,
                        variablesString,
                        responseString,
                        duration
                    )
//                    val hexStr = testGqlMessage.contentData().toHexString()
//                    println(hexStr)
                    MessageCollector.sendMessage(gqlMessage)
                } else {
                    DebugUtils.warn("Unknown message type passed: $type")
                }
            }
        }
    }
}
