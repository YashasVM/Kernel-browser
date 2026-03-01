package com.kernel.browser.extensions

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExtensionInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val cacheDir: File
        get() = File(context.cacheDir, "extensions").also { it.mkdirs() }

    fun copyToCache(uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val fileName = "extension_${System.currentTimeMillis()}.xpi"
            val destFile = File(cacheDir, fileName)
            inputStream.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            destFile.toURI().toString()
        } catch (e: Exception) {
            null
        }
    }

    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }
}
