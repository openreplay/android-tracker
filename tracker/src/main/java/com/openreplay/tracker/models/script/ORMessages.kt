package com.openreplay.tracker.models.script

import com.openreplay.tracker.models.ORMessage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.experimental.or

/**
 * Constants for variable-length integer encoding (VarInt/LEB128)
 */
private const val VARINT_CONTINUE_BIT: UByte = 0x80u
private const val VARINT_VALUE_MASK: UByte = 0x7Fu
private const val VARINT_SHIFT_BITS = 7

/**
 * Maximum string length to prevent memory issues (1MB)
 */
private const val MAX_STRING_LENGTH = 1_048_576

/**
 * Log severity levels
 */
enum class LogSeverity(val value: String) {
    VERBOSE("verbose"),
    DEBUG("debug"),
    INFO("info"),
    WARN("warn"),
    ERROR("error");

    companion object {
        fun from(value: String): LogSeverity? {
            return entries.find { it.value.equals(value, ignoreCase = true) }
        }
    }
}

/**
 * Swipe direction types
 */
enum class SwipeDirection(val value: String) {
    UP("up"),
    DOWN("down"),
    LEFT("left"),
    RIGHT("right");

    companion object {
        fun from(value: String): SwipeDirection? {
            return entries.find { it.value.equals(value, ignoreCase = true) }
        }
    }
}

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
    MobileBatchMeta(107u),
    GraphQL(89u);

    companion object {
        fun fromId(id: UByte): ORMessageType? = entries.find { it.id == id }
    }
}

/**
 * Reader for deserializing binary message data.
 * Maintains an internal offset for sequential reading.
 */
class DataReader(private val data: ByteArray) {
    private var offset: Int = 0

    @Throws(IllegalStateException::class)
    fun readString(): String {
        val length = readInt()
        if (offset + length > data.size) {
            throw IllegalStateException("Cannot read string of length $length at offset $offset (data size: ${data.size})")
        }

        val stringData = data.copyOfRange(offset, offset + length)
        val result = stringData.toString(Charsets.UTF_8)
        offset += length

        return result
    }

    @Throws(IllegalStateException::class)
    private fun readInt(): Int {
        if (offset + 4 > data.size) {
            throw IllegalStateException("Cannot read Int at offset $offset (data size: ${data.size})")
        }
        val result = ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
        offset += 4
        return result
    }

    @Throws(IndexOutOfBoundsException::class)
    fun readByte(): UByte {
        if (offset >= data.size) {
            throw IndexOutOfBoundsException("Cannot read byte at offset $offset (data size: ${data.size})")
        }
        val result = data[offset].toUByte()
        offset += 1
        return result
    }

    @Throws(IllegalStateException::class)
    fun readULong(): ULong {
        if (offset + 8 > data.size) {
            throw IllegalStateException("Cannot read ULong at offset $offset (data size: ${data.size})")
        }
        val result = data.copyOfRange(offset, offset + 8).fold(0uL) { acc, byte ->
            (acc shl 8) or byte.toULong()
        }
        offset += 8
        return result
    }

    @Throws(IllegalStateException::class)
    fun readFloat(): Float {
        if (offset + 4 > data.size) {
            throw IllegalStateException("Cannot read Float at offset $offset (data size: ${data.size})")
        }
        val bytes = data.copyOfRange(offset, offset + 4)
        val result = ByteBuffer.wrap(bytes).float
        offset += 4
        return result
    }

    fun readByteArray(): ByteArray {
        val result = data.copyOfRange(offset, data.size)
        offset = data.size
        return result
    }

    fun hasMore(): Boolean = offset < data.size

    fun remaining(): Int = data.size - offset

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
    val status: Int,
    val duration: ULong,
) : ORMessage(ORMessageType.MobileNetworkCall) {

    override fun contentData(): ByteArray {
        return this.prefixData() + withSize(
            fromValues(
                type,
                method,
                URL,
                response,
                request,
                status,
                duration
            )
        )
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

/**
 * Encodes a ULong value using variable-length encoding (VarInt/LEB128).
 * Uses 7 bits per byte with the MSB as a continuation bit.
 * More efficient for small numbers: 0-127 uses 1 byte, 128-16383 uses 2 bytes, etc.
 */
fun uLongToByteArray(value: ULong): ByteArray {
    val uLongBytes = ByteArrayOutputStream(10) // Max 10 bytes for ULong
    var v = value
    while (v >= VARINT_CONTINUE_BIT) {
        uLongBytes.write(byteArrayOf((v.toByte() or VARINT_CONTINUE_BIT.toByte())))
        v = v shr VARINT_SHIFT_BITS
    }
    uLongBytes.write(byteArrayOf(v.toByte()))
    return uLongBytes.toByteArray()
}

/**
 * Serializes multiple values into a byte array.
 * Supports: Array, ULong, UInt, UByte, Byte, Boolean, String, ByteArray, Int, Float, Double
 * 
 * String encoding: VarInt length prefix + UTF-8 bytes
 * Numbers: Fixed-size big-endian encoding
 * Boolean: 1 byte (0 or 1)
 */
fun fromValues(vararg values: Any?): ByteArray {
    val outputStream = ByteArrayOutputStream(256) // Optimized initial capacity
    values.forEach { value ->
        when (value) {
            null -> {}
            is Array<*> -> outputStream.write(fromValues(*value))
            is ULong -> outputStream.write(uLongToByteArray(value))
            is UInt -> outputStream.write(uLongToByteArray(value.toULong()))
            is UByte -> outputStream.write(byteArrayOf(value.toByte()))
            is Byte -> outputStream.write(byteArrayOf(value))
            is Boolean -> outputStream.write(byteArrayOf(if (value) 1 else 0))
            is String -> {
                val stringBytes = value.toByteArray(Charsets.UTF_8)
                require(stringBytes.size <= MAX_STRING_LENGTH) {
                    "String too long: ${stringBytes.size} bytes (max: $MAX_STRING_LENGTH)"
                }
                outputStream.write(uLongToByteArray(stringBytes.size.toULong()))
                outputStream.write(stringBytes)
            }
            is ByteArray -> outputStream.write(value)
            is Int -> outputStream.write(ByteBuffer.allocate(4).putInt(value).array())
            is Float -> outputStream.write(ByteBuffer.allocate(4).putFloat(value).array())
            is Double -> outputStream.write(ByteBuffer.allocate(8).putDouble(value).array())
            else -> throw IllegalArgumentException("Unsupported type: ${value::class.java.simpleName}")
        }
    }
    return outputStream.toByteArray()
}

/**
 * Wraps a byte array with its size as a VarInt prefix.
 * Format: [VarInt size][byte array content]
 */
fun withSize(value: ByteArray): ByteArray {
    return fromValues(value.size.toUInt()) + value
}

/**
 * Validates that a string is not empty and doesn't exceed max length.
 * @throws IllegalArgumentException if validation fails
 */
internal fun validateString(value: String, fieldName: String, allowEmpty: Boolean = false) {
    if (!allowEmpty && value.isEmpty()) {
        throw IllegalArgumentException("$fieldName cannot be empty")
    }
    val byteSize = value.toByteArray(Charsets.UTF_8).size
    require(byteSize <= MAX_STRING_LENGTH) {
        "$fieldName is too long: $byteSize bytes (max: $MAX_STRING_LENGTH)"
    }
}

/**
 * Validates that coordinates are non-negative.
 * @throws IllegalArgumentException if validation fails
 */
internal fun validateCoordinates(x: Float, y: Float) {
    require(x >= 0f) { "X coordinate cannot be negative: $x" }
    require(y >= 0f) { "Y coordinate cannot be negative: $y" }
    require(x.isFinite()) { "X coordinate must be finite: $x" }
    require(y.isFinite()) { "Y coordinate must be finite: $y" }
}

/**
 * Estimates the serialized size of a message in bytes.
 * Useful for buffer sizing and optimization.
 */
fun estimateMessageSize(vararg values: Any?): Int {
    var size = 0
    values.forEach { value ->
        size += when (value) {
            null -> 0
            is ULong -> 10 // Max VarInt size
            is UInt -> 5 // Max VarInt size for UInt
            is UByte -> 1
            is Byte -> 1
            is Boolean -> 1
            is String -> {
                val stringBytes = value.toByteArray(Charsets.UTF_8).size
                10 + stringBytes // VarInt size + string bytes
            }
            is ByteArray -> value.size
            is Int -> 4
            is Float -> 4
            is Double -> 8
            else -> 0
        }
    }
    return size
}

class ORMobileUserID(
    val id: String,
) : ORMessage(ORMessageType.MobileUserID) {

    override fun contentData(): ByteArray {
        return this.prefixData() + withSize(fromValues(id))
    }

    override fun toString(): String {
        return "-->> MobileUserID(94): timestamp: $timestamp userID: $id"
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

class ORMobileGraphQL(
    val operationKind: String,
    val operationName: String,
    val variables: String,
    val response: String,
    val duration: ULong,
) : ORMessage(ORMessageType.GraphQL) {
    override fun contentData(): ByteArray {
        return this.prefixData() + withSize(
            fromValues(
                operationKind,
                operationName,
                variables,
                response,
                duration,
            )
        )
    }

    override fun toString(): String {
        return "-->> GraphQL(89): timestamp: $timestamp operationKind: $operationKind operationName: $operationName variables: $variables response: $response duration: $duration"
    }
}
