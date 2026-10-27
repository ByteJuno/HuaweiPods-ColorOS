package moe.chenxy.huaweipods.ui.components

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.Recomposer
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.coroutines.EmptyCoroutineContext
import moe.chenxy.huaweipods.pods.HuaweiDeviceRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeBuds4eControlSessionTest {
    @Test
    fun sameAddressRouteSwitchRejectsDelayedSuccessAndFailure() = onMain {
        val routes = listOf(
            HuaweiDeviceRoute.HUAWEI_FREEBUDS4,
            HuaweiDeviceRoute.HUAWEI_FREEBUDS4E,
        )
        for (oldRoute in routes) {
            val newRoute = routes.first { it != oldRoute }
            for (oldSuccess in listOf(true, false)) {
                val host = SessionHost()
                try {
                    val oldSession = host.render(ADDRESS, oldRoute)
                    oldSession.selectedId.value = 1
                    oldSession.showDialog.value = true
                    oldSession.pending.value = true
                    var acceptedOldCompletions = 0
                    val delayedOldCompletion: (Boolean) -> Unit = { success ->
                        if (oldSession.completeWrite(2, success)) {
                            acceptedOldCompletions++
                        }
                    }

                    // Re-rendering the same identity must retain its in-flight UI state.
                    assertSame(oldSession, host.render(ADDRESS, oldRoute))
                    assertEquals(1, oldSession.selectedId.value)
                    assertTrue(oldSession.showDialog.value)
                    assertTrue(oldSession.pending.value)

                    val current = host.render(ADDRESS, newRoute)
                    assertNotSame(oldSession, current)
                    assertFresh(current)
                    current.selectedId.value = 3
                    current.showDialog.value = true
                    current.pending.value = true

                    // The old transport finishes only after the new route starts a write.
                    delayedOldCompletion(oldSuccess)
                    assertEquals(0, acceptedOldCompletions)
                    assertEquals(3, current.selectedId.value)
                    assertTrue(current.showDialog.value)
                    assertTrue(current.pending.value)
                    // Invalidation also prevents writes to the retired state object itself.
                    assertEquals(1, oldSession.selectedId.value)
                    assertTrue(oldSession.showDialog.value)
                    assertTrue(oldSession.pending.value)

                    // The active route still accepts its own completion normally.
                    assertTrue(current.completeWrite(2, true))
                    assertEquals(2, current.selectedId.value)
                    assertFalse(current.showDialog.value)
                    assertFalse(current.pending.value)

                    // Returning to the original route must not revive its old callback.
                    val returned = host.render(ADDRESS, oldRoute)
                    assertNotSame(oldSession, returned)
                    assertFresh(returned)
                    delayedOldCompletion(oldSuccess)
                    assertEquals(0, acceptedOldCompletions)
                    assertFresh(returned)
                } finally {
                    host.close()
                }
            }
        }
    }

    @Test
    fun addressSwitchInvalidatesOldWriteAndActiveFailureStillCompletes() = onMain {
        val host = SessionHost()
        try {
            val route = HuaweiDeviceRoute.HUAWEI_FREEBUDS4E
            val oldSession = host.render(ADDRESS, route)
            oldSession.selectedId.value = 1
            oldSession.showDialog.value = true
            oldSession.pending.value = true

            val current = host.render("AA:BB:CC:DD:EE:02", route)
            assertNotSame(oldSession, current)
            assertFresh(current)
            current.selectedId.value = 3
            current.showDialog.value = true
            current.pending.value = true

            assertFalse(oldSession.completeWrite(2, true))
            assertEquals(3, current.selectedId.value)
            assertTrue(current.showDialog.value)
            assertTrue(current.pending.value)

            assertTrue(current.completeWrite(2, false))
            assertEquals(3, current.selectedId.value)
            assertFalse(current.showDialog.value)
            assertFalse(current.pending.value)
        } finally {
            host.close()
        }
    }

    private fun assertFresh(session: FreeBuds4eControlSession) {
        assertNull(session.selectedId.value)
        assertFalse(session.showDialog.value)
        assertFalse(session.pending.value)
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { block() }
    }

    /** Runtime-only composition: exercises the production remember keys and disposal effect. */
    private class SessionHost {
        private val recomposer = Recomposer(EmptyCoroutineContext)
        private val composition = Composition(NoOpApplier(), recomposer)

        fun render(address: String, route: HuaweiDeviceRoute): FreeBuds4eControlSession {
            var result: FreeBuds4eControlSession? = null
            composition.setContent {
                result = rememberFreeBuds4eControlSession(address, route)
            }
            return checkNotNull(result)
        }

        fun close() {
            composition.dispose()
            recomposer.cancel()
        }
    }

    private class NoOpApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:01"
    }
}
