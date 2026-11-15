package moe.chenxy.huaweipods

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import moe.chenxy.huaweipods.config.ConfigManager
import moe.chenxy.huaweipods.pods.HuaweiDeviceRoute
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BottomPopupLifecycleRegressionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private val activities = mutableListOf<Activity>()
    private var hadPopupPreference = false
    private var previousPopupPreference = false

    @Before
    fun enablePopup() {
        val prefs = context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
        hadPopupPreference = prefs.contains(ConfigManager.PREF_KEY_BOTTOM_POPUP_ENABLED)
        previousPopupPreference = prefs.getBoolean(ConfigManager.PREF_KEY_BOTTOM_POPUP_ENABLED, false)
        setEnabled(true)
    }

    @After
    fun cleanUp() {
        instrumentation.runOnMainSync {
            activities.forEach { if (!it.isDestroyed) it.finish() }
            activities.clear()
        }
        instrumentation.waitForIdleSync()
        val prefs = context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            if (hadPopupPreference) {
                putBoolean(ConfigManager.PREF_KEY_BOTTOM_POPUP_ENABLED, previousPopupPreference)
            } else {
                remove(ConfigManager.PREF_KEY_BOTTOM_POPUP_ENABLED)
            }
        }.commit()
        ConfigManager.refreshFromPrefs(prefs)
    }

    @Test
    fun recreationReattachesAuthorizedSessionWithoutReusingLaunchToken() {
        val registration = register(ADDRESS_A)
        val original = launch(registration)
        val authorized = BottomPopupSessions.restore(context, registration.id)
        assertNotNull(authorized)
        assertNull(BottomPopupSessions.consume(context, registration.intent))

        val application = context.applicationContext as Application
        val resumed = CountDownLatch(1)
        val replacement = AtomicReference<BottomPopupActivity>()
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {
                if (activity is BottomPopupActivity && activity !== original) {
                    activities.add(activity)
                }
            }

            override fun onActivityResumed(activity: Activity) {
                if (activity is BottomPopupActivity && activity !== original) {
                    replacement.set(activity)
                    resumed.countDown()
                }
            }

            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        application.registerActivityLifecycleCallbacks(callbacks)
        try {
            instrumentation.runOnMainSync { original.recreate() }
            assertTrue("The authorized card must survive Activity recreation", resumed.await(5, TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            val recreated = replacement.get()
            assertNotNull(recreated)
            assertFalse(recreated.isFinishing)
            assertFalse(recreated.isDestroyed)
            assertSame(authorized, BottomPopupSessions.restore(context, registration.id))
            assertNull(BottomPopupSessions.consume(context, registration.intent))

            // A restored Activity must own the session, not merely remain as an empty window.
            instrumentation.runOnMainSync { recreated.finishSession(requireNotNull(authorized)) }
            assertTrue(recreated.isFinishing)
        } finally {
            application.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }

    @Test
    fun oldExitCompletionCannotFinishCardAfterSwitchingDevice() {
        assertOldExitCannotFinishReplacement(ADDRESS_B)
    }

    @Test
    fun oldExitCompletionCannotFinishCardAfterSameAddressReconnect() {
        assertOldExitCannotFinishReplacement(ADDRESS_A)
    }

    private fun assertOldExitCannotFinishReplacement(nextAddress: String) {
        val first = register(ADDRESS_A)
        val activity = launch(first)
        val firstSession = requireNotNull(BottomPopupSessions.restore(context, first.id))
        var survivedOldCompletion = false
        var replayDidNotReplaceCurrent = false
        var currentCompletionFinished = false

        instrumentation.runOnMainSync {
            // Model an old exit animation whose completion is ready to run. Deliver the new
            // Intent and that completion in one main-thread turn, before Compose can recompose
            // and cancel the old key's coroutine. No sleeps or cancellation timing assumptions.
            val oldExitCompletion = { activity.finishSession(firstSession) }
            val next = register(nextAddress)
            activity.onNewIntent(next.intent)
            val nextSession = requireNotNull(BottomPopupSessions.restore(context, next.id))
            oldExitCompletion()
            survivedOldCompletion = !activity.isFinishing

            // The consumed launch must not displace the legitimate replacement either.
            activity.onNewIntent(first.intent)
            oldExitCompletion()
            replayDidNotReplaceCurrent = !activity.isFinishing &&
                BottomPopupSessions.restore(context, next.id) === nextSession

            activity.finishSession(nextSession)
            currentCompletionFinished = activity.isFinishing
        }

        assertTrue("Old exit completion closed the replacement card", survivedOldCompletion)
        assertTrue("A replay displaced the replacement session", replayDidNotReplaceCurrent)
        assertTrue("The replacement card must still be able to finish itself", currentCompletionFinished)
    }

    @Test
    fun restorationDoesNotAuthorizePendingRegistrationOrConsumeItsToken() {
        val registration = register(ADDRESS_A)
        assertNull(BottomPopupSessions.restore(context, registration.id))
        val authorized = BottomPopupSessions.consume(context, registration.intent)
        assertNotNull(authorized)
        assertSame(authorized, BottomPopupSessions.restore(context, registration.id))
        assertNull(BottomPopupSessions.consume(context, registration.intent))
    }

    @Test
    fun restorationRejectsMissingWrongAndReplacedSessionIds() {
        val first = register(ADDRESS_A)
        assertNotNull(BottomPopupSessions.consume(context, first.intent))
        assertNull(BottomPopupSessions.restore(context, null))
        assertNull(BottomPopupSessions.restore(context, UUID.randomUUID().toString()))

        // Same MAC does not grant permission to restore an earlier connection generation.
        val replacement = register(ADDRESS_A)
        assertNotNull(BottomPopupSessions.consume(context, replacement.intent))
        assertNull(BottomPopupSessions.restore(context, first.id))
        assertNotNull(BottomPopupSessions.restore(context, replacement.id))
    }

    @Test
    fun restorationRevalidatesHostAndPopupPreference() {
        var hostActive = true
        val registration = register(ADDRESS_A) { hostActive }
        val authorized = BottomPopupSessions.consume(context, registration.intent)
        assertNotNull(authorized)
        assertSame(authorized, BottomPopupSessions.restore(context, registration.id))

        hostActive = false
        assertNull(BottomPopupSessions.restore(context, registration.id))
        hostActive = true
        setEnabled(false)
        assertNull(BottomPopupSessions.restore(context, registration.id))
        setEnabled(true)
        assertSame(authorized, BottomPopupSessions.restore(context, registration.id))
        assertNull(BottomPopupSessions.consume(context, registration.intent))
    }

    private fun setEnabled(enabled: Boolean) {
        context.getSharedPreferences(ConfigManager.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(ConfigManager.PREF_KEY_BOTTOM_POPUP_ENABLED, enabled)
            .commit()
    }

    private data class Registration(val id: String, val intent: Intent)

    private fun register(address: String, active: () -> Boolean = { true }): Registration {
        val id = UUID.randomUUID().toString()
        val token = BottomPopupSessions.register(
            id,
            PopupDeviceTarget(address, "Lifecycle test", HuaweiDeviceRoute.HUAWEI_FREEBUDS3),
            BottomPopupSessions.createHostValidator(context, active),
        )
        return Registration(id, Intent(context, BottomPopupActivity::class.java).apply {
            putExtra(BottomPopupSessions.EXTRA_LAUNCH_TOKEN, token)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun launch(registration: Registration): BottomPopupActivity {
        val activity = instrumentation.startActivitySync(registration.intent) as BottomPopupActivity
        instrumentation.runOnMainSync { activities.add(activity) }
        instrumentation.waitForIdleSync()
        assertFalse("An authenticated launch must remain visible", activity.isFinishing)
        return activity
    }

    private companion object {
        const val ADDRESS_A = "00:11:22:33:44:55"
        const val ADDRESS_B = "00:11:22:33:44:66"
    }
}
