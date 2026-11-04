package moe.chenxy.huaweipods.pods

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.UUID
import moe.chenxy.huaweipods.broadcast.HuaweiPodsBroadcastTrustPolicy
import moe.chenxy.huaweipods.hook.FreeClipControlSession
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.HuaweiPodsAction
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.sendIdentitySharingBroadcast

/** Shared wire client for native and module UI; only Bluetooth publishes confirmed state. */
internal class FreeClipControlClient(context: Context, val address: String, private val changed: () -> Unit) : AutoCloseable {
    val session = FreeClipControlSession(address)
    private val context = context.applicationContext ?: context
    private val handler = Handler(Looper.getMainLooper())
    var started = false
        private set
    private var completion: ((Boolean) -> Unit)? = null
    private val expiry = Runnable {
        if (session.expire(SystemClock.elapsedRealtime())) {
            finish(false)
            changed()
        }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            runCatching {
                if (!started || context.packageManager.getPackagesForUid(sentFromUid)?.any {
                        HuaweiPodsBroadcastTrustPolicy.shouldAcceptMyDevicesStateBroadcast(intent.action, it)
                    } != true
                ) return@runCatching
                intent.setExtrasClassLoader(FreeClipControlClient::class.java.classLoader)
                val replyAddress = intent.getStringExtra("address")
                if (!address.equals(replyAddress, ignoreCase = true)) return@runCatching
                val route = decodeHuaweiDeviceRouteFromBroadcast(intent.getStringExtra(HuaweiPodsAction.EXTRA_DEVICE_ROUTE))
                when (HuaweiPodsAction.canonical(intent.action)) {
                    HuaweiPodsAction.ACTION_FREECLIP_CONTROL_STATE -> {
                        val state = intent.getBundleExtra(HuaweiPodsAction.EXTRA_FREECLIP_CONTROL_STATE)
                            ?: return@runCatching
                        val values = FreeClipControlKey.entries.mapNotNull { key ->
                            state.getString(key.name)?.let { key to it }
                        }.toMap()
                        if (session.updateSnapshot(replyAddress, route,
                                intent.getLongExtra(HuaweiPodsAction.EXTRA_FREECLIP_CONTROL_EPOCH, -1),
                                intent.getLongExtra(HuaweiPodsAction.EXTRA_FREECLIP_CONTROL_REVISION, -1),
                                values, FreeClipControlProtocol.key(intent.getStringExtra(HuaweiPodsAction.EXTRA_FREECLIP_CONTROL_PENDING_KEY)))
                        ) changed()
                    }
                    HuaweiPodsAction.ACTION_FREECLIP_CONTROL_RESULT -> {
                        val current = session.isCurrentResult(
                            intent.getLongExtra(HuaweiPodsAction.EXTRA_FREECLIP_CONTROL_EPOCH, -1),
                            intent.getLongExtra(HuaweiPodsAction.EXTRA_FREECLIP_CONTROL_REVISION, -1),
                        )
                        // A stale transaction can finish its waiter, but cannot overwrite newer state.
                        val response = if (current) FreeClipControlProtocol.readback(
                            intent.getByteArrayExtra(HuaweiPodsAction.EXTRA_FREECLIP_CONTROL_RESPONSE) ?: byteArrayOf(),
                        ) else emptyMap()
                        if (session.complete(intent.getStringExtra(HuaweiPodsAction.EXTRA_FREECLIP_CONTROL_NONCE),
                                replyAddress, route, response, SystemClock.elapsedRealtime())) {
                            handler.removeCallbacks(expiry)
                            finish(current && !session.failed)
                            changed()
                        }
                    }
                    HuaweiPodsAction.ACTION_PODS_DISCONNECTED -> {
                        handler.removeCallbacksAndMessages(null)
                        session.reset(disconnected = true, invalidateEpoch = true)
                        finish(false)
                        changed()
                    }
                    HuaweiPodsAction.ACTION_PODS_CONNECTED -> {
                        if (route != FreeClipControlProtocol.ROUTE) return@runCatching
                        handler.removeCallbacksAndMessages(null)
                        session.reset()
                        finish(false)
                        request()
                    }
                }
            }.onFailure { android.util.Log.w("HuaweiPods-FreeClipClient", "Unable to consume state", it) }
        }
    }

    fun start() {
        if (started) return
        runCatching {
            context.registerReceiver(receiver, IntentFilter().apply {
                addAction(HuaweiPodsAction.ACTION_FREECLIP_CONTROL_RESULT)
                addAction(HuaweiPodsAction.ACTION_FREECLIP_CONTROL_STATE)
                addAction(HuaweiPodsAction.ACTION_PODS_CONNECTED)
                addAction(HuaweiPodsAction.ACTION_PODS_DISCONNECTED)
            }, Context.RECEIVER_EXPORTED)
        }.onSuccess {
            started = true
            request()
        }.onFailure {
            session.reset(disconnected = true)
            changed()
        }
    }

    fun request(key: FreeClipControlKey? = null, value: String? = null, onComplete: ((Boolean) -> Unit)? = null) {
        if (!started) { onComplete?.invoke(false); return }
        val pending = session.begin(UUID.randomUUID().toString(), SystemClock.elapsedRealtime(), key, value)
        if (pending == null) { onComplete?.invoke(false); return }
        finish(false)
        completion = onComplete
        changed()
        handler.removeCallbacks(expiry)
        handler.postDelayed(expiry, FreeClipControlProtocol.TIMEOUT_MS)
        runCatching {
            context.sendIdentitySharingBroadcast(Intent(HuaweiPodsAction.ACTION_FREECLIP_CONTROL_REQUEST).apply {
                setPackage(HuaweiPodsBroadcastTrustPolicy.PACKAGE_BLUETOOTH)
                putExtra("address", address)
                putExtra(HuaweiPodsAction.EXTRA_DEVICE_ROUTE, encodeHuaweiDeviceRouteForBroadcast(FreeClipControlProtocol.ROUTE))
                putExtra(HuaweiPodsAction.EXTRA_FREECLIP_CONTROL_NONCE, pending.nonce)
                putExtra(HuaweiPodsAction.EXTRA_FREECLIP_CONTROL_DEADLINE, pending.deadline)
                key?.let { putExtra(HuaweiPodsAction.EXTRA_FREECLIP_CONTROL_KEY, it.name) }
                value?.let { putExtra(HuaweiPodsAction.EXTRA_FREECLIP_CONTROL_VALUE, it) }
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            })
        }.onFailure {
            session.complete(pending.nonce, address, FreeClipControlProtocol.ROUTE, emptyMap(), SystemClock.elapsedRealtime())
            handler.removeCallbacks(expiry)
            finish(false)
            changed()
        }
    }

    private fun finish(success: Boolean) {
        val callback = completion
        completion = null
        callback?.invoke(success)
    }

    override fun close() {
        if (started) runCatching { context.unregisterReceiver(receiver) }
        started = false
        handler.removeCallbacksAndMessages(null)
        session.reset(disconnected = true)
        finish(false)
    }
}
