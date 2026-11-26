package moe.chenxy.huaweipods

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomPopupAutoShowPolicyTest {
    @Test
    fun restoredSessionSuppressesInitialAndEveryDelayedBatteryTrigger() {
        val policy = BottomPopupAutoShowPolicy()
        policy.beginSession(1L, isSessionRestore = true)
        assertFalse(policy.isAllowed(1L))
        repeat(10) { assertFalse(policy.claim(1L, enabled = true)) }
    }

    @Test
    fun duplicateConnectCannotUpgradeRestoredSession() {
        val policy = BottomPopupAutoShowPolicy()
        policy.beginSession(1L, isSessionRestore = true)
        policy.beginSession(1L, isSessionRestore = false)
        assertFalse(policy.isAllowed(1L))
        assertFalse(policy.claim(1L, enabled = true))
    }

    @Test
    fun onlyANewConnectionGenerationReenablesAutomaticPopup() {
        val policy = BottomPopupAutoShowPolicy()
        policy.beginSession(1L, isSessionRestore = true)
        policy.beginSession(2L, isSessionRestore = false)
        assertFalse(policy.claim(1L, enabled = true))
        assertTrue(policy.isAllowed(2L))
        assertTrue(policy.claim(2L, enabled = true))
        assertFalse(policy.claim(2L, enabled = true))
    }

    @Test
    fun permissionDoesNotExistBeforeConnectionOwnershipIsEstablished() {
        val policy = BottomPopupAutoShowPolicy()
        assertFalse(policy.isAllowed(0L))
        assertFalse(policy.claim(0L, enabled = true))
        policy.beginSession(1L, isSessionRestore = false)
        assertFalse(policy.isAllowed(2L))
        assertFalse(policy.claim(2L, enabled = true))
    }

    @Test
    fun settingAndShownStateAreCheckedForEveryTrigger() {
        val policy = BottomPopupAutoShowPolicy()
        policy.beginSession(1L, isSessionRestore = false)
        assertFalse(policy.claim(1L, enabled = false))
        assertTrue(policy.claim(1L, enabled = true))
        policy.beginSession(1L, isSessionRestore = false)
        assertFalse(policy.claim(1L, enabled = true))
        policy.beginSession(2L, isSessionRestore = true)
        assertFalse(policy.isAllowed(2L))
        assertFalse(policy.claim(2L, enabled = true))
    }
}
