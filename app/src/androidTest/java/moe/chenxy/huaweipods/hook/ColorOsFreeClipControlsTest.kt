package moe.chenxy.huaweipods.hook

import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import moe.chenxy.huaweipods.pods.HuaweiDeviceRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/** Detached native rows exercise binding/recycling without opening a Bluetooth connection. */
class ColorOsFreeClipControlsTest {
    @Test fun repeatedBindingKeepsPanelBesideOriginalQuickControls() = onMain {
        val (row, quickControls) = detailRow()
        val originalCount = quickControls.childCount
        ColorOsFreeClipControls.attach(row, ADDRESS, HuaweiDeviceRoute.HUAWEI_FREECLIP, false)
        val panel = row.getChildAt(2)
        assertEquals(3, row.childCount)
        assertSame(row, panel.parent)
        assertEquals(originalCount, quickControls.childCount)
        repeat(3) {
            ColorOsFreeClipControls.attach(row, ADDRESS.lowercase(), HuaweiDeviceRoute.HUAWEI_FREECLIP, false)
            assertSame(panel, row.getChildAt(2))
            assertEquals(3, row.childCount)
            assertEquals(originalCount, quickControls.childCount)
        }
        ColorOsFreeClipControls.remove(row)
        assertEquals(2, row.childCount)
        assertSame(quickControls, row.getChildAt(1))
    }

    @Test fun addressOrRouteRecyclingReplacesOrRemovesThePanel() = onMain {
        val (row, quickControls) = detailRow()
        ColorOsFreeClipControls.attach(row, ADDRESS, HuaweiDeviceRoute.HUAWEI_FREECLIP, false)
        val original = row.getChildAt(2)
        ColorOsFreeClipControls.attach(row, "AA:BB:CC:DD:EE:02", HuaweiDeviceRoute.HUAWEI_FREECLIP, false)
        assertNotSame(original, row.getChildAt(2))
        assertEquals(3, row.childCount)
        ColorOsFreeClipControls.attach(row, ADDRESS, HuaweiDeviceRoute.HUAWEI_FREECLIP2, false)
        assertEquals(2, row.childCount)
        assertSame(quickControls, row.getChildAt(1))
        ColorOsFreeClipControls.remove(row)
        assertEquals(2, row.childCount)
    }

    @Test fun colorOs17RelativeMessageLayoutPlacesPanelBelowSummary() = onMain {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val message = RelativeLayout(context).apply {
            addView(TextView(context).apply { id = android.R.id.title; text = "HuaweiPods" })
            addView(TextView(context).apply { id = android.R.id.summary; text = "Host summary" })
        }
        ColorOsFreeClipControls.attach(message, ADDRESS, HuaweiDeviceRoute.HUAWEI_FREECLIP, false)
        assertEquals(3, message.childCount)
        val panel = message.getChildAt(2)
        val params = panel.layoutParams as RelativeLayout.LayoutParams
        assertEquals(android.R.id.summary, params.getRule(RelativeLayout.BELOW))
        ColorOsFreeClipControls.attach(message, ADDRESS, HuaweiDeviceRoute.HUAWEI_FREECLIP, false)
        assertSame(panel, message.getChildAt(2))
        ColorOsFreeClipControls.remove(message)
        assertEquals(2, message.childCount)
    }

    @Test fun unknownHostLayoutRemainsUntouched() = onMain {
        val (row, _) = detailRow()
        row.orientation = LinearLayout.HORIZONTAL
        ColorOsFreeClipControls.attach(row, ADDRESS, HuaweiDeviceRoute.HUAWEI_FREECLIP, false)
        assertEquals(2, row.childCount)
    }

    @Test fun compactPanelHasFiveRowsAndUnknownSwitchesAreDisabled() = onMain {
        val (row, _) = detailRow()
        ColorOsFreeClipControls.attach(row, ADDRESS, HuaweiDeviceRoute.HUAWEI_FREECLIP, false)
        val panel = row.getChildAt(2) as LinearLayout
        assertEquals(6, panel.childCount) // status + five controls, still seven independent device keys
        assertEquals("freeclip_row_DOUBLE_LEFT", panel.getChildAt(1).tag)
        assertEquals("freeclip_row_TRIPLE_LEFT", panel.getChildAt(2).tag)
        listOf("ADAPTIVE_CHANNELS", "DROP_REMINDER").forEach { key ->
            val toggle = panel.findViewWithTag<android.widget.Switch>("freeclip_switch_$key")
            assertEquals(false, toggle.isEnabled)
            assertEquals(false, toggle.isChecked)
            org.junit.Assert.assertTrue(!toggle.stateDescription.isNullOrBlank())
        }
        ColorOsFreeClipControls.remove(row)
    }

    private fun detailRow(): Pair<LinearLayout, LinearLayout> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { id = android.R.id.summary; text = "Host summary" })
        }
        val quickControls = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply {
                addView(TextView(context).apply { text = "Latency" })
                addView(TextView(context).apply { text = "Wear detection" })
            })
        }
        row.addView(quickControls)
        return row to quickControls
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)

    companion object {
        private const val ADDRESS = "AA:BB:CC:DD:EE:01"
    }
}
