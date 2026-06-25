package com.kernel.browser

import android.content.Context
import org.mozilla.geckoview.Autocomplete
import org.mozilla.geckoview.GeckoResult
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

class LoginStore(context: Context) : Autocomplete.StorageDelegate {
    private val preferences = context.getSharedPreferences("browser_logins", Context.MODE_PRIVATE)

    data class Summary(
        val origin: String,
        val username: String
    )

    fun summaries(): List<Summary> {
        return entries().map { Summary(it.origin.orEmpty(), it.username.orEmpty()) }
    }

    fun clear() {
        preferences.edit().remove(KEY_ENTRIES).apply()
    }

    override fun onLoginFetch(origin: String): GeckoResult<Array<Autocomplete.LoginEntry>> {
        val matches = entries().filter { entry ->
            origin.isBlank() || entry.origin == origin
        }.toTypedArray()
        return GeckoResult.fromValue(matches)
    }

    override fun onLoginFetch(): GeckoResult<Array<Autocomplete.LoginEntry>> {
        return GeckoResult.fromValue(entries().toTypedArray())
    }

    override fun onLoginSave(login: Autocomplete.LoginEntry) {
        save(login)
    }

    override fun onLoginUsed(login: Autocomplete.LoginEntry, usedFields: Int) = Unit

    fun save(login: Autocomplete.LoginEntry) {
        if (login.origin.orEmpty().isBlank() || login.username.orEmpty().isBlank() || login.password.orEmpty().isBlank()) return
        val guid = login.guid.orEmpty().ifBlank { UUID.randomUUID().toString() }
        val normalized = Autocomplete.LoginEntry.Builder()
            .guid(guid)
            .origin(login.origin.orEmpty())
            .formActionOrigin(login.formActionOrigin.orEmpty())
            .httpRealm(login.httpRealm.orEmpty())
            .username(login.username.orEmpty())
            .password(login.password.orEmpty())
            .build()
        val updated = entries()
            .filterNot { it.origin == normalized.origin && it.username == normalized.username }
            .toMutableList()
            .apply { add(0, normalized) }
        write(updated)
    }

    private fun entries(): List<Autocomplete.LoginEntry> {
        return preferences.getString(KEY_ENTRIES, "").orEmpty()
            .lineSequence()
            .mapNotNull(::decodeEntry)
            .take(MAX_ENTRIES)
            .toList()
    }

    private fun write(entries: List<Autocomplete.LoginEntry>) {
        val payload = entries
            .take(MAX_ENTRIES)
            .joinToString(separator = "\n", transform = ::encodeEntry)
        preferences.edit().putString(KEY_ENTRIES, payload).apply()
    }

    private fun encodeEntry(entry: Autocomplete.LoginEntry): String {
        return listOf(
            entry.guid.orEmpty(),
            entry.origin.orEmpty(),
            entry.formActionOrigin.orEmpty(),
            entry.httpRealm.orEmpty(),
            entry.username.orEmpty(),
            entry.password.orEmpty()
        ).joinToString(separator = "\t") { encode(it) }
    }

    private fun decodeEntry(line: String): Autocomplete.LoginEntry? {
        val parts = line.split('\t')
        if (parts.size != FIELD_COUNT) return null
        val values = parts.map(::decode)
        if (values[1].isBlank() || values[4].isBlank() || values[5].isBlank()) return null
        return Autocomplete.LoginEntry.Builder()
            .guid(values[0].ifBlank { UUID.randomUUID().toString() })
            .origin(values[1])
            .formActionOrigin(values[2])
            .httpRealm(values[3])
            .username(values[4])
            .password(values[5])
            .build()
    }

    private companion object {
        const val KEY_ENTRIES = "entries"
        const val MAX_ENTRIES = 100
        const val FIELD_COUNT = 6
    }
}

private fun encode(value: String): String {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.toByteArray(StandardCharsets.UTF_8))
}

private fun decode(value: String): String {
    return runCatching {
        String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8)
    }.getOrDefault("")
}
