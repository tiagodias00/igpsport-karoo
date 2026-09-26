package com.tiagodias.igpsportkaroo.protocol

/**
 * iGPSPORT VS-series light protocol over the Nordic UART service.
 *
 * Every frame is a 20-byte transport header followed by a protobuf payload. Reverse-engineered by
 * cparfait/Bike-Light-Control (MIT) from the iGPSPORT Ride app and an HCI capture of a VS1800S;
 * VS1200S behaviour is recorded in docs/vs1200s-findings.md.
 */
object IgpsProtocol {
    const val UART_SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    /** Central to light (write). */
    const val UART_WRITE = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
    /** Light to central (notify). */
    const val UART_NOTIFY = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
    /** The only UUID the light advertises: it does not advertise the UART service. */
    const val ADVERT_MARKER = "a238c112-8136-52a9-364b-d61ac015024e"
    const val CCCD = "00002902-0000-1000-8000-00805f9b34fb"

    const val HEADER_LEN = 20
    const val MAX_CHUNK = 20

    const val TYPE_DATA = 0x01
    const val TYPE_ACK = 0x02
    const val TYPE_STATE = 0x03

    private const val SERVICE_LIGHT = 106
    private const val OP_WRITE = 1
    private const val OP_READ = 2

    private const val SUB_MODE_SUPPORTED = 1
    private const val SUB_MODE_CURRENT = 2
    private const val SUB_REMAINING_TIME = 5
    private const val SUB_BATTERY = 6
    private const val SUB_MODE_ENABLE = 7

    private const val F_SERVICE = 1
    private const val F_OPERATE = 2
    private const val F_SUB = 3
    private const val F_MODE_DECLARED = 6
    private const val F_MODE_ENABLE = 11
    private const val F_CURRENT_MODE = 13
    private const val F_REMAINING_TIME = 14
    private const val F_BATTERY = 15

    fun readSupportedModes(): ByteArray = message(SUB_MODE_SUPPORTED, OP_READ)
    fun readCurrentMode(): ByteArray = message(SUB_MODE_CURRENT, OP_READ)
    fun readRemainingTime(): ByteArray = message(SUB_REMAINING_TIME, OP_READ)
    fun readBattery(): ByteArray = message(SUB_BATTERY, OP_READ)

    /** Mode 0 (off) is the protobuf default, so its inner message is sent empty. */
    fun setMode(mode: Int): ByteArray {
        val inner = if (mode == LightModes.OFF) ByteArray(0) else ProtoWire.varintField(1, mode.toLong())
        return message(SUB_MODE_CURRENT, OP_WRITE, ProtoWire.messageField(F_CURRENT_MODE, inner))
    }

    /** Enables or disables a mode in the light's list: flash and custom modes ship disabled. */
    fun setModeEnabled(mode: Int, enabled: Boolean): ByteArray {
        var inner = ProtoWire.varintField(1, mode.toLong())
        if (enabled) inner += ProtoWire.varintField(2, 1)
        return message(SUB_MODE_ENABLE, OP_WRITE, ProtoWire.messageField(F_MODE_ENABLE, inner))
    }

    private fun message(sub: Int, op: Int, body: ByteArray = ByteArray(0)): ByteArray {
        val payload = ProtoWire.varintField(F_SERVICE, SERVICE_LIGHT.toLong()) +
            ProtoWire.varintField(F_OPERATE, op.toLong()) +
            ProtoWire.varintField(F_SUB, sub.toLong()) +
            body
        require(payload.size <= 0xFF) { "payload too long: ${payload.size}" }
        val header = ByteArray(HEADER_LEN) { 0xFF.toByte() }
        header[0] = TYPE_DATA.toByte()
        header[1] = SERVICE_LIGHT.toByte()
        header[2] = sub.toByte()
        header[4] = op.toByte()
        header[7] = 0
        header[8] = payload.size.toByte()
        header[9] = Crc8.maxim(payload).toByte()
        header[10] = 1
        header[19] = Crc8.maxim(header, 0, HEADER_LEN - 1).toByte()
        return header + payload
    }

    fun chunks(frame: ByteArray, size: Int = MAX_CHUNK): List<ByteArray> =
        (frame.indices step size).map { frame.copyOfRange(it, minOf(it + size, frame.size)) }

    fun headerValid(bytes: ByteArray): Boolean =
        bytes.size >= HEADER_LEN &&
            Crc8.maxim(bytes, 0, HEADER_LEN - 1) == (bytes[HEADER_LEN - 1].toInt() and 0xFF)

    /** Length of the frame starting at bytes[0]. Only data frames carry a payload; byte 8 is not a length otherwise. */
    fun expectedLength(bytes: ByteArray): Int =
        if (bytes.size >= HEADER_LEN && bytes[0].toInt() == TYPE_DATA) {
            HEADER_LEN + (bytes[8].toInt() and 0xFF)
        } else {
            HEADER_LEN
        }

    fun parseFrame(bytes: ByteArray): LightUpdate? {
        if (!headerValid(bytes)) return null
        // The VS1200S also sends type-03 frames for service 0x6B; only the lighting service (0x6A) is understood.
        if ((bytes[1].toInt() and 0xFF) != SERVICE_LIGHT) return null
        return when (bytes[0].toInt()) {
            TYPE_STATE -> parseState(bytes)
            TYPE_DATA -> parseData(bytes)
            else -> null
        }
    }

    private fun parseState(bytes: ByteArray): LightUpdate? = when (bytes[2].toInt() and 0xFF) {
        SUB_MODE_CURRENT -> LightUpdate(mode = bytes[7].toInt() and 0xFF)
        SUB_REMAINING_TIME -> LightUpdate(
            remainingMinutes = (bytes[11].toInt() and 0xFF) or
                ((bytes[12].toInt() and 0xFF) shl 8) or
                ((bytes[13].toInt() and 0xFF) shl 16),
        )
        else -> null
    }

    private fun parseData(bytes: ByteArray): LightUpdate? {
        val length = bytes[8].toInt() and 0xFF
        if (bytes.size < HEADER_LEN + length) return null
        val payload = bytes.copyOfRange(HEADER_LEN, HEADER_LEN + length)
        if (Crc8.maxim(payload) != (bytes[9].toInt() and 0xFF)) return null
        val fields = ProtoWire.parse(payload) ?: return null
        val declared = if (F_MODE_DECLARED in fields) {
            fields.messages(F_MODE_DECLARED).mapNotNull { entry ->
                val e = ProtoWire.parse(entry) ?: return@mapNotNull null
                val mode = e.varint(1)?.toInt() ?: return@mapNotNull null
                mode to ((e.varint(3) ?: 0L) != 0L)
            }.toMap(LinkedHashMap())
        } else {
            null
        }
        return LightUpdate(
            // An empty inner message means the protobuf default: off.
            mode = fields.message(F_CURRENT_MODE)?.let { ProtoWire.parse(it)?.varint(1)?.toInt() ?: LightModes.OFF },
            batteryPercent = fields.message(F_BATTERY)?.let { ProtoWire.parse(it)?.varint(13)?.toInt() },
            remainingMinutes = fields.message(F_REMAINING_TIME)?.let { ProtoWire.parse(it)?.varint(1)?.toInt() },
            declaredModes = declared,
        )
    }
}
