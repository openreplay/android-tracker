package com.openreplay.tracker.managers

import NetworkManager
import com.openreplay.tracker.OpenReplay
import com.openreplay.tracker.models.ORMessage
import com.openreplay.tracker.models.script.*
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

                is ORMobileViewComponentEvent -> {
                    if (activeCon.op(msg.viewName) || activeCon.op(msg.screenName)) {
                        return activeCon.name
                    }
                }

                is ORMobileClickEvent -> {
                    if (activeCon.op(msg.label)) {
                        return activeCon.name
                    }
                }

                is ORMobileMetadata -> {
                    if (activeCon.op(msg.key) || activeCon.op(msg.value)) {
                        return activeCon.name
                    }
                }

                is ORMobileEvent -> {
                    if (activeCon.op(msg.name) || activeCon.op(msg.payload)) {
                        return activeCon.name
                    }
                }

                is ORMobileLog -> {
                    if (activeCon.op(msg.content)) {
                        return activeCon.name
                    }
                }

                is ORMobileUserID -> {
                    if (activeCon.op(msg.iD)) {
                        return activeCon.name
                    }
                }

                is ORMobilePerformanceEvent -> {
                    if (msg.name == "memoryUsage") {
                        if (activeCon.op((msg.value / 1024u).toString())) {
                            return activeCon.name
                        }
                    } else {
                        if (activeCon.op(msg.value.toString())) {
                            return activeCon.name
                        }
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
                        durationCond(dur = filter.value)
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

    private fun durationCond(dur: List<String>) {
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
