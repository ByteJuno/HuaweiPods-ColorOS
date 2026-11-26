package moe.chenxy.huaweipods.broadcast

import android.app.BroadcastOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import moe.chenxy.huaweipods.BuildConfig
import moe.chenxy.huaweipods.platform.RomFamily
import moe.chenxy.huaweipods.platform.RomIntegrationPolicy
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.HuaweiPodsAction
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.sendIdentitySharingBroadcast

internal fun isHeadsetLifecycleAction(action: String?): Boolean = action in setOf(
    HuaweiPodsAction.ACTION_PODS_CONNECTED,
    HuaweiPodsAction.ACTION_PODS_DISCONNECTED,
    HuaweiPodsAction.ACTION_PODS_CONNECTION_STATE_CHANGED,
    HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED,
)

/**
 * 状态刷新事件：已显示的流体云卡片需要按最新降噪 / 低时延状态刷新按钮与副标题，
 * 但它们不改变连接生命周期，不能通过绑定服务冷启动应用进程。
 */
internal fun isLiveAlertStateAction(action: String?): Boolean = action in setOf(
    HuaweiPodsAction.ACTION_PODS_ANC_CHANGED,
    HuaweiPodsAction.ACTION_HUAWEI_LOW_LATENCY_CHANGED,
)

/** Use the Bluetooth host's own bounded FGS allowance, never a permanent battery exemption. */
internal fun Context.sendHeadsetAppBroadcast(intent: Intent) {
    val isLifecycle = isHeadsetLifecycleAction(intent.action)
    val isLiveAlertState = isLiveAlertStateAction(intent.action)
    if (!isLifecycle && !isLiveAlertState) {
        sendIdentitySharingBroadcast(intent)
        return
    }
    if (isLifecycle && RomIntegrationPolicy.detect(Build.MANUFACTURER, Build.BRAND) == RomFamily.COLOR_OS) {
        HeadsetLifecycleBinding.deliver(this, intent)
    }
    val options = runCatching {
        Class.forName("com.android.bluetooth.Utils", false, classLoader)
            .getMethod("getTempBroadcastOptions")
            .invoke(null) as BroadcastOptions
    }.getOrElse { BroadcastOptions.makeBasic() }
        .setShareIdentityEnabled(true)
        .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_NONE)
    val lifecycle = Intent(intent).apply {
        component = ComponentName(
            BuildConfig.APPLICATION_ID,
            "moe.chenxy.huaweipods.coloros.ColorOsLiveAlertReceiver",
        )
    }
    try {
        sendBroadcast(lifecycle, null, options.toBundle())
    } catch (_: SecurityException) {
        // Older hosts may lack permission for a temporary allowance. Keep identity validation.
        sendBroadcast(lifecycle, null, BroadcastOptions.makeBasic()
            .setShareIdentityEnabled(true)
            .setDeferralPolicy(BroadcastOptions.DEFERRAL_POLICY_NONE).toBundle())
    }
    // UI receivers still receive the state; the manifest receiver must not run twice for
    // actions declared in ColorOsLiveAlertReceiver's manifest intent-filter.
    val secondary = Intent(intent).apply {
        if (isLifecycle) addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY)
    }
    sendIdentitySharingBroadcast(secondary)
}
