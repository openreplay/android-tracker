package com.openreplay.tracker.models.script

import com.openreplay.tracker.models.ORMessage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.experimental.or

enum class ORMessageType(val id: UByte) {
    MobileMetadata(92u),
    MobileEvent(93u),
    MobileUserID(94u),
    MobileUserAnonymousID(95u),
    MobileScreenChanges(96u),
    MobileCrash(97u),
    MobileViewComponentEvent(98u),
    MobileClickEvent(100u),
    MobileInputEvent(101u),
    MobilePerformanceEvent(102u),
    MobileLog(103u),
    MobileInternalError(104u),
    MobileNetworkCall(105u),
    MobileSwipeEvent(106u),
    MobileBatchMeta(107u);

    companion object {
        fun fromId(id: UByte): ORMessageType? = entries.find { it.id == id }
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

    fun readByte(): UByte {
        if (offset >= data.size) {
            throw IndexOutOfBoundsException("Offset is out of bounds")
        }
        val result = data[offset].toUByte()
        offset += 1
        return result
    }


    fun readULong(): ULong {
        val result = data.copyOfRange(offset, offset + 8).fold(0uL) { acc, byte ->
            (acc shl 8) or byte.toULong()
        }
        offset += 8
        return result
    }

    fun readFloat(): Float {
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

class ORMobileCrash(
    val name: String,
    val reason: String,
    val stacktrace: String,
) : ORMessage(ORMessageType.MobileCrash) {
    override fun contentData(): ByteArray {
        return this.prefixData() + withSize(fromValues(name, reason, stacktrace))
    }

    override fun toString(): String {
        return "-->> MobileCrash(97): timestamp: $timestamp name: $name reason: $reason stacktrace: $stacktrace"
    }
}

class ORMobileInternalError(
    val content: String,
) : ORMessage(ORMessageType.MobileInternalError) {
    override fun contentData(): ByteArray {
        return this.prefixData() + withSize(fromValues(content))
    }

    override fun toString(): String {
        return "-->> MobileInternalError(104): timestamp: $timestamp content: $content"
    }
}

class ORMobileViewComponentEvent(
    val screenName: String,
    val viewName: String,
    val visible: Boolean,
) : ORMessage(ORMessageType.MobileViewComponentEvent) {
    override fun contentData(): ByteArray {
        return this.prefixData() + withSize(fromValues(screenName, viewName, visible))
    }

    override fun toString(): String {
        return "-->> MobileViewComponentEvent(98): timestamp: $timestamp screenName: $screenName viewName: $viewName visible: $visible"
    }
}

class ORMobileInputEvent(
    val label: String,
    val value: String,
    val valueMasked: Boolean,
) : ORMessage(ORMessageType.MobileInputEvent) {

    override fun contentData(): ByteArray {
        return this.prefixData() + withSize(fromValues(value, valueMasked, label))
    }

    override fun toString(): String {
        return "-->> MobileInputEvent(101): timestamp: $timestamp label: $label value: $value"
    }
}

class ORMobileMetadata(
    val key: String,
    val value: String,
) : ORMessage(ORMessageType.MobileMetadata) {

    override fun contentData(): ByteArray {
        return this.prefixData() + withSize(fromValues(key, value))
    }

    override fun toString(): String {
        return "-->> MobileMetadata(92): timestamp: $timestamp key: $key value: $value"
    }
}

class ORMobileLog(
    val severity: String,
    val content: String,
) : ORMessage(ORMessageType.MobileLog) {

    override fun contentData(): ByteArray {
        return this.prefixData() + withSize(fromValues(severity, content))
    }

    override fun toString(): String {
        return "-->> MobileLog(103): timestamp: $timestamp severity: $severity message: $content"
    }
}

class ORMobileBatchMeta(
    val firstIndex: ULong,
) : ORMessage(ORMessageType.MobileBatchMeta) {

    override fun contentData(): ByteArray {
        return this.prefixData() + withSize(fromValues(firstIndex))
    }

    override fun toString(): String {
        return "-->> MobileBatchMeta(107): timestamp: $timestamp firstIndex: $firstIndex"
    }
}

class ORMobileNetworkCall(
    val type: String,
    val method: String,
    val URL: String,
    val request: String,
    val response: String,
    val status: ULong,
    val duration: ULong,
) : ORMessage(ORMessageType.MobileNetworkCall) {

    override fun contentData(): ByteArray {
        return this.prefixData() + withSize(fromValues(type, method, URL, response, request, status, duration))
    }

    override fun toString(): String {
        return "-->> MobileNetworkCall(105): timestamp: $timestamp type: $type method: $method URL: $URL request: $request response: $response status: $status duration: $duration"
    }
}

class ORMobileClickEvent(
    val label: String,
    val x: Float,
    val y: Float,
) : ORMessage(ORMessageType.MobileClickEvent) {

    override fun contentData(): ByteArray {
        return this.prefixData() + withSize(fromValues(label, x.toULong(), y.toULong()))
    }

    override fun toString(): String {
        return "-->> MobileClickEvent(100): timestamp: $timestamp label: $label x: $x y: $y"
    }
}

class ORMobilePerformanceEvent(
    val name: String,
    val value: ULong,
) : ORMessage(ORMessageType.MobilePerformanceEvent) {

    override fun contentData(): ByteArray {
        return this.prefixData() + withSize(fromValues(name, value))
    }

    override fun toString(): String {
        return "-->> MobilePerformanceEvent(102): timestamp: $timestamp name: $name value: $value"
    }
}

fun uLongToByteArray(value: ULong): ByteArray {
    val uLongBytes = ByteArrayOutputStream()
    var v = value
    while (v >= 0x80u) {
        uLongBytes.write(byteArrayOf((v.toByte() or 0x80.toByte())))
        v = v shr 7
    }
    uLongBytes.write(byteArrayOf(v.toByte()))
    return uLongBytes.toByteArray()
}

fun fromValues(vararg values: Any?): ByteArray {
    val outputStream = ByteArrayOutputStream()
    values.forEach { value ->
        when (value) {
            is Array<*> -> outputStream.write(fromValues(*value))
            is ULong -> outputStream.write(uLongToByteArray(value))
            is UInt -> outputStream.write(uLongToByteArray(value.toULong()))
            is UByte -> outputStream.write(byteArrayOf(value.toByte()))
            is Byte -> outputStream.write(byteArrayOf(value))
            is Boolean -> outputStream.write(byteArrayOf(if (value) 1 else 0))
            is String -> {
                val stringBytes = value.toByteArray(Charsets.UTF_8)
                outputStream.write(uLongToByteArray(stringBytes.size.toULong()))
                outputStream.write(stringBytes)
            }

            is ByteArray -> outputStream.write(value)

            // TODO: review later
            is Int -> outputStream.write(ByteBuffer.allocate(4).putInt(value).array())
            is Float -> outputStream.write(ByteBuffer.allocate(4).putFloat(value).array())
            is Double -> outputStream.write(ByteBuffer.allocate(8).putDouble(value).array())

            // Handle encoding for custom types like UIEdgeInsets, CGRect, CGPoint, CGSize, UIColor
            else -> throw IllegalArgumentException("Unsupported type: ${value!!::class.java}")
        }
    }
    return outputStream.toByteArray()
}

fun withSize(value: ByteArray): ByteArray {
    return fromValues(value.size.toUInt()) + value
}


class ORMobileUserID(
    val iD: String,
) : ORMessage(ORMessageType.MobileUserID) {

    override fun contentData(): ByteArray {
        return this.prefixData() + withSize(fromValues(iD))
    }

    override fun toString(): String {
        return "-->> IOSUserID(94): timestamp: $timestamp userID: $iD"
    }
}

class ORMobileSwipeEvent(
    val label: String,
    val direction: String,
    val x: Float,
    val y: Float,
) : ORMessage(ORMessageType.MobileSwipeEvent) {

    override fun contentData(): ByteArray {
        return this.prefixData() + withSize(fromValues(label, x.toULong(), y.toULong(), direction))
    }

    override fun toString(): String {
        return "-->> MobileSwipeEvent(106): label: $label timestamp: $timestamp direction: $direction velocityX: $x velocityY: $y"
    }
}


class ORMobileEvent(
    val name: String,
    val payload: String,
) : ORMessage(ORMessageType.MobileEvent) {

    override fun contentData(): ByteArray {
        return this.prefixData() + withSize(fromValues(name, payload))
    }

    override fun toString(): String {
        return "-->> MobileEvent(93): timestamp: $timestamp name: $name payload: $payload"
    }
}
