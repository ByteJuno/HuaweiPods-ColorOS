package moe.chenxy.huaweipods

import android.bluetooth.BluetoothAdapter
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import java.util.UUID
import moe.chenxy.huaweipods.broadcast.HuaweiPodsBroadcastTrustPolicy
import moe.chenxy.huaweipods.config.ConfigManager
import moe.chenxy.huaweipods.pods.HuaweiDeviceRoute
import moe.chenxy.huaweipods.pods.isSupported

/**
 * Only the real Bluetooth UID may register a launch. Neither an Intent-supplied package name nor
 * possession of BLUETOOTH_CONNECT is authentication. No URI read/write interface is exposed.
 */
class BottomPopupSessionProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        val packages = ctx.packageManager.getPackagesForUid(Binder.getCallingUid())
        if (packages?.any(HuaweiPodsBroadcastTrustPolicy::isTrustedBluetoothStateSender) != true) {
            throw SecurityException("Only the Bluetooth UID may register a bottom popup session")
        }
        if (method != BottomPopupSessions.REGISTER) return null
        if (!BottomPopupSessions.isEnabled(ctx)) return null
        val data = extras ?: return null
        val id = data.getString(BottomPopupSessions.EXTRA_SESSION_ID)
            ?.takeIf { it.isNotBlank() && it.length <= 128 } ?: return null
        val address = data.getString("address")
            ?.takeIf(BluetoothAdapter::checkBluetoothAddress) ?: return null
        val route = data.getString("route")?.let {
            runCatching { HuaweiDeviceRoute.valueOf(it) }.getOrNull()
        }?.takeIf { it.isSupported } ?: return null
        val validator = data.getBinder("validator") ?: return null
        val target = PopupDeviceTarget(address, data.getString("device_name").orEmpty(), route)
        // Do not call back into the host here: it may hold its session lock during registration.
        val token = BottomPopupSessions.register(id, target, validator)
        return Bundle().apply { putString(BottomPopupSessions.EXTRA_LAUNCH_TOKEN, token) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}

internal class BottomPopupSession(
    val id: String,
    val target: PopupDeviceTarget,
    val validator: IBinder,
    val createdAt: Long,
    var launchToken: String?,
)

/** Wire constants are shared with the hooked Bluetooth process; registry state lives only in App. */
internal object BottomPopupSessions {
    const val EXTRA_SESSION_ID = "bottom_popup_session_id"
    const val EXTRA_LAUNCH_TOKEN = "bottom_popup_launch_token"
    const val REGISTER = "register"
    private const val DESCRIPTOR = "io.github.artifical0.huaweipods.coloros.BottomPopupSession.v1"
    private const val VALIDATE = IBinder.FIRST_CALL_TRANSACTION
    private const val LAUNCH_TTL_MS = 10_000L
    private val lock = Any()
    private var current: BottomPopupSession? = null

    fun isEnabled(context: Context): Boolean = ConfigManager.refreshFromPrefs(
        context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE),
    ).bottomPopupEnabled

    fun register(id: String, target: PopupDeviceTarget, validator: IBinder): String = synchronized(lock) {
        val token = UUID.randomUUID().toString()
        current = BottomPopupSession(id, target, validator, SystemClock.elapsedRealtime(), token)
        token
    }

    /** First launch and onNewIntent both consume a short-lived, single-use App-issued credential. */
    fun consume(context: Context, intent: Intent): BottomPopupSession? = runCatching {
        if (!isEnabled(context)) return null
        val token = intent.getStringExtra(EXTRA_LAUNCH_TOKEN) ?: return null
        val session = synchronized(lock) {
            val candidate = current ?: return null
            val age = SystemClock.elapsedRealtime() - candidate.createdAt
            if (candidate.launchToken != token || age !in 0..LAUNCH_TTL_MS) return null
            candidate.launchToken = null
            candidate
        }
        session.takeIf { isActive(it) }
    }.getOrNull()

    fun isCurrent(session: BottomPopupSession): Boolean = synchronized(lock) { current === session }

    /** Generation and connection ownership are checked in the Bluetooth host, not inferred from MAC. */
    fun isActive(session: BottomPopupSession): Boolean {
        if (!isCurrent(session)) return false
        val active = runCatching {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                if (!session.validator.transact(VALIDATE, data, reply, 0)) return@runCatching false
                reply.readException()
                reply.readInt() == 1
            } finally {
                data.recycle()
                reply.recycle()
            }
        }.getOrDefault(false)
        return active && isCurrent(session)
    }

    fun requestLaunchToken(
        context: Context,
        sessionId: String,
        address: String,
        deviceName: String,
        route: HuaweiDeviceRoute,
        validator: IBinder,
    ): String? = context.contentResolver.call(
        Uri.parse("content://${BuildConfig.APPLICATION_ID}.bottompopup"),
        REGISTER,
        null,
        Bundle().apply {
            putString(EXTRA_SESSION_ID, sessionId)
            putString("address", address)
            putString("device_name", deviceName)
            putString("route", route.name)
            putBinder("validator", validator)
        },
    )?.getString(EXTRA_LAUNCH_TOKEN)

    /** The capability never appears in broadcasts, and only the module UID may query it. */
    fun createHostValidator(context: Context, isActive: () -> Boolean): IBinder = object : Binder() {
        init { attachInterface(null, DESCRIPTOR) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != VALIDATE) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(DESCRIPTOR)
            data.enforceNoDataAvail()
            val packages = context.packageManager.getPackagesForUid(getCallingUid())
            if (packages?.contains(BuildConfig.APPLICATION_ID) != true) {
                throw SecurityException("Only the module UID may validate a bottom popup session")
            }
            val active = isActive()
            reply?.writeNoException()
            reply?.writeInt(if (active) 1 else 0)
            return true
        }
    }
}
