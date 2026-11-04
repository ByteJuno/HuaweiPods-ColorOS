package moe.chenxy.huaweipods.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ColorOsBatteryFieldOverridesTest {
    @Test
    fun `a disappearing channel restores its native value while another device stays unchanged`() {
        val first = mutableMapOf<String, Any?>("left" to null, "right" to null)
        val secondValue = Any()
        val second = mutableMapOf<String, Any?>("left" to secondValue)
        val overrides = ColorOsBatteryFieldOverrides()
        val left = Any()
        overrides.apply(mapOf("left" to left, "right" to Any()), first::get, first::set)
        val nextRight = Any()
        overrides.apply(mapOf("right" to nextRight), first::get, first::set)
        assertNull(first["left"])
        assertSame(nextRight, first["right"])
        assertSame(secondValue, second["left"])
    }

    @Test
    fun `disconnect or close restores native fields and supports a fresh generation`() {
        val native = Any()
        val fields = mutableMapOf<String, Any?>("battery" to native, "multi" to false)
        val overrides = ColorOsBatteryFieldOverrides()
        overrides.apply(mapOf("battery" to Any(), "multi" to true), fields::get, fields::set)
        overrides.restore(fields::get, fields::set)
        overrides.restore(fields::get, fields::set)
        assertSame(native, fields["battery"])
        assertFalse(fields["multi"] as Boolean)
        overrides.apply(mapOf("battery" to Any()), fields::get, fields::set)
        overrides.restore(fields::get, fields::set)
        assertSame(native, fields["battery"])
    }

    @Test
    fun `a newer native update survives restore and becomes the next baseline`() {
        val fields = mutableMapOf<String, Any?>("battery" to Any())
        val overrides = ColorOsBatteryFieldOverrides()
        overrides.apply(mapOf("battery" to Any()), fields::get, fields::set)
        val newerNative = Any()
        fields["battery"] = newerNative
        overrides.apply(mapOf("battery" to Any()), fields::get, fields::set)
        overrides.restore(fields::get, fields::set)
        assertSame(newerNative, fields["battery"])
    }
}
