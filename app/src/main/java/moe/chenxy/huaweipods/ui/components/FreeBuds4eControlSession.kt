package moe.chenxy.huaweipods.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import moe.chenxy.huaweipods.pods.HuaweiDeviceRoute

/** UI state and write-completion ownership for one address/route composition session. */
internal class FreeBuds4eControlSession {
    val selectedId = mutableStateOf<Int?>(null)
    val showDialog = mutableStateOf(false)
    val pending = mutableStateOf(false)

    private var active = true

    fun dispose() {
        active = false
    }

    /** False means the callback is stale, including any associated failure notification. */
    fun completeWrite(presetId: Int, success: Boolean): Boolean {
        if (!active) return false
        pending.value = false
        if (success) selectedId.value = presetId
        showDialog.value = false
        return true
    }
}

@Composable
internal fun rememberFreeBuds4eControlSession(
    address: String,
    route: HuaweiDeviceRoute,
): FreeBuds4eControlSession {
    val session = remember(address, route) { FreeBuds4eControlSession() }
    DisposableEffect(session) {
        onDispose { session.dispose() }
    }
    return session
}
