package com.kernel.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.print.PrintManager
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoViewPrintDocumentAdapter
import org.mozilla.geckoview.TranslationsController
import java.io.InputStream
import java.util.Collections
import java.util.Locale
import java.util.WeakHashMap

/** "Add to Home screen": pins a launcher shortcut that reopens a page in Kernel. */
object HomeShortcuts {
    private val tileColors = intArrayOf(
        R.color.tile_blue, R.color.tile_red, R.color.tile_green, R.color.tile_purple,
        R.color.tile_orange, R.color.tile_teal, R.color.tile_pink, R.color.tile_indigo
    )

    /**
     * Asks the launcher to pin a shortcut for [url]. Returns a message for the user when it
     * could not even ask (the launcher then shows its own confirmation otherwise).
     */
    fun pin(activity: Activity, title: String?, url: String): String? {
        if (!WebFeatureLogic.canPinShortcut(url)) return "Only web pages can be added to the Home screen"
        val manager = activity.getSystemService(ShortcutManager::class.java)
        if (manager == null || !manager.isRequestPinShortcutSupported) {
            return "Your launcher doesn't support adding shortcuts"
        }
        val label = WebFeatureLogic.shortcutLabel(title, url)
        val longLabel = title?.trim()?.takeIf { it.isNotEmpty() }?.take(LONG_LABEL_MAX) ?: label
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url.trim()), activity, MainActivity::class.java)
        return runCatching {
            val info = ShortcutInfo.Builder(activity, WebFeatureLogic.shortcutId(url))
                .setShortLabel(label)
                .setLongLabel(longLabel)
                .setIcon(Icon.createWithAdaptiveBitmap(letterIcon(activity, WebFeatureLogic.shortcutLetter(title, url), url)))
                .setIntent(intent)
                .build()
            if (manager.requestPinShortcut(info, null)) null else "Couldn't add to Home screen"
        }.getOrElse { "Couldn't add to Home screen" }
    }

    /**
     * A full-bleed adaptive icon (108dp, content kept inside the 72dp safe zone) with the
     * site's first letter on a color picked from its host, like the start page tiles.
     */
    private fun letterIcon(context: Context, letter: String, url: String): Bitmap {
        val size = (ADAPTIVE_ICON_DP * context.resources.displayMetrics.density).toInt().coerceAtLeast(48)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val key = WebFeatureLogic.displayHost(url) ?: url
        canvas.drawColor(context.getColor(tileColors[(key.hashCode() and 0x7fffffff) % tileColors.size]))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = context.getColor(android.R.color.white)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = size * 0.36f
        }
        val baseline = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(letter, size / 2f, baseline, paint)
        return bitmap
    }

    private const val ADAPTIVE_ICON_DP = 108
    private const val LONG_LABEL_MAX = 100
}

/**
 * Printing and "Save as PDF": GeckoView renders the page to a PDF and Android's print
 * framework shows the system print dialog (which offers "Save as PDF" as a printer).
 * Also serves pages that call window.print().
 */
class PagePrinter(
    private val activity: Activity,
    private val jobName: () -> String,
    private val onError: (String) -> Unit
) : GeckoSession.PrintDelegate {
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Prints [session]'s current page. Must be called on the main thread. */
    fun print(session: GeckoSession) {
        if (!session.isOpen) return
        runCatching {
            session.didPrintPageContent().accept({ }, { onError("Couldn't print this page") })
        }.onFailure { onError("Couldn't print this page") }
    }

    override fun onPrint(session: GeckoSession) {
        print(session)
    }

    override fun onPrint(pdfInputStream: InputStream) {
        onPrintWithStatus(pdfInputStream)
    }

    override fun onPrintWithStatus(pdfInputStream: InputStream): GeckoResult<Boolean>? {
        val finished = GeckoResult<Boolean>()
        val show = Runnable {
            val manager = activity.getSystemService(PrintManager::class.java)
            if (manager == null || activity.isFinishing || activity.isDestroyed) {
                finished.completeExceptionally(IllegalStateException("Printing is not available"))
                return@Runnable
            }
            runCatching {
                val adapter = GeckoViewPrintDocumentAdapter(pdfInputStream, activity, finished)
                manager.print(jobName().ifBlank { "Kernel page" }, adapter, null)
            }.onFailure {
                finished.completeExceptionally(it)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) show.run() else mainHandler.post(show)
        return finished
    }
}

/**
 * Full-page translation with Firefox's on-device translation engine, into the device language.
 * MainActivity installs [delegate] on every session to learn each page's detected language.
 */
class PageTranslator(private val showMessage: (String) -> Unit) {
    private val states: MutableMap<GeckoSession, TranslationsController.SessionTranslation.TranslationState> =
        Collections.synchronizedMap(WeakHashMap())

    val delegate = object : TranslationsController.SessionTranslation.Delegate {
        override fun onTranslationStateChange(
            session: GeckoSession,
            translationState: TranslationsController.SessionTranslation.TranslationState?
        ) {
            if (translationState == null) states.remove(session) else states[session] = translationState
        }
    }

    /** True while the page shows (or is producing) a translation. */
    fun isTranslated(session: GeckoSession?): Boolean {
        val state = session?.let { states[it] } ?: return false
        return state.requestedTranslationPair != null || state.hasVisibleChange
    }

    /** "French → English" for the menu, when the page's language is known. */
    fun describe(session: GeckoSession?): String? {
        val state = session?.let { states[it] } ?: return null
        val pair = state.requestedTranslationPair
        if (pair != null) return "${displayName(pair.fromLanguage)} → ${displayName(pair.toLanguage)}"
        val from = state.detectedLanguages?.docLangTag ?: return null
        return "${displayName(from)} → ${displayName(targetLanguage(state))}"
    }

    /** Translates the page into the device language, or restores the original when translated. */
    fun toggle(session: GeckoSession) {
        val translation = session.sessionTranslation
        if (translation == null) {
            showMessage("Translation isn't available for this page")
            return
        }
        if (isTranslated(session)) {
            runCatching {
                translation.restoreOriginalPage().accept({ }, { showMessage("Couldn't restore the original page") })
            }.onFailure { showMessage("Couldn't restore the original page") }
            return
        }
        val state = states[session]
        val from = state?.detectedLanguages?.docLangTag
        val to = targetLanguage(state)
        if (from.isNullOrBlank()) {
            showMessage("Couldn't detect this page's language yet")
            return
        }
        if (WebFeatureLogic.isSameLanguage(from, to)) {
            showMessage("This page is already in ${displayName(to)}")
            return
        }
        val options = TranslationsController.SessionTranslation.TranslationOptions.Builder()
            .downloadModel(true)
            .build()
        showMessage("Translating to ${displayName(to)}…")
        runCatching {
            translation.translate(from, to, options).accept({ }, { error -> showMessage(errorMessage(error)) })
        }.onFailure { showMessage(errorMessage(it)) }
    }

    private fun targetLanguage(state: TranslationsController.SessionTranslation.TranslationState?): String =
        state?.detectedLanguages?.userLangTag?.takeIf { it.isNotBlank() }
            ?: Locale.getDefault().language.ifBlank { "en" }

    private fun displayName(tag: String?): String {
        if (tag.isNullOrBlank()) return "?"
        val name = Locale.forLanguageTag(tag).getDisplayLanguage(Locale.getDefault())
        return name.ifBlank { tag }.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    private fun errorMessage(error: Throwable?): String {
        val code = (error as? TranslationsController.TranslationsException)?.code
        return when (code) {
            TranslationsController.TranslationsException.ERROR_ENGINE_NOT_SUPPORTED,
            TranslationsController.TranslationsException.ERROR_ENGINE_DEACTIVATED ->
                "Translation isn't supported on this device"
            TranslationsController.TranslationsException.ERROR_LANGUAGE_NOT_SUPPORTED ->
                "This language can't be translated yet"
            TranslationsController.TranslationsException.ERROR_MODEL_COULD_NOT_DOWNLOAD,
            TranslationsController.TranslationsException.ERROR_MODEL_DOWNLOAD_REQUIRED ->
                "Couldn't download the translation model"
            else -> "Couldn't translate this page"
        }
    }
}
