package moe.chenxy.huaweipods.config

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.Bundle
import moe.chenxy.huaweipods.HuaweiPodsApp
import moe.chenxy.huaweipods.BuildConfig
import moe.chenxy.huaweipods.R
import moe.chenxy.huaweipods.utils.PodImageLoader
import moe.chenxy.huaweipods.pods.HuaweiDeviceInfoRoutePolicy
import moe.chenxy.huaweipods.smartaudio.OfficialImageIdentityBridge
import moe.chenxy.huaweipods.smartaudio.SmartAudioImageCache
import moe.chenxy.huaweipods.smartaudio.SmartAudioResourceIdentityPolicy
import java.io.File

class PodImageProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        if (mode != "r") throw SecurityException("Pod images are read-only")
        val context = context ?: return null
        if (!PodImageProviderAccessPolicy.mayOpenImage(resolveCallingPackages(context))) {
            throw SecurityException("Caller is not an active HuaweiPods image scope")
        }
        if (uri.pathSegments.firstOrNull() == "device") {
            val request = PodPresentationImagePolicy.parse(uri.pathSegments) ?: return null
            return presentationImage(context, request)?.let {
                ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }
        val fileName = uri.lastPathSegment ?: return null
        val prefs = context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
        val allowedNames = PodImagePrefs.load(prefs).flatMap { earphone ->
            PodImageResource.entries.mapNotNull { resource ->
                earphone.imagePath(resource)?.let { File(it).name }
            } + PodImageResource.entries.mapNotNull { resource ->
                earphone.cloudImagePath(resource)?.let { File(it).name }
            }
        }.toSet()
        if (fileName !in allowedNames) return null
        val dir = PodImagePrefs.imageDir(context)
        val file = File(dir, fileName).canonicalFile
        if (!file.path.startsWith(dir.canonicalPath) || !file.isFile) return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** Use the same exact device and priority as the app; a host cannot select an arbitrary file. */
    private fun presentationImage(context: Context, request: PodPresentationImagePolicy.Request): File? {
        val prefs = context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
        val record = PodImagePrefs.find(prefs, request.address)
        val imageDir = PodImagePrefs.imageDir(context).canonicalFile
        PodPresentationImagePolicy.candidatePaths(request, record).forEach { path ->
            val image = File(imageDir, File(path).name).canonicalFile
            if (image.parentFile == imageDir && image.isFile) return image
        }
        // Copy only the known drawable's bytes, never its APK file descriptor/offset.
        return synchronized(presentationImageLock) {
            val directory = File(context.cacheDir, "device_space_images").apply { mkdirs() }
            val image = File(directory, "${request.route.name}.img")
            if (!image.isFile || image.lastModified() < BuildConfig.BUILD_TIMESTAMP) {
                val temporary = File.createTempFile("image-", ".tmp", directory)
                try {
                    val drawable = PodImageLoader.modelFallbackResId(
                        request.route, PodImageResource.BOX, R.drawable.img_box,
                    )
                    context.resources.openRawResource(drawable).use { input ->
                        temporary.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (!temporary.renameTo(image)) return@synchronized null
                } finally {
                    temporary.delete()
                }
            }
            image
        }
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != SmartAudioImageCache.PROVIDER_METHOD_RECORD_IDENTITY) {
            return super.call(method, arg, extras)
        }
        val context = context ?: return Bundle().apply { putBoolean("accepted", false) }
        if (!PodImageProviderAccessPolicy.maySubmitOfficialImageIdentity(resolveCallingPackage(context))) {
            return Bundle().apply { putBoolean("accepted", false) }
        }
        val identity = SmartAudioResourceIdentityPolicy.normalize(
            address = extras?.getString(SmartAudioImageCache.EXTRA_ADDRESS),
            modelId = extras?.getString(SmartAudioImageCache.EXTRA_MODEL_ID),
            subModelId = extras?.getString(SmartAudioImageCache.EXTRA_SUB_MODEL_ID),
        )
        val prefs = context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
        val verifiedRoute = identity?.modelId?.let(HuaweiDeviceInfoRoutePolicy::routeForModelId)
        val identityVerified = identity != null && verifiedRoute != null
        val routeBound = if (identity != null && verifiedRoute != null) {
            runCatching {
                DeviceRoutePrefs.bindIfAbsent(
                    prefs = prefs,
                    service = HuaweiPodsApp.xposedService,
                    address = identity.address,
                    route = verifiedRoute,
                ) && DeviceRoutePrefs.find(prefs, identity.address) == verifiedRoute
            }.getOrDefault(false)
        } else {
            false
        }
        val imageScheduled = identity?.let { confirmedIdentity ->
            runCatching { SmartAudioImageCache.request(context, confirmedIdentity) }
                .getOrDefault(false)
        } == true
        return Bundle().apply {
            // 保留旧调用方语义：accepted 只表示图片任务已就绪或已调度。
            putBoolean("accepted", imageScheduled)
            putBoolean(OfficialImageIdentityBridge.RESULT_IDENTITY_VERIFIED, identityVerified)
            putBoolean(OfficialImageIdentityBridge.RESULT_ROUTE_BOUND, routeBound)
            putBoolean(OfficialImageIdentityBridge.RESULT_IMAGE_SCHEDULED, imageScheduled)
        }
    }

    override fun getType(uri: Uri): String = "image/*"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun resolveCallingPackage(context: Context): String? =
        runCatching { callingPackage }.getOrNull() ?: context.packageManager
            .getPackagesForUid(android.os.Binder.getCallingUid())
            ?.singleOrNull()

    private fun resolveCallingPackages(context: Context): Set<String> = buildSet {
        runCatching { callingPackage }.getOrNull()?.let(::add)
        context.packageManager.getPackagesForUid(android.os.Binder.getCallingUid())
            ?.forEach(::add)
    }

    companion object {
        private val presentationImageLock = Any()
    }
}
