package moe.chenxy.huaweipods.config

import moe.chenxy.huaweipods.pods.HuaweiDeviceRoute
import org.junit.Assert.*
import org.junit.Test

class PodPresentationImagePolicyTest {
    @Test fun `manual image comes first while official images require the matching generation`() {
        val request = PodPresentationImagePolicy.Request(HuaweiDeviceRoute.HUAWEI_FREECLIP, "AA:BB:CC:DD:EE:01")
        val image = EarphonePref(request.address, "FreeClip", boxImagePath = "manual.png",
            cloudModelId = "000160", cloudBoxImagePath = "official.png")
        assertEquals(listOf("manual.png", "official.png"), PodPresentationImagePolicy.candidatePaths(request, image))
        val nextGeneration = request.copy(route = HuaweiDeviceRoute.HUAWEI_FREECLIP2)
        assertEquals(listOf("manual.png"), PodPresentationImagePolicy.candidatePaths(nextGeneration, image))
        assertTrue(PodPresentationImagePolicy.candidatePaths(nextGeneration, image.copy(boxImagePath = null)).isEmpty())
        assertTrue(PodPresentationImagePolicy.candidatePaths(request.copy(address = "AA:BB:CC:DD:EE:02"), image).isEmpty())
    }

    @Test fun `binds a validated image request to its exact address and generation`() {
        val request = PodPresentationImagePolicy.parse(listOf("device", "HUAWEI_FREECLIP", "aa:bb:cc:dd:ee:01"))!!
        assertEquals("AA:BB:CC:DD:EE:01", request.address)
        assertEquals(HuaweiDeviceRoute.HUAWEI_FREECLIP, request.route)
        assertEquals(HuaweiDeviceRoute.HUAWEI_FREECLIP2,
            PodPresentationImagePolicy.parse(listOf("device", "HUAWEI_FREECLIP2", request.address))!!.route)
    }

    @Test fun `rejects paths unknown models malformed addresses and extra segments`() {
        listOf(listOf("device", "../files", "AA:BB:CC:DD:EE:01"),
            listOf("device", "UNSUPPORTED", "AA:BB:CC:DD:EE:01"),
            listOf("device", "HUAWEI_FREECLIP", "latest"),
            listOf("device", "HUAWEI_FREECLIP", "../private"),
            listOf("device", "HUAWEI_FREECLIP", "AA:BB:CC:DD:EE:01", "box"))
            .forEach { assertNull(it.toString(), PodPresentationImagePolicy.parse(it)) }
    }
}
