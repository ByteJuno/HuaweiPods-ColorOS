package moe.chenxy.huaweipods.config

import java.util.Locale
import moe.chenxy.huaweipods.pods.HuaweiDeviceRoute
import moe.chenxy.huaweipods.pods.isSupported
import moe.chenxy.huaweipods.pods.HuaweiDeviceInfoRoutePolicy

/** Read-only, address-specific image requests. No paths or resource IDs supplied by a host. */
internal object PodPresentationImagePolicy {
    private val addressPattern = Regex("(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")

    data class Request(val route: HuaweiDeviceRoute, val address: String)

    fun candidatePaths(request: Request, record: EarphonePref?): List<String> {
        if (record == null || !record.address.equals(request.address, ignoreCase = true)) return emptyList()
        val cloud = record.cloudBoxImagePath?.takeIf {
            record.cloudModelId?.let { model -> HuaweiDeviceInfoRoutePolicy.isCompatible(request.route, model) } == true
        }
        return listOfNotNull(record.boxImagePath, cloud)
    }

    fun parse(segments: List<String>): Request? {
        if (segments.size != 3 || segments[0] != "device") return null
        val route = HuaweiDeviceRoute.entries.firstOrNull { it.name == segments[1] && it.isSupported }
            ?: return null
        if (!addressPattern.matches(segments[2])) return null
        return Request(route, segments[2].uppercase(Locale.ROOT))
    }
}
