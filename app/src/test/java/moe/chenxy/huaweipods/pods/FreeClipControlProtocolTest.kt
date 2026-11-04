package moe.chenxy.huaweipods.pods

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeClipControlProtocolTest {
    @Test fun `FreeClip menu exposes captured controls without ANC spatial or swipe`() {
        assertEquals(HuaweiDeviceRoute.HUAWEI_FREECLIP, FreeClipControlProtocol.ROUTE)
        assertEquals(7, FreeClipControlKey.entries.size)
        assertEquals(listOf("1", "10", "3", "9"), FreeClipControlKey.EQUALIZER.values)
        assertFalse("spatial_audio" in FreeClipControlKey.DOUBLE_LEFT.values)
        assertFalse("noise_cancellation" in FreeClipControlKey.DOUBLE_RIGHT.values)
        assertEquals(listOf("play_next", "play_previous", "none"), FreeClipControlKey.TRIPLE_LEFT.values)
        assertNull(FreeClipControlProtocol.key("ANC"))
    }

    @Test fun `writes reuse the exact existing captured protocol packets`() {
        assertArrayEquals(hex("5A0006002B4901010A9E71"), FreeClipControlProtocol.write(FreeClipControlKey.EQUALIZER, "10"))
        assertArrayEquals(hex("5A0006002B990101018719"), FreeClipControlProtocol.write(FreeClipControlKey.ADAPTIVE_CHANNELS, "true"))
        assertArrayEquals(hex("5A0009002BB4010107020100AFA4"), FreeClipControlProtocol.write(FreeClipControlKey.DROP_REMINDER, "false"))
        assertArrayEquals(
            HuaweiGestureController.buildTapPacket(HuaweiDeviceRoute.HUAWEI_FREECLIP, HuaweiGestureKind.TRIPLE_TAP, HuaweiGestureSide.RIGHT, HuaweiTapAction.PLAY_PREVIOUS),
            FreeClipControlProtocol.write(FreeClipControlKey.TRIPLE_RIGHT, "play_previous"),
        )
        assertNull(FreeClipControlProtocol.write(FreeClipControlKey.EQUALIZER, "2"))
        assertNull(FreeClipControlProtocol.write(FreeClipControlKey.TRIPLE_LEFT, "voice_assistant"))
        assertNull(FreeClipControlProtocol.write(FreeClipControlKey.ADAPTIVE_CHANNELS, "1"))
    }

    @Test fun `refresh queries every supported group and targeted reads omit other groups`() {
        assertArrayEquals(
            hex("5A000700012001000200E8975A0007000126010002002512" +
                "5A0005002B4A02008C465A0005002B9A0100BC815A0008002BB40101070200DDE9"),
            FreeClipControlProtocol.query(),
        )
        assertArrayEquals(hex("5A0005002B4A02008C46"), FreeClipControlProtocol.query(FreeClipControlKey.EQUALIZER))
        assertArrayEquals(hex("5A0005002B9A0100BC81"), FreeClipControlProtocol.query(FreeClipControlKey.ADAPTIVE_CHANNELS))
        assertArrayEquals(hex("5A0008002BB40101070200DDE9"), FreeClipControlProtocol.query(FreeClipControlKey.DROP_REMINDER))
    }

    @Test fun `complete checksummed device replies confirm all seven independent fields`() {
        val state = FreeClipControlProtocol.readback(allState())
        assertEquals(7, state.size)
        assertEquals("play_pause", state[FreeClipControlKey.DOUBLE_LEFT])
        assertEquals("voice_assistant", state[FreeClipControlKey.DOUBLE_RIGHT])
        assertEquals("play_previous", state[FreeClipControlKey.TRIPLE_LEFT])
        assertEquals("play_next", state[FreeClipControlKey.TRIPLE_RIGHT])
        assertEquals("10", state[FreeClipControlKey.EQUALIZER])
        assertEquals("true", state[FreeClipControlKey.ADAPTIVE_CHANNELS])
        assertEquals("false", state[FreeClipControlKey.DROP_REMINDER])
    }

    @Test fun `CRC failures truncated packets and acknowledgement alone cannot confirm a setting`() {
        val drop = hex("5A0009002BB4010107020101BF85")
        assertTrue(FreeClipControlProtocol.readback(drop.copyOf(drop.size - 1)).isEmpty())
        val damaged = drop.copyOf().also { it[11] = 0 }
        assertTrue(FreeClipControlProtocol.readback(damaged).isEmpty())
        assertTrue(FreeClipControlProtocol.readback(requireNotNull(FreeClipControlProtocol.write(FreeClipControlKey.EQUALIZER, "10"))).isEmpty())
        assertTrue(FreeClipControlProtocol.readback(requireNotNull(FreeClipControlProtocol.write(FreeClipControlKey.ADAPTIVE_CHANNELS, "true"))).isEmpty())
        assertTrue(FreeClipControlProtocol.readback(requireNotNull(FreeClipControlProtocol.write(FreeClipControlKey.DOUBLE_LEFT, "play_next"))).isEmpty())
        assertTrue(FreeClipControlProtocol.readback(ByteArray(65_537)).isEmpty())
    }

    @Test fun `partial response confirms only its own group and skips noise`() {
        val stream = hex("0102035AFFFF") + hex("5A0006002B9A0101011CC5") + hex("5A0009002BB401010B020101F0B7")
        assertEquals(mapOf(FreeClipControlKey.ADAPTIVE_CHANNELS to "true"), FreeClipControlProtocol.readback(stream))
        assertFalse(FreeClipControlKey.DOUBLE_LEFT in FreeClipControlProtocol.readback(stream))
    }

    @Test fun `latest device value wins while unsupported effect and unknown boolean remain unknown`() {
        val values = FreeClipControlProtocol.readback(
            hex("5A0006002B9A0101011CC55A0006002B9A0101000CE4") +
                frame("2B4A010101020164") + frame("2BB4010107020102"),
        )
        assertEquals(mapOf(FreeClipControlKey.ADAPTIVE_CHANNELS to "false"), values)
    }

    private fun allState(): ByteArray = frame("0120010101020100") + frame("0126010107020102") +
        frame("2B4A01010102010A") + hex("5A0006002B9A0101011CC5") + hex("5A0009002BB4010107020100AFA4")

    private fun frame(bodyHex: String): ByteArray {
        val body = hex(bodyHex)
        val payloadLength = body.size + 1
        val content = byteArrayOf(0x5A, 0, payloadLength.toByte(), 0) + body
        var crc = 0
        content.forEach { byte ->
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)
            repeat(8) { crc = ((crc shl 1) xor if (crc and 0x8000 != 0) 0x1021 else 0) and 0xFFFF }
        }
        return content + byteArrayOf((crc shr 8).toByte(), crc.toByte())
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
