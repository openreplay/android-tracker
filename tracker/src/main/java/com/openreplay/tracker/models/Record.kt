package com.openreplay.tracker.models

import java.io.Serializable

data class ORRecord(
    val img: String,
    val originX: Float,
    val originY: Float,
    val timestamp: Long
) : Serializable