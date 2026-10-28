package moe.chenxy.huaweipods.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import moe.chenxy.huaweipods.config.DeviceRoutePrefs
import moe.chenxy.huaweipods.config.PodImagePrefs
import moe.chenxy.huaweipods.pods.HuaweiDeviceRoute
import moe.chenxy.huaweipods.pods.isSupported
import moe.chenxy.huaweipods.utils.PodImageLoader

/** Verified against My Devices 17.25.10. Unknown host shapes retain their native rendering. */
object ColorOsDeviceSpaceHook : HookContext() {
    private const val TAG = "HuaweiPods-DeviceSpace"
    private const val CARD = "com.oplus.mydevices.bluetooth.BlueToothCardView"
    private const val CONVERTER = "com.oplus.mydevices.quickapp.data.QuickCardDeviceDataConverter"
    private const val DATA = "com.oplus.mydevices.domain.entities.cards.QuickCardDeviceData"
    private const val DEVICE = "com.oplus.mydevices.domain.ports.device.IDevice"
    private const val DETAIL_ACTIVITY = "com.oplus.mydevices.bluetooth.PluginPanelHostActivity"
    private val main = Handler(Looper.getMainLooper())
    private val requests = DeviceSpaceImageRequests<ImageView>()
    private val images = WeakHashMap<ImageView, OriginalImage>()
    private val cards = WeakHashMap<View, WeakReference<ImageView>>()
    private val scheduled = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    private val cache = object : LruCache<String, Bitmap>(3 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    private val worker = ThreadPoolExecutor(0, 1, 10L, TimeUnit.SECONDS, ArrayBlockingQueue(8), { task ->
        Thread(task, "HuaweiPods-DeviceSpaceImage").apply {
            isDaemon = true
            contextClassLoader = ClassLoader.getSystemClassLoader()
        }
    })
    @Volatile private var stopped = false

    override fun onHook() {
        runCatching { hookHomeImages() }
            .onSuccess { Log.i(TAG, "Device Space home image hook installed") }
            .onFailure { Log.w(TAG, "Unknown home card shape; retaining stock images", it) }
        runCatching { hookDetailImages() }
            .onSuccess { Log.i(TAG, "Device Space detail image hook installed") }
            .onFailure { Log.w(TAG, "Unknown detail card shape; retaining stock images", it) }
    }

    private fun hookHomeImages() {
        val dataType = findClass(DATA)
        listOf("icon", "name", "originalIconUri").forEach {
            check(dataType.getDeclaredField(it).type == String::class.java)
        }
        val deviceType = findClass(DEVICE)
        val addressGetter = deviceType.getMethod("getMacAddress").apply {
            check(returnType == String::class.java && parameterCount == 0)
        }
        val method = findMethod(CONVERTER, "h", deviceType, findClass("$CONVERTER\$c"),
            findClass("$CONVERTER\$e"), findClass("$CONVERTER\$d"), Boolean::class.javaPrimitiveType!!)
        check(method.returnType == dataType)
        hookAfter(method) {
            runCatching {
                // h returns a freshly allocated presentation DTO. Never change the IDevice or database.
                val address = addressGetter.invoke(args[0]) as? String ?: return@runCatching
                val app = currentApplicationOrNull() ?: return@runCatching
                val route = routeFor(app, address) ?: return@runCatching
                val data = result ?: return@runCatching
                val title = getObjectField(data, "name") as? String ?: return@runCatching
                val uri = imageUri(address, route)
                setObjectField(data, "icon", uri)
                setObjectField(data, "originalIconUri", uri)
                setObjectField(data, "name", deviceSpaceCardTitle(title, route))
            }.onFailure { Log.w(TAG, "Unable to customize Device Space card", it) }
        }
    }

    private fun hookDetailImages() {
        val type = findClass(CARD)
        check(View::class.java.isAssignableFrom(type))
        check(type.getDeclaredField("c").type == String::class.java)
        check(type.getDeclaredField("g").type == ImageView::class.java)
        // k initializes/replaces the native image; the setters also run during recycler rebinding.
        val methods = listOf(findMethod(CARD, "k"),
            findMethod(CARD, "setBluetoothAddress", String::class.java),
            findMethod(CARD, "setDeviceName", String::class.java))
        check(methods.all { it.returnType == Void.TYPE })
        methods.forEach { method -> hookAfter(method) { (instance as? View)?.let(::schedule) } }
        // Hot reload does not replay native view binding. An existing panel must be
        // rediscovered after old images are restored, and again when a paused panel resumes.
        hookAfter(Activity::class.java.getDeclaredMethod("onPostResume")) {
            (instance as? Activity)?.takeIf { it.javaClass.name == DETAIL_ACTIVITY }
                ?.let { activity -> main.post { refreshActivity(activity) } }
        }
        main.post { refreshVisibleDetails() }
    }

    private fun refreshVisibleDetails() {
        if (stopped) return
        runCatching {
            // The COUI detail sheet owns a Dialog window outside Activity.decorView.
            // Inspect only this host process's windows, and match the verified native card class.
            val windowType = Class.forName("android.view.WindowManagerGlobal")
            val manager = windowType.getDeclaredMethod("getInstance").invoke(null)
            val roots = (getObjectField(manager, "mViews") as? List<*>)
                ?.filterIsInstance<View>()?.take(16) ?: return@runCatching
            val count = roots.sumOf(::refreshRoot)
            Log.i(TAG, "Existing detail image recovery roots=${roots.size} cards=$count")
        }.onFailure { Log.w(TAG, "Unable to recover visible Device Space panels", it) }
    }

    private fun refreshActivity(activity: Activity) {
        if (stopped || activity.javaClass.name != DETAIL_ACTIVITY || activity.isDestroyed || activity.isFinishing) return
        refreshVisibleDetails()
    }

    private fun refreshRoot(root: View): Int {
        if (!root.isAttachedToWindow || root.windowVisibility != View.VISIBLE) return 0
        val pending = ArrayDeque<View>()
        pending.add(root)
        var visited = 0
        var matches = 0
        while (pending.isNotEmpty() && visited++ < 512) {
            val view = pending.removeFirst()
            if (view.javaClass.name == CARD) {
                schedule(view)
                matches++
            }
            if (view is ViewGroup) {
                repeat(minOf(view.childCount, (512 - visited - pending.size).coerceAtLeast(0))) { index ->
                    pending.add(view.getChildAt(index))
                }
            }
        }
        return matches
    }

    private fun schedule(card: View) {
        if (stopped) return
        cards[card]?.get()?.let(requests::invalidate)
        if (!scheduled.add(card)) return
        val reference = WeakReference(card)
        main.post {
            val current = reference.get() ?: return@post
            scheduled.remove(current)
            if (!stopped) runCatching { updateDetailImage(current) }
                .onFailure { Log.w(TAG, "Unable to update detail image", it) }
        }
    }

    private fun updateDetailImage(card: View) {
        val address = getObjectField(card, "c") as? String
        val route = address?.let { routeFor(card.context, it) }
        val image = getObjectField(card, "g") as? ImageView
        val previous = cards[card]?.get()
        if (previous != null && (previous !== image || route == null)) restore(previous)
        if (address == null || route == null || image == null) {
            cards.remove(card)
            return
        }
        cards[card] = WeakReference(image)
        val identity = imageUri(address, route)
        val ticket = requests.bind(image, identity) ?: return
        fun apply(bitmap: Bitmap) {
            val state = images.getOrPut(image) { OriginalImage(image.drawable, image.scaleType) }
            image.scaleType = ImageView.ScaleType.FIT_CENTER
            image.setImageBitmap(bitmap)
            state.applied = image.drawable
        }
        cache.get(identity)?.takeUnless(Bitmap::isRecycled)?.let { apply(it); return }
        val reference = WeakReference(image)
        val app = card.context.applicationContext ?: card.context
        runCatching {
            worker.execute {
                val bitmap = runCatching { PodImageLoader.loadDeviceSpaceBitmap(app, address, route) }.getOrNull()
                if (stopped) return@execute
                if (bitmap == null) {
                    Log.w(TAG, "Detail image decode unavailable; retaining native image")
                    return@execute
                }
                cache.put(identity, bitmap)
                main.post {
                    val target = reference.get() ?: return@post
                    if (requests.isCurrent(target, ticket) && !bitmap.isRecycled) {
                        val state = images.getOrPut(target) { OriginalImage(target.drawable, target.scaleType) }
                        target.scaleType = ImageView.ScaleType.FIT_CENTER
                        target.setImageBitmap(bitmap)
                        state.applied = target.drawable
                    }
                }
            }
        }.onFailure { Log.w(TAG, "Image queue unavailable; retaining native image") }
    }

    @SuppressLint("MissingPermission")
    private fun routeFor(context: Context, address: String): HuaweiDeviceRoute? = runCatching {
        if (!android.bluetooth.BluetoothAdapter.checkBluetoothAddress(address)) return@runCatching null
        val rawName = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?.getRemoteDevice(address)?.name
        DeviceRoutePrefs.resolve(prefs, address, rawName).takeIf { it.isSupported }
    }.getOrNull()

    private fun imageUri(address: String, route: HuaweiDeviceRoute): String {
        val record = PodImagePrefs.find(prefs, address)
        val revision = listOf(record?.boxImagePath, record?.cloudBoxImagePath,
            record?.cloudModelId, record?.cloudSubModelId).hashCode()
        return PodImagePrefs.deviceSpaceImageUri(address, route, revision).toString()
    }

    private fun restore(image: ImageView) {
        requests.invalidate(image)
        images.remove(image)?.let { state ->
            if (image.drawable === state.applied) {
                image.setImageDrawable(state.drawable)
                image.scaleType = state.scaleType
            }
        }
    }

    override fun onCanClose(): Boolean = worker.activeCount == 0 && worker.queue.isEmpty()

    override fun onClose() {
        stopped = true
        requests.close()
        worker.shutdownNow()
        check(worker.awaitTermination(1_500L, TimeUnit.MILLISECONDS)) { "Image worker did not stop" }
        check(runOnMainThreadBlocking {
            main.removeCallbacksAndMessages(null)
            images.keys.toList().forEach(::restore)
            cards.clear()
            scheduled.clear()
            cache.evictAll()
        }) { "Unable to restore detail images on main thread" }
    }

    private data class OriginalImage(val drawable: Drawable?, val scaleType: ImageView.ScaleType,
        var applied: Drawable? = null)
}
