package moe.chenxy.huaweipods.ui.components

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import moe.chenxy.huaweipods.R
import moe.chenxy.huaweipods.pods.HuaweiDeviceRoute
import moe.chenxy.huaweipods.pods.FreeClipControlKey
import moe.chenxy.huaweipods.pods.HuaweiFreeClipController
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

private data class FreeClipPreset(val id: Int, val labelRes: Int)

private val freeClipPresets = listOf(
    FreeClipPreset(0x01, R.string.freebuds5_sound_effect_default),
    FreeClipPreset(0x0A, R.string.freebuds5_sound_effect_bass),
    FreeClipPreset(0x03, R.string.freebuds5_sound_effect_treble),
    FreeClipPreset(0x09, R.string.freebuds5_sound_effect_clear_voice),
)

/** FreeClip 1 controls backed by the guided Huawei Audio capture. */
@Composable
fun FreeClipControls(address: String) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val controls = rememberFreeClipControls(address)
    val state = controls.state
    var wearDetection by remember(address) { mutableStateOf<Boolean?>(null) }

    DisposableEffect(address, context) {
        val device = context.freeClipBluetoothDevice(address)
            ?: return@DisposableEffect onDispose { }
        var disposed = false
        HuaweiFreeClipController.requestWearDetectionState(context, device) { value ->
            if (!disposed && value != null) wearDetection = value
        }
        onDispose { disposed = true }
    }

    val selectedPreset = freeClipPresets.firstOrNull {
        it.id.toString() == state.values[FreeClipControlKey.EQUALIZER]
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        FreeClipControlStatus(controls)
        FreeClipSectionTitle(R.string.freebuds5_smart_features)
        FreeClipFeatureToggle(
            titleRes = R.string.freebuds5_wear_detection,
            value = wearDetection,
            onChange = { enabled, complete ->
                val device = context.freeClipBluetoothDevice(address)
                if (device == null) {
                    complete(false)
                } else {
                    HuaweiFreeClipController.setWearDetection(context, device, enabled) { success ->
                        if (success) wearDetection = enabled
                        complete(success)
                    }
                }
            },
        )
        FreeClipFeatureToggle(
            titleRes = R.string.freeclip_adaptive_channels,
            summaryRes = R.string.freeclip_adaptive_channels_summary,
            value = state.values[FreeClipControlKey.ADAPTIVE_CHANNELS]?.toBooleanStrictOrNull(),
            enabled = state.canWrite,
            onChange = { enabled, complete ->
                controls.write(FreeClipControlKey.ADAPTIVE_CHANNELS, enabled.toString(), complete)
            },
        )
        FreeClipFeatureToggle(
            titleRes = R.string.freeclip2_drop_reminder,
            summaryRes = R.string.freeclip_drop_reminder_summary,
            value = state.values[FreeClipControlKey.DROP_REMINDER]?.toBooleanStrictOrNull(),
            enabled = state.canWrite,
            onChange = { enabled, complete ->
                controls.write(FreeClipControlKey.DROP_REMINDER, enabled.toString(), complete)
            },
        )

        FreeClipSectionTitle(R.string.freebuds5_sound_and_connection)
        FreeClipChoicePreference(
            controls = controls,
            title = stringResource(R.string.freebuds5_sound_effect),
            selected = selectedPreset,
            values = freeClipPresets,
            label = { stringResource(it.labelRes) },
            onSelected = { preset, complete ->
                controls.write(FreeClipControlKey.EQUALIZER, preset.id.toString(), complete)
            },
        )
        Card(modifier = Modifier.fillMaxWidth()) {
            LowLatencyControl(
                address = address,
                route = HuaweiDeviceRoute.HUAWEI_FREECLIP,
            )
        }
    }
}

@Composable
private fun FreeClipSectionTitle(titleRes: Int) {
    Text(
        text = stringResource(titleRes),
        color = MiuixTheme.colorScheme.primary,
        style = MiuixTheme.textStyles.headline1,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun FreeClipFeatureToggle(
    titleRes: Int,
    value: Boolean?,
    summaryRes: Int? = null,
    enabled: Boolean = true,
    onChange: (Boolean, (Boolean) -> Unit) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var pending by remember { mutableStateOf(false) }
    val canToggle = enabled && !pending && value != null
    val toggle = {
        if (canToggle) {
            pending = true
            val target = value == false
            onChange(target) { success ->
                pending = false
                if (!success) {
                    Toast.makeText(context, R.string.connect_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = canToggle, role = Role.Switch, onClick = toggle)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(titleRes),
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.headline1,
            )
            if (value == null) {
                Text(
                    text = stringResource(R.string.freeclip_control_unconfirmed),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
                )
            }
            if (summaryRes != null) {
                Text(
                    text = stringResource(summaryRes),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Checkbox(
            state = when (value) {
                true -> ToggleableState.On
                false -> ToggleableState.Off
                null -> ToggleableState.Indeterminate
            },
            enabled = canToggle,
            onClick = toggle,
        )
    }
}

@SuppressLint("MissingPermission")
private fun Context.freeClipBluetoothDevice(address: String) =
    takeIf { BluetoothAdapter.checkBluetoothAddress(address) }
        ?.getSystemService(BluetoothManager::class.java)
        ?.adapter
        ?.getRemoteDevice(address)
