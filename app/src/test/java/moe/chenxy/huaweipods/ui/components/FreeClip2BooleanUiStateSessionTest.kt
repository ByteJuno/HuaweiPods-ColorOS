package moe.chenxy.huaweipods.ui.components

import moe.chenxy.huaweipods.pods.FreeClip2BooleanFeature
import moe.chenxy.huaweipods.pods.FreeClip2HeadMotionAction
import moe.chenxy.huaweipods.pods.FreeClip2HeadMotionState
import org.junit.Assert.*
import org.junit.Test

class FreeClip2BooleanUiStateSessionTest {
    private val feature = FreeClip2BooleanFeature.LOW_LATENCY
    private val head = FreeClip2HeadMotionState(
        true,
        FreeClip2HeadMotionAction.ANSWER_CALL,
        FreeClip2HeadMotionAction.REJECT_CALL,
    )

    @Test
    fun `paused read cannot overwrite resumed read or clear its busy flag`() {
        val state = FreeClip2BooleanUiState()
        val old = requireNotNull(state.beginRead())
        state.deactivate()
        assertFalse(state.active)
        assertFalse(state.busy)
        state.activate()
        val current = requireNotNull(state.beginRead())
        assertNotEquals(old, current)

        state.finishRead(old, mapOf(feature to true), head)
        assertTrue(state.reading)
        assertTrue(state.values.isEmpty())
        assertNull(state.head)

        state.finishRead(current, mapOf(feature to false), null)
        assertFalse(state.busy)
        assertEquals(false, state.values[feature])
        assertNull(state.head)
        state.finishRead(current, mapOf(feature to true), head)
        assertEquals(false, state.values[feature])
        assertNull(state.head)
    }

    @Test
    fun `pause cancels old UI once and stale write cannot finish a new write or persist`() {
        val state = FreeClip2BooleanUiState()
        val oldResults = mutableListOf<Boolean>()
        val newResults = mutableListOf<Boolean>()
        var persisted = 0
        val old = requireNotNull(state.beginWrite { oldResults.add(it) })
        state.deactivate()
        state.deactivate()
        assertEquals(listOf(false), oldResults)
        assertFalse(state.writing)
        assertFalse(state.busy)

        state.activate()
        val current = requireNotNull(state.beginWrite { newResults.add(it) })
        state.finishWrite(old, true) { persisted++; true }
        assertTrue(state.writing)
        assertTrue(newResults.isEmpty())
        assertEquals(listOf(false), oldResults)
        assertEquals(0, persisted)

        state.finishWrite(current, true) { persisted++; true }
        assertFalse(state.busy)
        assertEquals(listOf(true), newResults)
        assertEquals(1, persisted)
        state.finishWrite(current, true) { persisted++; true }
        assertEquals(listOf(true), newResults)
        assertEquals(1, persisted)
    }

    @Test
    fun `resume starts a fresh read without waiting for a missing old completion`() {
        val state = FreeClip2BooleanUiState()
        val results = mutableListOf<Boolean>()
        val old = requireNotNull(state.beginWrite { results.add(it) })
        state.deactivate()
        var refreshes = 0
        var readTicket: Long? = null
        state.refresh = {
            refreshes++
            readTicket = state.beginRead()
        }
        state.activate()
        assertEquals(1, refreshes)
        assertNotNull(readTicket)
        assertTrue(state.reading)
        assertFalse(state.writing)

        state.finishWrite(old, true) { fail("Stale write must not persist"); true }
        assertEquals(1, refreshes)
        assertTrue(state.reading)
        assertEquals(listOf(false), results)
    }

    @Test
    fun `deactivation clears known values and rejects inactive work and late readbacks`() {
        val state = FreeClip2BooleanUiState()
        val read = requireNotNull(state.beginRead())
        state.finishRead(read, mapOf(feature to true), head)
        val write = requireNotNull(state.beginWrite { })
        assertTrue(state.isCurrent(write))
        state.deactivate()
        assertFalse(state.isCurrent(write))
        assertFalse(state.active)
        assertFalse(state.busy)
        assertTrue(state.values.isEmpty())
        assertNull(state.head)
        assertNull(state.beginRead())
        val rejected = mutableListOf<Boolean>()
        assertNull(state.beginWrite { rejected.add(it) })
        assertEquals(listOf(false), rejected)
        state.finishRead(read, mapOf(feature to true), head)
        state.finishWrite(write, true) { fail("Disposed write must not persist"); true }
        assertTrue(state.values.isEmpty())
        assertNull(state.head)
        assertFalse(state.busy)
    }

    @Test
    fun `unconfirmed writes never persist and persistence failure is reported`() {
        val state = FreeClip2BooleanUiState()
        val results = mutableListOf<Boolean>()
        var persisted = 0
        val failed = requireNotNull(state.beginWrite { results.add(it) })
        state.finishWrite(failed, false) { persisted++; true }
        assertEquals(0, persisted)
        assertEquals(listOf(false), results)
        assertFalse(state.busy)

        val confirmed = requireNotNull(state.beginWrite { results.add(it) })
        state.finishWrite(confirmed, true) { persisted++; false }
        assertEquals(1, persisted)
        assertEquals(listOf(false, false), results)
        assertFalse(state.busy)
    }

    @Test
    fun `busy requests are rejected without replacing current completion`() {
        val state = FreeClip2BooleanUiState()
        val accepted = mutableListOf<Boolean>()
        val rejected = mutableListOf<Boolean>()
        val ticket = requireNotNull(state.beginWrite { accepted.add(it) })
        assertNull(state.beginRead())
        assertNull(state.beginWrite { rejected.add(it) })
        assertEquals(listOf(false), rejected)
        assertTrue(state.isCurrent(ticket))
        assertTrue(state.writing)
        state.finishWrite(ticket, true)
        assertEquals(listOf(true), accepted)
        assertFalse(state.busy)
    }
}
