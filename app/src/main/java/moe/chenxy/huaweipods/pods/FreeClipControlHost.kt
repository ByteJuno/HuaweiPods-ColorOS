package moe.chenxy.huaweipods.pods

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import moe.chenxy.huaweipods.broadcast.HuaweiPodsBroadcastTrustPolicy
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.HuaweiPodsAction
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.sendIdentitySharingBroadcast

/** Bluetooth-only adapter; all state transitions and broadcasts run on HFP's main handler. */
internal object FreeClipControlHost {
    @Volatile private var session: Session? = null

    fun reset() {
        val old = session ?: return
        old.handler.onThread {
            old.close()
            if (session === old) session = null
        }
    }

    fun handle(
        context: Context,
        device: BluetoothDevice,
        route: HuaweiDeviceRoute,
        intent: Intent,
        handler: Handler,
        generation: Long,
        epoch: Long,
        stillCurrent: () -> Boolean,
    ) {
        handler.onThread {
            if (route != FreeClipControlProtocol.ROUTE || !stillCurrent()) return@onThread
            val nonce = intent.getStringExtra(HuaweiPodsAction.EXTRA_FREECLIP_CONTROL_NONCE)
                ?.takeIf { it.length in 16..80 } ?: return@onThread
            if (!device.address.equals(intent.getStringExtra("address"), ignoreCase = true) ||
                intent.getStringExtra(HuaweiPodsAction.EXTRA_DEVICE_ROUTE) != encodeHuaweiDeviceRouteForBroadcast(route)
            ) return@onThread
            var current = session
            if (current == null || current.generation != generation || current.epoch != epoch ||
                !current.device.address.equals(device.address, ignoreCase = true)
            ) {
                current?.close()
                current = Session(context, device, route, handler, generation, epoch, stillCurrent)
                session = current
                current.publish(current.coordinator.snapshot())
            }
            val keyName = intent.getStringExtra(HuaweiPodsAction.EXTRA_FREECLIP_CONTROL_KEY)
            val key = FreeClipControlProtocol.key(keyName)
            val value = intent.getStringExtra(HuaweiPodsAction.EXTRA_FREECLIP_CONTROL_VALUE)
            val deadline = intent.getLongExtra(HuaweiPodsAction.EXTRA_FREECLIP_CONTROL_DEADLINE, -1L)
            val now = SystemClock.elapsedRealtime()
            val refresh = keyName == null && value == null
            if (deadline <= now || deadline - now > FreeClipControlProtocol.TIMEOUT_MS ||
                (!refresh && (key == null || value == null || FreeClipControlProtocol.write(key, value) == null))
            ) {
                current.reply(nonce, null, current.coordinator.snapshot())
            } else if (refresh) {
                current.coordinator.refresh(nonce, deadline)
            } else {
                current.coordinator.write(nonce, deadline, requireNotNull(key), requireNotNull(value))
            }
            current.scheduleTimeout()
        }
    }

    private class Session(
        val context: Context,
        val device: BluetoothDevice,
        val route: HuaweiDeviceRoute,
        val handler: Handler,
        val generation: Long,
        val epoch: Long,
        val stillCurrent: () -> Boolean,
    ) {
        private var closed = false
        val coordinator = FreeClipControlCoordinator(
            epoch, SystemClock::elapsedRealtime, ::transport, ::publish, ::reply,
        )
        private val timeout = Runnable {
            if (!stillCurrent()) close() else {
                coordinator.expire()
                scheduleTimeout()
            }
        }

        fun close() {
            closed = true
            handler.removeCallbacks(timeout)
            coordinator.reset()
        }

        fun scheduleTimeout() {
            handler.removeCallbacks(timeout)
            if (closed) return
            coordinator.nextDeadline()?.let {
                handler.postDelayed(timeout, (it - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            }
        }

        private fun transport(operation: FreeClipControlCoordinator.Operation) {
            HuaweiL2capAncController.requestRawPacketOnce(
                context, device, route, FreeClipControlProtocol.query(),
                description = "freeclip-system-read key=${operation.key} write=${operation.value != null}",
                responseWindowMs = 1_500L,
                responseComplete = { bytes ->
                    val state = FreeClipControlProtocol.readback(bytes)
                    state.size == FreeClipControlKey.entries.size
                },
                onComplete = { if (!it) complete(operation, null) },
                shouldWrite = { stillCurrent() && operation.canSend(SystemClock.elapsedRealtime()) },
                discardBufferedInput = true,
                writeBeforeQuery = operation.key?.let { FreeClipControlProtocol.write(it, requireNotNull(operation.value)) },
                onResponse = { complete(operation, it) },
            )
        }

        private fun complete(operation: FreeClipControlCoordinator.Operation, raw: ByteArray?) {
            handler.onThread {
                if (closed || !stillCurrent()) return@onThread
                coordinator.complete(operation, raw, raw?.let(FreeClipControlProtocol::readback).orEmpty())
                scheduleTimeout()
            }
        }

        fun publish(snapshot: FreeClipControlCoordinator.Snapshot) {
            send(HuaweiPodsAction.ACTION_FREECLIP_CONTROL_STATE, snapshot) {
                putExtra(HuaweiPodsAction.EXTRA_FREECLIP_CONTROL_STATE, Bundle().apply {
                    snapshot.values.forEach { (key, value) -> putString(key.name, value) }
                })
                snapshot.pendingKey?.let { putExtra(HuaweiPodsAction.EXTRA_FREECLIP_CONTROL_PENDING_KEY, it.name) }
            }
        }

        fun reply(nonce: String, raw: ByteArray?, snapshot: FreeClipControlCoordinator.Snapshot) {
            send(HuaweiPodsAction.ACTION_FREECLIP_CONTROL_RESULT, snapshot) {
                putExtra(HuaweiPodsAction.EXTRA_FREECLIP_CONTROL_NONCE, nonce)
                raw?.let { putExtra(HuaweiPodsAction.EXTRA_FREECLIP_CONTROL_RESPONSE, it) }
            }
        }

        private fun send(action: String, snapshot: FreeClipControlCoordinator.Snapshot, extras: Intent.() -> Unit) {
            if (closed || !stillCurrent()) return
            // Never honor a caller-selected reply package: both clients match the nonce themselves.
            listOf(HuaweiPodsBroadcastTrustPolicy.PACKAGE_APP, HuaweiPodsBroadcastTrustPolicy.PACKAGE_HEYTAP_MYDEVICES)
                .forEach { target ->
                    context.sendIdentitySharingBroadcast(Intent(action).apply {
                        setPackage(target)
                        putExtra("address", device.address)
                        putExtra(HuaweiPodsAction.EXTRA_DEVICE_ROUTE, encodeHuaweiDeviceRouteForBroadcast(route))
                        putExtra(HuaweiPodsAction.EXTRA_FREECLIP_CONTROL_EPOCH, snapshot.epoch)
                        putExtra(HuaweiPodsAction.EXTRA_FREECLIP_CONTROL_REVISION, snapshot.revision)
                        extras()
                        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    })
                }
        }
    }

    private fun Handler.onThread(block: () -> Unit) {
        if (Looper.myLooper() == looper) block() else post { block() }
    }
}
