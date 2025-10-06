package com.openreplay.tracker.models

import com.openreplay.tracker.managers.DebugUtils
import com.openreplay.tracker.models.script.DataReader
import com.openreplay.tracker.models.script.ORMessageType
import com.openreplay.tracker.models.script.fromValues

/**
 * Generic message wrapper for deserializing binary message data.
 */
data class GenericMessage(
    val typeRaw: UByte,
    val type: ORMessageType?,
    val timestamp: ULong,
    val body: ByteArray
) {
    companion object {
        fun fromData(data: ByteArray): GenericMessage? {
            return try {
                val reader = DataReader.fromByteArray(data)
                val typeRaw = reader.readByte()
                val type = ORMessageType.fromId(typeRaw)
                val timestamp = reader.readULong()
                val body = reader.readByteArray()

                GenericMessage(typeRaw, type, timestamp, body)
            } catch (e: Exception) {
                DebugUtils.error("Failed to deserialize GenericMessage", e)
                null
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as GenericMessage

        if (typeRaw != other.typeRaw) return false
        if (type != other.type) return false
        if (timestamp != other.timestamp) return false
        if (!body.contentEquals(other.body)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = typeRaw.hashCode()
        result = 31 * result + (type?.hashCode() ?: 0)
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + body.contentHashCode()
        return result
    }
}


/**
 * Base class for all OpenReplay message types.
 * Provides timestamp and message type information.
 */
open class ORMessage(
    val messageRaw: UByte,
    val message: ORMessageType?,
    val timestamp: ULong
) {
    constructor(messageType: ORMessageType) : this(
        messageRaw = messageType.id,
        message = messageType,
        timestamp = System.currentTimeMillis().toULong()
    )

    companion object {
        fun fromGenericMessage(genericMessage: GenericMessage): ORMessage? {
            return ORMessage(
                messageRaw = genericMessage.typeRaw,
                message = genericMessage.type,
                timestamp = genericMessage.timestamp
            )
        }
    }

    fun prefixData(): ByteArray {
        return fromValues(messageRaw, timestamp)
    }

    open fun contentData(): ByteArray {
        throw UnsupportedOperationException("contentData() must be overridden in ${this::class.simpleName}")
    }
}
