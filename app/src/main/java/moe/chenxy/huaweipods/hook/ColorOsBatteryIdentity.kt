package moe.chenxy.huaweipods.hook

private val BATTERY_MAC = Regex("(?:[0-9a-f]{2}:){5}[0-9a-f]{2}", RegexOption.IGNORE_CASE)
private val BATTERY_DEVICE_ID = Regex("[0-9a-f]{32}", RegexOption.IGNORE_CASE)

/** 明确地址 / 卡片 id 优先；只有没有身份字段时才允许唯一名称匹配。 */
internal fun colorOsPodForIdentity(
    pods: List<ColorOsPodBattery>,
    address: String? = null,
    identities: List<String?> = emptyList(),
    names: List<String?> = emptyList(),
): ColorOsPodBattery? {
    if (address != null) {
        return pods.singleOrNull { ColorOsPodBatteryStore.key(it.address) == ColorOsPodBatteryStore.key(address) }
    }
    val ids = identities.filterNotNull().map(String::trim)
        .filter { BATTERY_MAC.matches(it) || BATTERY_DEVICE_ID.matches(it) }.distinct()
    if (ids.isNotEmpty()) {
        return pods.singleOrNull { pod ->
            ids.all { id ->
                id.equals(pod.address, ignoreCase = true) || ColorOsHomeCardBattery.matchesDeviceId(id, pod.address)
            }
        }
    }
    return pods.singleOrNull { pod -> names.any { ColorOsHomeCardBattery.matches(it, pod.name) } }
}
