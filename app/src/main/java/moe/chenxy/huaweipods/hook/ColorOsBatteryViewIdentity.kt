package moe.chenxy.huaweipods.hook

import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle

/** 详情页使用系统传入的设备地址；不根据页面上碰巧出现的设备名猜测。 */
internal object ColorOsBatteryViewIdentity {
    fun address(context: Context): String? = runCatching {
        val activity = activity(context) ?: return null
        val intent = activity.intent ?: return null
        val addresses = mutableListOf<String>()
        val keys = listOf("device_address", "device_mac_info", "bluetoothaddress")
        keys.mapNotNullTo(addresses) { intent.getStringExtra(it)?.takeIf(String::isNotBlank) }
        val args: Bundle? = intent.getBundleExtra(":settings:show_fragment_args")
        keys.mapNotNullTo(addresses) { args?.getString(it)?.takeIf(String::isNotBlank) }
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            ?.address?.let(addresses::add)
        addresses.map(ColorOsPodBatteryStore::key).distinct().singleOrNull()
    }.getOrNull()

    private fun activity(context: Context): Activity? {
        var current = context
        repeat(12) {
            if (current is Activity) return current
            val next = (current as? ContextWrapper)?.baseContext ?: return null
            if (next === current) return null
            current = next
        }
        return null
    }
}
