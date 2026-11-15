package moe.chenxy.huaweipods

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import moe.chenxy.huaweipods.broadcast.HuaweiPodsBroadcastTrustPolicy
import moe.chenxy.huaweipods.config.ConfigManager
import moe.chenxy.huaweipods.pods.hasChargingCase
import moe.chenxy.huaweipods.ui.AppTheme
import moe.chenxy.huaweipods.ui.components.PodStatus
import moe.chenxy.huaweipods.utils.PodImageLoader
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.BatteryParams
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.HuaweiPodsAction
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.addHuaweiPodsAction
import moe.chenxy.huaweipods.utils.miuiStrongToast.data.sendIdentitySharingBroadcast
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

class BottomPopupActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        moe.chenxy.huaweipods.ui.AppLocale.rememberDeviceLocale(newBase)
        moe.chenxy.huaweipods.ui.AppLocale.apply(
            newBase,
            newBase.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
                .getInt("app_language", moe.chenxy.huaweipods.ui.AppLocale.SYSTEM),
        )
        super.attachBaseContext(newBase)
    }

    private var popupSession by mutableStateOf<BottomPopupSession?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
        // Only a fresh launch consumes a credential. Android-supplied saved state may reattach
        // an already authorized session, but cannot authorize a pending or replaced session.
        val initial = if (savedInstanceState == null) {
            BottomPopupSessions.consume(this, intent)
        } else {
            BottomPopupSessions.restore(this, savedInstanceState.getString(STATE_SESSION_ID))
        }
        if (initial == null) {
            finish()
            return
        }
        popupSession = initial

        setContent {
            val colorSchemeMode = when (prefs.getInt("theme_mode", 0)) {
                1 -> ColorSchemeMode.Light
                2 -> ColorSchemeMode.Dark
                else -> ColorSchemeMode.System
            }
            AppTheme(colorSchemeMode = colorSchemeMode, accentMode = prefs.getInt("accent_mode", 0)) {
                val session = popupSession
                if (session != null) {
                    // 同地址重连也属于新会话，不能继承旧电量或关闭计时。
                    key(session.id) {
                        BottomPopupContent(
                            session = session,
                            onFinish = { finishSession(session) },
                        )
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        popupSession?.let { outState.putString(STATE_SESSION_ID, it.id) }
        super.onSaveInstanceState(outState)
    }

    internal fun finishSession(session: BottomPopupSession) {
        // onNewIntent can run before Compose removes the old key and cancels its exit delay.
        // Compare session objects, not device addresses (a reconnect is a new session too).
        if (popupSession === session) finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Invalid/replayed launches must not replace a legitimate visible card.
        val newSession = BottomPopupSessions.consume(this, intent) ?: return
        popupSession = newSession
        setIntent(intent)
    }
}

private const val STATE_SESSION_ID = "bottom_popup_authorized_session_id"
private const val AUTO_DISMISS_MS = 6000L
private const val ANIM_MS = 280

@SuppressLint("UnspecifiedRegisterReceiverFlag")
@androidx.compose.runtime.Composable
private fun BottomPopupContent(
    session: BottomPopupSession,
    onFinish: () -> Unit,
) {
    val target = session.target
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
    }
    val isDark = isSystemInDarkTheme()
    val cardColor = if (isDark) Color(0xFF1C1C1E) else Color(0xFFFFFFFF)

    var visible by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var battery by remember { mutableStateOf(BatteryParams()) }

    fun close() {
        if (finished) return
        finished = true
        visible = false
    }

    val receiver = remember(session) {
        object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                intent ?: return
                // Authenticate before reading identity, session extras or Parcelable battery data.
                if (!HuaweiPodsBroadcastTrustPolicy.isTrustedBluetoothStateSender(sentFromPackage)) return
                if (finished || !BottomPopupSessions.isCurrent(session)) return
                if (intent.getStringExtra(BottomPopupSessions.EXTRA_SESSION_ID) != session.id) return
                val identity = PopupBroadcastIdentity(
                    address = intent.getStringExtra("address"),
                    deviceName = intent.getStringExtra("device_name"),
                    route = moe.chenxy.huaweipods.pods.decodeHuaweiDeviceRouteFromBroadcast(
                        intent.getStringExtra(HuaweiPodsAction.EXTRA_DEVICE_ROUTE),
                    ),
                )
                if (!popupBroadcastMatchesTarget(target, identity)) return
                when (HuaweiPodsAction.canonical(intent.action)) {
                    HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED -> {
                        if (!BottomPopupSessions.isEnabled(context) || !BottomPopupSessions.isActive(session)) {
                            close()
                            return
                        }
                        intent.getParcelableExtra("status", BatteryParams::class.java)?.let {
                            battery = it
                        }
                    }
                    // The authenticated terminal event closes exactly this session. Its host may
                    // already have invalidated the validator before broadcast delivery completes.
                    HuaweiPodsAction.ACTION_PODS_DISCONNECTED -> close()
                }
            }
        }
    }

    DisposableEffect(session) {
        context.registerReceiver(
            receiver,
            IntentFilter().apply {
                addHuaweiPodsAction(HuaweiPodsAction.ACTION_PODS_BATTERY_CHANGED)
                addHuaweiPodsAction(HuaweiPodsAction.ACTION_PODS_DISCONNECTED)
            },
            Context.RECEIVER_EXPORTED,
        )
        context.sendIdentitySharingBroadcast(Intent(HuaweiPodsAction.ACTION_REFRESH_STATUS).apply {
            putPopupTarget(target)
            setPackage(HuaweiPodsBroadcastTrustPolicy.PACKAGE_BLUETOOTH)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        })
        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    // Recheck after receiver registration and while visible, including host death/hot reload.
    LaunchedEffect(session) {
        if (!BottomPopupSessions.isEnabled(context) || !BottomPopupSessions.isActive(session)) {
            close()
            return@LaunchedEffect
        }
        visible = true
        while (!finished) {
            kotlinx.coroutines.delay(500L)
            if (!BottomPopupSessions.isEnabled(context) || !BottomPopupSessions.isActive(session)) close()
        }
    }
    LaunchedEffect(session.id) {
        battery = BatteryParams()
        kotlinx.coroutines.delay(AUTO_DISMISS_MS)
        close()
    }
    LaunchedEffect(finished) {
        if (finished) {
            kotlinx.coroutines.delay(ANIM_MS.toLong() + 30L)
            onFinish()
        }
    }

    val boxBitmap = remember(target.address, target.route) {
        runCatching {
            PodImageLoader.loadBoxBitmap(
                context = context,
                prefs = prefs,
                address = target.address,
                verifiedRoute = target.route,
            )
        }.getOrNull()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                indication = null,
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            ) { close() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(
                animationSpec = tween(ANIM_MS),
                initialOffsetY = { it },
            ) + fadeIn(tween(ANIM_MS)),
            exit = slideOutVertically(
                animationSpec = tween(ANIM_MS),
                targetOffsetY = { it },
            ) + fadeOut(tween(ANIM_MS)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 24.dp)
                    .background(cardColor, RoundedCornerShape(28.dp))
                    .clickable(
                        // 拦截卡片内部点击，避免点卡片也关闭。
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        onClick = {},
                    )
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = target.deviceName.ifEmpty { stringResource(R.string.app_name) },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.bottom_popup_connected),
                    fontSize = 13.sp,
                    color = Color.Gray,
                )
                Spacer(Modifier.height(8.dp))
                if (boxBitmap != null) {
                    Image(
                        bitmap = boxBitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(150.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                PodStatus(
                    batteryParams = battery,
                    showCase = target.route.hasChargingCase,
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TextButton(
                        text = stringResource(R.string.done),
                        onClick = { close() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
