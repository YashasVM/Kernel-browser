package com.kernel.browser

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

class LatestPreferenceWriteTest {
    private val stored = ConcurrentHashMap<String, String>()
    private val writer = LatestPreferenceWrite(fakePreferences(), KEY)

    @Test
    fun `a burst of puts leaves the newest value visible`() {
        val encoded = mutableListOf<String>()

        writer.put { "first".also { encoded += it } }
        writer.put { "second".also { encoded += it } }

        assertEquals("second", stored[KEY])
        assertEquals(listOf("first", "second"), encoded)
    }

    @Test
    fun `remove after put clears the value`() {
        stored[KEY] = "old"

        writer.put { "new" }
        writer.remove()

        assertFalse(stored.containsKey(KEY))
    }

    @Test
    fun `put after remove is written`() {
        writer.remove()
        writer.put { "kept" }

        assertEquals("kept", stored[KEY])
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
                "apply" -> {
                    removals.forEach { stored.remove(it) }
                    stored.putAll(puts)
                    null
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
