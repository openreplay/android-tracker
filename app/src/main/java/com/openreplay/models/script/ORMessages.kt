package com.openreplay.models.script

import com.openreplay.models.GenericMessage
import com.openreplay.models.ORMessage
import com.openreplay.models.script.ByteArrayUtils.fromValues
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest

enum class ORMessageType(val id: ULong) {
    IOSMetadata(92u),
    IOSEvent(93u),
    IOSUserID(94u),
    IOSUserAnonymousID(95u),
    IOSScreenChanges(96u),
    IOSCrash(97u),
    IOSViewComponentEvent(98u),
    IOSClickEvent(100u),
    IOSInputEvent(101u),
    IOSPerformanceEvent(102u),
    IOSLog(103u),
    IOSInternalError(104u),
    IOSNetworkCall(105u),
    IOSSwipeEvent(106u),
    IOSBatchMeta(107u);

    companion object {
        fun fromId(id: ULong): ORMessageType? = entries.find { it.id == id }
    }
}

class DataReader(private val data: ByteArray) {
    private var offset: Int = 0

    @Throws(Exception::class)
    fun readString(): String {
        // Assuming readData reads the length first and then the actual string bytes.
        // This implementation needs to be aligned with your specific data format.
        val length = readInt() // Reads the length of the string first
        if (offset + length > data.size) throw Exception("Error reading string")

        val stringData = data.copyOfRange(offset, offset + length)
        val result = stringData.toString(Charsets.UTF_8)
        offset += length // Update the offset after reading

        return result
    }

    @Throws(Exception::class)
    private fun readInt(): Int {
        // This is a simplified example; adjust it according to how integers are encoded in your data.
        if (offset + 4 > data.size) throw Exception("Invalid offset for Int")
        val result = ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
        offset += 4 // Move the offset forward by the size of an Int
        return result
    }

    fun readULong(): ULong {
        // Placeholder for reading ULong from data at the current offset
        // Update offset accordingly
        // This is a simplified version; adjust for your data's format
        val result = data.copyOfRange(offset, offset + 8).fold(0uL) { acc, byte ->
            (acc shl 8) or byte.toULong()
        }
        offset += 8
        return result
    }

    fun readFloat(): Float {
        // Placeholder for reading ULong from data at the current offset
        // Update offset accordingly
        // This is a simplified version; adjust for your data's format
        val result = data.copyOfRange(offset, offset + 4).fold(0f) { acc, byte ->
            (acc * 256) + byte
        }
        offset += 4
        return result
    }

    fun readByteArray(): ByteArray {
        // Reads the remaining data from the current offset
        val result = data.copyOfRange(offset, data.size)
        offset = data.size // Update offset to the end of the data
        return result
    }

    // Implement readData here, similar to readString but for generic data reading.
    // It should update the offset accordingly as well.
    companion object {
        fun fromByteArray(data: ByteArray): DataReader = DataReader(data)
    }
}

class ORIOSMetadata(
    val key: String,
    val value: String,
    messageType: ORMessageType = ORMessageType.IOSMetadata
) : ORMessage(messageType) {

    companion object {
        fun fromGenericMessage(genericMessage: GenericMessage): ORIOSMetadata? {
            return try {
                val dataReader = DataReader(genericMessage.body)
                val key = dataReader.readString()
                val value = dataReader.readString()
                ORIOSMetadata(key, value)
            } catch (e: Exception) {
                null // Return null in case of any exceptions
            }
        }
    }

    override fun contentData(): ByteArray {
        return ByteArrayUtils.fromValues(messageRaw, timestamp, arrayOf(key, value))
    }

    override fun toString(): String {
        return "-->> IOSMetadata(92): timestamp: $timestamp key: $key value: $value"
    }

    private fun prependMetadata(typeId: ULong, timestamp: Long, data: ByteArray): ByteArray {
        // Placeholder: prepend metadata to the data array
        // Actual implementation depends on how you want to structure your binary data.
        return byteArrayOf() // Implement based on your data format
    }
}


class ORIOSBatchMeta(
    val firstIndex: ULong,
    messageType: ORMessageType = ORMessageType.IOSBatchMeta
) : ORMessage(messageType) {

    constructor(genericMessage: GenericMessage) : this(
        firstIndex = DataReader(genericMessage.body).readULong()
    )

    override fun contentData(): ByteArray {
        return ByteArrayUtils.fromValues(messageRaw, timestamp, firstIndex)
    }

    override fun toString(): String {
        return "-->> IOSBatchMeta(107): timestamp: $timestamp firstIndex: $firstIndex"
    }
}


class ORIOSNetworkCall(
    val type: String,
    val method: String,
    val URL: String,
    val request: String,
    val response: String,
    val status: ULong,
    val duration: ULong,
    messageType: ORMessageType = ORMessageType.IOSNetworkCall
) : ORMessage(messageType) {
    companion object {
        fun fromGenericMessage(genericMessage: GenericMessage): ORIOSNetworkCall? {
            return try {
                val dataReader = DataReader(genericMessage.body)
                val type = dataReader.readString()
                val method = dataReader.readString()
                val URL = dataReader.readString()
                val request = dataReader.readString()
                val response = dataReader.readString()
                val status = dataReader.readULong()
                val duration = dataReader.readULong()

                ORIOSNetworkCall(type, method, URL, request, response, status, duration)
            } catch (e: Exception) {
                null // Return null in case of any exceptions
            }
        }
    }

    override fun contentData(): ByteArray {
        return ByteArrayUtils.fromValues(
            messageRaw,
            timestamp,
            arrayOf(type, method, URL, request, response, status, duration)
        )
    }

    override fun toString(): String {
        return "-->> IOSNetworkCall(105): timestamp: $timestamp type: $type method: $method URL: $URL request: $request response: $response status: $status duration: $duration"
    }
}

class ORIOSClickEvent(
    val label: String,
    val x: Float,
    val y: Float,
    messageType: ORMessageType = ORMessageType.IOSClickEvent
) : ORMessage(messageType) {

    constructor(genericMessage: GenericMessage) : this(
        label = DataReader(genericMessage.body).readString(),
        x = DataReader(genericMessage.body).readFloat(),
        y = DataReader(genericMessage.body).readFloat(),
        messageType = ORMessageType.IOSClickEvent
    )

    override fun contentData(): ByteArray {
        return fromValues(messageRaw, timestamp, fromValues(label, x, y))
    }

    override fun toString(): String {
        return "-->> IOSClickEvent(100): timestamp: $timestamp label: $label x: $x y: $y"
    }
}

class ORIOSPerformanceEvent(
    val name: String,
    val value: ULong,
    messageType: ORMessageType = ORMessageType.IOSPerformanceEvent
) : ORMessage(messageType) {

    override fun contentData(): ByteArray {
        return fromValues(messageRaw, timestamp, name, value)
    }

    override fun toString(): String {
        return "-->> IOSPerformanceEvent(102): timestamp: $timestamp name: $name value: $value"
    }
}

class ByteArrayBuilder {
    private val outputStream = ByteArrayOutputStream()

    fun writeString(value: String) {
        val stringBytes = value.toByteArray(Charsets.UTF_8)
        writeInt(stringBytes.size) // Prefixed with length
        outputStream.write(stringBytes)
    }

    fun writeFloat(value: Float) {
        // Convert float to bytes and write to outputStream
        val floatBytes = ByteBuffer.allocate(4).putFloat(value).array()
        outputStream.write(floatBytes, 0, floatBytes.size)
    }

    fun writeInt(value: Int) {
        // Convert int to bytes and write to outputStream
        val intBytes = ByteBuffer.allocate(4).putInt(value).array()
        outputStream.write(intBytes, 0, intBytes.size)
    }

    fun writeULong(value: ULong) {
        val longBytes = ByteBuffer.allocate(8).putLong(value.toLong()).array()
        outputStream.write(longBytes, 0, longBytes.size)
    }

    fun toByteArray(): ByteArray = outputStream.toByteArray()
}

object ByteArrayUtils {
//    private fun ByteArray.appendInt(value: Int, includeSizePrefix: Boolean = false): ByteArray {
//        val byteBuffer = ByteBuffer.allocate(Int.SIZE_BYTES)
//        byteBuffer.putInt(value)
//        byteBuffer.flip() // Make the buffer ready for reading
//        return this + (if (includeSizePrefix) ByteBuffer.allocate(4).putInt(value)
//            .array().size.toByte() else 0) + byteBuffer.array()
//    }
//
//    private fun ByteArray.appendLong(value: Long, includeSizePrefix: Boolean = false): ByteArray {
//        val byteBuffer = ByteBuffer.allocate(Long.SIZE_BYTES)
//        byteBuffer.putLong(value)
//        byteBuffer.flip() // Make the buffer ready for reading
//        return this + (if (includeSizePrefix) ByteBuffer.allocate(8).putLong(value)
//            .array().size.toByte() else 0) + byteBuffer.array()
//    }
//
//    private fun ByteArray.appendString(value: String, includeSizePrefix: Boolean = true): ByteArray {
//        val stringBytes = value.toByteArray(Charsets.UTF_8)
//        return this + (if (includeSizePrefix) ByteBuffer.allocate(4).putInt(stringBytes.size)
//            .array() else byteArrayOf()) + stringBytes
//    }
//
//    private fun ByteArray.appendFloat(value: Float): ByteArray {
//        val byteBuffer = ByteBuffer.allocate(Float.SIZE_BYTES)
//        byteBuffer.putFloat(value)
//        byteBuffer.flip() // Make the buffer ready for reading
//        return this + byteBuffer.array()
//    }
//
//    private fun ByteArray.appendBoolean(value: Boolean): ByteArray {
//        return this + (if (value) 1.toByte() else 0.toByte())
//    }

//    fun fromValues(vararg values: Any?): ByteArray {
//        var byteArray = byteArrayOf() // Start with an empty ByteArray
//
//        values.forEach { value ->
//            when (value) {
//                is ULong -> byteArray = byteArray.appendLong(value.toLong(), true)
//                is UInt -> byteArray = byteArray.appendInt(value.toInt(), true)
//                is Int -> byteArray = byteArray.appendInt(value, true)
//                is UByte -> byteArray = byteArray.appendInt(value.toInt(), true)
//                is Byte -> byteArray = byteArray.appendInt(value.toInt(), true)
//                is Float -> byteArray = byteArray.appendFloat(value)
//                is Boolean -> byteArray = byteArray.appendBoolean(value)
//                is String -> byteArray = byteArray.appendString(value)
//                is ByteArray -> byteArray += value
//                else -> throw IllegalArgumentException("Unsupported type: ${value?.javaClass?.kotlin}")
//            }
//        }
//        return byteArray
//    }

    fun fromValues(vararg values: Any?): ByteArray {
        val outputStream = ByteArrayOutputStream()
        values.forEach { value ->
            when (value) {
                is Array<*> -> outputStream.write(fromValues(*value))
                is ULong -> outputStream.write(ByteBuffer.allocate(8).putLong(value.toLong()).array())
                is UInt -> outputStream.write(ByteBuffer.allocate(4).putInt(value.toInt()).array())
                is Int -> outputStream.write(ByteBuffer.allocate(4).putInt(value).array())
                is UByte, is Byte -> outputStream.write(byteArrayOf((value as Number).toByte()))
                is Float -> outputStream.write(ByteBuffer.allocate(4).putFloat(value).array())
                is Double -> outputStream.write(ByteBuffer.allocate(8).putDouble(value).array())
                is Boolean -> outputStream.write(byteArrayOf(if (value) 1 else 0))
                is String -> outputStream.write(value.toByteArray(Charsets.UTF_8))
                is ByteArray -> outputStream.write(value)
                // Handle encoding for custom types like UIEdgeInsets, CGRect, CGPoint, CGSize, UIColor
                else -> throw IllegalArgumentException("Unsupported type: ${value!!::class.java}")
            }
        }
        return outputStream.toByteArray()
    }

    fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }
}







