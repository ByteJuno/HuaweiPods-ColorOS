package moe.chenxy.huaweipods.hook

import android.view.View
import java.lang.reflect.Modifier

/** 从宿主 BluetoothDevicePreference 取得真实设备，绑定发生在宿主写摘要之前。 */
internal fun HookContext.hookColorOsBatteryRows(
    tag: String,
    rewrite: ColorOsBatterySummaryInjector.Rewrite,
) {
    val preference = findClass("com.android.settings.bluetooth.BluetoothDevicePreference")
    val bind = preference.declaredMethods.single {
        it.name == "onBindViewHolder" && it.parameterCount == 1 && it.returnType == Void.TYPE && !it.isBridge
    }.apply { isAccessible = true }
    val cachedDevice = preference.getMethod("getCachedDevice").apply { isAccessible = true }
    val viewField = generateSequence(bind.parameterTypes.single()) { it.superclass }
        .flatMap { it.declaredFields.asSequence() }
        .single { !Modifier.isStatic(it.modifiers) && it.type == View::class.java }
        .apply { isAccessible = true }

    hookBefore(bind) {
        runCatching {
            val holder = args.firstOrNull() ?: return@runCatching
            val root = viewField.get(holder) as? View ?: return@runCatching
            // 复用的行先清身份，未知宿主形状不能继续沿用上一副耳机。
            ColorOsBatterySummaryInjector.bindRow(root, null)
            val device = instance?.let { cachedDevice.invoke(it) } ?: return@runCatching
            val connected = device.javaClass.getMethod("isConnected").invoke(device) == true
            val address = if (connected) device.javaClass.getMethod("getAddress").invoke(device) as? String else null
            ColorOsBatterySummaryInjector.bindRow(root, address)
        }.onFailure { Log.d(tag, "Bluetooth preference identity unavailable: ${it.javaClass.simpleName}") }
    }
    hookAfter(bind) {
        runCatching {
            val holder = args.firstOrNull() ?: return@runCatching
            (viewField.get(holder) as? View)?.let { ColorOsBatterySummaryInjector.refreshRow(it, rewrite) }
        }.onFailure { Log.d(tag, "Bluetooth preference summary unavailable: ${it.javaClass.simpleName}") }
    }
}
