package moe.chenxy.huaweipods.hook

import moe.chenxy.huaweipods.pods.HuaweiDeviceRoute
import org.junit.Assert.*
import org.junit.Test

class DeviceSpaceImageRequestsTest {
    @Test fun `a reused view rejects old device and old image completions`() {
        val requests = DeviceSpaceImageRequests<Any>()
        val target = Any()
        val first = requests.bind(target, "freeclip-address-1")!!
        val next = requests.bind(target, "freeclip2-address-2")!!
        assertFalse(requests.isCurrent(target, first))
        assertTrue(requests.isCurrent(target, next))
        val replacement = requests.bind(target, next.identity)!!
        assertFalse(requests.isCurrent(target, next))
        assertTrue(requests.isCurrent(target, replacement))
    }

    @Test fun `native rebinding and module close cancel pending image updates`() {
        val requests = DeviceSpaceImageRequests<Any>()
        val target = Any()
        val first = requests.bind(target, "image")!!
        requests.invalidate(target)
        assertFalse(requests.isCurrent(target, first))
        val next = requests.bind(target, "image")!!
        requests.close()
        assertFalse(requests.isCurrent(target, next))
        assertNull(requests.bind(target, "after-close"))
    }

    @Test fun `compact model titles preserve user aliases and unknown devices`() {
        assertEquals("FreeClip", deviceSpaceCardTitle("HUAWEI FreeClip", HuaweiDeviceRoute.HUAWEI_FREECLIP))
        assertEquals("FreeClip 2", deviceSpaceCardTitle("HUAWEI FreeClip 2", HuaweiDeviceRoute.HUAWEI_FREECLIP2))
        assertEquals("我的耳机", deviceSpaceCardTitle("我的耳机", HuaweiDeviceRoute.HUAWEI_FREECLIP))
        assertEquals("HUAWEI FreeClip", deviceSpaceCardTitle("HUAWEI FreeClip", HuaweiDeviceRoute.UNSUPPORTED))
    }
}
