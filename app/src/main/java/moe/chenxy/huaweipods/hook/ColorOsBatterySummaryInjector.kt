package moe.chenxy.huaweipods.hook

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.util.WeakHashMap

/** 设置列表按宿主绑定的 MAC、详情页按 Intent 地址补全电量；每代订阅一次，关闭时恢复原文。 */
internal object ColorOsBatterySummaryInjector : AutoCloseable {
    fun interface Rewrite {
        fun apply(text: CharSequence?, triple: ColorOsBatteryTriple): String?
    }

    private data class Original(val text: String, val rendered: String)
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val rows = WeakHashMap<View, String>()
    private val originals = WeakHashMap<TextView, Original>()
    @Volatile private var source: ColorOsBatterySource? = null
    @Volatile private var closed = false

    fun attach(context: Context, tag: String, rewrite: Rewrite, enabled: () -> Boolean = { true }) {
        synchronized(lock) {
            if (closed) return
            if (source == null) {
                runCatching {
                    val next = ColorOsBatterySource.forContext(context.applicationContext ?: context, tag) {
                        main.post { patch(tag, rewrite, enabled) }
                    }
                    next.start()
                    source = next
                }.onFailure { Log.w(tag, "battery summary source unavailable", it) }
            }
        }
        schedulePatches(tag, rewrite, enabled)
    }

    /** onBindViewHolder 之前记录目标；即使目标断开，也阻止回退到另一台设备。 */
    fun bindRow(root: View, address: String?) {
        synchronized(rows) { rows[root] = address.orEmpty() }
    }

    fun schedulePatches(tag: String, rewrite: Rewrite, enabled: () -> Boolean = { true }) {
        if (closed) return
        for (delay in listOf(0L, 400L, 1_200L)) {
            main.postDelayed({ if (!closed) patch(tag, rewrite, enabled) }, delay)
        }
    }

    private fun currentTriple(view: TextView): ColorOsBatteryTriple? {
        var node: View? = view
        repeat(12) {
            val current = node ?: return@repeat
            val address = synchronized(rows) { rows[current] }
            if (address != null) return colorOsBatteryTriple(source?.get(address))
            node = current.parent as? View
        }
        return colorOsBatteryTriple(source?.get(ColorOsBatteryViewIdentity.address(view.context)))
    }

    /** 保留宿主原文，使断连、数据缺失或模块关闭时能撤掉已经写上的三段文字。 */
    fun replacement(view: TextView, text: CharSequence?, rewrite: Rewrite): String? {
        if (closed || text == null) return null
        val raw = text.toString()
        return synchronized(originals) {
            val previous = originals[view]
            val original = if (raw == previous?.rendered) previous.text else raw
            val rewritten = currentTriple(view)?.let { rewrite.apply(original, it) }
            val result = if (rewritten != null && rewritten != original) {
                originals[view] = Original(original, rewritten)
                rewritten
            } else {
                originals.remove(view)
                original
            }
            result.takeUnless { it == raw }
        }
    }

    fun refreshRow(root: View, rewrite: Rewrite) {
        if (closed) return
        val pending = ArrayDeque<View>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 512) {
            val view = pending.removeFirst()
            if (view is TextView) replacement(view, view.text, rewrite)?.let { view.text = it }
            if (view is ViewGroup) {
                repeat(minOf(view.childCount, 64)) { pending.add(view.getChildAt(it)) }
            }
        }
    }

    private fun patch(tag: String, rewrite: Rewrite, enabled: () -> Boolean) {
        if (closed || !enabled()) return
        runCatching {
            val manager = Class.forName("android.view.WindowManagerGlobal")
                .getDeclaredMethod("getInstance").invoke(null)
            val roots = (getObjectField(manager, "mViews") as? List<*>)
                ?.filterIsInstance<View>()?.take(16) ?: return
            roots.forEach { root ->
                if (!root.isAttachedToWindow) return@forEach
                refreshRow(root, rewrite)
            }
        }.onFailure { Log.w(tag, "unable to patch battery summary", it) }
    }

    /** 调用方在宿主主线程执行，用于热重载清理。 */
    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            main.removeCallbacksAndMessages(null)
            val previous = source
            source = null
            try {
                previous?.close()
            } finally {
                synchronized(originals) {
                    originals.forEach { (view, original) ->
                        if (view.text?.toString() == original.rendered) view.text = original.text
                    }
                    originals.clear()
                }
                synchronized(rows) { rows.clear() }
            }
        }
    }
}
