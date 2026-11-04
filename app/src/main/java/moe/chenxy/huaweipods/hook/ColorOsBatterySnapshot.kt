package moe.chenxy.huaweipods.hook

import android.content.Context
import android.provider.Settings

/** 蓝牙进程发布的多设备电量快照；新宿主订阅广播后读取一次，随后只消费实时事件。 */
internal object ColorOsBatterySnapshot {
    const val SETTINGS_KEY = "huaweipods_battery_snapshot"
    private const val VERSION = "v2\n"
    private const val SEPARATOR = '|'
    private const val MIN_FIELDS = 7

    /** 单条记录沿用旧格式，便于从旧版单设备快照迁移。 */
    fun encode(pod: ColorOsPodBattery): String = listOf(
        ColorOsPodBatteryStore.key(pod.address),
        pod.left.toString(),
        if (pod.leftConnected) "1" else "0",
        pod.right.toString(),
        if (pod.rightConnected) "1" else "0",
        pod.case.toString(),
        if (pod.caseConnected) "1" else "0",
        pod.name.orEmpty().replace(SEPARATOR, ' ').replace('\n', ' ').replace('\r', ' '),
    ).joinToString(SEPARATOR.toString())

    fun decode(raw: String?): ColorOsPodBattery? {
        val fields = raw?.trim()?.takeIf(String::isNotEmpty)?.split(SEPARATOR) ?: return null
        if (fields.size < MIN_FIELDS) return null
        val address = fields[0].trim().takeIf(String::isNotEmpty) ?: return null
        val left = fields[1].toIntOrNull() ?: return null
        val right = fields[3].toIntOrNull() ?: return null
        val case = fields[5].toIntOrNull() ?: return null
        return ColorOsPodBattery(
            address = ColorOsPodBatteryStore.key(address),
            left = left,
            leftConnected = fields[2] == "1",
            right = right,
            rightConnected = fields[4] == "1",
            case = case,
            caseConnected = fields[6] == "1",
            name = fields.getOrNull(7)?.trim()?.takeIf(String::isNotEmpty),
        )
    }

    internal fun encodeAll(pods: Collection<ColorOsPodBattery>): String = VERSION + pods
        .filter { it.address.isNotBlank() }
        .associateBy { ColorOsPodBatteryStore.key(it.address) }
        .toSortedMap().values.joinToString("\n", transform = ::encode)

    internal fun decodeAll(raw: String?): List<ColorOsPodBattery> {
        if (raw.isNullOrBlank()) return emptyList()
        if (!raw.startsWith(VERSION)) return listOfNotNull(decode(raw))
        return raw.removePrefix(VERSION).lineSequence().mapNotNull(::decode)
            .associateBy { ColorOsPodBatteryStore.key(it.address) }.values.toList()
    }

    /** 所有写入只在蓝牙进程执行，共用锁完成按地址的读改写。 */
    @Synchronized
    fun save(context: Context, pod: ColorOsPodBattery) {
        runCatching {
            saveInto(pod,
                read = { Settings.Global.getString(context.contentResolver, SETTINGS_KEY) },
                write = { Settings.Global.putString(context.contentResolver, SETTINGS_KEY, it) },
            )
        }
    }

    internal fun saveInto(pod: ColorOsPodBattery, read: () -> String?, write: (String?) -> Boolean): Boolean {
        if (pod.address.isBlank()) return false
        val entries = decodeAll(read()).associateBy { ColorOsPodBatteryStore.key(it.address) }.toMutableMap()
        entries[ColorOsPodBatteryStore.key(pod.address)] = pod
        return write(encodeAll(entries.values))
    }

    fun loadAll(context: Context): List<ColorOsPodBattery> = runCatching {
        decodeAll(Settings.Global.getString(context.contentResolver, SETTINGS_KEY))
    }.getOrDefault(emptyList())

    @Synchronized
    fun clear(context: Context, address: String): Boolean = runCatching {
        clearIfMatches(address,
            read = { Settings.Global.getString(context.contentResolver, SETTINGS_KEY) },
            write = { Settings.Global.putString(context.contentResolver, SETTINGS_KEY, it) },
        )
    }.getOrDefault(false)

    internal fun clearIfMatches(address: String, read: () -> String?, write: (String?) -> Boolean): Boolean {
        if (address.isBlank()) return false
        val entries = decodeAll(read()).associateBy { ColorOsPodBatteryStore.key(it.address) }.toMutableMap()
        if (entries.remove(ColorOsPodBatteryStore.key(address)) == null) return false
        return write(entries.values.takeIf { it.isNotEmpty() }?.let(::encodeAll))
    }
}
