package moe.chenxy.huaweipods.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorOsBatterySnapshotTest {

    private val pod = ColorOsPodBattery(
        address = "30:a9:98:cb:c4:54",
        left = 88,
        leftConnected = true,
        right = 92,
        rightConnected = true,
        case = 100,
        caseConnected = true,
        name = "HUAWEI FreeClip",
    )

    @Test
    fun `round trips a full snapshot`() {
        val decoded = ColorOsBatterySnapshot.decode(ColorOsBatterySnapshot.encode(pod))
        assertEquals("30:A9:98:CB:C4:54", decoded?.address)
        assertEquals(88, decoded?.left)
        assertEquals(92, decoded?.right)
        assertEquals(100, decoded?.case)
        assertEquals(true, decoded?.leftConnected)
        assertEquals(true, decoded?.rightConnected)
        assertEquals(true, decoded?.caseConnected)
        assertEquals("HUAWEI FreeClip", decoded?.name)
    }

    @Test
    fun `keeps disconnected parts disconnected`() {
        val half = pod.copy(rightConnected = false, caseConnected = false, case = 0, name = null)
        val decoded = ColorOsBatterySnapshot.decode(ColorOsBatterySnapshot.encode(half))
        assertEquals(88, decoded?.left)
        assertTrue(decoded?.leftConnected == true)
        assertFalse(decoded?.rightConnected == true)
        assertFalse(decoded?.caseConnected == true)
        assertNull(decoded?.name)
    }

    @Test
    fun `strips separators out of the device name`() {
        val weird = pod.copy(name = "Free|Clip|2")
        val decoded = ColorOsBatterySnapshot.decode(ColorOsBatterySnapshot.encode(weird))
        assertEquals("Free Clip 2", decoded?.name)
        assertEquals(100, decoded?.case)
    }

    @Test
    fun `rejects malformed snapshots instead of inventing values`() {
        assertNull(ColorOsBatterySnapshot.decode(null))
        assertNull(ColorOsBatterySnapshot.decode(""))
        assertNull(ColorOsBatterySnapshot.decode("30:A9:98:CB:C4:54|88|1|92"))
        assertNull(ColorOsBatterySnapshot.decode("|88|1|92|1|100|1|name"))
        assertNull(ColorOsBatterySnapshot.decode("30:A9:98:CB:C4:54|x|1|92|1|100|1|name"))
    }

    @Test
    fun `disconnect clears only the matching snapshot including lower case addresses`() {
        var persisted: String? = ColorOsBatterySnapshot.encode(pod)
        assertFalse(ColorOsBatterySnapshot.clearIfMatches("AA:BB:CC:DD:EE:FF", { persisted }) { persisted = it; true })
        assertEquals(pod.address.uppercase(), ColorOsBatterySnapshot.decode(persisted)?.address)
        assertTrue(ColorOsBatterySnapshot.clearIfMatches(pod.address, { persisted }) { persisted = it; true })
        assertNull(persisted)
    }

    @Test
    fun `invalid addresses or snapshots never issue a clear write`() {
        var writes = 0
        val write: (String?) -> Boolean = { writes++; true }
        assertFalse(ColorOsBatterySnapshot.clearIfMatches("", { ColorOsBatterySnapshot.encode(pod) }, write))
        assertFalse(ColorOsBatterySnapshot.clearIfMatches(pod.address, { "malformed" }, write))
        assertFalse(ColorOsBatterySnapshot.clearIfMatches(pod.address, { null }, write))
        assertEquals(0, writes)
    }

    @Test
    fun `failed storage write is not reported as a successful clear`() {
        assertFalse(ColorOsBatterySnapshot.clearIfMatches(pod.address, { ColorOsBatterySnapshot.encode(pod) }) { false })
    }

    @Test fun `legacy snapshot migrates without discarding the first device`() {
        val second = pod.copy(address = "AA:BB:CC:DD:EE:02", case = 20)
        var stored: String? = ColorOsBatterySnapshot.encode(pod)
        assertTrue(ColorOsBatterySnapshot.saveInto(second, { stored }) { stored = it; true })
        val restored = ColorOsBatterySnapshot.decodeAll(stored)
        assertEquals(2, restored.size)
        assertEquals(100, restored.single { it.address == pod.address.uppercase() }.case)
        assertEquals(20, restored.single { it.address == second.address }.case)
    }

    @Test fun `updates and disconnects only modify their own address`() {
        val second = pod.copy(address = "AA:BB:CC:DD:EE:02", case = 20)
        var stored: String? = ColorOsBatterySnapshot.encodeAll(listOf(pod, second))
        assertTrue(ColorOsBatterySnapshot.saveInto(pod.copy(case = 99), { stored }) { stored = it; true })
        assertEquals(2, ColorOsBatterySnapshot.decodeAll(stored).size)
        assertTrue(ColorOsBatterySnapshot.clearIfMatches(pod.address.lowercase(), { stored }) { stored = it; true })
        assertEquals(listOf(second), ColorOsBatterySnapshot.decodeAll(stored))
        assertTrue(ColorOsBatterySnapshot.clearIfMatches(second.address, { stored }) { stored = it; true })
        assertNull(stored)
    }

    @Test fun `names cannot inject additional snapshot rows`() {
        val named = pod.copy(name = "FreeClip\r\nInjected|record")
        val restored = ColorOsBatterySnapshot.decodeAll(ColorOsBatterySnapshot.encodeAll(listOf(named)))
        assertEquals(1, restored.size)
        assertEquals("FreeClip  Injected record", restored.single().name)
    }

    @Test fun `malformed rows do not hide other valid device records`() {
        val normalized = pod.copy(address = pod.address.uppercase())
        val legacy = ColorOsBatterySnapshot.encode(pod)
        assertEquals(listOf(normalized), ColorOsBatterySnapshot.decodeAll("v2\ninvalid\n$legacy\n"))
        assertEquals(listOf(normalized), ColorOsBatterySnapshot.decodeAll(legacy))
        assertEquals(emptyList<ColorOsPodBattery>(), ColorOsBatterySnapshot.decodeAll(null))
        assertEquals(emptyList<ColorOsPodBattery>(), ColorOsBatterySnapshot.decodeAll("v2\n"))
    }

    @Test fun `failed writes and empty addresses do not pretend to save`() {
        var writes = 0
        assertFalse(ColorOsBatterySnapshot.saveInto(pod.copy(address = ""), { null }) { writes++; true })
        assertEquals(0, writes)
        assertFalse(ColorOsBatterySnapshot.saveInto(pod, { null }) { false })
    }
}
