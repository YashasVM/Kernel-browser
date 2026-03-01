package com.kernel.browser.tabs

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TabThumbnailManager @Inject constructor() {

    private val _thumbnails = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val thumbnails: StateFlow<Map<String, Bitmap>> = _thumbnails.asStateFlow()

    fun saveThumbnail(tabId: String, bitmap: Bitmap) {
        val scaled = Bitmap.createScaledBitmap(bitmap, THUMB_WIDTH, THUMB_HEIGHT, true)
        _thumbnails.value = _thumbnails.value + (tabId to scaled)
    }

    fun getThumbnail(tabId: String): Bitmap? = _thumbnails.value[tabId]

    fun removeThumbnail(tabId: String) {
        _thumbnails.value[tabId]?.recycle()
        _thumbnails.value = _thumbnails.value - tabId
    }

    fun clear() {
        _thumbnails.value.values.forEach { it.recycle() }
        _thumbnails.value = emptyMap()
    }

    companion object {
        private const val THUMB_WIDTH = 320
        private const val THUMB_HEIGHT = 480
    }
}
