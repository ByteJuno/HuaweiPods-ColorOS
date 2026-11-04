package moe.chenxy.huaweipods.pods

import org.junit.Assert.*
import org.junit.Test

class FreeClipControlCoordinatorTest {
    private class Harness(epoch: Long = 100L) {
        var now = 100L
        val operations = mutableListOf<FreeClipControlCoordinator.Operation>()
        val states = mutableListOf<FreeClipControlCoordinator.Snapshot>()
        data class Reply(val nonce: String, val raw: ByteArray?, val state: FreeClipControlCoordinator.Snapshot)
        val replies = mutableListOf<Reply>()
        val events = mutableListOf<String>()
        val coordinator = FreeClipControlCoordinator(epoch, { now }, { operations.add(it) }, {
            states.add(it)
            events.add("state:${it.revision}")
        }, { nonce, raw, state ->
            replies.add(Reply(nonce, raw, state))
            events.add("result:$nonce:${state.revision}")
        })
        fun complete(index: Int, values: Map<FreeClipControlKey, String> = mapOf(FreeClipControlKey.EQUALIZER to "1")) {
            coordinator.complete(operations[index], byteArrayOf(1), values)
        }
    }

    @Test fun concurrentRefreshesShareTransportButFinishEveryNonce() {
        val h = Harness()
        h.coordinator.refresh("first", 1_000)
        h.coordinator.refresh("second", 2_000)
        assertEquals(1, h.operations.size)
        assertTrue(h.operations.single().canSend(1_500))
        h.complete(0)
        assertEquals(listOf("first", "second"), h.replies.map { it.nonce })
        assertTrue(h.replies.all { it.raw != null })
        assertEquals(listOf("state:1", "result:first:1", "result:second:1"), h.events)
        assertNull(h.coordinator.nextDeadline())
    }

    @Test fun writeBarrierDropsOldReadAndEndsSupersededWaiters() {
        val h = Harness()
        h.coordinator.refresh("seed", 1_000)
        h.complete(0)
        h.coordinator.refresh("old-a", 1_000)
        h.coordinator.refresh("old-b", 1_000)
        h.coordinator.write("write", 2_000, FreeClipControlKey.EQUALIZER, "9")
        assertFalse(h.operations[1].canSend(h.now))
        assertTrue(h.coordinator.snapshot().values.isEmpty())
        assertEquals(FreeClipControlKey.EQUALIZER, h.coordinator.snapshot().pendingKey)
        assertEquals(listOf("old-a", "old-b"), h.replies.takeLast(2).map { it.nonce })
        assertTrue(h.replies.takeLast(2).all { it.raw == null })
        val revision = h.coordinator.snapshot().revision
        h.complete(1)
        assertEquals(revision, h.coordinator.snapshot().revision)
        assertTrue(h.coordinator.snapshot().values.isEmpty())
        h.complete(2, mapOf(FreeClipControlKey.EQUALIZER to "9"))
        assertNull(h.coordinator.snapshot().pendingKey)
        assertEquals("9", h.coordinator.snapshot().values[FreeClipControlKey.EQUALIZER])
        assertEquals("write", h.replies.last().nonce)
        assertEquals(revision + 1, h.replies.last().state.revision)
    }

    @Test fun concurrentWritesAreRejectedNotMergedAndRefreshWaits() {
        val h = Harness()
        h.coordinator.write("one", 1_000, FreeClipControlKey.EQUALIZER, "9")
        h.coordinator.write("two", 1_000, FreeClipControlKey.DROP_REMINDER, "true")
        h.coordinator.refresh("refresh-a", 2_000)
        h.coordinator.refresh("refresh-b", 2_000)
        assertEquals(1, h.operations.size)
        assertEquals("two", h.replies.single().nonce)
        assertNull(h.replies.single().raw)
        h.complete(0, mapOf(FreeClipControlKey.EQUALIZER to "9"))
        assertEquals(2, h.operations.size)
        assertNull(h.operations[1].key)
        h.complete(1)
        assertEquals(listOf("two", "one", "refresh-a", "refresh-b"), h.replies.map { it.nonce })
    }

    @Test fun individualDeadlinesDoNotLetExpiredWaitersSucceed() {
        val h = Harness()
        h.coordinator.refresh("short", 200)
        h.coordinator.refresh("long", 400)
        h.now = 200
        // Completion itself checks deadlines, even if the Handler timeout has not run yet.
        h.complete(0)
        assertNull(h.replies[0].raw)
        assertNotNull(h.replies[1].raw)
        assertEquals(listOf("short", "long"), h.replies.map { it.nonce })
    }

    @Test fun timedOutReadCanRetryAndLateCompletionCannotConfirm() {
        val h = Harness()
        h.coordinator.refresh("expired", 200)
        h.now = 200
        h.coordinator.expire()
        assertFalse(h.operations[0].canSend(h.now))
        assertNull(h.replies.single().raw)
        h.coordinator.refresh("retry", 500)
        h.complete(0)
        assertEquals(1, h.replies.size)
        assertEquals(1, h.states.size)
        assertTrue(h.states.single().values.isEmpty())
        h.complete(1)
        assertNotNull(h.replies.last().raw)
        assertEquals("retry", h.replies.last().nonce)
    }

    @Test fun timedOutWriteClearsPendingAndQueuedRefreshRetriesWithoutOldConfirmation() {
        val h = Harness()
        h.coordinator.write("expired", 200, FreeClipControlKey.EQUALIZER, "9")
        h.coordinator.refresh("retry", 500)
        h.now = 200
        h.coordinator.expire()
        assertNull(h.replies.single().raw)
        assertNull(h.coordinator.snapshot().pendingKey)
        assertTrue(h.coordinator.snapshot().values.isEmpty())
        assertEquals(2, h.operations.size)
        h.complete(0, mapOf(FreeClipControlKey.EQUALIZER to "9"))
        assertTrue(h.coordinator.snapshot().values.isEmpty())
        h.complete(1)
        assertEquals("1", h.coordinator.snapshot().values[FreeClipControlKey.EQUALIZER])
    }

    @Test fun refreshWaitingBehindWriteExpiresIndependentlyAndFailureAllowsRetry() {
        val h = Harness()
        h.coordinator.write("write", 500, FreeClipControlKey.EQUALIZER, "9")
        h.coordinator.refresh("short", 200)
        h.coordinator.refresh("long", 800)
        h.now = 200
        h.coordinator.expire()
        assertEquals("short", h.replies.single().nonce)
        assertNull(h.replies.single().raw)
        assertEquals(1, h.operations.size)
        h.coordinator.complete(h.operations[0], null, emptyMap())
        assertNull(h.replies.last().raw)
        assertNull(h.coordinator.snapshot().pendingKey)
        assertEquals(2, h.operations.size)
        h.complete(1)
        assertEquals("long", h.replies.last().nonce)
        assertNotNull(h.replies.last().raw)
        val count = h.replies.size
        h.complete(1)
        assertEquals(count, h.replies.size)
    }

    @Test fun ackOrSendSuccessCannotConfirmAWrite() {
        val h = Harness()
        h.coordinator.write("write", 500, FreeClipControlKey.EQUALIZER, "9")
        h.complete(0, emptyMap())
        assertNull(h.replies.single().raw)
        assertTrue(h.coordinator.snapshot().values.isEmpty())
        assertNull(h.coordinator.snapshot().pendingKey)
    }

    @Test fun fullRefreshReplacesMissingFieldsAndFailedRefreshInvalidatesCache() {
        val h = Harness()
        h.coordinator.refresh("seed", 900)
        h.complete(0, mapOf(FreeClipControlKey.EQUALIZER to "9", FreeClipControlKey.DROP_REMINDER to "true"))
        h.coordinator.refresh("partial", 900)
        h.complete(1)
        assertEquals(mapOf(FreeClipControlKey.EQUALIZER to "1"), h.coordinator.snapshot().values)
        h.coordinator.refresh("failed", 900)
        h.coordinator.complete(h.operations[2], null, emptyMap())
        assertTrue(h.coordinator.snapshot().values.isEmpty())
        assertEquals(3L, h.coordinator.snapshot().revision)
        assertNull(h.replies.last().raw)
        assertEquals(listOf("state:3", "result:failed:3"), h.events.takeLast(2))
    }

    @Test fun fullRefreshTimeoutInvalidatesPreviouslyConfirmedSnapshot() {
        val h = Harness()
        h.coordinator.refresh("seed", 900)
        h.complete(0)
        h.coordinator.refresh("timeout", 200)
        h.now = 200
        h.coordinator.expire()
        assertTrue(h.coordinator.snapshot().values.isEmpty())
        assertEquals(2L, h.coordinator.snapshot().revision)
        assertNull(h.replies.last().raw)
        assertEquals(listOf("state:2", "result:timeout:2"), h.events.takeLast(2))
    }

    @Test fun writeFullReadbackPublishesAllSevenConfirmedKeysBeforeResult() {
        val h = Harness()
        val full = FreeClipControlKey.entries.associateWith { it.values.first() }
        h.coordinator.write("write", 900, FreeClipControlKey.EQUALIZER, "9")
        h.complete(0, full)
        assertEquals(full, h.coordinator.snapshot().values)
        assertEquals(7, h.replies.single().state.values.size)
        assertNotNull(h.replies.single().raw)
        assertEquals(listOf("state:2", "result:write:2"), h.events.takeLast(2))
    }

    @Test fun sessionResetCancelsOperationsAndVersionIsScopedToNewEpoch() {
        val old = Harness(100)
        old.coordinator.write("old", 500, FreeClipControlKey.EQUALIZER, "9")
        old.coordinator.reset()
        assertNull(old.coordinator.nextDeadline())
        assertFalse(old.operations.single().canSend(100))
        old.complete(0)
        old.coordinator.expire()
        assertTrue(old.replies.isEmpty())
        val fresh = Harness(600)
        fresh.now = 600
        assertEquals(600L, fresh.coordinator.snapshot().epoch)
        assertEquals(0L, fresh.coordinator.snapshot().revision)
        fresh.coordinator.refresh("fresh", 900)
        fresh.complete(0)
        assertEquals(1L, fresh.coordinator.snapshot().revision)
        // Even deliberately passing an old operation to the new coordinator cannot publish it.
        fresh.coordinator.complete(old.operations.single(), byteArrayOf(1), mapOf(FreeClipControlKey.EQUALIZER to "9"))
        assertEquals(1L, fresh.coordinator.snapshot().revision)
        assertEquals("1", fresh.coordinator.snapshot().values[FreeClipControlKey.EQUALIZER])
    }
}
