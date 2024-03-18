package com.openreplay.models

import com.openreplay.models.script.DataReader
import com.openreplay.models.script.ORMessageType

data class GenericMessage(
    val typeRaw: ULong,
    val type: ORMessageType?,
    val timestamp: ULong,
    val body: ByteArray
) {
    companion object {
        fun fromData(data: ByteArray): GenericMessage? {
            return try {
                val reader = DataReader.fromByteArray(data)
                val typeRaw = reader.readULong()
                val type = ORMessageType.fromId(typeRaw)
                val timestamp = reader.readULong()
                val body = reader.readByteArray()

                GenericMessage(typeRaw, type, timestamp, body)
            } catch (e: Exception) {
                // Log the error or handle it as necessary
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


open class ORMessage(
    val messageRaw: ULong,
    val message: ORMessageType?,
    val timestamp: ULong
) {
    constructor(messageType: ORMessageType) : this(
        messageRaw = messageType.id,
        message = messageType,
        timestamp = System.currentTimeMillis().toULong() // Conversion to milliseconds since epoch
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

    open fun contentData(): ByteArray {
        throw NotImplementedError("This method should be overridden")
    }
}