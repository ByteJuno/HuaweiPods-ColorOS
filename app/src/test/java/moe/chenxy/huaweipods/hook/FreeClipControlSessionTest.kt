package moe.chenxy.huaweipods.hook

import moe.chenxy.huaweipods.pods.FreeClipControlKey
import moe.chenxy.huaweipods.pods.FreeClipControlProtocol
import moe.chenxy.huaweipods.pods.HuaweiDeviceRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeClipControlSessionTest {
    private val address = "AA:BB:CC:DD:EE:01"
    private val key = FreeClipControlKey.ADAPTIVE_CHANNELS
    private val route = FreeClipControlProtocol.ROUTE

    @Test fun `write stays unknown until matching device readback`() {
        val session = confirmedSession()
        session.begin("write", 20, key, "true")
        assertNull(session.values[key])
        assertNotNull(session.pending)
        assertTrue(session.complete("write", address, route, mapOf(key to "true"), 21))
        assertEquals("true", session.values[key])
        assertFalse(session.failed)
        assertNull(session.pending)
    }

    @Test fun `mismatch shows actual device state rather than requested value`() {
        val session = confirmedSession()
        session.begin("write", 20, key, "true")
        assertTrue(session.complete("write", address, route, mapOf(key to "false"), 21))
        assertEquals("false", session.values[key])
        assertTrue(session.failed)
    }

    @Test fun `failed or timed out write never restores a guessed old value`() {
        val session = confirmedSession()
        val request = requireNotNull(session.begin("timeout", 20, key, "true"))
        assertFalse(session.expire(request.deadline - 1))
        assertTrue(session.expire(request.deadline))
        assertNull(session.values[key])
        assertTrue(session.failed)
        assertFalse(session.complete("timeout", address, route, mapOf(key to "true"), request.deadline + 1))
        session.begin("failed", request.deadline + 2, key, "true")
        session.complete("failed", address, route, emptyMap(), request.deadline + 3)
        assertNull(session.values[key])
        assertTrue(session.failed)
    }

    @Test fun `wrong nonce device route and late result do not complete pending request`() {
        val session = FreeClipControlSession(address)
        val request = requireNotNull(session.begin("expected", 100, key, "true"))
        val state = mapOf(key to "true")
        assertFalse(session.complete("old", address, route, state, 101))
        assertFalse(session.complete("expected", "AA:BB:CC:DD:EE:02", route, state, 101))
        assertFalse(session.complete("expected", address, HuaweiDeviceRoute.HUAWEI_FREECLIP2, state, 101))
        assertFalse(session.complete("expected", address, null, state, 101))
        assertFalse(session.complete("expected", address, route, state, request.deadline))
        assertEquals(request, session.pending)
        assertTrue(session.complete("expected", address.lowercase(), route, state, 102))
    }

    @Test fun `user write supersedes slow full refresh and its stale reply`() {
        val session = FreeClipControlSession(address)
        session.begin("refresh", 1)
        assertNotNull(session.begin("write", 2, key, "true"))
        assertFalse(session.complete("refresh", address, route, mapOf(key to "false"), 3))
        assertNull(session.values[key])
        assertEquals("write", session.pending?.nonce)
        assertTrue(session.complete("write", address, route, mapOf(key to "true"), 4))
        assertEquals("true", session.values[key])
    }

    @Test fun `parallel writes and duplicate refreshes are not admitted`() {
        val session = FreeClipControlSession(address)
        session.begin("refresh", 1)
        assertNull(session.begin("refresh-again", 2))
        session.begin("write", 3, key, "true")
        assertNull(session.begin("other-write", 4, FreeClipControlKey.DROP_REMINDER, "false"))
        assertNull(session.begin("refresh-during-write", 4))
    }

    @Test fun `partial refresh confirms only fields actually read`() {
        val session = FreeClipControlSession(address)
        session.begin("refresh", 1)
        assertTrue(session.complete("refresh", address, route, mapOf(key to "false"), 2))
        assertEquals(mapOf(key to "false"), session.values)
        assertTrue(session.failed)
        assertNotNull(session.begin("write", 3, FreeClipControlKey.EQUALIZER, "10"))
    }

    @Test fun `write completion ignores unrelated fields and invalid values`() {
        val session = confirmedSession()
        session.begin("write", 20, key, "true")
        session.complete("write", address, route, mapOf(key to "enabled", FreeClipControlKey.EQUALIZER to "10"), 21)
        assertNull(session.values[key])
        assertNull(session.values[FreeClipControlKey.EQUALIZER])
        assertTrue(session.failed)
    }

    @Test fun `disconnect close and reattachment invalidate old confirmation tokens`() {
        val session = confirmedSession()
        session.begin("old-write", 20, key, "true")
        session.reset(disconnected = true)
        assertTrue(session.values.isEmpty())
        assertNull(session.pending)
        assertFalse(session.connected)
        assertFalse(session.complete("old-write", address, route, mapOf(key to "true"), 21))
        assertNull(session.begin("offline-write", 22, key, "true"))
        assertNotNull(session.begin("reconnect-read", 23))
        session.reset()
        session.begin("new-read", 24)
        assertFalse(session.complete("reconnect-read", address, route, mapOf(key to "false"), 25))
        assertTrue(session.complete("new-read", address, route, mapOf(key to "false"), 26))
        assertTrue(session.connected)
    }

    @Test fun `invalid control values cannot start a transaction`() {
        val session = FreeClipControlSession(address)
        assertNull(session.begin("bad", 1, key, null))
        assertNull(session.begin("bad", 1, null, "true"))
        assertNull(session.begin("bad", 1, key, "1"))
        assertNull(session.begin("bad", 1, FreeClipControlKey.EQUALIZER, "2"))
        assertNull(session.pending)
    }

    @Test fun `snapshot requires matching identity and increasing nonnegative version`() {
        val session = FreeClipControlSession(address)
        val values = mapOf(key to "true")
        assertFalse(session.updateSnapshot("other", route, 10, 1, values, null))
        assertFalse(session.updateSnapshot(address, HuaweiDeviceRoute.HUAWEI_FREECLIP2, 10, 1, values, null))
        assertFalse(session.updateSnapshot(address, route, -1, 1, values, null))
        assertFalse(session.updateSnapshot(address, route, 10, -1, values, null))
        assertTrue(session.updateSnapshot(address.lowercase(), route, 10, 1, values, null))
        assertFalse(session.updateSnapshot(address, route, 10, 1, emptyMap(), null))
        assertFalse(session.updateSnapshot(address, route, 9, 99, emptyMap(), null))
        assertEquals(values, session.values)
        assertFalse(session.isCurrentResult(-1, -1))
        assertFalse(session.isCurrentResult(10, 0))
        assertTrue(session.isCurrentResult(10, 1))
    }

    @Test fun `snapshot replaces missing fields and does not finish local nonce`() {
        val session = FreeClipControlSession(address)
        session.begin("read", 0)
        session.updateSnapshot(address, route, 10, 1,
            mapOf(key to "true", FreeClipControlKey.EQUALIZER to "10"), null)
        assertEquals("read", session.pending?.nonce)
        session.updateSnapshot(address, route, 10, 2, mapOf(key to "false"), null)
        assertEquals(mapOf(key to "false"), session.values)
        assertEquals("read", session.pending?.nonce)
    }

    @Test fun `remote write invalidates its value and blocks writes but permits recovery read`() {
        val session = FreeClipControlSession(address)
        session.updateSnapshot(address, route, 10, 1, mapOf(key to "true"), key)
        assertNull(session.values[key])
        assertEquals(key, session.remotePending)
        assertNull(session.begin("write", 0, key, "false"))
        assertNotNull(session.begin("refresh", 1))
        session.updateSnapshot(address, route, 10, 2, mapOf(key to "false"), null)
        assertNull(session.remotePending)
        assertEquals("false", session.values[key])
    }

    @Test fun `stale result ends waiter without stealing snapshot or reporting success`() {
        val session = FreeClipControlSession(address)
        session.begin("write", 0, key, "true")
        session.updateSnapshot(address, route, 10, 3, mapOf(key to "true"), null)
        assertFalse(session.isCurrentResult(10, 2))
        assertTrue(session.complete("write", address, route, emptyMap(), 1))
        assertEquals("true", session.values[key])
        assertTrue(session.failed)
        assertNull(session.pending)
    }

    @Test fun `disconnect rejects all old epoch snapshots even at a higher revision`() {
        val session = FreeClipControlSession(address)
        session.updateSnapshot(address, route, 10, 1, mapOf(key to "true"), null)
        session.reset(disconnected = true, invalidateEpoch = true)
        assertFalse(session.updateSnapshot(address, route, 10, 100, mapOf(key to "true"), null))
        assertFalse(session.isCurrentResult(10, 100))
        assertFalse(session.connected)
        session.reset()
        assertFalse(session.updateSnapshot(address, route, 10, 101, mapOf(key to "true"), null))
        assertTrue(session.updateSnapshot(address, route, 11, 1, mapOf(key to "false"), null))
        assertEquals("false", session.values[key])
    }

    @Test fun `page reattachment can read the same bluetooth epoch`() {
        val session = FreeClipControlSession(address)
        session.updateSnapshot(address, route, 10, 1, mapOf(key to "true"), null)
        session.reset(disconnected = true)
        assertTrue(session.updateSnapshot(address, route, 10, 2, mapOf(key to "false"), null))
        assertTrue(session.connected)
    }

    private fun confirmedSession() = FreeClipControlSession(address).also {
        it.begin("initial", 1)
        it.complete("initial", address, route, mapOf(key to "false"), 2)
    }
}
