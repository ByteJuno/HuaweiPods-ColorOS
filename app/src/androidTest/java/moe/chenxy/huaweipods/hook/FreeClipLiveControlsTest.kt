package moe.chenxy.huaweipods.hook

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import moe.chenxy.huaweipods.pods.FreeClipControlClient
import moe.chenxy.huaweipods.pods.FreeClipControlKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in only: uses a connected earbud and restores every changed setting. */
class FreeClipLiveControlsTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun twoClientsShareReadbackAndEveryControlRestoresItsOriginalValue() {
        val address = InstrumentationRegistry.getArguments().getString("freeclipAddress")
        assumeTrue("Supply freeclipAddress only for an intentional hardware test", !address.isNullOrBlank())
        val clients = onMain {
            List(2) { FreeClipControlClient(instrumentation.targetContext, requireNotNull(address)) {} }
                .also { it.forEach(FreeClipControlClient::start) }
        }
        try {
            await("initial merged refresh") {
                clients.all { it.session.pending == null && it.session.values.size == FreeClipControlKey.entries.size }
            }
            val baseline = onMain { clients.first().session.values }
            assertEquals(baseline, onMain { clients.last().session.values })
            Log.i(TAG, "Initial two-client state confirmed: $baseline")
            FreeClipControlKey.entries.forEachIndexed { index, key ->
                val original = baseline.getValue(key)
                val target = key.values.first { it != original }
                val writer = clients[index % clients.size]
                try {
                    write(writer, key, target)
                    await("shared $key=$target") {
                        clients.all { it.session.remotePending == null && it.session.values[key] == target }
                    }
                    Log.i(TAG, "$key changed=$target confirmed by both clients")
                } finally {
                    // Also restore after a failed assertion: the device may have accepted the write.
                    write(writer, key, original)
                    await("restored $key=$original") {
                        clients.all { it.session.remotePending == null && it.session.values[key] == original }
                    }
                    Log.i(TAG, "$key restored=$original confirmed by both clients")
                }
            }
            clients.forEach { assertEquals(baseline, onMain { it.session.values }) }
            Log.i(TAG, "All seven controls restored; two-client round trips passed")
        } finally {
            onMain { clients.forEach(FreeClipControlClient::close) }
        }
    }

    private fun write(client: FreeClipControlClient, key: FreeClipControlKey, value: String) {
        await("writer available for $key") { client.session.pending == null && client.session.remotePending == null }
        val done = CountDownLatch(1)
        val success = AtomicReference(false)
        onMain {
            client.request(key, value) {
                success.set(it)
                done.countDown()
            }
        }
        assertTrue("$key=$value callback deadline", done.await(12, TimeUnit.SECONDS))
        assertTrue("$key=$value must be confirmed by its own readback", success.get())
    }

    private fun await(label: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        while (System.nanoTime() < deadline) {
            if (onMain(predicate)) return
            Thread.sleep(100)
        }
        assertTrue(label, onMain(predicate))
    }

    private fun <T> onMain(block: () -> T): T {
        val result = AtomicReference<T>()
        instrumentation.runOnMainSync { result.set(block()) }
        return result.get()
    }

    companion object { private const val TAG = "HuaweiPods-FreeClipLiveTest" }
}
