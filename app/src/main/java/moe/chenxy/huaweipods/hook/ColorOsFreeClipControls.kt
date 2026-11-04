package moe.chenxy.huaweipods.hook

import android.app.AlertDialog
import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import android.widget.Switch
import android.view.Gravity
import java.util.Locale
import moe.chenxy.huaweipods.pods.FreeClipControlClient
import moe.chenxy.huaweipods.pods.FreeClipControlKey
import moe.chenxy.huaweipods.pods.FreeClipControlProtocol
import moe.chenxy.huaweipods.pods.HuaweiDeviceRoute

/** A native detail-page panel. Its receiver, dialogs and confirmations belong to one attachment. */
internal class ColorOsFreeClipControls private constructor(context: Context, val address: String) : LinearLayout(context) {
    private val client: FreeClipControlClient = FreeClipControlClient(context, address) {
        if (!clientConnected()) closeDialog()
        render()
    }
    private val session get() = client.session
    private val registered get() = client.started
    private var dialog: AlertDialog? = null
    private fun clientConnected() = session.connected
    private val chinese = Locale.getDefault().language == Locale.CHINESE.language
    private val status = TextView(context)
    private val labels = mutableMapOf<FreeClipControlKey, TextView>()
    private val rows = mutableMapOf<FreeClipControlKey, View>()
    private val switches = mutableMapOf<FreeClipControlKey, Switch>()
    private var rendering = false
    private val gestureGroups = listOf(
        FreeClipControlKey.DOUBLE_LEFT to FreeClipControlKey.DOUBLE_RIGHT,
        FreeClipControlKey.TRIPLE_LEFT to FreeClipControlKey.TRIPLE_RIGHT,
    )
    init {
        tag = TAG
        orientation = VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        status.setTextSize(12f)
        status.setPadding(0, dp(10), 0, dp(8))
        status.setOnClickListener { request() }
        addView(status)
        gestureGroups.forEach { (left, right) ->
            addControlRow(left, gestureTitle(left)) { chooseSide(left, right) }
        }
        addControlRow(FreeClipControlKey.EQUALIZER, title(FreeClipControlKey.EQUALIZER)) {
            choose(FreeClipControlKey.EQUALIZER)
        }
        listOf(FreeClipControlKey.ADAPTIVE_CHANNELS, FreeClipControlKey.DROP_REMINDER).forEach { key ->
            val row = addControlRow(key, title(key)) { switches[key]?.performClick() }
            val toggle = Switch(context).apply {
                tag = "freeclip_switch_${key.name}"
                contentDescription = title(key)
                setOnCheckedChangeListener { _, checked ->
                    if (!rendering) {
                        request(key, checked.toString())
                        render()
                    }
                }
            }
            switches[key] = toggle
            row.addView(toggle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        render()
    }

    private fun addControlRow(key: FreeClipControlKey, heading: String, click: () -> Unit): LinearLayout {
        val row = LinearLayout(context).apply {
            tag = "freeclip_row_${key.name}"
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(52)
            setPadding(0, dp(6), 0, dp(6))
            isClickable = true
            isFocusable = true
            setOnClickListener { click() }
        }
        val texts = LinearLayout(context).apply { orientation = VERTICAL }
        texts.addView(TextView(context).apply { text = heading; setTextSize(14f) })
        val subtitle = TextView(context).apply { setTextSize(12f) }
        texts.addView(subtitle)
        row.addView(texts, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        labels[key] = subtitle
        rows[key] = row
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        return row
    }

    private fun gestureTitle(left: FreeClipControlKey) =
        if (left == FreeClipControlKey.DOUBLE_LEFT) text("双击设置", "Double tap")
        else text("三击设置", "Triple tap")

    private fun chooseSide(left: FreeClipControlKey, right: FreeClipControlKey) {
        if (!registered || session.pending?.key != null || session.remotePending != null || !session.connected) return
        closeDialog()
        val keys = listOf(left, right)
        dialog = AlertDialog.Builder(context)
            .setTitle(gestureTitle(left))
            .setItems(keys.map { "${title(it)} · ${valueLabel(it)}" }.toTypedArray()) { choice, index ->
                choice.dismiss()
                dialog = null
                choose(keys[index])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create().also { it.show() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        client.start()
    }

    override fun onDetachedFromWindow() {
        close()
        super.onDetachedFromWindow()
    }

    private fun closeDialog() {
        dialog?.dismiss()
        dialog = null
    }

    private fun close() {
        closeDialog()
        client.close()
    }

    private fun request(key: FreeClipControlKey? = null, value: String? = null) {
        if (!isAttachedToWindow) return
        client.request(key, value)
    }

    private fun choose(key: FreeClipControlKey) {
        if (!registered || session.pending?.key != null || session.remotePending != null || !session.connected) return
        closeDialog()
        val values = key.values
        dialog = AlertDialog.Builder(context)
            .setTitle(title(key))
            .setSingleChoiceItems(values.map { label(key, it) }.toTypedArray(), values.indexOf(session.values[key])) { choice, index ->
                choice.dismiss()
                dialog = null
                request(key, values[index])
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create().also { it.show() }
    }

    private fun render() {
        val pending = session.pending
        status.text = when {
            !session.connected -> text("FreeClip · 已断开，点此重新读取", "FreeClip · Disconnected, tap to refresh")
            pending?.key != null -> text("FreeClip · 写入中，等待设备回读…", "FreeClip · Writing, awaiting device readback…")
            session.remotePending != null -> text("FreeClip · 其他页面正在写入，等待回读…", "FreeClip · Another page is writing, awaiting readback…")
            pending != null -> text("FreeClip · 正在读取…", "FreeClip · Reading…")
            session.failed || session.values.size != FreeClipControlKey.entries.size ->
                text("FreeClip · 请求未生效或状态未确认，点此重试", "FreeClip · Request failed or state unconfirmed, tap to retry")
            else -> text("FreeClip · 设备已确认，点此刷新", "FreeClip · Device confirmed, tap to refresh")
        }
        status.isEnabled = pending == null
        val confirmed = session.values
        rendering = true
        try {
            labels.forEach { (key, view) ->
                val group = gestureGroups.firstOrNull { it.first == key }
                view.text = if (group != null) {
                    text("左：", "L: ") + valueLabel(group.first) + " · " +
                        text("右：", "R: ") + valueLabel(group.second)
                } else valueLabel(key)
                val enabled = registered && pending?.key == null && session.remotePending == null && session.connected &&
                    (key !in switches || (pending == null && confirmed[key] != null))
                rows[key]?.apply {
                    isEnabled = enabled
                    alpha = if (enabled) 1f else 0.5f
                    contentDescription = "${if (group == null) title(key) else gestureTitle(key)}, ${view.text}"
                }
                switches[key]?.apply {
                    isChecked = confirmed[key] == "true"
                    isEnabled = enabled
                    contentDescription = "${title(key)}, ${view.text}"
                    stateDescription = view.text
                }
            }
        } finally {
            rendering = false
        }
    }

    private fun valueLabel(key: FreeClipControlKey): String {
        val pending = session.pending
        return if (pending?.key == key) {
            text("等待回读 · ", "Awaiting readback · ") + label(key, requireNotNull(pending.value))
        } else session.values[key]?.let { label(key, it) } ?: text("未确认", "Unconfirmed")
    }

    private fun title(key: FreeClipControlKey): String = when (key) {
        FreeClipControlKey.DOUBLE_LEFT -> text("左耳双击", "Left double tap")
        FreeClipControlKey.DOUBLE_RIGHT -> text("右耳双击", "Right double tap")
        FreeClipControlKey.TRIPLE_LEFT -> text("左耳三击", "Left triple tap")
        FreeClipControlKey.TRIPLE_RIGHT -> text("右耳三击", "Right triple tap")
        FreeClipControlKey.EQUALIZER -> text("音效", "Sound effect")
        FreeClipControlKey.ADAPTIVE_CHANNELS -> text("左右自适应", "Adaptive left/right channels")
        FreeClipControlKey.DROP_REMINDER -> text("掉落提醒", "Drop reminder")
    }

    private fun label(key: FreeClipControlKey, value: String): String = when (key) {
        FreeClipControlKey.ADAPTIVE_CHANNELS, FreeClipControlKey.DROP_REMINDER ->
            if (value == "true") text("开启", "On") else text("关闭", "Off")
        FreeClipControlKey.EQUALIZER -> when (value) {
            "1" -> text("默认", "Default")
            "10" -> text("低音增强", "Bass boost")
            "3" -> text("高音增强", "Treble boost")
            "9" -> text("人声", "Clear voices")
            else -> text("未确认", "Unconfirmed")
        }
        else -> when (value) {
            "play_pause" -> text("播放 / 暂停", "Play / pause")
            "play_next" -> text("下一首", "Next track")
            "play_previous" -> text("上一首", "Previous track")
            "voice_assistant" -> text("语音助手", "Voice assistant")
            "none" -> text("无操作", "None")
            else -> text("未确认", "Unconfirmed")
        }
    }

    private fun text(zh: String, en: String) = if (chinese) zh else en
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "HuaweiPods-FreeClipDetail"

        fun attach(row: View, address: String, route: HuaweiDeviceRoute, allowLabelFallback: Boolean) {
            val existing = row.findViewWithTag<ColorOsFreeClipControls>(TAG)
            if (route != FreeClipControlProtocol.ROUTE) {
                remove(row)
                return
            }
            if (existing?.address.equals(address, ignoreCase = true)) return
            remove(row)
            val summary = row.findViewById<TextView>(android.R.id.summary)
                ?: (if (allowLabelFallback) textViews(row).getOrNull(1) else null)
                ?: return
            when (val container = summary.parent) {
                is LinearLayout -> {
                    if (container.orientation != VERTICAL) return
                    container.addView(ColorOsFreeClipControls(row.context, address))
                }
                is RelativeLayout -> {
                    // ColorOS 17 COUI Preference 的 messageLayout：标题和摘要都是直接子项。
                    if (summary.id != android.R.id.summary ||
                        container.findViewById<TextView>(android.R.id.title)?.parent !== container
                    ) return
                    container.addView(ColorOsFreeClipControls(row.context, address),
                        RelativeLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                            addRule(RelativeLayout.BELOW, summary.id)
                            addRule(RelativeLayout.ALIGN_PARENT_START)
                            addRule(RelativeLayout.ALIGN_PARENT_END)
                        },
                    )
                }
            }
        }

        fun remove(row: View) {
            val panel = row.findViewWithTag<ColorOsFreeClipControls>(TAG) ?: return
            panel.close()
            (panel.parent as? ViewGroup)?.removeView(panel)
        }

        private fun textViews(view: View): List<TextView> = buildList {
            if (view is TextView) add(view)
            if (view is ViewGroup) repeat(view.childCount) { addAll(textViews(view.getChildAt(it))) }
        }
    }
}
