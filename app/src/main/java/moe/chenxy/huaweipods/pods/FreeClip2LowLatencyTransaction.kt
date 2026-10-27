package moe.chenxy.huaweipods.pods

import java.util.concurrent.atomic.AtomicBoolean

internal data class FreeClip2LowLatencyResult(val state: Boolean?, val success: Boolean)

/**
 * FreeClip 2 手动低时延事务。写出成功不是策略提交条件；只有当前请求的匹配回读才允许提交。
 * I/O 和最终提交由宿主注入，所有步骤共用同一个请求有效性检查。
 */
internal object FreeClip2LowLatencyTransaction {
    fun run(
        enabled: Boolean,
        stillCurrent: () -> Boolean,
        write: (() -> Boolean, (Boolean) -> Unit) -> Unit,
        read: ((Boolean?) -> Unit) -> Unit,
        commit: () -> Boolean,
        complete: (FreeClip2LowLatencyResult) -> Unit,
    ) {
        val finished = AtomicBoolean(false)
        fun finish(state: Boolean?, matches: Boolean) {
            if (!finished.compareAndSet(false, true)) return
            if (!stillCurrent()) {
                complete(FreeClip2LowLatencyResult(null, false))
                return
            }
            // commit 必须在宿主会话锁内再次检查有效性，再更新策略和 applied generation。
            val committed = matches && commit()
            complete(FreeClip2LowLatencyResult(state, committed))
        }
        fun readState(attempt: Int) {
            if (!stillCurrent()) {
                finish(null, false)
                return
            }
            val delivered = AtomicBoolean(false)
            read(response@{ state ->
                if (!delivered.compareAndSet(false, true) || finished.get()) return@response
                if (!stillCurrent()) {
                    finish(null, false)
                } else if (state == enabled) {
                    finish(state, true)
                } else if (attempt < 2) {
                    readState(attempt + 1)
                } else {
                    finish(state, false)
                }
            })
        }
        if (!stillCurrent()) {
            finish(null, false)
            return
        }
        val written = AtomicBoolean(false)
        write(stillCurrent, response@{ success ->
            if (!written.compareAndSet(false, true) || finished.get()) return@response
            if (!success || !stillCurrent()) finish(null, false) else readState(1)
        })
    }
}
