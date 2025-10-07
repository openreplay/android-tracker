package com.openreplay.tracker.managers

import com.openreplay.tracker.OpenReplay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.openreplay.tracker.models.script.ORMobileGraphQL

/**
 * Supported message types for MessageHandler
 */
enum class MessageType(val value: String) {
    GRAPHQL("gql"),
    UNKNOWN("unknown");
    
    companion object {
        fun from(value: String): MessageType {
            return values().find { it.value == value } ?: UNKNOWN
        }
    }
}

object MessageHandler {
    private val gson = Gson()
    private val handlerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeJobs = mutableListOf<Job>()

    private fun String.toMap(): Map<String, Any> {
        val type = object : TypeToken<Map<String, Any>>() {}.type
        return gson.fromJson(this, type)
    }

    fun sendMessage(type: String, msg: Any) {
        val messageType = MessageType.from(type)

        val job = handlerScope.launch {
            when (messageType) {
                MessageType.GRAPHQL -> processGraphQLMessage(msg)
                MessageType.UNKNOWN -> {
                    DebugUtils.warn("Unknown message type: $type")
                }
            }
        }

        synchronized(activeJobs) {
            activeJobs.add(job)
            job.invokeOnCompletion {
                synchronized(activeJobs) {
                    activeJobs.remove(job)
                }
            }
        }
    }

    private suspend fun processGraphQLMessage(msg: Any) {
        try {
            val messageString = when (msg) {
                is String -> {
                    JsonParser.parseString(msg)
                    msg.trim()
                }

                else -> gson.toJson(msg)
            }

            val dict = messageString.toMap()

            val operationKind = dict["operationKind"] as? String
            val operationName = dict["operationName"] as? String

            if (operationKind.isNullOrBlank() || operationName.isNullOrBlank()) {
                DebugUtils.warn("GraphQL message missing required fields (operationKind or operationName)")
                return
            }

            val duration = when (val durationValue = dict["duration"]) {
                is Number -> durationValue.toLong().toULong()
                is String -> durationValue.toLongOrNull()?.toULong() ?: 0UL
                else -> 0UL
            }

            val variablesString = dict["variables"]?.let { variablesObj ->
                try {
                    gson.toJson(variablesObj)
                } catch (e: Exception) {
                    DebugUtils.warn("Failed to serialize GraphQL variables: ${e.message}")
                    ""
                }
            } ?: ""

            val responseString = dict["response"]?.let { responseObj ->
                try {
                    gson.toJson(responseObj)
                } catch (e: Exception) {
                    DebugUtils.warn("Failed to serialize GraphQL response: ${e.message}")
                    ""
                }
            } ?: ""

            val gqlMessage = ORMobileGraphQL(
                operationKind = operationKind,
                operationName = operationName,
                variables = variablesString,
                response = responseString,
                duration = duration
            )

            MessageCollector.sendMessage(gqlMessage)

            DebugUtils.log("GraphQL message sent: $operationKind - $operationName")

        } catch (e: Exception) {
            DebugUtils.error("Error processing GraphQL message", e)
        }
    }

    fun cancelAll() {
        synchronized(activeJobs) {
            activeJobs.forEach { it.cancel() }
            activeJobs.clear()
        }
        DebugUtils.log("All MessageHandler jobs cancelled")
    }
}
