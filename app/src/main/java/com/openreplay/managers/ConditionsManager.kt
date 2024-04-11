package com.openreplay.managers

import NetworkManager
import com.openreplay.OpenReplay
import com.openreplay.models.ORMessage
import com.openreplay.models.script.*
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class Filter(
    val operator: String,
    val value: List<String>,
    val type: String,
    val source: String? = null,
    val filters: List<Filter>? = null
)

data class ApiResponse(
    val name: String,
    val filters: List<Filter>
)

data class Condition(
    val name: String,
    val target: List<String>,
    val op: (String) -> Boolean,
    val type: String,
    val tp: ORMessageType,
    val subConditions: List<Condition>? = null
)

object ConditionsManager {
    private var mappedConditions: MutableList<Condition> = mutableListOf()

    fun processMessage(msg: ORMessage): String? {
        val messageType = msg.message ?: return null

        val matchingConditions = mappedConditions.filter { it.tp == messageType }
        for (activeCon in matchingConditions) {
            when (msg) {
                is ORMobileNetworkCall -> {
                    activeCon.subConditions?.let { subConditions ->
                        var networkConditionsMet = true
                        for (networkCondition in subConditions) {
                            networkConditionsMet = when (networkCondition.name) {
                                "fetchUrl" -> networkConditionsMet && networkCondition.op(msg.URL)

                                "fetchStatusCode" -> networkConditionsMet && networkCondition.op(msg.status.toString())

                                "fetchMethod" -> networkConditionsMet && networkCondition.op(msg.method)

                                "fetchDuration" -> networkConditionsMet && networkCondition.op(msg.duration.toString())

                                else -> continue
                            }
                        }
                        if (networkConditionsMet) return activeCon.name
                    }
                }

                is ORMobileLog -> {
                    activeCon.subConditions?.let { subConditions ->
                        var logConditionsMet = true
                        for (logCondition in subConditions) {
                            when (logCondition.name) {
                                "logSeverity" -> logConditionsMet =
                                    logConditionsMet && logCondition.op(msg.severity)

                                "logContent" -> logConditionsMet =
                                    logConditionsMet && logCondition.op(msg.content)

                                else -> continue
                            }
                        }
                        if (logConditionsMet) return activeCon.name
                    }
                }

//                is ORMobileCrash -> {
//                    activeCon.subConditions?.let { subConditions ->
//                        var crashConditionsMet = true
//                        for (crashCondition in subConditions) {
//                            when (crashCondition.name) {
//                                "crashSeverity" -> crashConditionsMet =
//                                    crashConditionsMet && crashCondition.op(msg.severity)
//
//                                "crashContent" -> crashConditionsMet =
//                                    crashConditionsMet && crashCondition.op(msg.content)
//
//                                else -> continue
//                            }
//                        }
//                        if (crashConditionsMet) return activeCon.name
//                    }
//                }
                is ORMobileMetadata -> {
                    activeCon.subConditions?.let { subConditions ->
                        var metadataConditionsMet = true
                        for (metadataCondition in subConditions) {
                            metadataConditionsMet = when (metadataCondition.name) {
                                "metadataKey" -> metadataConditionsMet && metadataCondition.op(msg.key)

                                "metadataValue" -> metadataConditionsMet && metadataCondition.op(msg.value)

                                else -> continue
                            }
                        }
                        if (metadataConditionsMet) return activeCon.name
                    }
                }

                is ORMobileClickEvent -> {
                    activeCon.subConditions?.let { subConditions ->
                        var clickConditionsMet = true
                        for (clickCondition in subConditions) {
                            clickConditionsMet = when (clickCondition.name) {
                                "clickLabel" -> clickConditionsMet && clickCondition.op(msg.label)
                                else -> continue
                            }
                        }
                        if (clickConditionsMet) return activeCon.name
                    }
                }

                else -> continue
            }
        }
        return null
    }

    fun getConditions() {
        NetworkManager.getConditions() { resp ->
            mapConditions(resp)
        }
    }

    private fun mapConditions(resp: List<ApiResponse>) {
        val conds = mutableListOf<Condition>()
        resp.forEach { condition ->
            val filters = condition.filters

            filters.forEach() { filter ->
                when (filter.type) {
                    "session_duration" -> {
                        durationCond(dur = filter.value, name = condition.name)
                    }

                    "network_message" -> {
                        val networkConditions = filter.filters?.mapNotNull { subFilter ->
                            OperatorsManager.mapConditions(subFilter)
                        } ?: mutableListOf()

                        if (networkConditions.isNotEmpty()) {
                            val combinedCondition = Condition(
                                name = condition.name,
                                target = emptyList(),
                                op = { true },
                                type = "network_message",
                                tp = ORMessageType.MobileNetworkCall,
                                subConditions = networkConditions
                            )
                            conds.add(combinedCondition)
                        }
                    }

                    else -> {
                        val mappedCondition = OperatorsManager.mapConditions(filter)
                        if (mappedCondition != null) {
                            conds.add(mappedCondition)
                        }
                    }
                }
            }
        }
        DebugUtils.log("conditions $conds")
        if (conds.isNotEmpty()) {
            mappedConditions = conds // Assuming mappedConditions is a mutable property of the class
        }
    }

    private fun durationCond(dur: List<String>, name: String) {
        val scheduler = Executors.newSingleThreadScheduledExecutor()
        var scheduledFuture: ScheduledFuture<*>? = null

        scheduledFuture = scheduler.scheduleAtFixedRate({
            val now = System.currentTimeMillis()
            val diff = now - OpenReplay.sessionStartTs
            if (dur.any { (it.toLongOrNull() ?: 9999999L) <= diff }) {
//                OpenReplay.triggerRecording(name)
                scheduledFuture?.cancel(false) // Cancel the scheduled task after triggering
            }
        }, 1, 1, TimeUnit.SECONDS) // Schedule the task to run every second
    }
}

object OperatorsManager {
    fun isAnyOp(value: String, target: List<String>): Boolean = true
    fun isOp(value: String, target: List<String>): Boolean = target.contains(value)
    fun isNotOp(value: String, target: List<String>): Boolean = !target.contains(value)
    fun containsOp(value: String, target: List<String>): Boolean = target.any { it.contains(value) }
    fun notContainsOp(value: String, target: List<String>): Boolean = !target.any { it.contains(value) }
    fun startsWithOp(value: String, target: List<String>): Boolean = target.any { it.startsWith(value) }
    fun endsWithOp(value: String, target: List<String>): Boolean = target.any { it.endsWith(value) }
    fun greaterThanOp(value: String, target: List<String>): Boolean = target.any {
        (it.toFloatOrNull() ?: 0f) > (value.toFloatOrNull() ?: 0f)
    }

    fun lessThanOp(value: String, target: List<String>): Boolean = target.any {
        (it.toFloatOrNull() ?: 0f) < (value.toFloatOrNull() ?: 0f)
    }

    fun greaterOrEqualOp(value: String, target: List<String>): Boolean = target.any {
        (it.toFloatOrNull() ?: 0f) >= (value.toFloatOrNull() ?: 0f)
    }

    fun lessOrEqualOp(value: String, target: List<String>): Boolean = target.any {
        (it.toFloatOrNull() ?: 0f) <= (value.toFloatOrNull() ?: 0f)
    }

    fun equalOp(value: String, target: List<String>): Boolean = target.any { it == value }

    fun getOperator(op: String): (String, List<String>) -> Boolean {
        val opMap = mapOf(
            "is" to ::isOp,
            "isNot" to ::isNotOp,
            "contains" to ::containsOp,
            "notContains" to ::notContainsOp,
            "startsWith" to ::startsWithOp,
            "endsWith" to ::endsWithOp,
            "greaterThan" to ::greaterThanOp,
            ">" to ::greaterThanOp,
            "lessThan" to ::lessThanOp,
            "<" to ::lessThanOp,
            "greaterOrEqual" to ::greaterOrEqualOp,
            ">=" to ::greaterOrEqualOp,
            "lessOrEqual" to ::lessOrEqualOp,
            "<=" to ::lessOrEqualOp,
            "isAny" to ::isAnyOp,
            "=" to ::equalOp
        )

        return opMap[op] ?: ::isAnyOp
    }

    fun mapConditions(cond: Filter): Condition? {
        val opFn = getOperator(cond.operator)

        return when (cond.type) {
            "event" -> Condition(
                name = "event",
                target = cond.value,
                op = { value -> opFn(value, cond.value) },
                type = cond.type,
                tp = ORMessageType.MobileEvent
            )

            "metadata" -> Condition(
                name = "metadata",
                target = cond.value,
                op = { value -> opFn(value, cond.value) },
                type = cond.type,
                tp = ORMessageType.MobileMetadata
            )

            "userId" -> Condition(
                name = "user_id",
                target = cond.value,
                op = { value -> opFn(value, cond.value) },
                type = cond.type,
                tp = ORMessageType.MobileUserID
            )

            "viewComponent" -> Condition(
                name = "view_component",
                target = cond.value,
                op = { value -> opFn(value, cond.value) },
                type = cond.type,
                tp = ORMessageType.MobileViewComponentEvent
            )

            "clickEvent" -> Condition(
                name = "click",
                target = cond.value,
                op = { value -> opFn(value, cond.value) },
                type = cond.type,
                tp = ORMessageType.MobileClickEvent
            )

            "logEvent" -> Condition(
                name = "log",
                target = cond.value,
                op = { value -> opFn(value, cond.value) },
                type = cond.type,
                tp = ORMessageType.MobileLog
            )

            "fetchUrl", "fetchStatusCode", "fetchMethod", "fetchDuration" -> Condition(
                name = cond.type,
                target = cond.value,
                op = { value -> opFn(value, cond.value) },
                type = cond.type,
                tp = ORMessageType.MobileNetworkCall
            )

            "thermalState", "mainThreadCpu", "memoryUsage" -> Condition(
                name = cond.type,
                target = cond.value,
                op = { value -> opFn(value, cond.value) },
                type = cond.type,
                tp = ORMessageType.MobilePerformanceEvent
            )

            else -> null
        }
    }
}
