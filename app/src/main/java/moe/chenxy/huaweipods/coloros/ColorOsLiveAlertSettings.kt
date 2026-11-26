package moe.chenxy.huaweipods.coloros

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import moe.chenxy.huaweipods.R
import moe.chenxy.huaweipods.config.AppConfig
import moe.chenxy.huaweipods.hook.colorOsNoiseControlOptions
import moe.chenxy.huaweipods.pods.HuaweiDeviceRoute
import moe.chenxy.huaweipods.pods.NoiseControlMode
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.HuaweiPodsAction
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.sendIdentitySharingBroadcast

internal fun colorOsLiveAlertVisibility(lockscreenEnabled: Boolean): Int =
    if (lockscreenEnabled) Notification.VISIBILITY_PUBLIC else Notification.VISIBILITY_SECRET

/**
 * 流体云卡片「降噪」按钮的下一档：关 → 降噪 → 通透 → 关，按机型能力裁剪。
 *
 * 尚未收到耳机上报时先给降噪：用户点这一下的意图几乎总是「开始降噪」，从关闭开始循环
 * 会先切到「关」，等于什么都没做。
 */
internal fun colorOsLiveAlertNextNoiseMode(
    route: HuaweiDeviceRoute?,
    current: NoiseControlMode?,
): NoiseControlMode? {
    if (route == null) return null
    val options = colorOsNoiseControlOptions(route)
    if (options.isEmpty()) return null
    if (current == null || current == NoiseControlMode.UNKNOWN) {
        return NoiseControlMode.NOISE_CANCELLATION
    }
    val index = options.indexOf(current)
    if (index < 0) return options.first()
    return options[(index + 1) % options.size]
}

/**
 * 确定降噪状态广播在流体云持久化中的目标设备地址。
 *
 * 首次连接时 ANC 回读可能早于第一包电量到达，此时卡片尚未持久化（`cardAddress` 为空），
 * 允许落到已建立的 `currentAddress`；若已有卡片地址且与事件地址冲突，则拒绝覆盖。
 */
internal fun resolveLiveAlertNoiseTargetAddress(
    eventAddress: String?,
    cardAddress: String?,
    currentAddress: String?,
): String? {
    val normalizedEvent = eventAddress?.trim()?.uppercase()?.takeIf(String::isNotEmpty)
    val normalizedCard = cardAddress?.trim()?.uppercase()?.takeIf(String::isNotEmpty)
    val normalizedCurrent = currentAddress?.trim()?.uppercase()?.takeIf(String::isNotEmpty)
    if (normalizedEvent != null) {
        if (normalizedCard != null && normalizedCard != normalizedEvent) return null
        if (normalizedCard == null && normalizedCurrent != null && normalizedCurrent != normalizedEvent) return null
        return normalizedEvent
    }
    return normalizedCard ?: normalizedCurrent
}

internal fun colorOsLiveAlertNoiseActionLabel(context: Context, next: NoiseControlMode): String =
    context.getString(R.string.coloros_live_alert_noise_action, colorOsLiveAlertNoiseLabel(context, next))

internal fun colorOsLiveAlertNoiseLabel(context: Context, mode: NoiseControlMode): String = when (mode) {
    NoiseControlMode.OFF -> context.getString(R.string.coloros_live_alert_noise_off)
    NoiseControlMode.NOISE_CANCELLATION -> context.getString(R.string.coloros_live_alert_noise_cancellation)
    NoiseControlMode.TRANSPARENCY -> context.getString(R.string.coloros_live_alert_noise_transparency)
    NoiseControlMode.UNKNOWN -> ""
}

/** Apply the settings immediately, including when no new battery report is arriving. */
@SuppressLint("MissingPermission")
internal fun applyColorOsLiveAlertSettings(context: Context, config: AppConfig) {
    val manager = context.getSystemService(NotificationManager::class.java)
    if (!config.persistentNotificationEnabled) {
        context.stopService(Intent(context, ColorOsLiveAlertService::class.java))
        manager.cancel(ColorOsLiveAlertService.NOTIFICATION_ID)
        return
    }

    manager.activeNotifications.firstOrNull {
        it.id == ColorOsLiveAlertService.NOTIFICATION_ID && it.tag == null
    }?.let { active ->
        // Retain the channel and its user settings, promotion extras, actions and current battery.
        val notification = active.notification.clone().apply {
            visibility = colorOsLiveAlertVisibility(config.lockscreenNotificationEnabled)
        }
        manager.notify(ColorOsLiveAlertService.NOTIFICATION_ID, notification)
    }
    // The Bluetooth module republishes its current state and requests a real battery reading.
    context.sendIdentitySharingBroadcast(Intent(HuaweiPodsAction.ACTION_REFRESH_STATUS).apply {
        setPackage("com.android.bluetooth")
        addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
    })
}
