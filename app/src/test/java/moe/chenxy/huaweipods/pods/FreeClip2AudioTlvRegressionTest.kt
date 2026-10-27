package moe.chenxy.huaweipods.pods

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FreeClip2AudioTlvRegressionTest {
    @Test
    fun spatialAcceptsAllKnownModesAndScenes() {
        for (mode in FreeClip2SpatialAudioMode.entries) {
            for (scene in FreeClip2SpatialScene.entries) {
                val packet = spatial(
                    1, 1, 0x18,
                    2, 1, mode.protocolValue,
                    3, 1, scene.protocolValue,
                )
                assertEquals(
                    FreeClip2AudioState(mode = mode, scene = scene),
                    HuaweiFreeClip2Controller.parseSpatialAudioState(packet),
                )
            }
        }
    }

    @Test
    fun spatialUsesFieldTypesRatherThanFixedOffsets() {
        assertNull(
            HuaweiFreeClip2Controller.parseSpatialAudioState(
                spatial(1, 1, 0x18, 2, 1, 1, 4, 1, 2),
            ),
        )
        assertEquals(
            FreeClip2AudioState(
                mode = FreeClip2SpatialAudioMode.HEAD_TRACKING,
                scene = FreeClip2SpatialScene.CINEMA,
            ),
            HuaweiFreeClip2Controller.parseSpatialAudioState(
                spatial(3, 1, 2, 1, 1, 0x18, 2, 1, 1),
            ),
        )
    }

    @Test
    fun spatialRejectsWrongSceneLengthsEvenWithValidCrc() {
        val invalidScenes = listOf(
            intArrayOf(3, 0),
            intArrayOf(3, 2, 2, 0),
            intArrayOf(3, 2, 2),
            intArrayOf(3, 1),
        )
        for (scene in invalidScenes) {
            assertNull(
                HuaweiFreeClip2Controller.parseSpatialAudioState(
                    spatial(1, 1, 0x18, 2, 1, 1, *scene),
                ),
            )
        }
    }

    @Test
    fun spatialRejectsDuplicateFieldsIncludingIdenticalDuplicates() {
        val validFields = intArrayOf(1, 1, 0x18, 2, 1, 1, 3, 1, 2)
        val duplicates = listOf(
            intArrayOf(1, 1, 0x18),
            intArrayOf(1, 1, 0x0B),
            intArrayOf(2, 1, 1),
            intArrayOf(2, 1, 2),
            intArrayOf(3, 1, 2),
            intArrayOf(3, 1, 0),
        )
        for (duplicate in duplicates) {
            assertNull(
                HuaweiFreeClip2Controller.parseSpatialAudioState(
                    spatial(*validFields, *duplicate),
                ),
            )
        }
    }

    @Test
    fun spatialRejectsTrailingMalformedFieldsAndUnknownEnums() {
        assertNull(
            HuaweiFreeClip2Controller.parseSpatialAudioState(
                spatial(1, 1, 0x18, 2, 1, 1, 3, 1, 2, 4),
            ),
        )
        assertNull(
            HuaweiFreeClip2Controller.parseSpatialAudioState(
                spatial(1, 1, 0x18, 2, 1, 0xFF, 3, 1, 2),
            ),
        )
        assertNull(
            HuaweiFreeClip2Controller.parseSpatialAudioState(
                spatial(1, 1, 0x18, 2, 1, 1, 3, 1, 0xFF),
            ),
        )
    }

    @Test
    fun spatialDoesNotSearchForResponsePrefixInsideAnotherCommand() {
        assertNull(
            HuaweiFreeClip2Controller.parseSpatialAudioState(
                frame(listOf(0x2B, 0x4A, 0x2B, 0xB4, 1, 1, 0x18, 2, 1, 1, 3, 1, 2)),
            ),
        )
    }

    @Test
    fun malformedSpatialFrameDoesNotReplaceEarlierVerifiedState() {
        val valid = spatial(1, 1, 0x18, 2, 1, 1, 3, 1, 2)
        val malformed = spatial(1, 1, 0x18, 2, 1, 2, 4, 1, 0)
        assertEquals(
            HuaweiFreeClip2Controller.parseSpatialAudioState(valid),
            HuaweiFreeClip2Controller.parseSpatialAudioState(valid + malformed),
        )
    }

    @Test
    fun soundEffectAcceptsMinimalReports() {
        for (effect in FreeClip2SoundEffect.selectableEntries) {
            val state = HuaweiFreeClip2Controller.parseSoundEffectState(
                sound(2, 1, effect.protocolValue),
            )
            assertEquals(effect, state?.effect)
            assertEquals(effect.protocolValue, state?.equalizer?.selectedId)
        }
    }

    @Test
    fun soundEffectDoesNotMatchAcrossFieldBoundaries() {
        // Field 03 contains 02, followed by field 01 containing 09. There is
        // no selected-effect field, despite the cross-boundary bytes 02 01 01.
        assertNull(
            HuaweiFreeClip2Controller.parseSoundEffectState(
                sound(3, 1, 2, 1, 1, 9),
            ),
        )
    }

    @Test
    fun soundEffectDoesNotMatchInsideFieldValues() {
        assertNull(
            HuaweiFreeClip2Controller.parseSoundEffectState(
                sound(3, 3, 2, 1, 1),
            ),
        )
    }

    @Test
    fun soundEffectRejectsWrongLengthsAndTruncatedFields() {
        val invalidFields = listOf(
            intArrayOf(2, 0),
            intArrayOf(2, 2, 1, 9),
            intArrayOf(2, 2, 1),
            intArrayOf(2, 1),
            intArrayOf(2, 1, 1, 3),
        )
        for (fields in invalidFields) {
            assertNull(
                HuaweiFreeClip2Controller.parseSoundEffectState(sound(*fields)),
            )
        }
    }

    @Test
    fun soundEffectRejectsDuplicateSelectedFields() {
        assertNull(
            HuaweiFreeClip2Controller.parseSoundEffectState(
                sound(2, 1, 1, 2, 1, 1),
            ),
        )
        assertNull(
            HuaweiFreeClip2Controller.parseSoundEffectState(
                sound(2, 1, 1, 2, 1, 9),
            ),
        )
    }

    @Test
    fun soundEffectAcceptsLegalVariableLengthFieldsWithoutScanningTheirValues() {
        val state = HuaweiFreeClip2Controller.parseSoundEffectState(
            sound(1, 1, 1, 3, 4, 2, 1, 1, 9, 2, 1, 9),
        )
        assertEquals(FreeClip2SoundEffect.CLEAR_VOICE, state?.effect)
        assertEquals(listOf(2, 1, 1, 9), state?.equalizer?.builtInIds)
    }

    @Test
    fun soundEffectPreservesCustomEqualizerReports() {
        val state = HuaweiFreeClip2Controller.parseSoundEffectState(
            sound(1, 1, 1, 2, 1, 0x64, 5, 1, 2, 6, 2, 0, 1),
        )
        assertEquals(FreeClip2SoundEffect.CUSTOM, state?.effect)
        assertEquals(0x64, state?.equalizer?.selectedId)
        assertEquals(listOf(0, 1), state?.equalizer?.selectedGains)
    }

    @Test
    fun audioReportsStillRequireValidCrc() {
        val spatial = spatial(1, 1, 0x18, 2, 1, 1, 3, 1, 2)
        spatial[spatial.lastIndex] = (spatial.last().toInt() xor 1).toByte()
        assertNull(HuaweiFreeClip2Controller.parseSpatialAudioState(spatial))

        val sound = sound(2, 1, 1)
        sound[sound.lastIndex] = (sound.last().toInt() xor 1).toByte()
        assertNull(HuaweiFreeClip2Controller.parseSoundEffectState(sound))
    }

    private fun spatial(vararg fields: Int): ByteArray =
        frame(listOf(0x2B, 0xB4) + fields.toList())

    private fun sound(vararg fields: Int): ByteArray =
        frame(listOf(0x2B, 0x4A) + fields.toList())

    // Build complete frames independently of production helpers, including a
    // fresh CRC for malformed TLVs so CRC rejection cannot hide parser defects.
    private fun frame(body: List<Int>): ByteArray {
        val payloadLength = body.size + 1
        val bytes = (listOf(0x5A, 0, payloadLength and 0xFF, payloadLength shr 8) + body)
            .map { it.toByte() }
            .toByteArray()
        var crc = 0
        for (byte in bytes) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return bytes + byteArrayOf((crc shr 8).toByte(), crc.toByte())
    }
}
