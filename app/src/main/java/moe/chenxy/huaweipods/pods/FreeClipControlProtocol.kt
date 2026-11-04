package moe.chenxy.huaweipods.pods

/** Only first-generation FreeClip controls with captured writes and device state reads. */
internal enum class FreeClipControlKey(
    val kind: HuaweiGestureKind? = null,
    val side: HuaweiGestureSide? = null,
) {
    DOUBLE_LEFT(HuaweiGestureKind.DOUBLE_TAP, HuaweiGestureSide.LEFT),
    DOUBLE_RIGHT(HuaweiGestureKind.DOUBLE_TAP, HuaweiGestureSide.RIGHT),
    TRIPLE_LEFT(HuaweiGestureKind.TRIPLE_TAP, HuaweiGestureSide.LEFT),
    TRIPLE_RIGHT(HuaweiGestureKind.TRIPLE_TAP, HuaweiGestureSide.RIGHT),
    EQUALIZER,
    ADAPTIVE_CHANNELS,
    DROP_REMINDER;

    val values: List<String>
        get() = when (this) {
            EQUALIZER -> listOf("1", "10", "3", "9")
            ADAPTIVE_CHANNELS, DROP_REMINDER -> listOf("false", "true")
            else -> HuaweiTapAction.availableFor(FreeClipControlProtocol.ROUTE, requireNotNull(kind))
                .map { it.extraValue }
        }
}

internal object FreeClipControlProtocol {
    val ROUTE = HuaweiDeviceRoute.HUAWEI_FREECLIP
    const val TIMEOUT_MS = 8_000L

    fun key(name: String?): FreeClipControlKey? = FreeClipControlKey.entries.firstOrNull { it.name == name }

    fun query(key: FreeClipControlKey? = null): ByteArray = when (key) {
        FreeClipControlKey.EQUALIZER -> HuaweiEqualizerCodec.stateQueryPacket()
        FreeClipControlKey.ADAPTIVE_CHANNELS -> HuaweiFreeClipController.adaptiveChannelsQueryPacket()
        FreeClipControlKey.DROP_REMINDER -> HuaweiFreeClipController.dropReminderQueryPacket()
        null -> requireNotNull(HuaweiGestureController.buildGestureStateQuery(ROUTE)) +
            query(FreeClipControlKey.EQUALIZER) + query(FreeClipControlKey.ADAPTIVE_CHANNELS) +
            query(FreeClipControlKey.DROP_REMINDER)
        else -> requireNotNull(HuaweiGestureController.buildGestureStateQuery(ROUTE))
    }

    fun write(key: FreeClipControlKey, value: String): ByteArray? {
        if (value !in key.values) return null
        return when (key) {
            FreeClipControlKey.EQUALIZER -> HuaweiEqualizerCodec.buildBuiltInPresetPacket(ROUTE, value.toInt())
            FreeClipControlKey.ADAPTIVE_CHANNELS -> HuaweiFreeClipController.adaptiveChannelsPacket(value == "true")
            FreeClipControlKey.DROP_REMINDER -> HuaweiFreeClipController.dropReminderPacket(value == "true")
            else -> HuaweiGestureController.buildTapPacket(
                ROUTE, requireNotNull(key.kind), requireNotNull(key.side),
                HuaweiTapAction.fromExtra(value) ?: return null,
            )
        }
    }

    fun readback(stream: ByteArray): Map<FreeClipControlKey, String> {
        // Some legacy parsers accept frames without checking CRC. The system control bridge only
        // confirms complete, checksum-valid device frames, never the write acknowledgement.
        val verified = verifiedFrames(stream)
        val gestures = HuaweiRfcommResponseParser.parseGestureState(verified, ROUTE)
        return buildMap {
            gestures.doubleTap?.let {
                put(FreeClipControlKey.DOUBLE_LEFT, it.left.extraValue)
                put(FreeClipControlKey.DOUBLE_RIGHT, it.right.extraValue)
            }
            gestures.tripleTap?.let {
                put(FreeClipControlKey.TRIPLE_LEFT, it.left.extraValue)
                put(FreeClipControlKey.TRIPLE_RIGHT, it.right.extraValue)
            }
            HuaweiEqualizerCodec.parseState(verified)?.takeIf { it.supported }?.let {
                // A firmware-specific/custom preset stays unconfirmed rather than selecting Default.
                it.selectedId.toString().takeIf(FreeClipControlKey.EQUALIZER.values::contains)?.let { id ->
                    put(FreeClipControlKey.EQUALIZER, id)
                }
            }
            HuaweiFreeClipController.parseAdaptiveChannelsState(verified)?.let {
                put(FreeClipControlKey.ADAPTIVE_CHANNELS, it.toString())
            }
            HuaweiFreeClipController.parseDropReminderState(verified)?.let {
                put(FreeClipControlKey.DROP_REMINDER, it.toString())
            }
        }
    }

    private fun verifiedFrames(stream: ByteArray): ByteArray {
        if (stream.size > 64 * 1024) return byteArrayOf()
        val result = java.io.ByteArrayOutputStream()
        var offset = 0
        while (offset + 5 <= stream.size) {
            if (stream[offset].u8() != 0x5A || stream[offset + 1].u8() != 0) {
                offset++
                continue
            }
            val size = 5 + stream[offset + 2].u8() + (stream[offset + 3].u8() shl 8)
            if (size < 9 || offset + size > stream.size) {
                offset++
                continue
            }
            var crc = 0
            for (index in offset until offset + size - 2) {
                crc = crc xor (stream[index].u8() shl 8)
                repeat(8) { crc = ((crc shl 1) xor if (crc and 0x8000 != 0) 0x1021 else 0) and 0xFFFF }
            }
            if (stream[offset + size - 2].u8() == (crc shr 8) && stream[offset + size - 1].u8() == (crc and 0xFF)) {
                result.write(stream, offset, size)
                offset += size
            } else {
                offset++
            }
        }
        return result.toByteArray()
    }

    private fun Byte.u8(): Int = toInt() and 0xFF
}
