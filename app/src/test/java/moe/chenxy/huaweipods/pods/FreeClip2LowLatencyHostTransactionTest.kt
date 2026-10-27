package moe.chenxy.huaweipods.pods

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 测试实际宿主操作分发和生产事务，不依赖页面 UI 状态机。 */
class FreeClip2LowLatencyHostTransactionTest {
    @Test
    fun oppositeReadbacksDoNotSaveTheSuccessfullyWrittenEnableCommand() {
        val host = HostFixture(false)
        val request = host.start(true)
        host.writes[0].complete(true)
        assertEquals(0, host.commits)
        assertFalse(host.desired)
        assertNull(host.appliedGeneration)
        assertNull(host.policy.autoToken())
        host.reads[0](false)
        assertEquals(0, host.commits)
        host.reads[1](false)
        assertArrayEquals(byteArrayOf(0, 0), request.responses.single())
        assertFalse(host.desired)
        assertNull(host.appliedGeneration)
        assertEquals(0, host.commits)
        assertEquals(0, host.legacyCalls)
        assertFalse(host.desired) // 重连自动应用读取的仍然是原关闭策略。
    }

    @Test
    fun unknownReadbacksDoNotSavePolicy() {
        val host = HostFixture(false)
        val request = host.start(true)
        host.writes[0].complete(true)
        host.reads[0](null)
        host.reads[1](null)
        assertArrayEquals(byteArrayOf(0, 2), request.responses.single())
        assertFalse(host.desired)
        assertNull(host.appliedGeneration)
        assertEquals(0, host.commits)
        assertEquals(0, host.legacyCalls)
    }

    @Test
    fun retryConfirmationCommitsExactlyOnceAndOnlyAfterReadback() {
        val host = HostFixture(false)
        val request = host.start(true)
        host.writes[0].complete(true)
        host.writes[0].complete(true)
        assertEquals(1, host.reads.size)
        assertTrue(request.responses.isEmpty())
        assertEquals(0, host.commits)
        host.reads[0](false)
        assertEquals(0, host.commits)
        host.reads[1](true)
        host.reads[0](true)
        host.reads[1](true)
        assertArrayEquals(byteArrayOf(1, 1), request.responses.single())
        assertTrue(host.desired)
        assertEquals(1L, host.appliedGeneration)
        assertEquals(1, host.commits)
        assertEquals(0, host.legacyCalls)
    }

    @Test
    fun firstMatchingReadbackCommitsWithoutAnExtraQuery() {
        val host = HostFixture(false)
        val request = host.start(true)
        host.writes[0].complete(true)
        host.reads[0](true)
        assertEquals(1, host.reads.size)
        assertArrayEquals(byteArrayOf(1, 1), request.responses.single())
        assertEquals(1, host.commits)
        assertTrue(host.desired)
    }

    @Test
    fun failedDisableKeepsThePreviouslyEnabledReconnectPolicy() {
        val host = HostFixture(true)
        val request = host.start(false)
        host.writes[0].complete(true)
        host.reads[0](true)
        host.reads[1](true)
        assertArrayEquals(byteArrayOf(0, 1), request.responses.single())
        assertTrue(host.desired)
        assertEquals(0, host.commits)
    }

    @Test
    fun confirmedDisableSavesFalseRatherThanTreatingFalseAsMissing() {
        val host = HostFixture(true)
        val request = host.start(false)
        host.writes[0].complete(true)
        host.reads[0](false)
        assertArrayEquals(byteArrayOf(1, 0), request.responses.single())
        assertFalse(host.desired)
        assertNull(host.appliedGeneration)
        assertEquals(1, host.commits)
    }

    @Test
    fun failedWriteDoesNotQueryOrCommit() {
        val host = HostFixture(false)
        val request = host.start(true)
        host.writes[0].complete(false)
        assertTrue(host.reads.isEmpty())
        assertArrayEquals(byteArrayOf(0, 2), request.responses.single())
        assertFalse(host.desired)
        assertEquals(0, host.commits)
    }

    @Test
    fun expiredQueuedRequestCannotWriteOrCommit() {
        val host = HostFixture(false)
        val request = host.start(true)
        request.current = false
        assertFalse(host.writes[0].shouldWrite())
        host.writes[0].complete(true)
        assertTrue(host.reads.isEmpty())
        assertNull(request.responses.single())
        assertFalse(host.desired)
        assertEquals(0, host.commits)
    }

    @Test
    fun expiredRequestCannotCommitALateMatchingReadback() {
        val host = HostFixture(false)
        val request = host.start(true)
        host.writes[0].complete(true)
        host.reads[0](null)
        request.current = false
        host.reads[1](true)
        assertNull(request.responses.single())
        assertFalse(host.desired)
        assertNull(host.appliedGeneration)
        assertEquals(0, host.commits)
    }

    @Test
    fun disconnectedSessionCannotCommitALateMatchingReadback() {
        val host = HostFixture(false)
        val request = host.start(true)
        host.writes[0].complete(true)
        host.reads[0](false)
        host.generation++
        host.policy.resetSession()
        host.reads[1](true)
        assertNull(request.responses.single())
        assertFalse(host.desired)
        assertNull(host.appliedGeneration)
        assertEquals(0, host.commits)
    }

    @Test
    fun supersededConfirmationCannotCommitOrFinishTheNewManualRequest() {
        val host = HostFixture(false)
        val old = host.start(true)
        host.writes[0].complete(true)
        val latest = host.start(false)
        host.reads[0](true)
        assertArrayEquals(byteArrayOf(0, 2), old.responses.single())
        assertEquals(0, host.commits)
        assertNull(host.policy.autoToken())
        host.writes[1].complete(true)
        host.reads[1](false)
        assertArrayEquals(byteArrayOf(1, 0), latest.responses.single())
        assertFalse(host.desired)
        assertEquals(1, host.commits)
        assertTrue(host.policy.autoToken() != null)
        assertEquals(0, host.legacyCalls)
    }

    @Test
    fun finalCommitGuardCanRejectAnInvalidatedRequest() {
        val host = HostFixture(false)
        val request = host.start(true)
        host.writes[0].complete(true)
        host.rejectCommit = true
        host.reads[0](true)
        assertArrayEquals(byteArrayOf(0, 1), request.responses.single())
        assertFalse(host.desired)
        assertNull(host.appliedGeneration)
        assertEquals(0, host.commits)
    }

    @Test
    fun verifiedOperationRejectsOtherModelsAndMissingEnabledWithoutCallingLegacySetter() {
        val host = HostFixture(false)
        val wrongModel = host.start(true, route = HuaweiDeviceRoute.HUAWEI_FREEBUDS6I)
        val missingEnabled = host.start(null)
        assertNull(wrongModel.responses.single())
        assertNull(missingEnabled.responses.single())
        assertTrue(host.writes.isEmpty())
        assertEquals(0, host.commits)
        assertEquals(0, host.legacyCalls)
    }

    @Test
    fun legacyOperationRetainsItsExistingDispatch() {
        val host = HostFixture(false)
        val request = host.start(
            true,
            route = HuaweiDeviceRoute.HUAWEI_FREEBUDS6I,
            operation = HuaweiSharedControlPolicy.LOW_LATENCY_SET,
        )
        assertArrayEquals(byteArrayOf(1), request.responses.single())
        assertEquals(1, host.legacyCalls)
        assertTrue(host.writes.isEmpty())
    }

    private class Request {
        var current = true
        val responses = mutableListOf<ByteArray?>()
    }

    private data class PendingWrite(
        val shouldWrite: () -> Boolean,
        val complete: (Boolean) -> Unit,
    )

    /**
     * 与 HFP 宿主相同的边界：旧 setter 有写出即保存副作用；verified setter 使用生产事务，
     * 最终提交在 token、会话和请求检查之后才更新持久策略及 applied generation。
     */
    private class HostFixture(initialDesired: Boolean) {
        val policy = LowLatencyWritePolicy()
        val writes = mutableListOf<PendingWrite>()
        val reads = mutableListOf<(Boolean?) -> Unit>()
        var generation = 1L
        var desired = initialDesired
        var appliedGeneration: Long? = null
        var commits = 0
        var legacyCalls = 0
        var rejectCommit = false

        fun start(
            enabled: Boolean?,
            route: HuaweiDeviceRoute = HuaweiDeviceRoute.HUAWEI_FREECLIP2,
            operation: String = HuaweiSharedControlPolicy.LOW_LATENCY_SET_VERIFIED,
        ): Request {
            val request = Request()
            val requestedGeneration = generation
            val handled = HuaweiSharedControlHost.dispatchLowLatency(
                operation = operation,
                route = route,
                enabled = enabled,
                writeLowLatency = { requested, complete ->
                    legacyCalls++
                    desired = requested
                    complete(true)
                },
                writeLowLatencyVerified = { requested, requestCurrent, complete ->
                    val token = policy.beginManual()
                    val stillCurrent = { requestCurrent() && policy.isCurrent(token) }
                    FreeClip2LowLatencyTransaction.run(
                        enabled = requested,
                        stillCurrent = stillCurrent,
                        write = { shouldWrite, written -> writes.add(PendingWrite(shouldWrite, written)) },
                        read = { reads.add(it) },
                        commit = {
                            if (!stillCurrent() || rejectCommit) {
                                false
                            } else {
                                desired = requested
                                appliedGeneration = requestedGeneration.takeIf { requested }
                                commits++
                                true
                            }
                        },
                        complete = { result ->
                            policy.finishManual(token)
                            complete(result)
                        },
                    )
                },
                stillCurrent = { request.current && generation == requestedGeneration },
                reply = { request.responses.add(it) },
            )
            assertTrue(handled)
            return request
        }
    }
}
