package moe.chenxy.huaweipods.coloros

import moe.chenxy.huaweipods.pods.HuaweiDeviceRoute
import moe.chenxy.huaweipods.pods.NoiseControlMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ColorOsLiveAlertNoiseModeTest {
    @Test
    fun `models without noise control get no button`() {
        assertNull(colorOsLiveAlertNextNoiseMode(HuaweiDeviceRoute.HUAWEI_FREECLIP, NoiseControlMode.OFF))
        assertNull(colorOsLiveAlertNextNoiseMode(null, NoiseControlMode.OFF))
    }

    @Test
    fun `three state models cycle off to anc to awareness`() {
        val route = HuaweiDeviceRoute.HUAWEI_FREEBUDS_PRO5
        assertEquals(NoiseControlMode.NOISE_CANCELLATION, colorOsLiveAlertNextNoiseMode(route, NoiseControlMode.OFF))
        assertEquals(
            NoiseControlMode.TRANSPARENCY,
            colorOsLiveAlertNextNoiseMode(route, NoiseControlMode.NOISE_CANCELLATION),
        )
        assertEquals(NoiseControlMode.OFF, colorOsLiveAlertNextNoiseMode(route, NoiseControlMode.TRANSPARENCY))
    }

    @Test
    fun `two state models skip awareness`() {
        val route = HuaweiDeviceRoute.HUAWEI_FREEBUDS3
        assertEquals(NoiseControlMode.NOISE_CANCELLATION, colorOsLiveAlertNextNoiseMode(route, NoiseControlMode.OFF))
        assertEquals(
            NoiseControlMode.OFF,
            colorOsLiveAlertNextNoiseMode(route, NoiseControlMode.NOISE_CANCELLATION),
        )
        // 机型不支持通透时，即使耳机上报了通透也不能把按钮指向它。
        assertEquals(
            NoiseControlMode.OFF,
            colorOsLiveAlertNextNoiseMode(route, NoiseControlMode.TRANSPARENCY),
        )
    }

    @Test
    fun `freebuds 5 cycles between noise cancellation and off`() {
        val route = HuaweiDeviceRoute.HUAWEI_FREEBUDS5
        assertEquals(
            NoiseControlMode.NOISE_CANCELLATION,
            colorOsLiveAlertNextNoiseMode(route, NoiseControlMode.UNKNOWN),
        )
        assertEquals(
            NoiseControlMode.OFF,
            colorOsLiveAlertNextNoiseMode(route, NoiseControlMode.NOISE_CANCELLATION),
        )
        assertEquals(
            NoiseControlMode.NOISE_CANCELLATION,
            colorOsLiveAlertNextNoiseMode(route, NoiseControlMode.OFF),
        )
    }

    @Test
    fun `pre-battery anc readback resolves target address from current connection`() {
        assertEquals(
            "AA:BB:CC:DD:EE:FF",
            resolveLiveAlertNoiseTargetAddress(
                eventAddress = "aa:bb:cc:dd:ee:ff",
                cardAddress = null,
                currentAddress = "AA:BB:CC:DD:EE:FF",
            ),
        )
        assertEquals(
            "AA:BB:CC:DD:EE:FF",
            resolveLiveAlertNoiseTargetAddress(
                eventAddress = "aa:bb:cc:dd:ee:ff",
                cardAddress = "AA:BB:CC:DD:EE:FF",
                currentAddress = "AA:BB:CC:DD:EE:FF",
            ),
        )
        assertNull(
            resolveLiveAlertNoiseTargetAddress(
                eventAddress = "11:22:33:44:55:66",
                cardAddress = "AA:BB:CC:DD:EE:FF",
                currentAddress = "AA:BB:CC:DD:EE:FF",
            ),
        )
        assertNull(
            resolveLiveAlertNoiseTargetAddress(
                eventAddress = "11:22:33:44:55:66",
                cardAddress = null,
                currentAddress = "AA:BB:CC:DD:EE:FF",
            ),
        )
    }

    @Test
    fun `unknown state starts from noise cancellation`() {
        val route = HuaweiDeviceRoute.HUAWEI_FREEBUDS_PRO5
        assertEquals(
            NoiseControlMode.NOISE_CANCELLATION,
            colorOsLiveAlertNextNoiseMode(route, NoiseControlMode.UNKNOWN),
        )
        assertEquals(
            NoiseControlMode.NOISE_CANCELLATION,
            colorOsLiveAlertNextNoiseMode(route, null),
        )
    }
}
