package moe.chenxy.huaweipods.pods

import java.util.concurrent.atomic.AtomicBoolean

/** Main-thread state machine. Only Operation.canSend is called by the existing transport worker. */
internal class FreeClipControlCoordinator(
    private val epoch: Long,
    private val now: () -> Long,
    private val transport: (Operation) -> Unit,
    private val publish: (Snapshot) -> Unit,
    private val result: (String, ByteArray?, Snapshot) -> Unit,
) {
    data class Snapshot(
        val epoch: Long,
        val revision: Long,
        val values: Map<FreeClipControlKey, String>,
        val pendingKey: FreeClipControlKey?,
    )

    class Operation internal constructor(
        val key: FreeClipControlKey?,
        val value: String?,
        deadline: Long,
    ) {
        private val valid = AtomicBoolean(true)
        @Volatile internal var deadline: Long = deadline
        fun canSend(now: Long): Boolean = valid.get() && now < deadline
        internal fun cancel() { valid.set(false) }
    }

    private val reads = linkedMapOf<String, Long>()
    private var read: Operation? = null
    private var write: Operation? = null
    private var writer: String? = null
    private val values = linkedMapOf<FreeClipControlKey, String>()
    private var revision = 0L
    private var closed = false

    fun snapshot() = Snapshot(epoch, revision, values.toMap(), write?.key)
    fun nextDeadline(): Long? = (reads.values + listOfNotNull(write?.deadline)).minOrNull()

    fun refresh(nonce: String, deadline: Long) {
        expire()
        if (closed || nonce in reads || nonce == writer) return
        if (deadline <= now()) { result(nonce, null, snapshot()); return }
        reads[nonce] = deadline
        read?.deadline = reads.values.maxOrNull()!!
        startRead()
    }

    fun write(nonce: String, deadline: Long, key: FreeClipControlKey, value: String) {
        expire()
        if (closed || nonce in reads || nonce == writer) return
        if (deadline <= now() || write != null) {
            result(nonce, null, snapshot())
            return
        }
        // A write crosses every preceding full read. End its waiters, never strand them or use
        // their response as confirmation. The next refresh can retry after this write.
        read?.cancel()
        read = null
        val superseded = reads.keys.toList()
        reads.clear()
        values.clear()
        val operation = Operation(key, value, deadline)
        write = operation
        writer = nonce
        changed()
        superseded.forEach { result(it, null, snapshot()) }
        transport(operation)
    }

    /** raw is device readback, not an ACK or the transport's successful-send notification. */
    fun complete(operation: Operation, raw: ByteArray?, confirmed: Map<FreeClipControlKey, String>) {
        expire()
        if (closed || (operation !== read && operation !== write)) return
        operation.cancel()
        if (operation === write) {
            val nonce = requireNotNull(writer)
            val key = requireNotNull(operation.key)
            write = null
            writer = null
            // Writes also query the full state; only actual readback values enter the snapshot.
            val accepted = if (raw != null) confirmed else emptyMap()
            values.clear()
            values.putAll(accepted)
            changed()
            result(nonce, raw?.takeIf { key in accepted }, snapshot())
            startRead()
        } else {
            read = null
            val waiters = reads.keys.toList()
            reads.clear()
            values.clear()
            if (raw != null) values.putAll(confirmed)
            changed()
            waiters.forEach { result(it, raw?.takeIf { confirmed.isNotEmpty() }, snapshot()) }
        }
    }

    fun expire() {
        if (closed) return
        val time = now()
        val expired = reads.filterValues { it <= time }.keys.toList()
        expired.forEach { reads.remove(it) }
        if (reads.isEmpty()) {
            if (read != null) {
                read?.cancel()
                read = null
                values.clear()
                changed()
            }
        } else {
            read?.deadline = reads.values.maxOrNull()!!
        }
        val expiredWriter = if (write?.deadline?.let { it <= time } == true) writer else null
        if (expiredWriter != null) {
            write?.cancel()
            write = null
            writer = null
            changed()
        }
        expired.forEach { result(it, null, snapshot()) }
        expiredWriter?.let { result(it, null, snapshot()) }
        startRead()
    }

    /** Called on disconnect/new generation/close. No old nonce is published into the new session. */
    fun reset() {
        closed = true
        read?.cancel()
        write?.cancel()
        read = null
        write = null
        writer = null
        reads.clear()
        values.clear()
    }

    private fun startRead() {
        if (closed || read != null || write != null || reads.isEmpty()) return
        val operation = Operation(null, null, reads.values.maxOrNull()!!)
        read = operation
        transport(operation)
    }

    private fun changed() {
        revision++
        publish(snapshot())
    }
}
