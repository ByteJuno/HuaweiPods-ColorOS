package moe.chenxy.huaweipods.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorOsBatteryIdentityTest {
    private val first = ColorOsPodBattery("AA:BB:CC:DD:EE:01", 100, true, 100, true, 88, true, "HUAWEI FreeClip")
    private val second = first.copy(address = "AA:BB:CC:DD:EE:02", case = 20)
    private val pods = listOf(first, second)

    @Test fun `equal earbud values and identical names still resolve by address`() {
        assertEquals(second, colorOsPodForIdentity(pods, address = second.address.lowercase(), names = listOf(first.name)))
        assertEquals(first, colorOsPodForIdentity(pods.reversed(), address = first.address))
    }

    @Test fun `an explicit unknown or blank address never falls back to the name`() {
        assertNull(colorOsPodForIdentity(listOf(first), address = "AA:BB:CC:DD:EE:03", names = listOf(first.name)))
        assertNull(colorOsPodForIdentity(listOf(first), address = "", names = listOf(first.name)))
    }

    @Test fun `card id selects the correct partial snapshot rather than the fullest one`() {
        val partial = second.copy(rightConnected = false)
        assertEquals(partial, colorOsPodForIdentity(listOf(first, partial),
            identities = listOf(ColorOsHomeCardBattery.deviceIdFor(second.address)?.uppercase()), names = listOf(first.name)))
    }

    @Test fun `conflicting id and same name cannot override device identity`() {
        assertNull(colorOsPodForIdentity(listOf(first),
            identities = listOf(ColorOsHomeCardBattery.deviceIdFor(second.address)), names = listOf(first.name)))
        assertNull(colorOsPodForIdentity(pods, identities = pods.map { ColorOsHomeCardBattery.deviceIdFor(it.address) }))
    }

    @Test fun `ambiguous identical device names remain native`() {
        assertNull(colorOsPodForIdentity(pods, names = listOf("FreeClip")))
        assertNull(colorOsPodForIdentity(pods.reversed(), names = listOf("HUAWEI FreeClip")))
    }

    @Test fun `unique normalized names can match when no id exists`() {
        assertEquals(first, colorOsPodForIdentity(listOf(first), names = listOf("free-clip")))
        assertNull(colorOsPodForIdentity(listOf(first), names = listOf(null, "", "Free")))
    }

    @Test fun `FreeClip and FreeClip 2 names do not identify the same model`() {
        val newer = second.copy(name = "HUAWEI FreeClip 2")
        assertEquals(first, colorOsPodForIdentity(listOf(first, newer), names = listOf("FreeClip")))
        assertEquals(newer, colorOsPodForIdentity(listOf(first, newer), names = listOf("FreeClip 2")))
    }
}
