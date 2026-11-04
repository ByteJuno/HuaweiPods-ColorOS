package moe.chenxy.huaweipods.hook

import android.app.Activity
import android.app.Application
import android.widget.TextView

/** Settings 详情页按 Intent 地址、已连接设备列表按 Preference 的 CachedBluetoothDevice 匹配。 */
internal object ColorOsDeviceDetailBatteryHook : HookContext() {
    private const val TAG = "HuaweiPods-ColorOsDeviceDetail"
    private val rewrite = ColorOsBatterySummaryInjector.Rewrite { text, triple ->
        ColorOsSettingsBatteryText.rewriteSingleValue(text, triple, identityConfirmed = true)
            ?: ColorOsSettingsBatteryText.rewrite(text, triple.left, triple.right, triple.case, identityConfirmed = true)
    }

    override fun onHook() {
        runCatching { hookColorOsBatteryRows(TAG, rewrite) }
            .onFailure { Log.w(TAG, "device list identity hook unavailable", it) }
        runCatching {
            hookAfter(Activity::class.java.getDeclaredMethod("onResume")) {
                (instance as? Activity)?.let { ColorOsBatterySummaryInjector.attach(it, TAG, rewrite) }
            }
            hookAfter(Application::class.java.getDeclaredMethod("onCreate")) {
                (instance as? Application)?.let { ColorOsBatterySummaryInjector.attach(it, TAG, rewrite) }
            }
            currentApplicationOrNull()?.let { ColorOsBatterySummaryInjector.attach(it, TAG, rewrite) }
        }.onFailure { Log.w(TAG, "device detail battery source unavailable", it) }
        runCatching {
            hookBefore(TextView::class.java.getDeclaredMethod("setText", CharSequence::class.java)) {
                val view = instance as? TextView ?: return@hookBefore
                ColorOsBatterySummaryInjector.replacement(view, args[0] as? CharSequence, rewrite)
                    ?.let { proceedWithArgs(it) }
            }
        }.onFailure { Log.w(TAG, "device detail summary hook unavailable", it) }
    }

    override fun onClose() {
        if (!runOnMainThreadBlocking { ColorOsBatterySummaryInjector.close() }) {
            Log.w(TAG, "device detail summaries could not be restored on main thread")
        }
    }
}
