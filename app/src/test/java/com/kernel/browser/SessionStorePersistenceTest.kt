package com.kernel.browser

import android.content.SharedPreferences
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionStorePersistenceTest {
    private val stored = ConcurrentHashMap<String, String>()
    private val preferences = Proxy.newProxyInstance(
        javaClass.classLoader,
        arrayOf(SharedPreferences::class.java),
        InvocationHandler { _, method, args ->
            when (method.name) {
                "getString" -> stored[args!![0] as String] ?: args[1]
                "edit" -> editor()
                else -> error("Unexpected preference method: ${method.name}")
            }
        }
    ) as SharedPreferences

    @Test
    fun `save is visible to a new store immediately`() {
        val expected = listOf(SessionStore.Entry("New", "https://example.com/new", "state"))
        SessionStore(preferences).save(expected)
        assertEquals(expected, SessionStore(preferences).restore())
    }

    @Test
    fun `clear is visible to a new store immediately`() {
        stored["normal_tabs"] = SessionStoreCodec.encode(
            listOf(SessionStore.Entry("Closed", "https://example.com/closed", "state"))
        )
        SessionStore(preferences).clear()
        assertEquals(emptyList<SessionStore.Entry>(), SessionStore(preferences).restore())
    }

    private fun editor(): SharedPreferences.Editor {
        val puts = mutableMapOf<String, String>()
        val removals = mutableSetOf<String>()
        return Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
            InvocationHandler { proxy, method, args ->
                when (method.name) {
                    "putString" -> {
                        puts[args!![0] as String] = args[1] as String
                        proxy
                    }
                    "remove" -> {
                        removals += args!![0] as String
                        proxy
                    }
                    "apply", "commit" -> {
                        removals.forEach(stored::remove)
                        stored.putAll(puts)
                        if (method.name == "commit") true else null
                    }
                    else -> error("Unexpected editor method: ${method.name}")
                }
            }
        ) as SharedPreferences.Editor
    }
}
