package com.kernel.browser

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LatestPreferenceWriteTest {
    private val stored = ConcurrentHashMap<String, String>()
    private val commits = AtomicInteger()
    private val writer = LatestPreferenceWrite(fakePreferences(), KEY)

    @Test
    fun `a burst of puts writes only the newest value`() {
        val encoded = mutableListOf<String>()
        val release = holdWriter()

        writer.put { "first".also { encoded += it } }
        writer.put { "second".also { encoded += it } }
        release()
        drain()

        assertEquals("second", stored[KEY])
        assertEquals(listOf("second"), encoded)
        assertEquals(1, commits.get())
    }

    @Test
    fun `an unchanged value is not written again`() {
        writer.put { "same" }
        drain()
        writer.put { "same" }
        drain()

        assertEquals(1, commits.get())
    }

    @Test
    fun `remove after a pending put wins`() {
        stored[KEY] = "old"
        val release = holdWriter()

        writer.put { "new" }
        writer.remove()
        release()
        drain()

        assertFalse(stored.containsKey(KEY))
    }

    @Test
    fun `put after remove is written`() {
        val release = holdWriter()

        writer.remove()
        writer.put { "kept" }
        release()
        drain()

        assertEquals("kept", stored[KEY])
    }

    /** Blocks the writer thread until the returned function is called. */
    private fun holdWriter(): () -> Unit {
        val gate = CountDownLatch(1)
        DiskWriter.execute { gate.await(5, TimeUnit.SECONDS) }
        return { gate.countDown() }
    }

    /** Waits for every task queued so far to finish. */
    private fun drain() {
        val done = CountDownLatch(1)
        DiskWriter.execute { done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }

    private fun fakePreferences(): SharedPreferences {
        val loader = javaClass.classLoader
        val prefsHandler = InvocationHandler { _, method, _ ->
            when (method.name) {
                "edit" -> newEditor(loader)
                else -> throw UnsupportedOperationException(method.name)
            }
        }
        return Proxy.newProxyInstance(loader, arrayOf(SharedPreferences::class.java), prefsHandler) as SharedPreferences
    }

    private fun newEditor(loader: ClassLoader?): SharedPreferences.Editor {
        val puts = mutableMapOf<String, String>()
        val removals = mutableListOf<String>()
        val handler = InvocationHandler { proxy, method, args ->
            when (method.name) {
                "putString" -> {
                    puts[args!![0] as String] = args[1] as String
                    proxy
                }
                "remove" -> {
                    removals += args!![0] as String
                    proxy
                }
                "commit" -> {
                    removals.forEach { stored.remove(it) }
                    stored.putAll(puts)
                    commits.incrementAndGet()
                    true
                }
                else -> throw UnsupportedOperationException(method.name)
            }
        }
        return Proxy.newProxyInstance(loader, arrayOf(SharedPreferences.Editor::class.java), handler) as SharedPreferences.Editor
    }

    private companion object {
        const val KEY = "entries"
    }
}
