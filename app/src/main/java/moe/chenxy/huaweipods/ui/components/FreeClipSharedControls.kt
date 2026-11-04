package moe.chenxy.huaweipods.ui.components

import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import moe.chenxy.huaweipods.R
import moe.chenxy.huaweipods.pods.FreeClipControlClient
import moe.chenxy.huaweipods.pods.FreeClipControlKey
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Immutable UI projection: observing the client itself would not invalidate skipped children. */
internal data class FreeClipUiState(
    val values: Map<FreeClipControlKey, String> = emptyMap(),
    val reading: Boolean = false,
    val writing: Boolean = false,
    val remotePending: FreeClipControlKey? = null,
    val connected: Boolean = false,
    val failed: Boolean = false,
    val started: Boolean = false,
) {
    val busy: Boolean get() = reading || writing || remotePending != null
    val canWrite: Boolean get() = started && connected && !busy
}

internal class FreeClipUiControls(context: Context, address: String) {
    var state by mutableStateOf(FreeClipUiState())
        private set
    val client: FreeClipControlClient = FreeClipControlClient(context, address) { publish() }

    private fun publish() {
        val session = client.session
        state = FreeClipUiState(
            values = session.values,
            reading = session.pending?.let { it.key == null } == true,
            writing = session.pending?.key != null,
            remotePending = session.remotePending,
            connected = session.connected,
            failed = session.failed,
            started = client.started,
        )
    }

    fun refresh() {
        if (client.started) client.request() else client.start()
    }

    fun write(key: FreeClipControlKey, value: String, complete: (Boolean) -> Unit) {
        // Also check at dispatch time: another surface may have started a write since composition.
        if (!state.canWrite) complete(false) else client.request(key, value, complete)
    }
}

@Composable
internal fun rememberFreeClipControls(address: String): FreeClipUiControls {
    val context = LocalContext.current
    val controls = remember(context, address) { FreeClipUiControls(context, address) }
    DisposableEffect(controls, context) {
        val lifecycle = context.freeClipLifecycleOwner()?.lifecycle
        var refreshOnResume = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> refreshOnResume = true
                Lifecycle.Event.ON_RESUME -> if (refreshOnResume) {
                    refreshOnResume = false
                    controls.refresh()
                }
                else -> Unit
            }
        }
        // start() reads once; ignore the initial ON_RESUME dispatched by addObserver.
        controls.client.start()
        lifecycle?.addObserver(observer)
        onDispose {
            lifecycle?.removeObserver(observer)
            controls.client.close()
        }
    }
    return controls
}

private tailrec fun Context.freeClipLifecycleOwner(): LifecycleOwner? = when (this) {
    is LifecycleOwner -> this
    is ContextWrapper -> baseContext.freeClipLifecycleOwner()
    else -> null
}

@Composable
internal fun FreeClipControlStatus(controls: FreeClipUiControls) {
    val state = controls.state
    val status = when {
        state.writing -> stringResource(R.string.freeclip_control_writing)
        state.remotePending != null -> stringResource(R.string.freeclip_control_remote_writing)
        state.reading -> stringResource(R.string.freeclip2_settings_loading)
        state.failed || !state.connected -> stringResource(R.string.connect_failed)
        state.values.size < FreeClipControlKey.entries.size -> stringResource(R.string.freeclip_control_unconfirmed)
        else -> stringResource(R.string.freeclip_control_confirmed)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Text(status, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.body2)
        Text(
            text = stringResource(if (state.failed || !state.connected) R.string.retry else R.string.freeclip2_refresh_settings),
            color = MiuixTheme.colorScheme.primary,
            modifier = Modifier
                .clickable(enabled = !state.reading && !state.writing, role = Role.Button) { controls.refresh() }
                .padding(vertical = 12.dp),
        )
    }
}

/** Like the existing choice preference, but with a surface-wide/remote write gate. */
@Composable
internal fun <T> FreeClipChoicePreference(
    controls: FreeClipUiControls,
    title: String,
    selected: T?,
    values: List<T>,
    label: @Composable (T) -> String,
    onSelected: (T, (Boolean) -> Unit) -> Unit,
) {
    val context = LocalContext.current
    var showDialog by remember(controls, title) { mutableStateOf(false) }
    var pending by remember(controls, title) { mutableStateOf(false) }
    val state = controls.state
    val enabled = state.canWrite && !pending
    val summary = selected?.let { label(it) } ?: stringResource(R.string.freeclip_control_unconfirmed)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, role = Role.Button) { showDialog = true }
            .padding(horizontal = 24.dp, vertical = 14.dp),
    ) {
        Text(title, color = MiuixTheme.colorScheme.onSurface, style = MiuixTheme.textStyles.headline1)
        Text(summary, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.body2)
    }
    OverlayDialog(
        title = title,
        summary = summary,
        show = showDialog,
        onDismissRequest = { if (!pending) showDialog = false },
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            values.forEach { value ->
                val isSelected = value == selected
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = enabled, role = Role.RadioButton) {
                            if (isSelected) {
                                showDialog = false
                            } else {
                                pending = true
                                onSelected(value) { success ->
                                    // A snapshot (including remotePending clearing) never finishes this waiter.
                                    pending = false
                                    showDialog = false
                                    if (!success) Toast.makeText(context, R.string.connect_failed, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }.padding(horizontal = 12.dp, vertical = 12.dp),
                ) {
                    Text(label(value), modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.headline1)
                    Checkbox(state = ToggleableState(isSelected), enabled = enabled, onClick = null)
                }
            }
        }
    }
}
