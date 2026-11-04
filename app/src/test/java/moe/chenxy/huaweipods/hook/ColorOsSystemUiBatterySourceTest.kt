package moe.chenxy.huaweipods.hook

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorOsBatterySourceTest {
    private val pod = ColorOsPodBattery("30:A9:98:CB:C4:54", 100, true, 99, true, 88, true)
    private var receive: (ColorOsPodBattery) -> Unit = {}
    private var disconnect: (String?) -> Unit = {}
    private var registrations = 0
    private var releases = 0
    private var loads = 0
    private var changes = 0

    private fun source(load: () -> ColorOsPodBattery? = { pod }) = ColorOsBatterySource(
        loadSnapshot = { loads++; listOfNotNull(load()) },
        subscribe = { battery, disconnected ->
            registrations++
            receive = battery
            disconnect = disconnected
            AutoCloseable { releases++ }
        },
        onChanged = { changes++ },
    )

    @After
    fun clearStore() {
        ColorOsPodBatteryStore.clear(null)
    }

    @Test
    fun `cold start restores once and repeated start does not register again`() {
        val source = source()
        assertNull(source.get(pod.address))
        source.start()
        source.start()
        assertEquals(pod, source.get(pod.address))
        assertEquals(1, registrations)
        assertEquals(1, loads)
        assertEquals(1, changes)
        source.close()
    }

    @Test
    fun `receiver is ready before loading and newer battery wins`() {
        val fresh = pod.copy(case = 77)
        val source = source {
            assertEquals(1, registrations)
            receive(fresh)
            pod
        }
        source.start()
        assertEquals(fresh, source.get(pod.address))
        source.close()
    }

    @Test
    fun `disconnect received during restore blocks the old snapshot`() {
        val source = source {
            disconnect(pod.address)
            pod
        }
        source.start()
        assertNull(source.get(pod.address))
        source.close()
    }

    @Test
    fun `disconnect is not undone by reads or repeated start`() {
        val source = source()
        source.start()
        disconnect(pod.address.lowercase())
        repeat(3) {
            assertNull(source.get(pod.address))
            source.start()
        }
        assertEquals(1, loads)
        source.close()
    }

    @Test
    fun `disconnect one earbud preserves another address`() {
        val second = pod.copy(address = "AA:BB:CC:DD:EE:FF", rightConnected = false)
        val source = source()
        source.start()
        receive(second)
        disconnect(pod.address)
        assertNull(source.get(pod.address))
        assertEquals(second, source.get(second.address))
        disconnect(null)
        assertNull(source.get(second.address))
        source.close()
    }

    @Test
    fun `close unregisters once and ignores late events and reattach`() {
        val source = source()
        source.start()
        source.close()
        source.close()
        val before = changes
        receive(pod.copy(case = 1))
        disconnect(pod.address)
        source.start()
        assertEquals(1, registrations)
        assertEquals(1, releases)
        assertEquals(before, changes)
        assertEquals(pod, ColorOsPodBatteryStore.get(pod.address))
        assertNull(source.get(pod.address))
    }

    @Test
    fun `closing before initialization never registers`() {
        val source = source()
        source.close()
        source.start()
        assertEquals(0, registrations)
        assertEquals(0, loads)
    }

    @Test
    fun `restore failure releases the receiver and initialization can retry`() {
        var fail = true
        val source = source {
            if (fail) error("snapshot unavailable")
            pod
        }
        runCatching { source.start() }
        assertEquals(1, releases)
        assertNull(source.get(pod.address))
        fail = false
        source.start()
        assertEquals(2, registrations)
        assertEquals(pod, source.get(pod.address))
        source.close()
        assertEquals(2, releases)
    }

    @Test
    fun `hot reload starts a new subscription without rolling back received values`() {
        val previous = source()
        previous.start()
        val fresh = pod.copy(case = 61)
        receive(fresh)
        previous.close()
        val next = source()
        next.start()
        assertEquals(fresh, next.get(pod.address))
        assertEquals(2, registrations)
        next.close()
    }

    @Test fun `cold restore loads both devices and a live event only replaces its own entry`() {
        val second = pod.copy(address = "AA:BB:CC:DD:EE:02", case = 20)
        val source = multiSource { listOf(pod, second) }
        source.start()
        assertEquals(pod, source.get(pod.address))
        assertEquals(second, source.get(second.address))
        receive(pod.copy(case = 70))
        assertEquals(70, source.get(pod.address)?.case)
        assertEquals(second, source.get(second.address))
        disconnect(second.address)
        assertNull(source.get(second.address))
        assertEquals(70, source.get(pod.address)?.case)
        source.close()
    }

    @Test fun `disconnect during multi restore excludes only the disconnected record`() {
        val second = pod.copy(address = "AA:BB:CC:DD:EE:02", case = 20)
        val source = multiSource {
            disconnect(pod.address)
            listOf(pod, second)
        }
        source.start()
        assertNull(source.get(pod.address))
        assertEquals(second, source.get(second.address))
        source.close()
    }

    @Test fun `disconnect all while restoring cannot resurrect any cached device`() {
        val second = pod.copy(address = "AA:BB:CC:DD:EE:02")
        val source = multiSource {
            disconnect(null)
            listOf(pod, second)
        }
        source.start()
        assertNull(source.get(pod.address))
        assertNull(source.get(second.address))
        receive(second.copy(case = 60))
        assertEquals(60, source.get(second.address)?.case)
        assertNull(source.get(pod.address))
        source.close()
    }

    private fun multiSource(load: () -> List<ColorOsPodBattery>) = ColorOsBatterySource(
        loadSnapshot = load,
        subscribe = { battery, disconnected ->
            receive = battery
            disconnect = disconnected
            AutoCloseable { releases++ }
        },
        onChanged = { changes++ },
    )
}
