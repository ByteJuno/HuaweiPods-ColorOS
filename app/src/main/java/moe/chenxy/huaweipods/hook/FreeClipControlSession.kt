package moe.chenxy.huaweipods.hook

import moe.chenxy.huaweipods.pods.FreeClipControlKey
import moe.chenxy.huaweipods.pods.FreeClipControlProtocol
import moe.chenxy.huaweipods.pods.HuaweiDeviceRoute

/** One detail-page attachment, one in-flight transaction; no persisted/optimistic confirmations. */
internal class FreeClipControlSession(val address: String) {
    data class Request(
        val nonce: String,
        val key: FreeClipControlKey?,
        val value: String?,
        val deadline: Long,
    )

    private val confirmed = mutableMapOf<FreeClipControlKey, String>()
    val values: Map<FreeClipControlKey, String> get() = confirmed.toMap()
    var pending: Request? = null
        private set
    var connected = true
        private set
    var failed = false
        private set

    fun begin(nonce: String, now: Long, key: FreeClipControlKey? = null, value: String? = null): Request? {
        // A user write can supersede a slow full refresh; its old nonce can no longer confirm state.
        if ((remotePending != null && key != null) || pending?.key != null || (pending != null && key == null) || (!connected && key != null)) return null
        if ((key == null) != (value == null) || (key != null && value !in key.values)) return null
        failed = false
        if (key == null) confirmed.clear()
        // The previous value is no longer current evidence once a write may have reached the device.
        else confirmed.remove(key)
        return Request(nonce, key, value, now + FreeClipControlProtocol.TIMEOUT_MS).also { pending = it }
    }

    fun complete(
        nonce: String?,
        replyAddress: String?,
        route: HuaweiDeviceRoute?,
        readback: Map<FreeClipControlKey, String>,
        now: Long,
    ): Boolean {
        val request = pending ?: return false
        if (request.nonce != nonce || !address.equals(replyAddress, ignoreCase = true) ||
            route != FreeClipControlProtocol.ROUTE || now >= request.deadline
        ) return false
        val valid = readback.filter { (key, value) -> value in key.values }
        if (request.key == null) confirmed.putAll(valid)
        else valid[request.key]?.let { confirmed[request.key] = it }
        failed = if (request.key == null) valid.size != FreeClipControlKey.entries.size
        else valid[request.key] != request.value
        if (valid.isNotEmpty()) connected = true
        pending = null
        return true
    }

    fun expire(now: Long): Boolean {
        val request = pending ?: return false
        if (now < request.deadline) return false
        pending = null
        failed = true
        return true
    }

    var remotePending: FreeClipControlKey? = null
        private set
    private var epoch = -1L
    private var revision = -1L
    private var minimumEpoch = 0L

    fun updateSnapshot(
        replyAddress: String?, route: HuaweiDeviceRoute?, nextEpoch: Long, nextRevision: Long,
        values: Map<FreeClipControlKey, String>, writing: FreeClipControlKey?,
    ): Boolean {
        if (!address.equals(replyAddress, ignoreCase = true) || route != FreeClipControlProtocol.ROUTE ||
            nextEpoch < minimumEpoch || nextRevision < 0 || nextEpoch < epoch ||
            (nextEpoch == epoch && nextRevision <= revision)
        ) return false
        epoch = nextEpoch
        revision = nextRevision
        remotePending = writing
        confirmed.clear()
        confirmed.putAll(values.filter { (key, value) -> value in key.values && key != writing })
        if (confirmed.isNotEmpty()) connected = true
        return true
    }

    fun isCurrentResult(nextEpoch: Long, nextRevision: Long): Boolean =
        nextEpoch >= minimumEpoch && nextRevision >= 0 && nextEpoch >= epoch &&
            (nextEpoch != epoch || nextRevision >= revision)

    fun reset(disconnected: Boolean = false, invalidateEpoch: Boolean = false) {
        if (invalidateEpoch) minimumEpoch = maxOf(minimumEpoch, epoch + 1)
        pending = null
        confirmed.clear()
        remotePending = null
        connected = !disconnected
        failed = false
    }
}
