package moe.chenxy.huaweipods.hook

import java.util.WeakHashMap
import moe.chenxy.huaweipods.pods.HuaweiDeviceRoute
import moe.chenxy.huaweipods.pods.displayName
import moe.chenxy.huaweipods.pods.isSupported

/** A recycled target, a new device, and a closed module all invalidate old image callbacks. */
internal class DeviceSpaceImageRequests<K : Any> {
    data class Ticket(val identity: String, val revision: Long)
    private val targets = WeakHashMap<K, Ticket>()
    private var revision = 0L
    private var closed = false

    @Synchronized fun bind(target: K, identity: String): Ticket? {
        if (closed) return null
        return Ticket(identity, ++revision).also { targets[target] = it }
    }

    @Synchronized fun invalidate(target: K) { targets.remove(target) }
    @Synchronized fun isCurrent(target: K, ticket: Ticket): Boolean = !closed && targets[target] == ticket
    @Synchronized fun close() { closed = true; targets.clear() }
}

/** Drop a redundant brand only for a canonical model title; never replace the user's alias. */
internal fun deviceSpaceCardTitle(title: String, route: HuaweiDeviceRoute): String =
    if (route.isSupported && title.trim().equals(route.displayName, ignoreCase = true)) {
        title.trim().replaceFirst(Regex("^HUAWEI\\s+", RegexOption.IGNORE_CASE), "")
    } else title
